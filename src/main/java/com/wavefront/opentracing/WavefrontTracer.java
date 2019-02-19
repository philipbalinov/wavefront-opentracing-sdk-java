package com.wavefront.opentracing;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.wavefront.internal.reporter.WavefrontInternalReporter;
import com.wavefront.internal_reporter_java.io.dropwizard.metrics5.MetricName;
import com.wavefront.opentracing.propagation.Propagator;
import com.wavefront.opentracing.propagation.PropagatorRegistry;
import com.wavefront.opentracing.reporting.CompositeReporter;
import com.wavefront.opentracing.reporting.Reporter;
import com.wavefront.opentracing.reporting.WavefrontSpanReporter;
import com.wavefront.sdk.appagent.jvm.reporter.WavefrontJvmReporter;
import com.wavefront.sdk.common.Pair;
import com.wavefront.sdk.common.WavefrontSender;
import com.wavefront.sdk.common.application.ApplicationTags;
import com.wavefront.sdk.common.application.HeartbeaterService;
import com.wavefront.sdk.entities.histograms.HistogramGranularity;
import com.wavefront.sdk.entities.tracing.sampling.Sampler;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.util.ThreadLocalScopeManager;

import static com.wavefront.sdk.common.Constants.APPLICATION_TAG_KEY;
import static com.wavefront.sdk.common.Constants.CLUSTER_TAG_KEY;
import static com.wavefront.sdk.common.Constants.COMPONENT_TAG_KEY;
import static com.wavefront.sdk.common.Constants.NULL_TAG_VAL;
import static com.wavefront.sdk.common.Constants.SERVICE_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SHARD_TAG_KEY;

/**
 * The Wavefront OpenTracing tracer for sending distributed traces to Wavefront.
 *
 * @author Vikram Raman (vikram@wavefront.com)
 */
public class WavefrontTracer implements Tracer, Closeable {

  private static final Logger logger = Logger.getLogger(WavefrontTracer.class.getName());
  private final ScopeManager scopeManager;
  private final PropagatorRegistry registry;
  private final Reporter reporter;
  private final List<Pair<String, String>> tags;
  private final List<Sampler> samplers;
  @Nullable
  private final WavefrontInternalReporter wfInternalReporter;
  @Nullable
  private final HeartbeaterService heartbeaterService;
  @Nullable
  private final WavefrontJvmReporter wfJvmReporter;
  @Nullable
  private final WavefrontSender wfSender;
  @Nullable
  private final String source;
  private final Supplier<Long> reportFrequencyMillis;
  private final static String WAVEFRONT_GENERATED_COMPONENT = "wavefront-generated";
  private final static String OPENTRACING_COMPONENT = "opentracing";
  private final static String JAVA_COMPONENT = "java";
  private final static String DERIVED_METRIC_PREFIX = "tracing.derived";
  private final static String INVOCATION_SUFFIX = ".invocation";
  // To differentiate between span and trace derived metrics
  private final static String REQUESTS_SUFFIX = ".requests";
  private final static String ERROR_SUFFIX = ".error";
  // To differentiate between span and trace derived metrics
  private final static String ERRORS_SUFFIX = ".errors";
  private final static String TOTAL_TIME_SUFFIX = ".total_time.millis";
  private final static String DURATION_SUFFIX = ".duration.micros";
  private final static String OPERATION_NAME_TAG = "operationName";
  private final static String ROOT_TAG = "root";
  private final String applicationServicePrefix;
  private final LoadingCache<String, TraceInfo> traces;

  private WavefrontTracer(Reporter reporter, List<Pair<String, String>> tags,
                          ApplicationTags applicationTags, List<Sampler> samplers,
                          Supplier<Long> reportFrequencyMillis, boolean includeJvmMetrics) {
    scopeManager = new ThreadLocalScopeManager();
    registry = new PropagatorRegistry();
    this.reporter = reporter;
    this.tags = tags;
    this.samplers = samplers;
    applicationServicePrefix = applicationTags.getApplication() + "." +
        applicationTags.getService() + ".";
    this.reportFrequencyMillis = reportFrequencyMillis;

    /**
     * Tracing spans will be converted to metrics and histograms and will be reported to Wavefront
     * only if you use the WavefrontSpanReporter
     */
    WavefrontSpanReporter wfSpanReporter = getWavefrontSpanReporter(reporter);
    if (wfSpanReporter != null) {
      Tuple tuple = instantiateWavefrontStatsReporter(wfSpanReporter, applicationTags,
          includeJvmMetrics);
      wfInternalReporter = tuple.wfInternalReporter;
      wfJvmReporter = tuple.wfJvmReporter;
      wfSender = wfSpanReporter.getWavefrontSender();
      source = wfSpanReporter.getSource();
      heartbeaterService = tuple.heartbeaterService;
    } else {
      wfInternalReporter = null;
      wfJvmReporter = null;
      wfSender = null;
      source = null;
      heartbeaterService = null;
    }

    // Store up to 100 million traces in the cache with the assumption that all the spans in a
    // trace will be reported within 10min
    traces = Caffeine.newBuilder().expireAfterAccess(10, TimeUnit.MINUTES).
            maximumSize(100_000_000L).removalListener(
                (RemovalListener<String, TraceInfo>) (key, traceInfo, cause) -> {
      if (traceInfo != null && wfSender != null) {
        for (String root : traceInfo.getRoots()) {
          long startTimeMicros = traceInfo.getStartTimeMicros();
          long finishTimeMicros = traceInfo.getFinishTimeMicros();
          try {
            wfSender.sendDistribution(sanitize(DERIVED_METRIC_PREFIX + "." + ROOT_TAG + "." +
                    applicationServicePrefix + root + DURATION_SUFFIX),
                // Sending a single centroid
                Collections.singletonList(Pair.of((double) (finishTimeMicros - startTimeMicros), 1)),
                Collections.singleton(HistogramGranularity.MINUTE),
                // Should be sent at a timestamp when the trace actually finished
                traceInfo.getFinishTimeMicros() / 1000, source,
                new HashMap<String, String>() {{
                  putAll(applicationTags.toPointTags());
                  put(ROOT_TAG, root);
                }});
          } catch (IOException e) {
            logger.log(Level.WARNING, "Error reporting trace duration for root: " + root, e);
          }
        }
      }
    }).build(item -> new TraceInfo());
  }

  @Nullable
  private WavefrontSpanReporter getWavefrontSpanReporter(Reporter reporter) {
    if (reporter instanceof WavefrontSpanReporter) {
      return (WavefrontSpanReporter) reporter;
    }

    if (reporter instanceof CompositeReporter) {
      CompositeReporter compositeReporter = (CompositeReporter) reporter;
      for (Reporter item : compositeReporter.getReporters()) {
        if (item instanceof WavefrontSpanReporter) {
          return (WavefrontSpanReporter) item;
        }
      }
    }

    // default
    return null;
  }

  private class Tuple {
    WavefrontInternalReporter wfInternalReporter;
    @Nullable
    WavefrontJvmReporter wfJvmReporter;
    HeartbeaterService heartbeaterService;
    Tuple(WavefrontInternalReporter wfInternalReporter,
          WavefrontJvmReporter wfJvmReporter,
          HeartbeaterService heartbeaterService) {
      this.wfInternalReporter = wfInternalReporter;
      this.wfJvmReporter = wfJvmReporter;
      this.heartbeaterService = heartbeaterService;
    }
  }

  private Tuple instantiateWavefrontStatsReporter(
      WavefrontSpanReporter wfSpanReporter, ApplicationTags applicationTags,
      boolean includeJvmMetrics) {
    Map<String, String> pointTags = new HashMap<>(applicationTags.toPointTags());
    WavefrontInternalReporter wfInternalReporter = new WavefrontInternalReporter.Builder().
        prefixedWith(DERIVED_METRIC_PREFIX).withSource(wfSpanReporter.getSource()).
        withReporterPointTags(pointTags).reportMinuteDistribution().
            build(wfSpanReporter.getWavefrontSender());
    // Start the internal reporter
    wfInternalReporter.start(reportFrequencyMillis.get(), TimeUnit.MILLISECONDS);

    WavefrontJvmReporter wfJvmReporter = null;
    if (includeJvmMetrics) {
      wfJvmReporter = new WavefrontJvmReporter.Builder(applicationTags).
          withSource(wfSpanReporter.getSource()).build(wfSpanReporter.getWavefrontSender());
      // Start the JVM reporter
      wfJvmReporter.start();
    }

    HeartbeaterService heartbeaterService = new HeartbeaterService(
        wfSpanReporter.getWavefrontSender(), applicationTags,
            Arrays.asList(WAVEFRONT_GENERATED_COMPONENT, OPENTRACING_COMPONENT, JAVA_COMPONENT),
        wfSpanReporter.getSource());
    return new Tuple(wfInternalReporter, wfJvmReporter, heartbeaterService);
  }

  @Override
  public ScopeManager scopeManager() {
    return scopeManager;
  }

  @Override
  public Span activeSpan() {
    Scope scope = this.scopeManager.active();
    return scope == null ? null : scope.span();
  }

  @Override
  public SpanBuilder buildSpan(String operationName) {
    return new WavefrontSpanBuilder(operationName, this);
  }

  @Override
  public <T> void inject(SpanContext spanContext, Format<T> format, T carrier) {
    Propagator<T> propagator = registry.get(format);
    if (propagator == null) {
      throw new IllegalArgumentException("invalid format: " + format.toString());
    }
    propagator.inject((WavefrontSpanContext) spanContext, carrier);
  }

  @Override
  public <T> SpanContext extract(Format<T> format, T carrier) {
    Propagator<T> propagator = registry.get(format);
    if (propagator == null) {
      throw new IllegalArgumentException("invalid format: " + format.toString());
    }
    return propagator.extract(carrier);
  }

  boolean sample(String operationName, long traceId, long duration) {
    if (samplers == null || samplers.isEmpty()) {
      return true;
    }
    boolean earlySampling = (duration == 0);
    for (Sampler sampler : samplers) {
      boolean doSample = earlySampling == sampler.isEarly();
      if (doSample && sampler.sample(operationName, traceId, duration)) {
        if (logger.isLoggable(Level.FINER)) {
          logger.finer(sampler.getClass().getSimpleName() + "=" + true +
              " op=" + operationName);
        }
        return true;
      }
      if (logger.isLoggable(Level.FINER)) {
        logger.finer(sampler.getClass().getSimpleName() + "=" + false +
            " op=" + operationName);
      }
    }
    return false;
  }

  void startSpan(WavefrontSpan span) {
    if (span.getParents().size() == 0 && span.getFollows().size() == 0) {
      // root span
      processRootSpan(span);
    }
  }

  private void processRootSpan(WavefrontSpan rootSpan) {
    if (wfInternalReporter == null) {
      // WavefrontSpanReporter not set, so no traces will be reported as metrics/histograms.
      return;
    }

    wfInternalReporter.newCounter(new MetricName(sanitize(ROOT_TAG + "." +
        applicationServicePrefix + rootSpan.getOperationName() + REQUESTS_SUFFIX),
        new HashMap<String, String>() {{
          put(ROOT_TAG, rootSpan.getOperationName());
    }})).inc();

    TraceInfo traceInfo = traces.get(rootSpan.getTraceIdStr());
    if (traceInfo != null) {
      traceInfo.addRoot(rootSpan.getOperationName());
    }
  }

  void reportWavefrontGeneratedData(WavefrontSpan span) {
    if (wfInternalReporter == null) {
      // WavefrontSpanReporter not set, so no tracing spans will be reported as metrics/histograms.
      return;
    }
    TraceInfo traceInfo = traces.get(span.getTraceIdStr());
    if (traceInfo != null) {
      traceInfo.setStartAndFinishTime(span.getStartTimeMicros(),
          span.getStartTimeMicros() + span.getDurationMicroseconds());
    }
    // Need to sanitize metric name as application, service and operation names can have spaces
    // and other invalid metric name characters
    Map<String, String> pointTags = new HashMap<String, String>() {{
      put(OPERATION_NAME_TAG, span.getOperationName());
      put(COMPONENT_TAG_KEY, span.getComponentTagValue());
    }};
    wfInternalReporter.newCounter(new MetricName(sanitize(applicationServicePrefix +
        span.getOperationName() + INVOCATION_SUFFIX), pointTags)).inc();
    if (span.isError()) {
      wfInternalReporter.newCounter(new MetricName(sanitize(applicationServicePrefix +
          span.getOperationName() + ERROR_SUFFIX), pointTags)).inc();
      // set the trace to false if it isn't already
      if (traceInfo != null && traceInfo.isError.compareAndSet(false,
          true)) {
        for (String root: traceInfo.getRoots()) {
          wfInternalReporter.newCounter(new MetricName(sanitize(ROOT_TAG + "." +
              applicationServicePrefix + root + ERRORS_SUFFIX), new HashMap<String, String>() {{
            put(ROOT_TAG, root);
          }})).inc();
        }
      }
    }
    long spanDurationMicros = span.getDurationMicroseconds();
    // Convert from micros to millis and add to duration counter
    wfInternalReporter.newCounter(new MetricName(sanitize(applicationServicePrefix +
        span.getOperationName() + TOTAL_TIME_SUFFIX), pointTags)).
        inc(spanDurationMicros / 1000);
    // Support duration in microseconds instead of milliseconds
    wfInternalReporter.newWavefrontHistogram(new MetricName(sanitize(applicationServicePrefix +
        span.getOperationName() + DURATION_SUFFIX), pointTags)).
        update(spanDurationMicros);
  }

  private String sanitize(String s) {
    Pattern WHITESPACE = Pattern.compile("[\\s]+");
    final String whitespaceSanitized = WHITESPACE.matcher(s).replaceAll("-");
    if (s.contains("\"") || s.contains("'")) {
      // for single quotes, once we are double-quoted, single quotes can exist happily inside it.
      return whitespaceSanitized.replaceAll("\"", "\\\\\"");
    } else {
      return whitespaceSanitized;
    }
  }

  void reportSpan(WavefrontSpan span) {
    // reporter will flush it to Wavefront/proxy
    try {
      reporter.report(span);
    } catch (IOException ex) {
      logger.log(Level.WARNING, "Error reporting span", ex);
    }
  }

  long currentTimeMicros() {
    return System.currentTimeMillis() * 1000;
  }

  /**
   * Gets the list of global tags to be added to all spans.
   *
   * @return the list of global tags
   */
  List<Pair<String, String>> getTags() {
    return tags;
  }

  /**
   * A builder for {@link WavefrontTracer} instances.
   */
  public static class Builder {
    // tags can be repeated and include high-cardinality tags
    private final List<Pair<String, String>> tags;
    private final Reporter reporter;
    // application metadata, will not have repeated tags and will be low cardinality tags
    private final ApplicationTags applicationTags;
    private final List<Sampler> samplers;
    // Default to 1min
    private Supplier<Long> reportingFrequencyMillis = () -> 60000L;
    private boolean includeJvmMetrics = true;

    /**
     * Constructor.
     */
    public Builder(Reporter reporter, ApplicationTags applicationTags) {
      this.reporter = reporter;
      this.applicationTags = applicationTags;
      this.tags = new ArrayList<>();
      this.samplers = new ArrayList<>();
    }

    /**
     * Global tag included with every reported span.
     *
     * @param key the tag key
     * @param value the tag value
     * @return {@code this}
     */
    public Builder withGlobalTag(String key, String value) {
      if (key != null && !key.isEmpty() && value != null && !value.isEmpty()) {
        this.tags.add(Pair.of(key, value));
      }
      return this;
    }

    /**
     * Global tags included with every reported span.
     *
     * @param tags Map of tags
     * @return {@code this}
     */
    public Builder withGlobalTags(Map<String, String> tags) {
      if (tags != null && !tags.isEmpty()) {
        for (Map.Entry<String, String> tag : tags.entrySet()) {
          withGlobalTag(tag.getKey(), tag.getValue());
        }
      }
      return this;
    }

    /**
     * Global multi-valued tags included with every reported span.
     *
     * @param tags Map of multi-valued tags
     * @return {@code this}
     */
    public Builder withGlobalMultiValuedTags(Map<String, Collection<String>> tags) {
      if (tags != null && !tags.isEmpty()) {
        for (Map.Entry<String, Collection<String>> tag : tags.entrySet()) {
          for (String value : tag.getValue()) {
            withGlobalTag(tag.getKey(), value);
          }
        }
      }
      return this;
    }

    /**
     * Apply ApplicationTags as global span tags.
     */
    private void applyApplicationTags() {
      withGlobalTag(APPLICATION_TAG_KEY, applicationTags.getApplication());
      withGlobalTag(SERVICE_TAG_KEY, applicationTags.getService());
      withGlobalTag(CLUSTER_TAG_KEY,
          applicationTags.getCluster() == null ? NULL_TAG_VAL : applicationTags.getCluster());
      withGlobalTag(SHARD_TAG_KEY,
          applicationTags.getShard() == null ? NULL_TAG_VAL : applicationTags.getShard());
      withGlobalTags(applicationTags.getCustomTags());
    }

    /**
     * Sampler for sampling traces.
     *
     * Samplers can be chained by calling this method multiple times. Sampling decisions are
     * OR'd when multiple samplers are used.
     *
     * @param sampler
     * @return {@code this}
     */
    public Builder withSampler(Sampler sampler) {
      this.samplers.add(sampler);
      return this;
    }

    /**
     * Invoke this method if you already are publishing JVM metrics from your app to Wavefront.
     *
     * @return {@code this}
     */
    public Builder excludeJvmMetrics() {
      includeJvmMetrics = false;
      return this;
    }

    /**
     * Visible for testing only.
     *
     * @param reportFrequenceMillis how frequently you want to report data to Wavefront.
     * @return {@code this}
     */
    Builder setReportFrequenceMillis(long reportFrequenceMillis) {
      this.reportingFrequencyMillis = () -> reportFrequenceMillis;
      return this;
    }

    /**
     * Builds and returns the WavefrontTracer instance based on the provided configuration.
     *
     * @return a {@link WavefrontTracer}
     */
    public WavefrontTracer build() {
      applyApplicationTags();
      return new WavefrontTracer(reporter, tags, applicationTags, samplers,
          reportingFrequencyMillis, includeJvmMetrics);
    }
  }

  @Override
  public void close() throws IOException {
    this.reporter.close();
    if (wfInternalReporter != null) {
      wfInternalReporter.stop();
    }
    if (wfJvmReporter != null) {
      wfJvmReporter.stop();
    }
    if (heartbeaterService != null) {
      heartbeaterService.close();
    }
    // Note: Application code will be responsible for closing wfSender connection.
  }
}

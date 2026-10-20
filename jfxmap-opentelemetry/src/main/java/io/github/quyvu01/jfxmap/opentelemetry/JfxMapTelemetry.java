package io.github.quyvu01.jfxmap.opentelemetry;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * Traces and measures the requests of jfxmap with OpenTelemetry, as FxMap does with its {@code ActivitySource}:
 * <ul>
 *   <li>a client span {@value TelemetryNames#SPAN_REQUEST} around each request a {@link RequestClient} sends, whose
 *       W3C trace context travels with the request as the {@code traceparent} and {@code tracestate} headers;</li>
 *   <li>a server span {@value TelemetryNames#SPAN_PROCESS} around each request a {@link QueryHandler} answers, child
 *       of the trace context of the request, so a trace goes from the consumer to the owner, Java or .NET;</li>
 *   <li>the histograms {@value TelemetryNames#METRIC_CLIENT_DURATION} and
 *       {@value TelemetryNames#METRIC_SERVER_DURATION}, in seconds.</li>
 * </ul>
 * The trace context is always written and read in the W3C format, the one FxMap reads, whatever propagators the
 * application configured. Without an OpenTelemetry SDK (the no-op API), nothing is recorded and no header is added.
 *
 * <pre>{@code
 * JfxMapTelemetry telemetry = JfxMapTelemetry.create(openTelemetry);
 * DistributedMapper mapper = FxMap.builder().requestClient(telemetry.instrument(natsClient))...build();
 * NatsServer server = NatsServer.builder(connection, telemetry.instrument(handlers)).build();
 * }</pre>
 */
public final class JfxMapTelemetry {
    /** The version of jfxmap, in the {@code fxmap.version} attribute. */
    public static final String VERSION = readVersion();

    // How many ids the selector_ids attribute shows, as FxMap
    private static final int SHOWN_IDS = 5;
    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<Map<String, String>> HEADERS = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            if (carrier == null) return null;
            var value = carrier.get(key);
            if (value != null) return value;
            // Some transports change the case of the header names
            for (var entry : carrier.entrySet())
                if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
            return null;
        }
    };

    private final Tracer tracer;
    private final DoubleHistogram clientDuration;
    private final DoubleHistogram serverDuration;

    private JfxMapTelemetry(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracerProvider().tracerBuilder(TelemetryNames.INSTRUMENTATION_NAME)
                .setInstrumentationVersion(VERSION).build();
        var meter = openTelemetry.getMeterProvider().meterBuilder(TelemetryNames.INSTRUMENTATION_NAME)
                .setInstrumentationVersion(VERSION).build();
        this.clientDuration = meter.histogramBuilder(TelemetryNames.METRIC_CLIENT_DURATION).setUnit("s")
                .setDescription("The duration of the FxMap requests sent to the owners of distributed keys.").build();
        this.serverDuration = meter.histogramBuilder(TelemetryNames.METRIC_SERVER_DURATION).setUnit("s")
                .setDescription("The duration of the FxMap requests answered by this service.").build();
    }

    public static JfxMapTelemetry create(OpenTelemetry openTelemetry) {
        return new JfxMapTelemetry(Objects.requireNonNull(openTelemetry, "openTelemetry"));
    }

    /** The client with a span and a measure around each request it sends, and the trace context in the headers. */
    public RequestClient instrument(RequestClient client) {
        Objects.requireNonNull(client, "client");
        if (client instanceof InstrumentedClient) return client;
        return new InstrumentedClient(client);
    }

    /** The handler with a span and a measure around each request it answers, child of the trace of the request. */
    public QueryHandler instrument(QueryHandler handler) {
        Objects.requireNonNull(handler, "handler");
        if (handler instanceof InstrumentedHandler) return handler;
        return new InstrumentedHandler(handler);
    }

    /** Each handler, instrumented. */
    public List<QueryHandler> instrument(Collection<? extends QueryHandler> handlers) {
        return handlers.stream().map(this::instrument).toList();
    }

    private final class InstrumentedClient implements RequestClient {
        private final RequestClient client;

        InstrumentedClient(RequestClient client) {
            this.client = client;
        }

        @Override
        @SuppressWarnings("try")
        public CompletionStage<ItemsResponse> request(DistributedKeyRef key, MapRequest request, MapContext context) {
            var transport = client.transport();
            var started = System.nanoTime();
            var span = tracer.spanBuilder(TelemetryNames.SPAN_REQUEST).setSpanKind(SpanKind.CLIENT)
                    .setAttribute(TelemetryNames.DISTRIBUTED_KEY, key.name())
                    .setAttribute(TelemetryNames.TRANSPORT, transport)
                    .setAttribute(TelemetryNames.VERSION, VERSION)
                    .setAttribute(TelemetryNames.MESSAGING_SYSTEM, transport)
                    .startSpan();
            setRequestAttributes(span, request);
            var traced = Context.current().with(span);
            var headers = new LinkedHashMap<String, String>();
            PROPAGATOR.inject(traced, headers, Map::put);
            var sent = context;
            for (var header : headers.entrySet()) sent = sent.withHeader(header.getKey(), header.getValue());

            CompletionStage<ItemsResponse> response;
            try (var ignored = traced.makeCurrent()) {
                response = client.request(key, request, sent);
            } catch (RuntimeException e) {
                response = CompletableFuture.failedFuture(e);
            }
            return response.whenComplete((items, failure) -> end(span, clientDuration, started,
                    Attributes.builder().put(TelemetryNames.DISTRIBUTED_KEY, key.name())
                            .put(TelemetryNames.TRANSPORT, transport), items, failure));
        }

        @Override
        public String transport() {
            return client.transport();
        }
    }

    private final class InstrumentedHandler implements QueryHandler {
        private final QueryHandler handler;

        InstrumentedHandler(QueryHandler handler) {
            this.handler = handler;
        }

        @Override
        public DistributedKeyRef key() {
            return handler.key();
        }

        @Override
        public Class<?> entityType() {
            return handler.entityType();
        }

        @Override
        public String dbSystem() {
            return handler.dbSystem();
        }

        @Override
        @SuppressWarnings("try")
        public CompletionStage<ItemsResponse> handle(MapRequest request, MapContext context) {
            var key = handler.key().name();
            var dbSystem = handler.dbSystem();
            var started = System.nanoTime();
            // The trace of the caller: from the headers when it came over a transport, else the current one
            var parent = context.headers().containsKey(TelemetryNames.TRACEPARENT)
                    ? PROPAGATOR.extract(Context.root(), context.headers(), HEADERS) : Context.current();
            var builder = tracer.spanBuilder(TelemetryNames.SPAN_PROCESS).setSpanKind(SpanKind.SERVER)
                    .setParent(parent)
                    .setAttribute(TelemetryNames.DISTRIBUTED_KEY, key)
                    .setAttribute(TelemetryNames.VERSION, VERSION);
            if (dbSystem != null) builder.setAttribute(TelemetryNames.DB_SYSTEM, dbSystem);
            var span = builder.startSpan();
            setRequestAttributes(span, request);

            CompletionStage<ItemsResponse> response;
            try (var ignored = parent.with(span).makeCurrent()) {
                response = handler.handle(request, context);
            } catch (RuntimeException e) {
                response = CompletableFuture.failedFuture(e);
            }
            var measured = Attributes.builder().put(TelemetryNames.DISTRIBUTED_KEY, key);
            if (dbSystem != null) measured.put(TelemetryNames.DB_SYSTEM, dbSystem);
            return response.whenComplete((items, failure) -> end(span, serverDuration, started, measured, items,
                    failure));
        }
    }

    private static void setRequestAttributes(Span span, MapRequest request) {
        if (!span.isRecording()) return;
        span.setAttribute(TelemetryNames.EXPRESSIONS, request.expressions().stream()
                .map(expression -> expression == null ? "" : expression).toList());
        span.setAttribute(TelemetryNames.SELECTOR_COUNT, (long) request.selectorIds().size());
        if (!request.selectorIds().isEmpty())
            span.setAttribute(TelemetryNames.SELECTOR_IDS, request.selectorIds().stream().limit(SHOWN_IDS)
                    .collect(Collectors.joining(",")));
    }

    private static void end(Span span, DoubleHistogram duration, long started, AttributesBuilder measured,
                            ItemsResponse items, Throwable failure) {
        var seconds = (System.nanoTime() - started) / 1e9;
        if (failure != null) {
            var cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            span.recordException(cause);
            span.setStatus(StatusCode.ERROR, String.valueOf(cause.getMessage()));
            measured.put(TelemetryNames.ERROR_TYPE, cause.getClass().getName());
        } else {
            var found = items == null ? 0 : items.items().size();
            span.setAttribute(TelemetryNames.ITEM_COUNT, (long) found);
            if (items != null && !items.errors().isEmpty())
                span.setAttribute(TelemetryNames.EXPRESSION_ERROR_COUNT, (long) items.errors().size());
            span.setStatus(StatusCode.OK);
        }
        span.end();
        duration.record(seconds, measured.build());
    }

    private static String readVersion() {
        try (var stream = JfxMapTelemetry.class.getResourceAsStream("jfxmap-version.properties")) {
            if (stream == null) return "unknown";
            var properties = new Properties();
            properties.load(stream);
            return properties.getProperty("version", "unknown");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

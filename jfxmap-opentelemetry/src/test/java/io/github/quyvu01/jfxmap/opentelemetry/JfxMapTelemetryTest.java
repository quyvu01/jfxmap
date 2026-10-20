package io.github.quyvu01.jfxmap.opentelemetry;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.provider.LocalRequestClient;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ExpressionError;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings("try")
class JfxMapTelemetryTest {
    private static final DistributedKeyRef USER = DistributedKeyRef.of("UserOf");
    private static final MapRequest REQUEST = new MapRequest(List.of("1", "2", "3", "4", "5", "6"),
            java.util.Arrays.asList(null, "UserEmail"));
    private static final ItemsResponse TWO_USERS = new ItemsResponse(List.of(new DataResponse("1", List.of()),
            new DataResponse("2", List.of())));

    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final InMemoryMetricReader metrics = InMemoryMetricReader.create();
    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
            .build();
    private final JfxMapTelemetry telemetry = JfxMapTelemetry.create(sdk);
    private final AtomicReference<MapContext> sent = new AtomicReference<>();

    @AfterEach
    void closeSdk() {
        sdk.close();
    }

    private RequestClient transport(CompletionStage<ItemsResponse> answer) {
        return new RequestClient() {
            @Override
            public CompletionStage<ItemsResponse> request(DistributedKeyRef key, MapRequest request,
                                                          MapContext context) {
                sent.set(context);
                return answer;
            }

            @Override
            public String transport() {
                return "nats";
            }
        };
    }

    private QueryHandler handler(String dbSystem, ItemsResponse answer) {
        return new QueryHandler() {
            @Override
            public DistributedKeyRef key() {
                return USER;
            }

            @Override
            public Class<?> entityType() {
                return String.class;
            }

            @Override
            public String dbSystem() {
                return dbSystem;
            }

            @Override
            public CompletionStage<ItemsResponse> handle(MapRequest request, MapContext context) {
                sent.set(context);
                return CompletableFuture.completedFuture(answer);
            }
        };
    }

    private static MapContext headers(Map<String, String> headers) {
        return new MapContext(headers, null);
    }

    private SpanData onlySpan() {
        assertThat(spans.getFinishedSpanItems()).hasSize(1);
        return spans.getFinishedSpanItems().getFirst();
    }

    private HistogramPointData onlyPoint(String metric) {
        var data = metrics.collectAllMetrics().stream().filter(m -> m.getName().equals(metric))
                .map(MetricData::getHistogramData).findFirst().orElseThrow();
        assertThat(data.getPoints()).hasSize(1);
        return data.getPoints().iterator().next();
    }

    @Test
    void tracesARequestSentAndCarriesItsTraceContext() {
        var client = telemetry.instrument(transport(CompletableFuture.completedFuture(TWO_USERS)));

        client.request(USER, REQUEST, headers(Map.of("x-caller", "orders"))).toCompletableFuture().join();

        var span = onlySpan();
        assertThat(span.getName()).isEqualTo("fxmap.request");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(span.getInstrumentationScopeInfo().getName()).isEqualTo("FxMap");
        var attributes = span.getAttributes();
        assertThat(attributes.get(TelemetryNames.DISTRIBUTED_KEY)).isEqualTo("UserOf");
        assertThat(attributes.get(TelemetryNames.TRANSPORT)).isEqualTo("nats");
        assertThat(attributes.get(TelemetryNames.MESSAGING_SYSTEM)).isEqualTo("nats");
        assertThat(attributes.get(TelemetryNames.VERSION)).isEqualTo(JfxMapTelemetry.VERSION);
        assertThat(attributes.get(TelemetryNames.EXPRESSIONS)).containsExactly("", "UserEmail");
        assertThat(attributes.get(TelemetryNames.SELECTOR_COUNT)).isEqualTo(6L);
        assertThat(attributes.get(TelemetryNames.SELECTOR_IDS)).isEqualTo("1,2,3,4,5");
        assertThat(attributes.get(TelemetryNames.ITEM_COUNT)).isEqualTo(2L);
        assertThat(sent.get().headers()).containsEntry("x-caller", "orders");
        assertThat(sent.get().headers().get("traceparent"))
                .startsWith("00-" + span.getTraceId() + "-" + span.getSpanId() + "-");

        var point = onlyPoint("fxmap.client.request.duration");
        assertThat(point.getCount()).isEqualTo(1);
        assertThat(point.getAttributes().get(TelemetryNames.DISTRIBUTED_KEY)).isEqualTo("UserOf");
        assertThat(point.getAttributes().get(TelemetryNames.TRANSPORT)).isEqualTo("nats");
        assertThat(point.getAttributes().get(TelemetryNames.ERROR_TYPE)).isNull();
    }

    @Test
    void recordsAFailedRequest() {
        var client = telemetry.instrument(transport(CompletableFuture.failedFuture(new IllegalStateException("down"))));

        var response = client.request(USER, REQUEST, headers(Map.of()));

        assertThatThrownBy(() -> response.toCompletableFuture().join()).hasRootCauseMessage("down");
        var span = onlySpan();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).isEqualTo("down");
        assertThat(span.getEvents()).extracting(e -> e.getName()).containsExactly("exception");
        assertThat(onlyPoint("fxmap.client.request.duration").getAttributes().get(TelemetryNames.ERROR_TYPE))
                .isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void aTransportThatThrowsFailsTheRequestAndEndsTheSpan() {
        RequestClient throwing = (key, request, context) -> {
            throw new IllegalArgumentException("thrown");
        };

        var response = telemetry.instrument(throwing).request(USER, REQUEST, headers(Map.of()));

        assertThatThrownBy(() -> response.toCompletableFuture().join())
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(onlySpan().getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(onlySpan().getAttributes().get(TelemetryNames.TRANSPORT)).isEqualTo("custom");
    }

    @Test
    void tracesARequestAnsweredAsAChildOfItsTraceContext() {
        var traceId = "0af7651916cd43dd8448eb211c80319c";
        var parentId = "b7ad6b7169203331";
        var handler = telemetry.instrument(handler("jpa", new ItemsResponse(List.of(),
                List.of(new ExpressionError("Bad", "bad expression")))));

        handler.handle(REQUEST, headers(Map.of("traceparent", "00-" + traceId + "-" + parentId + "-01")))
                .toCompletableFuture().join();

        var span = onlySpan();
        assertThat(span.getName()).isEqualTo("fxmap.process");
        assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
        assertThat(span.getTraceId()).isEqualTo(traceId);
        assertThat(span.getParentSpanId()).isEqualTo(parentId);
        assertThat(span.getParentSpanContext().isRemote()).isTrue();
        assertThat(span.getAttributes().get(TelemetryNames.DISTRIBUTED_KEY)).isEqualTo("UserOf");
        assertThat(span.getAttributes().get(TelemetryNames.DB_SYSTEM)).isEqualTo("jpa");
        assertThat(span.getAttributes().get(TelemetryNames.ITEM_COUNT)).isZero();
        assertThat(span.getAttributes().get(TelemetryNames.EXPRESSION_ERROR_COUNT)).isEqualTo(1L);
        var point = onlyPoint("fxmap.server.request.duration");
        assertThat(point.getAttributes().get(TelemetryNames.DB_SYSTEM)).isEqualTo("jpa");
    }

    @Test
    void readsTheTraceContextWhateverTheCaseOfItsHeader() {
        var traceId = "0af7651916cd43dd8448eb211c80319c";
        var handler = telemetry.instrument(handler(null, TWO_USERS));

        handler.handle(REQUEST, headers(Map.of("traceparent", "00-" + traceId + "-b7ad6b7169203331-01",
                "TraceState", "vendor=1"))).toCompletableFuture().join();

        assertThat(onlySpan().getSpanContext().getTraceState().get("vendor")).isEqualTo("1");
        assertThat(onlySpan().getAttributes().get(TelemetryNames.DB_SYSTEM)).isNull();
    }

    @Test
    void aRequestAnsweredInProcessIsAChildOfTheCurrentSpan() {
        var handler = telemetry.instrument(handler(null, TWO_USERS));
        var parent = sdk.getTracer("test").spanBuilder("caller").startSpan();

        try (var ignored = parent.makeCurrent()) {
            handler.handle(REQUEST, headers(Map.of())).toCompletableFuture().join();
        } finally {
            parent.end();
        }

        var process = spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals("fxmap.process"))
                .findFirst().orElseThrow();
        assertThat(process.getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
    }

    @Test
    void theTraceGoesFromTheClientToTheHandler() {
        var users = telemetry.instrument(handler("mongodb", TWO_USERS));
        var client = telemetry.instrument(new LocalRequestClient(users));

        client.request(USER, REQUEST, headers(Map.of())).toCompletableFuture().join();

        var request = spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals("fxmap.request"))
                .findFirst().orElseThrow();
        var process = spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals("fxmap.process"))
                .findFirst().orElseThrow();
        assertThat(process.getTraceId()).isEqualTo(request.getTraceId());
        assertThat(process.getParentSpanId()).isEqualTo(request.getSpanId());
        assertThat(request.getAttributes().get(TelemetryNames.TRANSPORT)).isEqualTo("local");
    }

    @Test
    void aHandlerThatFailsRecordsIt() {
        QueryHandler failing = new QueryHandler() {
            @Override
            public DistributedKeyRef key() {
                return USER;
            }

            @Override
            public Class<?> entityType() {
                return String.class;
            }

            @Override
            public CompletionStage<ItemsResponse> handle(MapRequest request, MapContext context) {
                throw new IllegalStateException("database down");
            }
        };

        var response = telemetry.instrument(failing).handle(REQUEST, headers(Map.of()));

        assertThatThrownBy(() -> response.toCompletableFuture().join()).hasRootCauseMessage("database down");
        assertThat(onlySpan().getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(onlyPoint("fxmap.server.request.duration").getAttributes().get(TelemetryNames.ERROR_TYPE))
                .isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void withoutAnSdkNothingIsAddedToTheHeaders() {
        var client = JfxMapTelemetry.create(OpenTelemetry.noop())
                .instrument(transport(CompletableFuture.completedFuture(TWO_USERS)));

        client.request(USER, REQUEST, headers(Map.of("x-caller", "orders"))).toCompletableFuture().join();

        assertThat(sent.get().headers()).containsOnlyKeys("x-caller");
    }

    @Test
    void instrumentingKeepsWhatTheClientAndTheHandlerSayAndIsDoneOnce() {
        var client = telemetry.instrument(transport(CompletableFuture.completedFuture(TWO_USERS)));
        var handler = telemetry.instrument(handler("jpa", TWO_USERS));

        assertThat(client.transport()).isEqualTo("nats");
        assertThat(telemetry.instrument(client)).isSameAs(client);
        assertThat(handler.key()).isEqualTo(USER);
        assertThat(handler.entityType()).isEqualTo(String.class);
        assertThat(handler.dbSystem()).isEqualTo("jpa");
        assertThat(telemetry.instrument(handler)).isSameAs(handler);
        assertThat(telemetry.instrument(List.of(handler, handler(null, TWO_USERS)))).hasSize(2)
                .first().isSameAs(handler);
        assertThatThrownBy(() -> JfxMapTelemetry.create(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void knowsTheVersionOfJfxmap() {
        assertThat(JfxMapTelemetry.VERSION).isNotBlank().isNotEqualTo("unknown").doesNotContain("$");
    }
}

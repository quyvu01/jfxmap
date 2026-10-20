package io.github.quyvu01.jfxmap.opentelemetry;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.provider.InMemoryQueryHandler;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One OpenTelemetry for a client and a server in the same test: the request span of the client and the process span
 * of the server it reached, which must be one trace.
 */
final class Traces implements AutoCloseable {
    static final DistributedKeyRef USER_OF = DistributedKeyRef.of("UserOf");

    record User(int id, String name) {
    }

    static class UserConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::id);
            defaultProperty(User::name);
            useDistributedKey("UserOf");
        }
    }

    final InMemorySpanExporter spans = InMemorySpanExporter.create();
    final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .build();
    final JfxMapTelemetry telemetry = JfxMapTelemetry.create(sdk);

    /** The users handler, traced. */
    List<QueryHandler> handlers() {
        return List.of(telemetry.instrument(new InMemoryQueryHandler<>(new UserConfig(),
                List.of(new User(1, "Alice"), new User(2, "Bob")))));
    }

    /**
     * Sends a request with the traced client, then checks that the server's span is the child of the client's, over
     * the transport.
     */
    void assertCarriedOver(RequestClient client, String transport) throws InterruptedException {
        send(client, transport);

        var request = span(TelemetryNames.SPAN_REQUEST);
        var process = span(TelemetryNames.SPAN_PROCESS);
        assertThat(process.getTraceId()).isEqualTo(request.getTraceId());
        assertThat(process.getParentSpanId()).isEqualTo(request.getSpanId());
        assertThat(process.getParentSpanContext().isRemote()).isTrue();
    }

    /** Sends a request with the traced client; the server's span starts a trace of its own. */
    void assertNotCarriedOver(RequestClient client, String transport) throws InterruptedException {
        send(client, transport);

        var request = span(TelemetryNames.SPAN_REQUEST);
        var process = span(TelemetryNames.SPAN_PROCESS);
        assertThat(process.getTraceId()).isNotEqualTo(request.getTraceId());
        assertThat(process.getParentSpanContext().isValid()).isFalse();
    }

    private void send(RequestClient client, String transport) throws InterruptedException {
        var response = telemetry.instrument(client).request(USER_OF,
                new MapRequest(List.of("1", "2"), Arrays.asList((String) null)),
                new MapContext(Map.of("authorization", "Bearer x"), null)).toCompletableFuture().join();
        assertThat(response.items()).hasSize(2);

        var request = span(TelemetryNames.SPAN_REQUEST);
        var process = span(TelemetryNames.SPAN_PROCESS);
        assertThat(request.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(request.getAttributes().get(TelemetryNames.TRANSPORT)).isEqualTo(transport);
        assertThat(process.getKind()).isEqualTo(SpanKind.SERVER);
        assertThat(process.getAttributes().get(TelemetryNames.ITEM_COUNT)).isEqualTo(2L);
    }

    private SpanData span(String name) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var found = spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name)).toList();
            if (!found.isEmpty()) {
                assertThat(found).hasSize(1);
                return found.getFirst();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No " + name + " span in " + spans.getFinishedSpanItems());
    }

    @Override
    public void close() {
        sdk.close();
    }
}

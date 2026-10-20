package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.grpc.FxMapGrpcService;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.spring.Fixtures.UserConfig;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JfxMapPartsTest {
    @Test
    void requestClientFailsAKeyItCannotSend() {
        var client = new JfxMapRequestClient(new JfxMapQueryHandlers(List.of(), List.of(), List.of()), null);

        assertThat(client.request(DistributedKeyRef.of("UserOf"), new MapRequest(List.of("1"), List.of("Name")),
                MapContext.empty()).toCompletableFuture())
                .failsWithin(java.time.Duration.ofSeconds(1))
                .withThrowableOfType(java.util.concurrent.ExecutionException.class)
                .withCauseInstanceOf(DistributedMapException.NoHandlerForDistributedKey.class);
        assertThatThrownBy(() -> new JfxMapRequestClient(null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void queryHandlersAskTheFactoriesInOrder() {
        var handler = Fixtures.inMemoryUsers();
        EntityQueryHandlerFactory none = config -> Optional.empty();
        EntityQueryHandlerFactory memory = config -> Optional.of(handler);

        var handlers = new JfxMapQueryHandlers(List.of(), List.of(new UserConfig()), List.of(none, memory));

        assertThat(handlers.get(DistributedKeyRef.of("UserOf"))).isSameAs(handler);
        assertThat(handlers.get(DistributedKeyRef.of("OtherOf"))).isNull();
    }

    @Test
    void grpcServerStartsOnceAndStopsWhenRunning() {
        var server = new JfxMapGrpcServer(new FxMapGrpcService(List.of()), 0);
        assertThat(server.getPort()).isEqualTo(-1);
        server.stop();

        server.start();
        var port = server.getPort();
        server.start();

        assertThat(server.isRunning()).isTrue();
        assertThat(server.getPort()).isEqualTo(port);
        server.stop();
        assertThat(server.isRunning()).isFalse();
    }

    @Test
    void grpcServerReportsAPortInUse() throws Exception {
        try (var socket = new ServerSocket(0)) {
            var server = new JfxMapGrpcServer(new FxMapGrpcService(List.of()), socket.getLocalPort());

            assertThatThrownBy(server::start).isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining(String.valueOf(socket.getLocalPort()));
        }
    }
}

package io.github.quyvu01.jfxmap.rabbitmq;

import com.rabbitmq.client.Connection;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.provider.InMemoryQueryHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the client and the server check before they touch the connection. */
class RabbitMqBuildersTest {
    record User(int id, String name) {
    }

    static class UserConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::id);
            useDistributedKey("UserOf");
        }
    }

    private static final Connection CONNECTION = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });

    @Test
    void theClientNeedsAConnectionAPositiveTimeoutAndAnExecutor() {
        assertThatThrownBy(() -> RabbitMqRequestClient.builder(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RabbitMqRequestClient.builder(CONNECTION).timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitMqRequestClient.builder(CONNECTION).executor(null))
                .isInstanceOf(NullPointerException.class);
        try (var client = RabbitMqRequestClient.builder(CONNECTION).timeout(Duration.ofSeconds(1)).build()) {
            assertThat(client.pendingRequests()).isZero();
        }
    }

    @Test
    void theServerConsumesTheQueueOfItsApplication() {
        var server = RabbitMqServer.builder(CONNECTION, "UserService",
                List.of(new InMemoryQueryHandler<>(new UserConfig(), List.of()))).build();

        assertThat(server.queue()).isEqualTo("fxmap-rpc-queue-userservice");
        assertThat(server.keys()).containsExactly(DistributedKeyRef.of("UserOf"));
        assertThat(server.isRunning()).isFalse();
    }

    @Test
    void aServerWithoutKeysDoesNotStart() {
        var server = RabbitMqServer.builder(CONNECTION, "empty", List.of()).build().start();

        assertThat(server.isRunning()).isFalse();
    }

    @Test
    void theServerRejectsBadOptionsAndSharedKeys() {
        var handler = new InMemoryQueryHandler<>(new UserConfig(), List.<User>of());

        assertThatThrownBy(() -> RabbitMqServer.builder(CONNECTION, " ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitMqServer.builder(null, "a", List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RabbitMqServer.builder(CONNECTION, "a", List.of()).channelPoolSize(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitMqServer.builder(CONNECTION, "a", List.of()).maxConcurrentProcessing(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitMqServer.builder(CONNECTION, "a", List.of()).timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitMqServer.builder(CONNECTION, "a", List.of(handler, handler)).build())
                .isInstanceOf(DistributedMapException.DistributedKeyAssignedToMultipleEntities.class);
    }
}

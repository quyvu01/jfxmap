package io.github.quyvu01.jfxmap.nats;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.provider.InMemoryQueryHandler;
import io.nats.client.Connection;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the client and the server check before they touch the connection. */
class NatsBuildersTest {
    record User(int id, String name) {
    }

    static class UserConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::id);
            useDistributedKey("UserOf");
        }
    }

    // Never used: the builders only keep it
    private static final Connection CONNECTION = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });

    @Test
    void theClientNeedsAConnectionAndAPositiveTimeout() {
        assertThatThrownBy(() -> NatsRequestClient.builder(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NatsRequestClient.builder(CONNECTION).timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NatsRequestClient.builder(CONNECTION).timeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(NatsRequestClient.builder(CONNECTION).topicPrefix("p").timeout(Duration.ofSeconds(1)).build())
                .isNotNull();
    }

    @Test
    void theServerListensOnTheSubjectOfEachKey() {
        var server = NatsServer.builder(CONNECTION, List.of(new InMemoryQueryHandler<>(new UserConfig(), List.of())))
                .topicPrefix("Prod").build();

        assertThat(server.keys()).containsExactly(DistributedKeyRef.of("UserOf"));
        assertThat(server.subjects()).containsEntry(DistributedKeyRef.of("UserOf"), "prod-fxmap--userof");
        assertThat(server.isRunning()).isFalse();
        server.close();
    }

    @Test
    void theServerRejectsBadOptionsAndSharedKeys() {
        var handler = new InMemoryQueryHandler<>(new UserConfig(), List.<User>of());

        assertThatThrownBy(() -> NatsServer.builder(CONNECTION, List.of()).maxConcurrentProcessing(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NatsServer.builder(CONNECTION, List.of()).timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NatsServer.builder(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NatsServer.builder(CONNECTION, List.of(handler, handler)).build())
                .isInstanceOf(DistributedMapException.DistributedKeyAssignedToMultipleEntities.class);
    }

    @Test
    void theHeadersOfAMessageAreItsFirstValues() {
        var headers = new Headers().add("tenant", "a", "b").add("user", "u");
        var message = NatsMessage.builder().subject("s").headers(headers).data(new byte[0]).build();

        assertThat(NatsServer.headers(message)).containsOnly(Map.entry("tenant", "a"),
                Map.entry("user", "u"));
        assertThat(NatsServer.headers(NatsMessage.builder().subject("s").data(new byte[0]).build())).isEmpty();
    }
}

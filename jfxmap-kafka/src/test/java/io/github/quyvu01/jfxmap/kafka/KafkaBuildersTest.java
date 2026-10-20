package io.github.quyvu01.jfxmap.kafka;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.provider.InMemoryQueryHandler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the client and the server check before they connect. */
class KafkaBuildersTest {
    record User(int id, String name) {
    }

    static class UserConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::id);
            useDistributedKey("UserOf");
        }
    }

    @Test
    void theClientNeedsServersAnApplicationAndValidOptions() {
        assertThatThrownBy(() -> KafkaRequestClient.builder(" ", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaRequestClient.builder("k:9092", " ")).isInstanceOf(IllegalArgumentException.class);
        var builder = KafkaRequestClient.builder("k:9092", "a");
        assertThatThrownBy(() -> builder.timeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.replicationFactor((short) 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.executor(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.property(null, "x")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aClientConnectsOnlyOnItsFirstRequest() {
        try (var client = KafkaRequestClient.builder("localhost:1", "OrderService").property("client.id", "t").build()) {
            assertThat(client.replyTopic()).startsWith("fxmap-response-topic-orderservice-");
            assertThat(client.pendingRequests()).isZero();
        }
    }

    @Test
    void theServerReadsTheRequestTopicOfEachKey() {
        var server = KafkaServer.builder("localhost:1", List.of(new InMemoryQueryHandler<>(new UserConfig(),
                List.of()))).build();

        assertThat(server.keys()).containsExactly(DistributedKeyRef.of("UserOf"));
        assertThat(server.topics()).containsEntry(DistributedKeyRef.of("UserOf"), "fxmap-request-topic-userof");
        assertThat(server.isRunning()).isFalse();
        server.close();
    }

    @Test
    void aServerWithoutKeysDoesNotStart() {
        assertThat(KafkaServer.builder("localhost:1", List.of()).build().start().isRunning()).isFalse();
    }

    @Test
    void theServerRejectsBadOptionsAndSharedKeys() {
        var handler = new InMemoryQueryHandler<>(new UserConfig(), List.<User>of());
        var builder = KafkaServer.builder("k:9092", List.of());

        assertThatThrownBy(() -> KafkaServer.builder("", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.partitions(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.replicationFactor((short) 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.maxConcurrentProcessing(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.timeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.property("x", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> KafkaServer.builder("k:9092", List.of(handler, handler)).build())
                .isInstanceOf(DistributedMapException.DistributedKeyAssignedToMultipleEntities.class);
    }
}

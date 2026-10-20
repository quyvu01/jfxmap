package io.github.quyvu01.jfxmap.azure.servicebus;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorSource;
import com.azure.messaging.servicebus.ServiceBusException;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.provider.InMemoryQueryHandler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the client and the server check before they connect. */
class ServiceBusBuildersTest {
    record User(int id, String name) {
    }

    static class UserConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::id);
            useDistributedKey("UserOf");
        }
    }

    // Nothing connects until a client is built from it
    private static final ServiceBusClientBuilder CLIENTS = new ServiceBusClientBuilder();

    @Test
    void theClientNeedsClientsAndAPositiveTimeout() {
        assertThatThrownBy(() -> ServiceBusRequestClient.builder(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ServiceBusRequestClient.builder(CLIENTS).timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        try (var client = ServiceBusRequestClient.builder(CLIENTS).topicPrefix("t").timeout(Duration.ofSeconds(1))
                .build()) {
            assertThat(client.pendingRequests()).isZero();
            assertThat(client.sessionId()).hasSize(36);
        }
    }

    @Test
    void aClosedClientRefusesRequests() {
        var client = ServiceBusRequestClient.builder(CLIENTS).build();
        client.close();

        assertThat(client.request(DistributedKeyRef.of("UserOf"),
                new io.github.quyvu01.jfxmap.response.MapRequest(List.of("1"), List.of()),
                new io.github.quyvu01.jfxmap.MapContext(Map.of(), null))).isCompletedExceptionally();
    }

    @Test
    void theServerReadsTheRequestQueueOfEachKey() {
        var server = ServiceBusServer.builder(CLIENTS, List.of(new InMemoryQueryHandler<>(new UserConfig(), List.of())))
                .topicPrefix("tenant").build();

        assertThat(server.keys()).containsExactly(DistributedKeyRef.of("UserOf"));
        assertThat(server.requestQueues()).containsExactly(
                Map.entry(DistributedKeyRef.of("UserOf"), "tenant-fxmap-request--userof"));
        assertThat(server.isRunning()).isFalse();
    }

    @Test
    void aServerWithoutKeysDoesNotStart() {
        var server = ServiceBusServer.builder(CLIENTS, List.of()).build().start();

        assertThat(server.isRunning()).isFalse();
    }

    @Test
    void theServerRejectsBadOptionsAndSharedKeys() {
        var handler = new InMemoryQueryHandler<>(new UserConfig(), List.<User>of());

        assertThatThrownBy(() -> ServiceBusServer.builder(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ServiceBusServer.builder(CLIENTS, List.of()).maxConcurrentSessions(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServiceBusServer.builder(CLIENTS, List.of()).timeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServiceBusServer.builder(CLIENTS, List.of(handler, handler)).build())
                .isInstanceOf(DistributedMapException.DistributedKeyAssignedToMultipleEntities.class);
    }

    @Test
    void aMissingEntityIsFoundInTheCauses() {
        var missing = new ServiceBusException(new com.azure.core.amqp.exception.AmqpException(false,
                com.azure.core.amqp.exception.AmqpErrorCondition.NOT_FOUND, "no queue", null),
                ServiceBusErrorSource.SEND);

        assertThat(ServiceBusMessages.isMissingEntity(new RuntimeException(missing))).isTrue();
        assertThat(ServiceBusMessages.isMissingEntity(new IllegalStateException("other"))).isFalse();
    }

    @Test
    void theHeadersAreTheApplicationPropertiesAsStringsAndBytesAsUtf8() {
        var properties = new java.util.LinkedHashMap<String, Object>();
        properties.put("tenant", "a");
        properties.put("count", 3);
        properties.put("traceparent", "00-abc-01".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        properties.put("none", null);

        assertThat(ServiceBusMessages.headers(properties)).containsExactly(Map.entry("tenant", "a"),
                Map.entry("count", "3"), Map.entry("traceparent", "00-abc-01"));
        assertThat(ServiceBusMessages.headers((Map<String, Object>) null)).isEmpty();
    }
}

package io.github.quyvu01.jfxmap.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.FxMap;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import io.github.quyvu01.jfxmap.provider.InMemoryQueryHandler;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client and the server against a real Kafka broker, and the messages they exchange with FxMap's. */
@Testcontainers
class KafkaTransportIntegrationTest {
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    record User(int id, String name, String email) {
    }

    // Each test has keys of its own: the topics and the server group outlive a test
    static EntityConfigureOf<User> userConfig(String key) {
        return new EntityConfigureOf<>(User.class) {
            @Override
            protected void configure() {
                id(User::id);
                defaultProperty(User::name);
                useDistributedKey(key);
                exposedName(User::email, "UserEmail");
            }
        };
    }

    static class OrderDto {
        int userId;
        String userName;
        String userEmail;

        OrderDto(int userId) {
            this.userId = userId;
        }

        int userId() {
            return userId;
        }

        String userName() {
            return userName;
        }

        String userEmail() {
            return userEmail;
        }
    }

    static class OrderDtoProfile extends ProfileOf<OrderDto> {
        @Override
        protected void configure() {
            useDistributedKey("MappedUserOf")
                    .of(OrderDto::userId)
                    .map(OrderDto::userName)
                    .map(OrderDto::userEmail, "UserEmail");
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (var closeable : closeables.reversed()) closeable.close();
    }

    private KafkaServer serve(KafkaServer.Builder builder) {
        var server = builder.build().start();
        closeables.add(server);
        return server;
    }

    private KafkaRequestClient client(Duration timeout) {
        var client = KafkaRequestClient.builder(KAFKA.getBootstrapServers(), "OrderService").timeout(timeout).build();
        closeables.add(client);
        return client;
    }

    private static InMemoryQueryHandler<User> users(String key) {
        return new InMemoryQueryHandler<>(userConfig(key),
                List.of(new User(1, "Alice", "alice@example.com"), new User(2, "Bob", "bob@example.com")));
    }

    private static ItemsResponse request(KafkaRequestClient client, String key, Map<String, String> headers,
                                         String... expressions) {
        return client.request(DistributedKeyRef.of(key), new MapRequest(List.of("1", "2"), Arrays.asList(expressions)),
                new MapContext(headers, null)).toCompletableFuture().join();
    }

    @Test
    void mapsADtoThroughKafka() {
        serve(KafkaServer.builder(KAFKA.getBootstrapServers(), List.of(users("MappedUserOf"))));
        var mapper = FxMap.builder().addProfiles(new OrderDtoProfile())
                .requestClient(client(Duration.ofSeconds(30))).throwIfException().build();
        var dtos = List.of(new OrderDto(1), new OrderDto(2), new OrderDto(3));

        mapper.map(dtos);

        assertThat(dtos).extracting(d -> d.userName).containsExactly("Alice", "Bob", null);
        assertThat(dtos).extracting(d -> d.userEmail).containsExactly("alice@example.com", "bob@example.com", null);
    }

    @Test
    void carriesTheHeaders() {
        var seen = new AtomicReference<MapContext>();
        QueryHandler recording = new QueryHandler() {
            @Override
            public DistributedKeyRef key() {
                return DistributedKeyRef.of("HeadersOf");
            }

            @Override
            public Class<?> entityType() {
                return User.class;
            }

            @Override
            public CompletionStage<ItemsResponse> handle(MapRequest request, MapContext context) {
                seen.set(context);
                return CompletableFuture.completedFuture(ItemsResponse.empty());
            }
        };
        serve(KafkaServer.builder(KAFKA.getBootstrapServers(), List.of(recording)));

        request(client(Duration.ofSeconds(30)), "HeadersOf", Map.of("authorization", "Bearer x"), (String) null);

        assertThat(seen.get().headers()).containsEntry("authorization", "Bearer x");
    }

    @Test
    void anOwnerFailureComesBackAsAReceivedFault() {
        QueryHandler failing = new InMemoryQueryHandler<>(userConfig("FailingOf"), () -> {
            throw new IllegalStateException("database down");
        });
        serve(KafkaServer.builder(KAFKA.getBootstrapServers(), List.of(failing)));

        assertThatThrownBy(() -> request(client(Duration.ofSeconds(30)), "FailingOf", Map.of(), "Name"))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(DistributedMapException.ReceivedFault.class)
                .hasMessageContaining("database down");
    }

    @Test
    void aKeyWithoutRequestTopicFailsAtOnce() {
        assertThatThrownBy(() -> request(client(Duration.ofSeconds(30)), "NobodyOf", Map.of(), "Name"))
                .hasCauseInstanceOf(DistributedMapException.NoHandlerForDistributedKey.class);
    }

    @Test
    void aReplyThatDoesNotComeTimesOut() {
        // A request topic that no server reads
        KafkaTopics.create(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                List.of("fxmap-request-topic-silentof"), 1, (short) 1);
        var client = client(Duration.ofSeconds(2));

        assertThatThrownBy(() -> request(client, "SilentOf", Map.of(), "Name"))
                .hasCauseInstanceOf(DistributedMapException.RequestTimedOut.class);
        assertThat(client.pendingRequests()).isZero();
    }

    @Test
    void theClientSendsWhatAnFxMapServerReadsAndReadsWhatItReplies() throws Exception {
        KafkaTopics.create(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                List.of("fxmap-request-topic-wireuserof"), 1, (short) 1);
        var client = client(Duration.ofSeconds(30));
        var sent = client.request(DistributedKeyRef.of("WireUserOf"), new MapRequest(List.of("1"), List.of("UserEmail")),
                new MapContext(Map.of("tenant", "a"), null)).toCompletableFuture();

        // What FxMap's KafkaServer does: read the wrapped request, reply on ReplyTo with the same key
        try (var consumer = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "fake-fxmap-server",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer());
             var producer = new KafkaProducer<>(Map.<String, Object>of(
                     ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                     new StringSerializer(), new StringSerializer())) {
            consumer.subscribe(List.of("fxmap-request-topic-wireuserof"));
            ConsumerRecord<String, String> record = null;
            for (int i = 0; i < 100 && record == null; i++)
                for (var polled : consumer.poll(Duration.ofMillis(300))) record = polled;
            assertThat(record).isNotNull();
            var request = JSON.readTree(record.value());
            assertThat(request.get("Message")).isEqualTo(
                    JSON.readTree("{\"SelectorIds\":[\"1\"],\"Expressions\":[\"UserEmail\"]}"));
            assertThat(request.get("ReplyTo").asText()).isEqualTo(client.replyTopic());
            assertThat(new String(record.headers().lastHeader("tenant").value(), UTF_8)).isEqualTo("a");
            producer.send(new ProducerRecord<>(request.get("ReplyTo").asText(), record.key(),
                    "{\"IsSuccess\":true,\"Data\":{\"Items\":[{\"Id\":\"1\",\"Values\":[{\"Expression\":"
                    + "\"UserEmail\",\"Value\":\"\\\"alice@example.com\\\"\"}]}],\"Errors\":[]},\"Fault\":null}"));
        }

        assertThat(sent.join().items()).containsExactly(
                new DataResponse("1", List.of(new ValueResponse("UserEmail", "\"alice@example.com\""))));
    }

    @Test
    void theServerAnswersWhatAnFxMapClientSends() throws Exception {
        serve(KafkaServer.builder(KAFKA.getBootstrapServers(), List.of(users("FxClientUserOf"))));
        var replyTopic = "fxmap-response-topic-orderservice-0123456789abcdef0123456789abcdef";
        KafkaTopics.create(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                List.of(replyTopic), 1, (short) 1);

        // What FxMap's KafkaClient sends: the wrapped request keyed by its correlation id
        try (var producer = new KafkaProducer<>(Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer());
             var consumer = new KafkaConsumer<>(Map.<String, Object>of(
                     ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                     ConsumerConfig.GROUP_ID_CONFIG, "fake-fxmap-client",
                     ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                     new StringDeserializer(), new StringDeserializer())) {
            producer.send(new ProducerRecord<>("fxmap-request-topic-fxclientuserof", "c-1",
                    "{\"Message\":{\"SelectorIds\":[\"2\"],\"Expressions\":[null,\"UserEmail\"]},\"ReplyTo\":\""
                    + replyTopic + "\"}")).get();
            consumer.subscribe(List.of(replyTopic));
            ConsumerRecord<String, String> reply = null;
            for (int i = 0; i < 100 && reply == null; i++)
                for (var polled : consumer.poll(Duration.ofMillis(300))) reply = polled;

            assertThat(reply).isNotNull();
            assertThat(reply.key()).isEqualTo("c-1");
            assertThat(JSON.readTree(reply.value())).isEqualTo(JSON.readTree("{\"IsSuccess\":true,\"Data\":{\"Items\":"
                    + "[{\"Id\":\"2\",\"Values\":[{\"Expression\":null,\"Value\":\"\\\"Bob\\\"\"},"
                    + "{\"Expression\":\"UserEmail\",\"Value\":\"\\\"bob@example.com\\\"\"}]}],\"Errors\":[]},"
                    + "\"Fault\":null}"));
        }
    }

    @Test
    void aClosedClientRefusesRequests() {
        var client = client(Duration.ofSeconds(30));
        client.close();
        KafkaTopics.create(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                List.of("fxmap-request-topic-closedof"), 1, (short) 1);

        assertThatThrownBy(() -> request(client, "ClosedOf", Map.of(), "Name"))
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void closeCanBeRepeated() {
        var server = serve(KafkaServer.builder(KAFKA.getBootstrapServers(), List.of(users("RestartOf"))));
        server.start();
        server.close();
        server.close();

        assertThat(server.isRunning()).isFalse();
    }
}

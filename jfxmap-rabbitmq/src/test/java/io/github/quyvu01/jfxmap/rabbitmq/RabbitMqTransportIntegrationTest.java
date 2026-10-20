package io.github.quyvu01.jfxmap.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client and the server against a real RabbitMQ broker, and the messages they exchange with FxMap's. */
@Testcontainers
class RabbitMqTransportIntegrationTest {
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1");

    record User(int id, String name, String email) {
    }

    static class UserConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::id);
            defaultProperty(User::name);
            useDistributedKey("UserOf");
            exposedName(User::email, "UserEmail");
        }
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
            useDistributedKey("UserOf")
                    .of(OrderDto::userId)
                    .map(OrderDto::userName)
                    .map(OrderDto::userEmail, "UserEmail");
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DistributedKeyRef USER_OF = DistributedKeyRef.of("UserOf");

    private final List<AutoCloseable> closeables = new ArrayList<>();
    private Connection connection;

    @BeforeEach
    void connect() throws Exception {
        var factory = new ConnectionFactory();
        factory.setUri(RABBIT.getAmqpUrl());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        connection = factory.newConnection();
        // Each test starts without the exchanges and queues of the others
        try (var channel = connection.createChannel()) {
            for (var exchange : List.of("FxMap-:UserOf", "FxMap-:SilentOf")) channel.exchangeDelete(exchange);
            channel.queueDelete("fxmap-rpc-queue-userservice");
        }
    }

    @AfterEach
    void close() throws Exception {
        for (var closeable : closeables.reversed()) closeable.close();
        connection.close();
    }

    private RabbitMqServer serve(RabbitMqServer.Builder builder) {
        var server = builder.build().start();
        closeables.add(server);
        return server;
    }

    private RabbitMqRequestClient client(Duration timeout) {
        var client = RabbitMqRequestClient.builder(connection).timeout(timeout).build();
        closeables.add(client);
        return client;
    }

    private static InMemoryQueryHandler<User> users() {
        return new InMemoryQueryHandler<>(new UserConfig(),
                List.of(new User(1, "Alice", "alice@example.com"), new User(2, "Bob", "bob@example.com")));
    }

    private static ItemsResponse request(RabbitMqRequestClient client, DistributedKeyRef key,
                                         Map<String, String> headers, String... expressions) {
        return client.request(key, new MapRequest(List.of("1", "2"), Arrays.asList(expressions)),
                new MapContext(headers, null)).toCompletableFuture().join();
    }

    @Test
    void mapsADtoThroughRabbitMq() {
        serve(RabbitMqServer.builder(connection, "UserService", List.of(users())));
        var mapper = FxMap.builder().addProfiles(new OrderDtoProfile())
                .requestClient(client(Duration.ofSeconds(10))).throwIfException().build();
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
                return USER_OF;
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
        serve(RabbitMqServer.builder(connection, "UserService", List.of(recording)));

        request(client(Duration.ofSeconds(10)), USER_OF, Map.of("authorization", "Bearer x"), (String) null);

        assertThat(seen.get().headers()).containsEntry("authorization", "Bearer x");
    }

    @Test
    void anOwnerFailureComesBackAsAReceivedFault() {
        QueryHandler failing = new InMemoryQueryHandler<>(new UserConfig(), () -> {
            throw new IllegalStateException("database down");
        });
        serve(RabbitMqServer.builder(connection, "UserService", List.of(failing)));

        assertThatThrownBy(() -> request(client(Duration.ofSeconds(10)), USER_OF, Map.of(), "Name"))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(DistributedMapException.ReceivedFault.class)
                .hasMessageContaining("database down");
    }

    @Test
    void aKeyWithoutExchangeFailsAtOnce() {
        assertThatThrownBy(() -> request(client(Duration.ofSeconds(10)), DistributedKeyRef.of("NobodyOf"), Map.of(),
                "Name")).hasCauseInstanceOf(DistributedMapException.NoHandlerForDistributedKey.class);
    }

    @Test
    void aKeyWhoseExchangeHasNoQueueFailsAtOnce() throws Exception {
        try (var channel = connection.createChannel()) {
            channel.exchangeDeclare("FxMap-:SilentOf", BuiltinExchangeType.DIRECT);
        }

        assertThatThrownBy(() -> request(client(Duration.ofSeconds(10)), DistributedKeyRef.of("SilentOf"), Map.of(),
                "Name")).hasCauseInstanceOf(DistributedMapException.NoHandlerForDistributedKey.class);
    }

    @Test
    void aReplyThatDoesNotComeTimesOut() throws Exception {
        // A queue bound to the exchange that nobody answers
        try (var channel = connection.createChannel()) {
            channel.exchangeDeclare("FxMap-:SilentOf", BuiltinExchangeType.DIRECT);
            var queue = channel.queueDeclare().getQueue();
            channel.queueBind(queue, "FxMap-:SilentOf", RabbitMqNames.ROUTING_KEY);
        }
        var client = client(Duration.ofMillis(300));

        assertThatThrownBy(() -> request(client, DistributedKeyRef.of("SilentOf"), Map.of(), "Name"))
                .hasCauseInstanceOf(DistributedMapException.RequestTimedOut.class);
        assertThat(client.pendingRequests()).isZero();
    }

    @Test
    void theClientSendsWhatAnFxMapServerReadsAndReadsWhatItReplies() throws Exception {
        // What FxMap's RabbitMqServer declares, consumes and replies
        var channel = connection.createChannel();
        closeables.add(channel::close);
        channel.queueDeclare("fxmap-rpc-queue-userservice", false, false, false, null);
        channel.exchangeDeclare("FxMap-:UserOf", BuiltinExchangeType.DIRECT);
        channel.queueBind("fxmap-rpc-queue-userservice", "FxMap-:UserOf", "fxmap-rpc-rounting-key");
        var received = new AtomicReference<AMQP.BasicProperties>();
        var body = new AtomicReference<String>();
        channel.basicConsume("fxmap-rpc-queue-userservice", false, new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(String tag, Envelope envelope, AMQP.BasicProperties properties, byte[] bytes)
                    throws IOException {
                received.set(properties);
                body.set(new String(bytes, UTF_8));
                getChannel().basicPublish("", properties.getReplyTo(), true,
                        new AMQP.BasicProperties.Builder().correlationId(properties.getCorrelationId()).build(),
                        ("{\"IsSuccess\":true,\"Data\":{\"Items\":[{\"Id\":\"1\",\"Values\":[{\"Expression\":"
                         + "\"UserEmail\",\"Value\":\"\\\"alice@example.com\\\"\"}]}],\"Errors\":[]},\"Fault\":null}")
                                .getBytes(UTF_8));
                getChannel().basicAck(envelope.getDeliveryTag(), false);
            }
        });

        var response = request(client(Duration.ofSeconds(10)), USER_OF, Map.of("tenant", "a"), "UserEmail");

        assertThat(JSON.readTree(body.get())).isEqualTo(
                JSON.readTree("{\"SelectorIds\":[\"1\",\"2\"],\"Expressions\":[\"UserEmail\"]}"));
        assertThat(received.get().getType()).isEqualTo(
                "UserOf, FxMap.DynamicDistributedKeys, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null");
        assertThat(received.get().getReplyTo()).startsWith("fxmap-reply-");
        assertThat(UUID.fromString(received.get().getCorrelationId())).isNotNull();
        assertThat(received.get().getHeaders().get("tenant")).hasToString("a");
        assertThat(response.items()).containsExactly(
                new DataResponse("1", List.of(new ValueResponse("UserEmail", "\"alice@example.com\""))));
    }

    @Test
    void theServerAnswersWhatAnFxMapClientSends() throws Exception {
        serve(RabbitMqServer.builder(connection, "UserService", List.of(users())));
        // What FxMap's RabbitMqRequestClient sends: a DistributedMapRequest to the exchange of the key
        var channel = connection.createChannel();
        closeables.add(channel::close);
        var replyQueue = channel.queueDeclare("fxmap-reply-" + UUID.randomUUID().toString().replace("-", ""),
                false, true, true, null).getQueue();
        var replies = new ArrayBlockingQueue<String>(1);
        var correlation = new AtomicReference<String>();
        channel.basicConsume(replyQueue, true, new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(String tag, Envelope envelope, AMQP.BasicProperties properties, byte[] bytes) {
                correlation.set(properties.getCorrelationId());
                replies.add(new String(bytes, UTF_8));
            }
        });
        channel.basicPublish("FxMap-:UserOf", "fxmap-rpc-rounting-key", true, new AMQP.BasicProperties.Builder()
                        .correlationId("c-1").replyTo(replyQueue)
                        .type("UserOf, FxMap.DynamicDistributedKeys, Version=0.0.0.0, Culture=neutral, "
                              + "PublicKeyToken=null").build(),
                "{\"SelectorIds\":[\"2\"],\"Expressions\":[null,\"UserEmail\"]}".getBytes(UTF_8));

        var reply = replies.poll(10, TimeUnit.SECONDS);

        assertThat(correlation.get()).isEqualTo("c-1");
        assertThat(JSON.readTree(reply)).isEqualTo(JSON.readTree("{\"IsSuccess\":true,\"Data\":{\"Items\":"
                + "[{\"Id\":\"2\",\"Values\":[{\"Expression\":null,\"Value\":\"\\\"Bob\\\"\"},"
                + "{\"Expression\":\"UserEmail\",\"Value\":\"\\\"bob@example.com\\\"\"}]}],\"Errors\":[]},"
                + "\"Fault\":null}"));
    }

    @Test
    void startAndCloseCanBeRepeated() {
        var server = serve(RabbitMqServer.builder(connection, "UserService", List.of(users())));
        server.start();
        server.close();
        server.close();

        assertThat(server.isRunning()).isFalse();
        server.start();
        assertThat(request(client(Duration.ofSeconds(10)), USER_OF, Map.of(), (String) null).items()).hasSize(2);
    }

    @Test
    void aClosedClientRefusesRequests() {
        var client = client(Duration.ofSeconds(10));
        client.close();

        assertThatThrownBy(() -> request(client, USER_OF, Map.of(), "Name")).isInstanceOf(CompletionException.class);
    }
}

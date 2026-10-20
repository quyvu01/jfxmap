package io.github.quyvu01.jfxmap.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.FxMap;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import io.github.quyvu01.jfxmap.pipeline.RetryPolicy;
import io.github.quyvu01.jfxmap.provider.InMemoryQueryHandler;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import io.nats.client.Connection;
import io.nats.client.Nats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client and the server against a real NATS server, and the messages they exchange with FxMap's. */
@Testcontainers
class NatsTransportIntegrationTest {
    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.11")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

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
        connection = Nats.connect("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
    }

    @AfterEach
    void close() throws Exception {
        for (var closeable : closeables) closeable.close();
        connection.close();
    }

    private NatsServer serve(NatsServer.Builder builder) {
        var server = builder.build().start();
        closeables.add(server);
        return server;
    }

    private static InMemoryQueryHandler<User> users() {
        return new InMemoryQueryHandler<>(new UserConfig(),
                List.of(new User(1, "Alice", "alice@example.com"), new User(2, "Bob", "bob@example.com")));
    }

    private static ItemsResponse request(NatsRequestClient client, DistributedKeyRef key, Map<String, String> headers,
                                         String... expressions) {
        return client.request(key, new MapRequest(List.of("1", "2"), Arrays.asList(expressions)),
                new MapContext(headers, null)).toCompletableFuture().join();
    }

    @Test
    void mapsADtoThroughNats() throws Exception {
        serve(NatsServer.builder(connection, List.of(users())));
        connection.flush(Duration.ofSeconds(5));
        var mapper = FxMap.builder().addProfiles(new OrderDtoProfile())
                .requestClient(NatsRequestClient.builder(connection).build()).throwIfException().build();
        var dtos = List.of(new OrderDto(1), new OrderDto(2), new OrderDto(3));

        mapper.map(dtos);

        assertThat(dtos).extracting(d -> d.userName).containsExactly("Alice", "Bob", null);
        assertThat(dtos).extracting(d -> d.userEmail).containsExactly("alice@example.com", "bob@example.com", null);
    }

    @Test
    void carriesTheHeadersAndTheTopicPrefix() throws Exception {
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
        serve(NatsServer.builder(connection, List.of(recording)).topicPrefix("tenant1"));
        connection.flush(Duration.ofSeconds(5));
        var client = NatsRequestClient.builder(connection).topicPrefix("tenant1").build();

        request(client, USER_OF, Map.of("authorization", "Bearer x"), (String) null);

        assertThat(seen.get().headers()).containsEntry("authorization", "Bearer x");
    }

    @Test
    void anOwnerFailureComesBackAsAReceivedFault() throws Exception {
        QueryHandler failing = new InMemoryQueryHandler<>(new UserConfig(), () -> {
            throw new IllegalStateException("database down");
        });
        serve(NatsServer.builder(connection, List.of(failing)));
        connection.flush(Duration.ofSeconds(5));
        var client = NatsRequestClient.builder(connection).build();

        assertThatThrownBy(() -> request(client, USER_OF, Map.of(), "Name"))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(DistributedMapException.ReceivedFault.class)
                .hasMessageContaining("database down");
    }

    @Test
    void aKeyThatNobodyServesFailsAtOnce() {
        var client = NatsRequestClient.builder(connection).build();

        assertThatThrownBy(() -> request(client, DistributedKeyRef.of("NobodyOf"), Map.of(), "Name"))
                .hasCauseInstanceOf(DistributedMapException.NoHandlerForDistributedKey.class);
    }

    @Test
    void aReplyThatDoesNotComeTimesOut() throws Exception {
        // A subscriber that never answers
        var silent = connection.createDispatcher();
        silent.subscribe("fxmap--silentof", message -> {
        });
        closeables.add(() -> connection.closeDispatcher(silent));
        connection.flush(Duration.ofSeconds(5));
        var client = NatsRequestClient.builder(connection).timeout(Duration.ofMillis(300)).build();

        assertThatThrownBy(() -> request(client, DistributedKeyRef.of("SilentOf"), Map.of(), "Name"))
                .hasCauseInstanceOf(DistributedMapException.RequestTimedOut.class);
    }

    @Test
    void theClientSendsWhatAnFxMapServerReadsAndReadsWhatItReplies() throws Exception {
        var received = new AtomicReference<String>();
        // What FxMap's NatsServer would do: read a DistributedMapRequest, reply with a Result (System.Text.Json)
        var fxMapServer = connection.createDispatcher();
        fxMapServer.subscribe("fxmap--userof", message -> {
            received.set(new String(message.getData(), UTF_8));
            connection.publish(message.getReplyTo(), ("{\"IsSuccess\":true,\"Data\":{\"Items\":[{\"Id\":\"1\","
                    + "\"Values\":[{\"Expression\":\"UserEmail\",\"Value\":\"\\\"alice@example.com\\\"\"}]}],"
                    + "\"Errors\":[]}}").getBytes(UTF_8));
        });
        closeables.add(() -> connection.closeDispatcher(fxMapServer));
        connection.flush(Duration.ofSeconds(5));
        var client = NatsRequestClient.builder(connection).build();

        var response = request(client, USER_OF, Map.of(), "UserEmail");

        assertThat(JSON.readTree(received.get())).isEqualTo(
                JSON.readTree("{\"SelectorIds\":[\"1\",\"2\"],\"Expressions\":[\"UserEmail\"]}"));
        assertThat(response.items()).containsExactly(
                new DataResponse("1", List.of(new ValueResponse("UserEmail", "\"alice@example.com\""))));
    }

    @Test
    void theServerAnswersWhatAnFxMapClientSends() throws Exception {
        serve(NatsServer.builder(connection, List.of(users())));
        connection.flush(Duration.ofSeconds(5));

        // What FxMap's NatsRequestClient sends: a DistributedMapRequest on the subject of the key
        var reply = connection.request("fxmap--userof",
                "{\"SelectorIds\":[\"2\"],\"Expressions\":[null,\"UserEmail\"]}".getBytes(UTF_8),
                Duration.ofSeconds(5));

        assertThat(JSON.readTree(reply.getData())).isEqualTo(JSON.readTree("{\"IsSuccess\":true,\"Data\":{\"Items\":"
                + "[{\"Id\":\"2\",\"Values\":[{\"Expression\":null,\"Value\":\"\\\"Bob\\\"\"},"
                + "{\"Expression\":\"UserEmail\",\"Value\":\"\\\"bob@example.com\\\"\"}]}],\"Errors\":[]},"
                + "\"Fault\":null}"));
    }

    @Test
    void startAndCloseCanBeRepeated() throws Exception {
        var server = serve(NatsServer.builder(connection, List.of(users())));
        server.start();
        server.close();
        server.close();

        assertThat(server.isRunning()).isFalse();
        server.start();
        connection.flush(Duration.ofSeconds(5));
        assertThat(request(NatsRequestClient.builder(connection).build(), USER_OF, Map.of(), (String) null).items())
                .hasSize(2);
    }

    @Test
    void retriesARequestUntilAServerAnswers() {
        var retries = new AtomicInteger();
        var mapper = FxMap.builder().addProfiles(new OrderDtoProfile())
                .requestClient(NatsRequestClient.builder(connection).build())
                .retryPolicy(RetryPolicy.retries(3).delay(Duration.ofMillis(100)).onRetry((failure, delay) -> {
                    // Nobody answers the first attempt; the server is up for the second
                    if (retries.incrementAndGet() == 1) {
                        serve(NatsServer.builder(connection, List.of(users())));
                        try {
                            connection.flush(Duration.ofSeconds(5));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }))
                .throwIfException().build();
        var dtos = List.of(new OrderDto(1), new OrderDto(2));

        mapper.map(dtos);

        assertThat(dtos).extracting(d -> d.userName).containsExactly("Alice", "Bob");
        assertThat(retries).hasValue(1);
    }

    @Test
    void theRunOfTheServerEndsWhenItIsClosed() {
        var server = NatsServer.builder(connection, List.of(users())).build().start();
        var run = server.whenStopped().toCompletableFuture();
        assertThat(run).isNotDone();

        server.close();

        assertThat(run).isCompletedWithValue(null);
        assertThat(server.isRunning()).isFalse();
        assertThat(server.start().whenStopped().toCompletableFuture()).isNotDone();
        server.close();
    }
}

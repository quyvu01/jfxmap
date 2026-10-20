package io.github.quyvu01.jfxmap.azure.servicebus;

import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.azure.ServiceBusEmulatorContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client and the server against the Azure Service Bus emulator, and the messages they exchange with FxMap's. The
 * emulator has no management API: the queues are declared in servicebus-emulator.json.
 */
class ServiceBusTransportIntegrationTest {
    static final Network NETWORK = Network.newNetwork();
    @SuppressWarnings("resource")
    static final MSSQLServerContainer SQL = new MSSQLServerContainer(
            "mcr.microsoft.com/mssql/server:2022-CU14-ubuntu-22.04").acceptLicense().withNetwork(NETWORK);
    @SuppressWarnings("resource")
    static final ServiceBusEmulatorContainer EMULATOR = new ServiceBusEmulatorContainer(
            "mcr.microsoft.com/azure-messaging/servicebus-emulator:2.0.1")
            .acceptLicense()
            .withConfig(MountableFile.forClasspathResource("servicebus-emulator.json"))
            .withNetwork(NETWORK)
            .withMsSqlServerContainer(SQL);

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

    private static ServiceBusClientBuilder clients;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeAll
    static void startEmulator() {
        EMULATOR.start();
        // The emulator answers a wait for the next session only when it times out: a short one picks up new sessions
        clients = new ServiceBusClientBuilder().connectionString(EMULATOR.getConnectionString())
                .retryOptions(new AmqpRetryOptions().setTryTimeout(Duration.ofSeconds(3)));
    }

    @AfterAll
    static void stopEmulator() {
        EMULATOR.stop();
        SQL.stop();
        NETWORK.close();
    }

    @AfterEach
    void close() throws Exception {
        for (var closeable : closeables.reversed()) closeable.close();
    }

    private ServiceBusServer serve(ServiceBusServer.Builder builder) {
        var server = builder.build().start();
        closeables.add(server);
        return server;
    }

    private ServiceBusRequestClient client(Duration timeout) {
        var client = ServiceBusRequestClient.builder(clients).timeout(timeout).build();
        closeables.add(client);
        return client;
    }

    private static InMemoryQueryHandler<User> users() {
        return new InMemoryQueryHandler<>(new UserConfig(),
                List.of(new User(1, "Alice", "alice@example.com"), new User(2, "Bob", "bob@example.com")));
    }

    private static ItemsResponse request(ServiceBusRequestClient client, DistributedKeyRef key,
                                         Map<String, String> headers, String... expressions) {
        return client.request(key, new MapRequest(List.of("1", "2"), Arrays.asList(expressions)),
                new MapContext(headers, null)).toCompletableFuture().join();
    }

    /** The next message of a session queue, completed: any session, or the given one. */
    private static ServiceBusReceivedMessage receiveOne(String queue, String sessionId) {
        try (var sessions = clients.sessionReceiver().queueName(queue).buildClient();
             var receiver = sessionId == null ? sessions.acceptNextSession() : sessions.acceptSession(sessionId)) {
            for (int i = 0; i < 10; i++) {
                for (var message : receiver.receiveMessages(1, Duration.ofSeconds(3))) {
                    receiver.complete(message);
                    return message;
                }
            }
        }
        throw new AssertionError("No message on " + queue);
    }

    @Test
    void mapsADtoThroughServiceBus() {
        serve(ServiceBusServer.builder(clients, List.of(users())));
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
        serve(ServiceBusServer.builder(clients, List.of(recording)));

        request(client(Duration.ofSeconds(30)), USER_OF, Map.of("authorization", "Bearer x"), (String) null);

        assertThat(seen.get().headers()).containsEntry("authorization", "Bearer x");
    }

    @Test
    void anOwnerFailureComesBackAsAReceivedFault() {
        QueryHandler failing = new InMemoryQueryHandler<>(new UserConfig(), () -> {
            throw new IllegalStateException("database down");
        });
        serve(ServiceBusServer.builder(clients, List.of(failing)));

        assertThatThrownBy(() -> request(client(Duration.ofSeconds(30)), USER_OF, Map.of(), "Name"))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(DistributedMapException.ReceivedFault.class)
                .hasMessageContaining("database down");
    }

    @Test
    void aKeyWithoutQueueFailsAtOnce() {
        assertThatThrownBy(() -> request(client(Duration.ofSeconds(30)), DistributedKeyRef.of("NobodyOf"), Map.of(),
                "Name")).hasCauseInstanceOf(DistributedMapException.NoHandlerForDistributedKey.class);
    }

    @Test
    void aReplyThatDoesNotComeTimesOut() {
        var client = client(Duration.ofSeconds(1));

        assertThatThrownBy(() -> request(client, DistributedKeyRef.of("SilentOf"), Map.of(), "Name"))
                .hasCauseInstanceOf(DistributedMapException.RequestTimedOut.class);
        assertThat(client.pendingRequests()).isZero();
    }

    @Test
    void aPrefixNamesTheQueuesOfBothSides() {
        serve(ServiceBusServer.builder(clients, List.of(users())).topicPrefix("tenant"));
        var client = ServiceBusRequestClient.builder(clients).topicPrefix("tenant").timeout(Duration.ofSeconds(30))
                .build();
        closeables.add(client);

        assertThat(request(client, USER_OF, Map.of(), (String) null).items()).hasSize(2);
    }

    @Test
    void theClientSendsWhatAnFxMapServerReadsAndReadsWhatItReplies() throws Exception {
        // What FxMap's AzureServiceBusServer reads and replies
        var client = client(Duration.ofSeconds(30));
        var response = CompletableFuture.supplyAsync(() -> request(client, USER_OF, Map.of("tenant", "a"),
                "UserEmail"));

        var received = receiveOne("fxmap-request--userof", null);
        try (var sender = clients.sender().queueName(received.getReplyTo()).buildClient()) {
            sender.sendMessage(new ServiceBusMessage(("{\"IsSuccess\":true,\"Data\":{\"Items\":[{\"Id\":\"1\","
                    + "\"Values\":[{\"Expression\":\"UserEmail\",\"Value\":\"\\\"alice@example.com\\\"\"}]}],"
                    + "\"Errors\":[]},\"Fault\":null}").getBytes(UTF_8))
                    .setCorrelationId(received.getCorrelationId()).setSessionId(received.getSessionId()));
        }

        assertThat(JSON.readTree(received.getBody().toBytes())).isEqualTo(
                JSON.readTree("{\"SelectorIds\":[\"1\",\"2\"],\"Expressions\":[\"UserEmail\"]}"));
        assertThat(received.getReplyTo()).isEqualTo("fxmap-reply--userof");
        assertThat(received.getSessionId()).isEqualTo(client.sessionId());
        assertThat(UUID.fromString(received.getCorrelationId())).isNotNull();
        assertThat(received.getApplicationProperties()).containsEntry("tenant", "a");
        assertThat(response.join().items()).containsExactly(
                new DataResponse("1", List.of(new ValueResponse("UserEmail", "\"alice@example.com\""))));
    }

    @Test
    void theServerAnswersWhatAnFxMapClientSends() throws Exception {
        var seen = new AtomicReference<MapContext>();
        var users = users();
        serve(ServiceBusServer.builder(clients, List.of(new QueryHandler() {
            @Override
            public DistributedKeyRef key() {
                return users.key();
            }

            @Override
            public Class<?> entityType() {
                return users.entityType();
            }

            @Override
            public CompletionStage<ItemsResponse> handle(MapRequest request, MapContext context) {
                seen.set(context);
                return users.handle(request, context);
            }
        })));
        // What FxMap's OpenAzureServiceBusClient sends
        var session = UUID.randomUUID().toString();
        try (var sender = clients.sender().queueName("fxmap-request--userof").buildClient()) {
            var message = new ServiceBusMessage("{\"SelectorIds\":[\"2\"],\"Expressions\":[null,\"UserEmail\"]}"
                    .getBytes(UTF_8)).setCorrelationId("c-1").setReplyTo("fxmap-reply--userof").setSessionId(session);
            message.getApplicationProperties().put("tenant", "a");
            sender.sendMessage(message);
        }

        var reply = receiveOne("fxmap-reply--userof", session);

        assertThat(reply.getCorrelationId()).isEqualTo("c-1");
        assertThat(JSON.readTree(reply.getBody().toBytes())).isEqualTo(JSON.readTree("{\"IsSuccess\":true,"
                + "\"Data\":{\"Items\":[{\"Id\":\"2\",\"Values\":[{\"Expression\":null,\"Value\":\"\\\"Bob\\\"\"},"
                + "{\"Expression\":\"UserEmail\",\"Value\":\"\\\"bob@example.com\\\"\"}]}],\"Errors\":[]},"
                + "\"Fault\":null}"));
        assertThat(seen.get().headers()).containsEntry("tenant", "a");
    }

    @Test
    void startAndCloseCanBeRepeated() {
        var server = serve(ServiceBusServer.builder(clients, List.of(users())));
        server.start();
        server.close();
        server.close();

        assertThat(server.isRunning()).isFalse();
        server.start();
        assertThat(request(client(Duration.ofSeconds(30)), USER_OF, Map.of(), (String) null).items()).hasSize(2);
    }

    @Test
    void aServerWithAnAdministrationClientGoesOnWhenItCannotCreateTheQueues() {
        // The emulator has no management API: the queues it declared are used as they are
        var administration = new com.azure.messaging.servicebus.administration.ServiceBusAdministrationClientBuilder()
                .connectionString(EMULATOR.getConnectionString())
                .retryOptions(new com.azure.core.http.policy.RetryOptions(
                        new com.azure.core.http.policy.ExponentialBackoffOptions().setMaxRetries(0)))
                .buildClient();
        serve(ServiceBusServer.builder(clients, List.of(users())).administration(administration));

        assertThat(request(client(Duration.ofSeconds(30)), USER_OF, Map.of(), (String) null).items()).hasSize(2);
    }
}

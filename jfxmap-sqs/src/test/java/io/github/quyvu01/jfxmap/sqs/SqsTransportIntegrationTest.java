package io.github.quyvu01.jfxmap.sqs;

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
import io.github.quyvu01.jfxmap.supervision.ServerSupervisor;
import io.github.quyvu01.jfxmap.supervision.SupervisorOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client and the server against the SQS of LocalStack, and the messages they exchange with FxMap's. */
@Testcontainers
class SqsTransportIntegrationTest {
    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer("localstack/localstack:4.9");

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
    private static final Duration WAIT = Duration.ofSeconds(1);

    private final List<AutoCloseable> closeables = new ArrayList<>();
    private SqsClient sqs;

    static SqsClient sqsClient() {
        return SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    @BeforeEach
    void connect() {
        sqs = sqsClient();
        // Each test starts without the queues of the others
        for (var name : List.of("fxmap--userof", "fxmap--silentof")) {
            try {
                var url = sqs.getQueueUrl(b -> b.queueName(name)).queueUrl();
                sqs.deleteQueue(b -> b.queueUrl(url));
            } catch (QueueDoesNotExistException e) {
                // Not created by an earlier test
            }
        }
    }

    @AfterEach
    void close() throws Exception {
        for (var closeable : closeables.reversed()) closeable.close();
        sqs.close();
    }

    private SqsServer serve(SqsServer.Builder builder) {
        var server = builder.waitTime(WAIT).build().start();
        closeables.add(server);
        return server;
    }

    private SqsRequestClient client(Duration timeout) {
        var client = SqsRequestClient.builder(sqs).timeout(timeout).waitTime(WAIT).build();
        closeables.add(client);
        return client;
    }

    private static InMemoryQueryHandler<User> users() {
        return new InMemoryQueryHandler<>(new UserConfig(),
                List.of(new User(1, "Alice", "alice@example.com"), new User(2, "Bob", "bob@example.com")));
    }

    private static ItemsResponse request(SqsRequestClient client, DistributedKeyRef key, Map<String, String> headers,
                                         String... expressions) {
        return client.request(key, new MapRequest(List.of("1", "2"), Arrays.asList(expressions)),
                new MapContext(headers, null)).toCompletableFuture().join();
    }

    private Message receiveOne(String queueUrl) {
        for (int i = 0; i < 20; i++) {
            var messages = sqs.receiveMessage(b -> b.queueUrl(queueUrl).waitTimeSeconds(1)
                    .messageAttributeNames("All")).messages();
            if (!messages.isEmpty()) {
                sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(messages.getFirst().receiptHandle()));
                return messages.getFirst();
            }
        }
        throw new AssertionError("No message on " + queueUrl);
    }

    @Test
    void mapsADtoThroughSqs() {
        serve(SqsServer.builder(sqs, List.of(users())));
        var mapper = FxMap.builder().addProfiles(new OrderDtoProfile())
                .requestClient(client(Duration.ofSeconds(20))).throwIfException().build();
        var dtos = List.of(new OrderDto(1), new OrderDto(2), new OrderDto(3));

        mapper.map(dtos);

        assertThat(dtos).extracting(d -> d.userName).containsExactly("Alice", "Bob", null);
        assertThat(dtos).extracting(d -> d.userEmail).containsExactly("alice@example.com", "bob@example.com", null);
    }

    @Test
    void theServerCreatesTheQueueOfEachKey() {
        var server = serve(SqsServer.builder(sqs, List.of(users())));

        assertThat(server.queueUrls().get(USER_OF)).endsWith("/fxmap--userof");
        assertThat(sqs.getQueueUrl(b -> b.queueName("fxmap--userof")).queueUrl()).isNotEmpty();
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
        serve(SqsServer.builder(sqs, List.of(recording)));

        request(client(Duration.ofSeconds(20)), USER_OF, Map.of("authorization", "Bearer x", "empty", ""),
                (String) null);

        assertThat(seen.get().headers()).containsEntry("authorization", "Bearer x").doesNotContainKey("empty")
                .containsKeys("CorrelationId", "ReplyTo", "Type");
    }

    @Test
    void anOwnerFailureComesBackAsAReceivedFault() {
        QueryHandler failing = new InMemoryQueryHandler<>(new UserConfig(), () -> {
            throw new IllegalStateException("database down");
        });
        serve(SqsServer.builder(sqs, List.of(failing)));

        assertThatThrownBy(() -> request(client(Duration.ofSeconds(20)), USER_OF, Map.of(), "Name"))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(DistributedMapException.ReceivedFault.class)
                .hasMessageContaining("database down");
    }

    @Test
    void aKeyWithoutQueueFailsAtOnce() {
        assertThatThrownBy(() -> request(client(Duration.ofSeconds(20)), DistributedKeyRef.of("NobodyOf"), Map.of(),
                "Name")).hasCauseInstanceOf(DistributedMapException.NoHandlerForDistributedKey.class);
    }

    @Test
    void aReplyThatDoesNotComeTimesOut() {
        // A queue that nobody reads
        sqs.createQueue(b -> b.queueName("fxmap--silentof"));
        var client = client(Duration.ofMillis(500));

        assertThatThrownBy(() -> request(client, DistributedKeyRef.of("SilentOf"), Map.of(), "Name"))
                .hasCauseInstanceOf(DistributedMapException.RequestTimedOut.class);
        assertThat(client.pendingRequests()).isZero();
    }

    @Test
    void theClientSendsWhatAnFxMapServerReadsAndReadsWhatItReplies() throws Exception {
        // What FxMap's SqsServer creates, reads and replies
        var queueUrl = sqs.createQueue(b -> b.queueName("fxmap--userof")).queueUrl();
        var client = client(Duration.ofSeconds(20));
        var response = CompletableFuture.supplyAsync(() -> request(client, USER_OF, Map.of("tenant", "a"),
                "UserEmail"));

        var received = receiveOne(queueUrl);
        sqs.sendMessage(b -> b.queueUrl(received.messageAttributes().get("ReplyTo").stringValue())
                .messageBody("{\"IsSuccess\":true,\"Data\":{\"Items\":[{\"Id\":\"1\",\"Values\":[{\"Expression\":"
                             + "\"UserEmail\",\"Value\":\"\\\"alice@example.com\\\"\"}]}],\"Errors\":[]},"
                             + "\"Fault\":null}")
                .messageAttributes(Map.of("CorrelationId",
                        received.messageAttributes().get("CorrelationId"))));

        assertThat(JSON.readTree(received.body())).isEqualTo(
                JSON.readTree("{\"SelectorIds\":[\"1\",\"2\"],\"Expressions\":[\"UserEmail\"]}"));
        assertThat(received.messageAttributes().get("Type").stringValue()).isEqualTo(
                "UserOf, FxMap.DynamicDistributedKeys, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null");
        assertThat(received.messageAttributes().get("ReplyTo").stringValue()).contains("/fxmap-response-")
                .isEqualTo(client.responseQueue());
        assertThat(UUID.fromString(received.messageAttributes().get("CorrelationId").stringValue())).isNotNull();
        assertThat(received.messageAttributes().get("tenant").stringValue()).isEqualTo("a");
        assertThat(response.join().items()).containsExactly(
                new DataResponse("1", List.of(new ValueResponse("UserEmail", "\"alice@example.com\""))));
    }

    @Test
    void theServerAnswersWhatAnFxMapClientSends() throws Exception {
        serve(SqsServer.builder(sqs, List.of(users())));
        // What FxMap's SqsRequestClient sends: a DistributedMapRequest to the queue of the key
        var replyQueue = sqs.createQueue(b -> b.queueName("fxmap-response-dotnet-" + UUID.randomUUID())).queueUrl();
        closeables.add(() -> sqs.deleteQueue(b -> b.queueUrl(replyQueue)));
        var queueUrl = sqs.getQueueUrl(b -> b.queueName("fxmap--userof")).queueUrl();
        sqs.sendMessage(b -> b.queueUrl(queueUrl)
                .messageBody("{\"SelectorIds\":[\"2\"],\"Expressions\":[null,\"UserEmail\"]}")
                .messageAttributes(Map.of(
                        "CorrelationId", string("c-1"),
                        "ReplyTo", string(replyQueue),
                        "Type", string("UserOf, FxMap.DynamicDistributedKeys, Version=0.0.0.0, Culture=neutral, "
                                       + "PublicKeyToken=null"))));

        var reply = receiveOne(replyQueue);

        assertThat(reply.messageAttributes().get("CorrelationId").stringValue()).isEqualTo("c-1");
        assertThat(JSON.readTree(reply.body())).isEqualTo(JSON.readTree("{\"IsSuccess\":true,\"Data\":{\"Items\":"
                + "[{\"Id\":\"2\",\"Values\":[{\"Expression\":null,\"Value\":\"\\\"Bob\\\"\"},"
                + "{\"Expression\":\"UserEmail\",\"Value\":\"\\\"bob@example.com\\\"\"}]}],\"Errors\":[]},"
                + "\"Fault\":null}"));
    }

    @Test
    void startAndCloseCanBeRepeated() {
        var server = serve(SqsServer.builder(sqs, List.of(users())));
        server.start();
        server.close();
        server.close();

        assertThat(server.isRunning()).isFalse();
        server.start();
        assertThat(request(client(Duration.ofSeconds(20)), USER_OF, Map.of(), (String) null).items()).hasSize(2);
    }

    @Test
    void aClosedClientDeletesItsResponseQueueAndRefusesRequests() {
        serve(SqsServer.builder(sqs, List.of(users())));
        var client = client(Duration.ofSeconds(20));
        request(client, USER_OF, Map.of(), (String) null);
        var responseQueue = client.responseQueue();
        client.close();
        client.close();

        assertThatThrownBy(() -> sqs.getQueueAttributes(b -> b.queueUrl(responseQueue)))
                .isInstanceOf(QueueDoesNotExistException.class);
        assertThatThrownBy(() -> request(client, USER_OF, Map.of(), "Name")).isInstanceOf(CompletionException.class);
    }

    private static MessageAttributeValue string(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }

    @Test
    void aSupervisorStartsTheServerAgainWhenItsQueueIsDeleted() throws Exception {
        var server = SqsServer.builder(sqs, List.of(users())).waitTime(WAIT).build();
        var supervisor = new ServerSupervisor(SupervisorOptions.builder().initialBackoff(Duration.ofMillis(100))
                .build()).register("sqs", server);
        closeables.add(supervisor);
        supervisor.start();
        var firstRun = server.whenStopped().toCompletableFuture();

        sqs.deleteQueue(b -> b.queueUrl(server.queueUrls().get(USER_OF)));

        // The next poll finds no queue: the run fails, and the supervisor starts the server again
        assertThatThrownBy(() -> firstRun.get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(QueueDoesNotExistException.class);
        var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!(server.isRunning() && supervisor.states().get("sqs").restartCount() == 1)
               && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(supervisor.states().get("sqs").restartCount()).isEqualTo(1);
        assertThat(sqs.getQueueUrl(b -> b.queueName("fxmap--userof")).queueUrl()).isNotEmpty();

        var mapper = FxMap.builder().addProfiles(new OrderDtoProfile())
                .requestClient(client(Duration.ofSeconds(20))).throwIfException().build();
        var dtos = List.of(new OrderDto(1), new OrderDto(2));
        mapper.map(dtos);
        assertThat(dtos).extracting(d -> d.userName).containsExactly("Alice", "Bob");
    }
}

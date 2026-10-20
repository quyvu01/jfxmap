package io.github.quyvu01.jfxmap.interop;

import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.azure.servicebus.ServiceBusRequestClient;
import io.github.quyvu01.jfxmap.azure.servicebus.ServiceBusServer;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.kafka.KafkaRequestClient;
import io.github.quyvu01.jfxmap.kafka.KafkaServer;
import io.github.quyvu01.jfxmap.nats.NatsRequestClient;
import io.github.quyvu01.jfxmap.nats.NatsServer;
import io.github.quyvu01.jfxmap.opentelemetry.JfxMapTelemetry;
import io.github.quyvu01.jfxmap.provider.InMemoryQueryHandler;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.rabbitmq.RabbitMqRequestClient;
import io.github.quyvu01.jfxmap.rabbitmq.RabbitMqServer;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import io.github.quyvu01.jfxmap.sqs.SqsRequestClient;
import io.github.quyvu01.jfxmap.sqs.SqsServer;
import io.nats.client.Nats;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.azure.ServiceBusEmulatorContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * jfxmap and a real .NET service on FxMap 5.1.1 (dotnet-probe/) talk over each message transport, in both directions:
 * a Java client asks the .NET owner of the string key UserOf, and a .NET consumer asks a Java owner.
 *
 * <p>Runs when {@code JFXMAP_DOTNET_PROBE} gives the command that starts the probe (CI publishes it first).
 */
@EnabledIfEnvironmentVariable(named = "JFXMAP_DOTNET_PROBE", matches = ".+")
@SuppressWarnings({"try", "resource"})
class DotnetInteropTest {
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.11")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer("localstack/localstack:4.9");
    static final Network NETWORK = Network.newNetwork();
    static final MSSQLServerContainer SQL = new MSSQLServerContainer(
            "mcr.microsoft.com/mssql/server:2022-CU14-ubuntu-22.04").acceptLicense().withNetwork(NETWORK);
    // The emulator has no management API: the queues of UserOf are declared in servicebus-emulator.json
    static final ServiceBusEmulatorContainer SERVICE_BUS = new ServiceBusEmulatorContainer(
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

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<DataResponse> DOTNET_USERS = List.of(
            new DataResponse("1", List.of(new ValueResponse(null, "\"Alice\""),
                    new ValueResponse("UserEmail", "\"alice@dotnet\""))),
            new DataResponse("2", List.of(new ValueResponse(null, "\"Bob\""),
                    new ValueResponse("UserEmail", "\"bob@dotnet\""))));

    @BeforeAll
    static void startBrokers() {
        NATS.start();
        RABBIT.start();
        KAFKA.start();
        LOCALSTACK.start();
        SERVICE_BUS.start();
    }

    @AfterAll
    static void stopBrokers() {
        SERVICE_BUS.stop();
        SQL.stop();
        NETWORK.close();
        LOCALSTACK.stop();
        KAFKA.stop();
        RABBIT.stop();
        NATS.stop();
    }

    private static String natsUrl() {
        return "nats://localhost:" + NATS.getMappedPort(4222);
    }

    private static String rabbitAddress() {
        return "localhost:" + RABBIT.getAmqpPort();
    }

    private static Connection rabbitConnection() throws Exception {
        var factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(RABBIT.getAmqpPort());
        return factory.newConnection();
    }

    private static SqsClient sqsClient() {
        return SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    /**
     * Deletes the queue of UserOf: a long poll of a .NET owner stopped by an earlier test can still take the next
     * request from LocalStack, which would then wait out its visibility timeout.
     */
    private static SqsClient withoutStalePolls(SqsClient sqs) {
        try {
            sqs.deleteQueue(b -> b.queueUrl(sqs.getQueueUrl(q -> q.queueName("fxmap--userof")).queueUrl()));
        } catch (QueueDoesNotExistException e) {
            // No earlier test created it
        }
        return sqs;
    }

    private static ServiceBusClientBuilder serviceBusClients() {
        // The emulator answers a wait for the next session only when it times out: a short one picks up new sessions
        return new ServiceBusClientBuilder().connectionString(SERVICE_BUS.getConnectionString())
                .retryOptions(new AmqpRetryOptions().setTryTimeout(Duration.ofSeconds(3)));
    }

    private static QueryHandler javaUsers(AtomicReference<MapContext> seen) {
        var users = new InMemoryQueryHandler<>(new UserConfig(),
                List.of(new User(1, "Alice", "alice@java"), new User(2, "Bob", "bob@java")));
        return new QueryHandler() {
            @Override
            public DistributedKeyRef key() {
                return users.key();
            }

            @Override
            public Class<?> entityType() {
                return users.entityType();
            }

            @Override
            public CompletionStage<ItemsResponse> handle(
                    MapRequest request, MapContext context) {
                seen.set(context);
                return users.handle(request, context);
            }
        };
    }

    // --- The probe

    private static List<String> probe(String... args) {
        var command = new ArrayList<>(Arrays.asList(System.getenv("JFXMAP_DOTNET_PROBE").trim().split("\\s+")));
        command.addAll(List.of(args));
        return command;
    }

    /** A .NET owner of UserOf, ready to answer; what it prints goes to {@code output}. */
    private static Process startDotnetOwner(List<String> output, String... args) throws Exception {
        var process = new ProcessBuilder(probe(args)).redirectErrorStream(true).start();
        var ready = new CompletableFuture<Void>();
        Thread.ofPlatform().daemon().start(() -> {
            try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    System.out.println("[dotnet owner] " + line);
                    output.add(line);
                    if (line.contains("READY")) ready.complete(null);
                }
            } catch (IOException e) {
                ready.completeExceptionally(e);
            }
            ready.completeExceptionally(new IllegalStateException("The .NET owner stopped before it was ready."));
        });
        ready.get(3, TimeUnit.MINUTES);
        // Its servers subscribe in a background worker once the host started
        Thread.sleep(Duration.ofSeconds(3));
        return process;
    }

    private static Process startDotnetOwner(String transport, String address) throws Exception {
        return startDotnetOwner(new CopyOnWriteArrayList<>(), "owner", transport, address);
    }

    /** What a .NET consumer printed, once it mapped the orders of users 1, 2 and 3. */
    private static String runDotnetConsumer(String... args) throws Exception {
        var process = new ProcessBuilder(probe(args)).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), UTF_8);
        System.out.println("[dotnet consumer] " + output);
        assertThat(process.waitFor(3, TimeUnit.MINUTES)).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        return output;
    }

    /** What a .NET consumer mapped: the orders of users 1, 2 and 3. */
    private static List<Map<String, Object>> orders(String output) throws Exception {
        var result = output.lines().filter(line -> line.startsWith("RESULT ")).findFirst().orElseThrow();
        return JSON.readValue(result.substring("RESULT ".length()),
                JSON.getTypeFactory().constructCollectionType(List.class, Map.class));
    }

    private static void stop(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly();
    }

    private static void asksTheDotnetOwner(RequestClient client) {
        var response = client.request(DistributedKeyRef.of("UserOf"),
                        new MapRequest(List.of("1", "2", "3"), Arrays.asList(null, "UserEmail")),
                        new MapContext(Map.of("x-probe", "java"), null))
                .toCompletableFuture().join();

        assertThat(response.errors()).isEmpty();
        assertThat(response.items()).containsExactlyInAnyOrderElementsOf(DOTNET_USERS);
    }

    private static void theDotnetConsumerMapsFromJava(String transport, String address,
                                                      AtomicReference<MapContext> seen) throws Exception {
        var orders = orders(runDotnetConsumer("consumer", transport, address));

        assertThat(orders).extracting(o -> o.get("UserName")).containsExactly("Alice", "Bob", null);
        assertThat(orders).extracting(o -> o.get("UserEmail")).containsExactly("alice@java", "bob@java", null);
        assertThat(seen.get().headers()).containsEntry("x-probe", "dotnet");
    }

    // --- NATS

    @Test
    void aJavaClientAsksADotnetOwnerOverNats() throws Exception {
        var owner = startDotnetOwner("nats", natsUrl());
        try (var connection = Nats.connect(natsUrl())) {
            asksTheDotnetOwner(NatsRequestClient.builder(connection).timeout(Duration.ofSeconds(30)).build());
        } finally {
            stop(owner);
        }
    }

    @Test
    void aDotnetConsumerAsksAJavaOwnerOverNats() throws Exception {
        var seen = new AtomicReference<MapContext>();
        try (var connection = Nats.connect(natsUrl());
             var server = NatsServer.builder(connection, List.of(javaUsers(seen))).build().start()) {
            connection.flush(Duration.ofSeconds(5));
            theDotnetConsumerMapsFromJava("nats", natsUrl(), seen);
        }
    }

    // --- RabbitMQ

    @Test
    void aJavaClientAsksADotnetOwnerOverRabbitMq() throws Exception {
        var owner = startDotnetOwner("rabbitmq", rabbitAddress());
        try (var connection = rabbitConnection();
             var client = RabbitMqRequestClient.builder(connection).timeout(Duration.ofSeconds(30)).build()) {
            asksTheDotnetOwner(client);
        } finally {
            stop(owner);
        }
    }

    @Test
    void aDotnetConsumerAsksAJavaOwnerOverRabbitMq() throws Exception {
        var seen = new AtomicReference<MapContext>();
        try (var connection = rabbitConnection();
             var server = RabbitMqServer.builder(connection, "JavaUserService", List.of(javaUsers(seen))).build()
                     .start()) {
            theDotnetConsumerMapsFromJava("rabbitmq", rabbitAddress(), seen);
        }
    }

    // --- Kafka

    @Test
    void aJavaClientAsksADotnetOwnerOverKafka() throws Exception {
        var owner = startDotnetOwner("kafka", KAFKA.getBootstrapServers());
        try (var client = KafkaRequestClient.builder(KAFKA.getBootstrapServers(), "JavaOrderService")
                .timeout(Duration.ofSeconds(60)).build()) {
            asksTheDotnetOwner(client);
        } finally {
            stop(owner);
        }
    }

    @Test
    void aDotnetConsumerAsksAJavaOwnerOverKafka() throws Exception {
        var seen = new AtomicReference<MapContext>();
        try (var server = KafkaServer.builder(KAFKA.getBootstrapServers(), List.of(javaUsers(seen))).build().start()) {
            theDotnetConsumerMapsFromJava("kafka", KAFKA.getBootstrapServers(), seen);
        }
    }

    // --- Amazon SQS

    @Test
    void aJavaClientAsksADotnetOwnerOverSqs() throws Exception {
        var owner = startDotnetOwner("sqs", LOCALSTACK.getEndpoint().toString());
        try (var sqs = sqsClient();
             var client = SqsRequestClient.builder(sqs).timeout(Duration.ofSeconds(30))
                     .waitTime(Duration.ofSeconds(1)).build()) {
            asksTheDotnetOwner(client);
        } finally {
            stop(owner);
        }
    }

    @Test
    void aDotnetConsumerAsksAJavaOwnerOverSqs() throws Exception {
        var seen = new AtomicReference<MapContext>();
        try (var sqs = withoutStalePolls(sqsClient());
             var server = SqsServer.builder(sqs, List.of(javaUsers(seen))).waitTime(Duration.ofSeconds(1)).build()
                     .start()) {
            theDotnetConsumerMapsFromJava("sqs", LOCALSTACK.getEndpoint().toString(), seen);
        }
    }

    // --- Azure Service Bus

    @Test
    void aJavaClientAsksADotnetOwnerOverServiceBus() throws Exception {
        var owner = startDotnetOwner("servicebus", SERVICE_BUS.getConnectionString());
        try (var client = ServiceBusRequestClient.builder(serviceBusClients()).timeout(Duration.ofSeconds(60))
                .build()) {
            asksTheDotnetOwner(client);
        } finally {
            stop(owner);
        }
    }

    @Test
    void aDotnetConsumerAsksAJavaOwnerOverServiceBus() throws Exception {
        var seen = new AtomicReference<MapContext>();
        try (var server = ServiceBusServer.builder(serviceBusClients(), List.of(javaUsers(seen))).build().start()) {
            theDotnetConsumerMapsFromJava("servicebus", SERVICE_BUS.getConnectionString(), seen);
        }
    }

    // --- Traces: a .NET service on FxMap traced with an ActivityListener, a Java one with jfxmap-opentelemetry

    /** The OpenTelemetry of the Java side of a test, and the spans it ended. */
    private static final class JavaTraces implements AutoCloseable {
        final InMemorySpanExporter spans = InMemorySpanExporter.create();
        final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spans)).build()).build();
        final JfxMapTelemetry telemetry = JfxMapTelemetry.create(sdk);

        SpanData only(String name) throws InterruptedException {
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                var found = spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name)).toList();
                if (!found.isEmpty()) {
                    assertThat(found).hasSize(1);
                    return found.getFirst();
                }
                Thread.sleep(20);
            }
            throw new AssertionError("No " + name + " span in " + spans.getFinishedSpanItems());
        }

        @Override
        public void close() {
            sdk.close();
        }
    }

    /** A span the probe printed: SPAN name trace-id span-id parent-span-id. */
    record DotnetSpan(String name, String traceId, String spanId, String parentSpanId) {
        static List<DotnetSpan> in(List<String> output) {
            return output.stream().filter(line -> line.startsWith("SPAN ")).map(line -> line.split(" "))
                    .map(f -> new DotnetSpan(f[1], f[2], f[3], f[4])).toList();
        }
    }

    private static DotnetSpan dotnetSpan(List<String> output, String name) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var found = DotnetSpan.in(output).stream().filter(s -> s.name().equals(name)).toList();
            if (!found.isEmpty()) return found.getFirst();
            Thread.sleep(50);
        }
        throw new AssertionError("The probe printed no " + name + " span: " + output);
    }

    /** A traced Java client asks a traced .NET owner, which continues the Java trace. */
    private static void theDotnetOwnerContinuesTheTrace(String transport, String address,
                                                        Function<JavaTraces, RequestClient> client) throws Exception {
        var output = new CopyOnWriteArrayList<String>();
        var owner = startDotnetOwner(output, "owner", transport, address, "trace");
        try (var traces = new JavaTraces()) {
            asksTheDotnetOwner(traces.telemetry.instrument(client.apply(traces)));

            var request = traces.only("fxmap.request");
            var process = dotnetSpan(output, "fxmap.process");
            assertThat(process.traceId()).isEqualTo(request.getTraceId());
            assertThat(process.parentSpanId()).isEqualTo(request.getSpanId());
        } finally {
            stop(owner);
        }
    }

    /** A traced .NET consumer asks a traced Java owner, which continues the .NET trace. */
    private static void theJavaOwnerContinuesTheTrace(String transport, String address,
                                                      Function<QueryHandler, AutoCloseable> serve) throws Exception {
        var seen = new AtomicReference<MapContext>();
        try (var traces = new JavaTraces();
             var server = serve.apply(traces.telemetry.instrument(javaUsers(seen)))) {
            var output = runDotnetConsumer("consumer", transport, address, "trace");
            assertThat(orders(output)).extracting(o -> o.get("UserName")).containsExactly("Alice", "Bob", null);

            var request = dotnetSpan(output.lines().toList(), "fxmap.request");
            var process = traces.only("fxmap.process");
            assertThat(process.getTraceId()).isEqualTo(request.traceId());
            assertThat(process.getParentSpanId()).isEqualTo(request.spanId());
        }
    }

    @Test
    void traceAJavaRequestIntoADotnetOwnerOverNats() throws Exception {
        try (var connection = Nats.connect(natsUrl())) {
            theDotnetOwnerContinuesTheTrace("nats", natsUrl(),
                    traces -> NatsRequestClient.builder(connection).timeout(Duration.ofSeconds(30)).build());
        }
    }

    @Test
    void traceADotnetRequestIntoAJavaOwnerOverNats() throws Exception {
        try (var connection = Nats.connect(natsUrl())) {
            theJavaOwnerContinuesTheTrace("nats", natsUrl(), users -> {
                var server = NatsServer.builder(connection, List.of(users)).build().start();
                try {
                    connection.flush(Duration.ofSeconds(5));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return server;
            });
        }
    }

    @Test
    void traceAJavaRequestIntoADotnetOwnerOverRabbitMq() throws Exception {
        try (var connection = rabbitConnection();
             var client = RabbitMqRequestClient.builder(connection).timeout(Duration.ofSeconds(30)).build()) {
            theDotnetOwnerContinuesTheTrace("rabbitmq", rabbitAddress(), traces -> client);
        }
    }

    @Test
    void traceADotnetRequestIntoAJavaOwnerOverRabbitMq() throws Exception {
        try (var connection = rabbitConnection()) {
            theJavaOwnerContinuesTheTrace("rabbitmq", rabbitAddress(),
                    users -> RabbitMqServer.builder(connection, "JavaUserService", List.of(users)).build().start());
        }
    }

    @Test
    void traceAJavaRequestIntoADotnetOwnerOverKafka() throws Exception {
        try (var client = KafkaRequestClient.builder(KAFKA.getBootstrapServers(), "JavaOrderService")
                .timeout(Duration.ofSeconds(60)).build()) {
            theDotnetOwnerContinuesTheTrace("kafka", KAFKA.getBootstrapServers(), traces -> client);
        }
    }

    @Test
    void traceADotnetRequestIntoAJavaOwnerOverKafka() throws Exception {
        theJavaOwnerContinuesTheTrace("kafka", KAFKA.getBootstrapServers(),
                users -> KafkaServer.builder(KAFKA.getBootstrapServers(), List.of(users)).build().start());
    }

    @Test
    void traceAJavaRequestIntoADotnetOwnerOverSqs() throws Exception {
        try (var sqs = sqsClient();
             var client = SqsRequestClient.builder(sqs).timeout(Duration.ofSeconds(30))
                     .waitTime(Duration.ofSeconds(1)).build()) {
            theDotnetOwnerContinuesTheTrace("sqs", LOCALSTACK.getEndpoint().toString(), traces -> client);
        }
    }

    @Test
    void traceADotnetRequestIntoAJavaOwnerOverSqs() throws Exception {
        try (var sqs = withoutStalePolls(sqsClient())) {
            theJavaOwnerContinuesTheTrace("sqs", LOCALSTACK.getEndpoint().toString(),
                    users -> SqsServer.builder(sqs, List.of(users)).waitTime(Duration.ofSeconds(1)).build().start());
        }
    }

    /**
     * Service Bus carries no trace: FxMap 5.1.1 sends the trace context in bytes, which Service Bus refuses ("unsupported
     * type System.Byte[]"), so a traced .NET consumer fails on it and jfxmap leaves the trace context out. The .NET owner
     * starts a trace of its own.
     */
    @Test
    void aJavaTraceStopsAtServiceBus() throws Exception {
        var output = new CopyOnWriteArrayList<String>();
        var owner = startDotnetOwner(output, "owner", "servicebus", SERVICE_BUS.getConnectionString(), "trace");
        try (var traces = new JavaTraces();
             var client = ServiceBusRequestClient.builder(serviceBusClients()).timeout(Duration.ofSeconds(60))
                     .build()) {
            asksTheDotnetOwner(traces.telemetry.instrument(client));

            var request = traces.only("fxmap.request");
            var process = dotnetSpan(output, "fxmap.process");
            assertThat(process.traceId()).isNotEqualTo(request.getTraceId());
            assertThat(process.parentSpanId()).isEqualTo("0000000000000000");
        } finally {
            stop(owner);
        }
    }
}

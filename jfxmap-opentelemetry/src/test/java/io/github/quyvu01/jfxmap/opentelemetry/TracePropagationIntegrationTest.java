package io.github.quyvu01.jfxmap.opentelemetry;

import io.github.quyvu01.jfxmap.grpc.FxMapGrpcService;
import io.github.quyvu01.jfxmap.grpc.GrpcRequestClient;
import io.github.quyvu01.jfxmap.kafka.KafkaRequestClient;
import io.github.quyvu01.jfxmap.kafka.KafkaServer;
import io.github.quyvu01.jfxmap.nats.NatsRequestClient;
import io.github.quyvu01.jfxmap.nats.NatsServer;
import io.github.quyvu01.jfxmap.rabbitmq.RabbitMqRequestClient;
import io.github.quyvu01.jfxmap.rabbitmq.RabbitMqServer;
import io.github.quyvu01.jfxmap.sqs.SqsRequestClient;
import io.github.quyvu01.jfxmap.sqs.SqsServer;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.nats.client.Nats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** The trace of a request carried from a traced client to a traced server over each transport. */
@Testcontainers
class TracePropagationIntegrationTest {
    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.11")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer("localstack/localstack:4.9");

    private final Traces traces = new Traces();
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (var closeable : closeables.reversed()) closeable.close();
        traces.close();
    }

    private <T extends AutoCloseable> T closing(T closeable) {
        closeables.add(closeable);
        return closeable;
    }

    @Test
    void overNats() throws Exception {
        var connection = Nats.connect("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
        closing(connection::close);
        closing(NatsServer.builder(connection, traces.handlers()).build().start());
        connection.flush(Duration.ofSeconds(5));

        traces.assertCarriedOver(NatsRequestClient.builder(connection).build(), "nats");
    }

    @Test
    void overRabbitMq() throws Exception {
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setUri(RABBIT.getAmqpUrl());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        var connection = closing(factory.newConnection());
        closing(RabbitMqServer.builder(connection, "UserService", traces.handlers()).build().start());

        traces.assertCarriedOver(closing(RabbitMqRequestClient.builder(connection).timeout(Duration.ofSeconds(10))
                .build()), "rabbitmq");
    }

    @Test
    void overKafka() throws Exception {
        closing(KafkaServer.builder(KAFKA.getBootstrapServers(), traces.handlers()).build().start());

        traces.assertCarriedOver(closing(KafkaRequestClient.builder(KAFKA.getBootstrapServers(), "OrderService")
                .timeout(Duration.ofSeconds(30)).build()), "kafka");
    }

    @Test
    void overSqs() throws Exception {
        var sqs = closing(SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build());
        closing(SqsServer.builder(sqs, traces.handlers()).waitTime(Duration.ofSeconds(1)).build().start());

        traces.assertCarriedOver(closing(SqsRequestClient.builder(sqs).timeout(Duration.ofSeconds(20))
                .waitTime(Duration.ofSeconds(1)).build()), "sqs");
    }

    @Test
    void overGrpc() throws Exception {
        var name = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new FxMapGrpcService(traces.handlers())).build().start();
        closing(server::shutdownNow);

        traces.assertCarriedOver(closing(GrpcRequestClient.builder().hosts(name)
                .channelFactory(host -> InProcessChannelBuilder.forName(host).directExecutor().build())
                .build()), "grpc");
    }
}

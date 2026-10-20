package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.RequestServer;
import io.github.quyvu01.jfxmap.azure.servicebus.ServiceBusRequestClient;
import io.github.quyvu01.jfxmap.azure.servicebus.ServiceBusServer;
import io.github.quyvu01.jfxmap.graphql.JfxMapGraphQl;
import io.github.quyvu01.jfxmap.jpa.JpaQueryHandler;
import io.github.quyvu01.jfxmap.kafka.KafkaRequestClient;
import io.github.quyvu01.jfxmap.kafka.KafkaServer;
import io.github.quyvu01.jfxmap.mongodb.MongoQueryHandler;
import io.github.quyvu01.jfxmap.nats.NatsRequestClient;
import io.github.quyvu01.jfxmap.nats.NatsServer;
import io.github.quyvu01.jfxmap.rabbitmq.RabbitMqRequestClient;
import io.github.quyvu01.jfxmap.rabbitmq.RabbitMqServer;
import io.github.quyvu01.jfxmap.sqs.SqsRequestClient;
import io.github.quyvu01.jfxmap.sqs.SqsServer;
import io.github.quyvu01.jfxmap.supervision.ServerHealth;
import io.github.quyvu01.jfxmap.supervision.SupervisorOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The parts of the transports' auto-configuration that need no broker. */
class JfxMapTransportsConfigurationTest {
    private static final String SERVICE_BUS =
            "Endpoint=sb://localhost:1;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=key";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JfxMapAutoConfiguration.class));

    @Test
    void noTransportIsOnUntilItIsConfigured() {
        runner.run(context -> assertThat(context)
                .doesNotHaveBean(NatsRequestClient.class)
                .doesNotHaveBean(RabbitMqRequestClient.class)
                .doesNotHaveBean(KafkaRequestClient.class)
                .doesNotHaveBean(SqsClient.class)
                .doesNotHaveBean(SqsRequestClient.class)
                .doesNotHaveBean(ServiceBusRequestClient.class)
                .doesNotHaveBean(JfxMapServiceBusClients.class)
                .doesNotHaveBean(JfxMapTransportServer.class)
                .doesNotHaveBean(JfxMapRabbitMqConnection.class));
    }

    @Test
    void aTransportThatIsNotOnTheClasspathStaysOff() {
        runner.withClassLoader(new FilteredClassLoader(NatsServer.class, RabbitMqServer.class, KafkaServer.class,
                        MongoQueryHandler.class, JpaQueryHandler.class, SqsServer.class, ServiceBusServer.class,
                        JfxMapGraphQl.class))
                .withPropertyValues("jfxmap.nats.url=nats://localhost:1", "jfxmap.rabbitmq.host=localhost",
                        "jfxmap.kafka.bootstrap-servers=localhost:1", "jfxmap.sqs.region=us-east-1",
                        "jfxmap.servicebus.connection-string=" + SERVICE_BUS)
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(JfxMapTransportServer.class)
                        .doesNotHaveBean(SqsClient.class)
                        .doesNotHaveBean(JfxMapServiceBusClients.class)
                        .doesNotHaveBean(JfxMapGraphQl.class)
                        .doesNotHaveBean(JfxMapRabbitMqConnection.class)
                        .doesNotHaveBean(EntityQueryHandlerFactory.class));
    }

    @Test
    void sqsGivesAClientWithoutConnectingFirst() {
        runner.withPropertyValues("jfxmap.sqs.region=eu-west-1", "jfxmap.sqs.endpoint=http://localhost:1",
                        "jfxmap.sqs.access-key-id=key", "jfxmap.sqs.secret-access-key=secret",
                        "jfxmap.sqs.wait-time=2s", "jfxmap.sqs.server-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SqsClient.class);
                    assertThat(context.getBean(SqsRequestClient.class).responseQueue()).isNull();
                    assertThat(context).doesNotHaveBean(JfxMapTransportServer.class);
                    var sqs = context.getBean(JfxMapProperties.class).getSqs();
                    assertThat(sqs.getWaitTime()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(sqs.getAccessKeyId()).isEqualTo("key");
                    assertThat(sqs.getSecretAccessKey()).isEqualTo("secret");
                });
    }

    @Test
    void sqsUsesTheSqsClientOfTheApplication() {
        runner.withBean(SqsClient.class, () -> SqsClient.builder()
                        .region(software.amazon.awssdk.regions.Region.US_EAST_1).build())
                .withPropertyValues("jfxmap.sqs.server-enabled=false")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(SqsClient.class)
                        .hasSingleBean(SqsRequestClient.class));
    }

    @Test
    void serviceBusGivesAClientWithoutConnectingFirst() {
        runner.withPropertyValues("jfxmap.servicebus.connection-string=" + SERVICE_BUS,
                        "jfxmap.servicebus.topic-prefix=tenant", "jfxmap.servicebus.try-timeout=3s",
                        "jfxmap.servicebus.max-concurrent-sessions=2", "jfxmap.servicebus.create-queues=false",
                        "jfxmap.servicebus.server-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JfxMapServiceBusClients.class);
                    assertThat(context.getBean(ServiceBusRequestClient.class).sessionId()).isNotBlank();
                    assertThat(context).doesNotHaveBean(JfxMapTransportServer.class);
                    var servicebus = context.getBean(JfxMapProperties.class).getServicebus();
                    assertThat(servicebus.getTopicPrefix()).isEqualTo("tenant");
                    assertThat(servicebus.getTryTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(servicebus.getMaxConcurrentSessions()).isEqualTo(2);
                    assertThat(servicebus.isCreateQueues()).isFalse();
                });
        assertThatThrownBy(() -> new JfxMapServiceBusClients(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void kafkaGivesAClientAndAServerWithoutConnectingFirst() {
        runner.withPropertyValues("jfxmap.kafka.bootstrap-servers=localhost:1", "jfxmap.kafka.partitions=3",
                        "jfxmap.kafka.properties.client.id=orders", "jfxmap.kafka.timeout=5s",
                        "spring.application.name=OrderService", "jfxmap.kafka.server-enabled=false")
                .run(context -> {
                    assertThat(context.getBean(KafkaRequestClient.class).replyTopic())
                            .startsWith("fxmap-response-topic-orderservice-");
                    assertThat(context).doesNotHaveBean(JfxMapTransportServer.class);
                    var kafka = context.getBean(JfxMapProperties.class).getKafka();
                    assertThat(kafka.getPartitions()).isEqualTo(3);
                    assertThat(kafka.getTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(kafka.getProperties()).containsEntry("client.id", "orders");
                });
    }

    @Test
    void theApplicationNameComesFromJfxmapThenSpringThenTheFallback() {
        var properties = new JfxMapProperties();
        var environment = new MockEnvironment();

        assertThat(JfxMapAutoConfiguration.applicationName(properties, environment, "fallback")).isEqualTo("fallback");
        assertThatThrownBy(() -> JfxMapAutoConfiguration.applicationName(properties, environment, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("jfxmap.application-name");
        environment.setProperty("spring.application.name", "spring-name");
        assertThat(JfxMapAutoConfiguration.applicationName(properties, environment, null)).isEqualTo("spring-name");
        properties.setApplicationName("jfxmap-name");
        assertThat(JfxMapAutoConfiguration.applicationName(properties, environment, null)).isEqualTo("jfxmap-name");
    }

    @Test
    void theTransportServerStartsAndStopsItsServer() {
        var server = new FakeServer();
        var transport = new JfxMapTransportServer("nats", server, null);

        transport.start();
        assertThat(transport.isRunning()).isTrue();
        transport.stop();

        assertThat(transport.isRunning()).isFalse();
        assertThat(server.starts).hasValue(1);
        assertThat(transport.getName()).isEqualTo("nats");
        assertThat(transport.getServer()).isSameAs(server);
        assertThat(transport.getSupervisor()).isEmpty();
        assertThat(transport.getState()).isEmpty();
        assertThat(transport).hasToString("JfxMapTransportServer[nats]");
        assertThatThrownBy(() -> new JfxMapTransportServer(null, server, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JfxMapTransportServer("nats", null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aSupervisedTransportServerIsStartedAgainWhenItFails() throws Exception {
        var server = new FakeServer();
        var options = SupervisorOptions.builder().initialBackoff(Duration.ofMillis(10)).build();
        var transport = new JfxMapTransportServer("nats", server, options);
        try {
            transport.start();
            assertThat(transport.isRunning()).isTrue();
            assertThat(transport.getSupervisor()).isPresent();
            assertThat(transport.getState()).hasValueSatisfying(
                    state -> assertThat(state.health()).isEqualTo(ServerHealth.HEALTHY));

            server.fail(new IllegalStateException("queue deleted"));

            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (server.starts.get() < 2 && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(server.starts).hasValue(2);
            transport.stop();
            assertThat(transport.isRunning()).isFalse();
            assertThat(server.isRunning()).isFalse();
        } finally {
            transport.destroy();
        }
    }

    /** A server that runs until it is closed or made to fail. */
    private static final class FakeServer implements RequestServer {
        final AtomicInteger starts = new AtomicInteger();
        private CompletableFuture<Void> run;

        @Override
        public synchronized RequestServer start() {
            if (run != null) return this;
            starts.incrementAndGet();
            run = new CompletableFuture<>();
            return this;
        }

        @Override
        public synchronized boolean isRunning() {
            return run != null;
        }

        @Override
        public synchronized CompletionStage<Void> whenStopped() {
            return run != null ? run : CompletableFuture.completedFuture(null);
        }

        synchronized void fail(Throwable failure) {
            var ended = run;
            run = null;
            ended.completeExceptionally(failure);
        }

        @Override
        public synchronized void close() {
            if (run == null) return;
            var ended = run;
            run = null;
            ended.complete(null);
        }
    }

    @Test
    void theTransportPropertiesHaveFxMapsDefaults() {
        var properties = new JfxMapProperties();

        assertThat(properties.getNats().getTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getNats().getMaxConcurrentProcessing()).isEqualTo(128);
        assertThat(properties.getNats().isServerEnabled()).isTrue();
        assertThat(properties.getRabbitmq().getPort()).isEqualTo(5672);
        assertThat(properties.getRabbitmq().getVirtualHost()).isEqualTo("/");
        assertThat(properties.getRabbitmq().getUsername()).isEqualTo("guest");
        assertThat(properties.getRabbitmq().getChannelPoolSize()).isEqualTo(5);
        assertThat(properties.getKafka().getPartitions()).isEqualTo(1);
        assertThat(properties.getKafka().getReplicationFactor()).isEqualTo((short) 1);
        assertThat(properties.getSqs().getWaitTime()).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.getSqs().getTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getSqs().getRegion()).isNull();
        assertThat(properties.getServicebus().getMaxConcurrentSessions()).isEqualTo(8);
        assertThat(properties.getServicebus().isCreateQueues()).isTrue();
        assertThat(properties.getServicebus().getTryTimeout()).isNull();
        assertThat(properties.getServicebus().getTopicPrefix()).isNull();
        assertThat(properties.getGraphql().getTypeNames()).isEmpty();
        assertThat(properties.getGraphql().getForwardedHeaders()).isEmpty();
    }

    @Test
    void rabbitMqThatCannotConnectFailsTheContext() {
        runner.withPropertyValues("jfxmap.rabbitmq.host=localhost", "jfxmap.rabbitmq.port=1")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("jfxmap cannot connect to RabbitMQ at localhost:1"));
    }
}

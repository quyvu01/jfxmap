package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.jpa.JpaQueryHandler;
import io.github.quyvu01.jfxmap.kafka.KafkaRequestClient;
import io.github.quyvu01.jfxmap.kafka.KafkaServer;
import io.github.quyvu01.jfxmap.mongodb.MongoQueryHandler;
import io.github.quyvu01.jfxmap.nats.NatsRequestClient;
import io.github.quyvu01.jfxmap.nats.NatsServer;
import io.github.quyvu01.jfxmap.rabbitmq.RabbitMqRequestClient;
import io.github.quyvu01.jfxmap.rabbitmq.RabbitMqServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The parts of the transports' auto-configuration that need no broker. */
class JfxMapTransportsConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JfxMapAutoConfiguration.class));

    @Test
    void noTransportIsOnUntilItIsConfigured() {
        runner.run(context -> assertThat(context)
                .doesNotHaveBean(NatsRequestClient.class)
                .doesNotHaveBean(RabbitMqRequestClient.class)
                .doesNotHaveBean(KafkaRequestClient.class)
                .doesNotHaveBean(JfxMapTransportServer.class)
                .doesNotHaveBean(JfxMapRabbitMqConnection.class));
    }

    @Test
    void aTransportThatIsNotOnTheClasspathStaysOff() {
        runner.withClassLoader(new FilteredClassLoader(NatsServer.class, RabbitMqServer.class, KafkaServer.class,
                        MongoQueryHandler.class, JpaQueryHandler.class))
                .withPropertyValues("jfxmap.nats.url=nats://localhost:1", "jfxmap.rabbitmq.host=localhost",
                        "jfxmap.kafka.bootstrap-servers=localhost:1")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(JfxMapTransportServer.class)
                        .doesNotHaveBean(JfxMapRabbitMqConnection.class)
                        .doesNotHaveBean(EntityQueryHandlerFactory.class));
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
        var running = new AtomicBoolean();
        var starts = new AtomicInteger();
        var server = new JfxMapTransportServer("nats", () -> {
            starts.incrementAndGet();
            running.set(true);
        }, () -> running.set(false), running::get);

        server.start();
        assertThat(server.isRunning()).isTrue();
        server.stop();

        assertThat(server.isRunning()).isFalse();
        assertThat(starts).hasValue(1);
        assertThat(server.getName()).isEqualTo("nats");
        assertThat(server).hasToString("JfxMapTransportServer[nats]");
        assertThatThrownBy(() -> new JfxMapTransportServer(null, () -> {
        }, () -> {
        }, () -> true)).isInstanceOf(NullPointerException.class);
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
    }

    @Test
    void rabbitMqThatCannotConnectFailsTheContext() {
        runner.withPropertyValues("jfxmap.rabbitmq.host=localhost", "jfxmap.rabbitmq.port=1")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("jfxmap cannot connect to RabbitMQ at localhost:1"));
    }
}

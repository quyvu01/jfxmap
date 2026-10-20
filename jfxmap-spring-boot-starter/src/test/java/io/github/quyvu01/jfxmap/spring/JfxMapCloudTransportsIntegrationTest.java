package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.DistributedMapper;
import io.github.quyvu01.jfxmap.azure.servicebus.ServiceBusRequestClient;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.spring.Fixtures.EmailVisibility;
import io.github.quyvu01.jfxmap.spring.Fixtures.OrderDto;
import io.github.quyvu01.jfxmap.spring.Fixtures.OrderDtoProfile;
import io.github.quyvu01.jfxmap.sqs.SqsRequestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.azure.ServiceBusEmulatorContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.MountableFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two applications that map across each other over Amazon SQS (LocalStack) and Azure Service Bus (its emulator, whose
 * queues are declared in servicebus-emulator.json since it has no management API).
 */
class JfxMapCloudTransportsIntegrationTest {
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer("localstack/localstack:4.9");
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

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JfxMapAutoConfiguration.class));

    @BeforeAll
    static void startContainers() {
        LOCALSTACK.start();
        EMULATOR.start();
    }

    @AfterAll
    static void stopContainers() {
        LOCALSTACK.stop();
        EMULATOR.stop();
        SQL.stop();
        NETWORK.close();
    }

    /** The owner of the users answers over the transport; the consumer maps its orders over it. */
    private void mapsAcross(Class<?> clientType, String... transport) {
        runner.withBean(QueryHandler.class, Fixtures::inMemoryUsers)
                .withPropertyValues(transport)
                .run(owner -> {
                    assertThat(owner).hasNotFailed().hasSingleBean(JfxMapTransportServer.class);
                    assertThat(owner.getBean(JfxMapTransportServer.class).isRunning()).isTrue();

                    runner.withBean(OrderDtoProfile.class)
                            .withBean(EmailVisibility.class, () -> new EmailVisibility(true))
                            .withPropertyValues(transport)
                            .withPropertyValues("jfxmap.throw-if-exception=true")
                            .run(consumer -> {
                                assertThat(consumer).hasNotFailed().hasSingleBean(clientType);
                                var orders = List.of(new OrderDto(1), new OrderDto(2));
                                consumer.getBean(DistributedMapper.class).map(orders);

                                assertThat(orders).extracting(o -> o.userName).containsExactly("Alice (memory)", null);
                                assertThat(orders.getFirst().userEmail).isEqualTo("alice@memory");
                            });
                });
    }

    @Test
    void mapsAcrossTwoApplicationsOverSqs() {
        mapsAcross(SqsRequestClient.class, "jfxmap.sqs.region=" + LOCALSTACK.getRegion(),
                "jfxmap.sqs.endpoint=" + LOCALSTACK.getEndpoint(),
                "jfxmap.sqs.access-key-id=" + LOCALSTACK.getAccessKey(),
                "jfxmap.sqs.secret-access-key=" + LOCALSTACK.getSecretKey(),
                "jfxmap.sqs.wait-time=1s");
    }

    @Test
    void mapsAcrossTwoApplicationsOverServiceBus() {
        // The emulator answers a wait for the next session only when it times out: a short one picks up new sessions
        mapsAcross(ServiceBusRequestClient.class, "jfxmap.servicebus.connection-string=" + EMULATOR.getConnectionString(),
                "jfxmap.servicebus.try-timeout=3s");
    }
}

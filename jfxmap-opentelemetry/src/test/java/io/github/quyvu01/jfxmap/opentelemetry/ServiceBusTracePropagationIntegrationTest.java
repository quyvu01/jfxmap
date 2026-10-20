package io.github.quyvu01.jfxmap.opentelemetry;

import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import io.github.quyvu01.jfxmap.azure.servicebus.ServiceBusRequestClient;
import io.github.quyvu01.jfxmap.azure.servicebus.ServiceBusServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.azure.ServiceBusEmulatorContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

/**
 * Azure Service Bus does not carry the trace: it refuses the trace context as FxMap sends it (bytes), so the client
 * leaves it out, and the server starts a trace of its own (on the emulator).
 */
@SuppressWarnings("try")
class ServiceBusTracePropagationIntegrationTest {
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

    @BeforeAll
    static void startEmulator() {
        EMULATOR.start();
    }

    @AfterAll
    static void stopEmulator() {
        EMULATOR.stop();
        SQL.stop();
        NETWORK.close();
    }

    @Test
    void stopsAtServiceBus() throws Exception {
        // The emulator answers a wait for the next session only when it times out: a short one picks up new sessions
        var clients = new ServiceBusClientBuilder().connectionString(EMULATOR.getConnectionString())
                .retryOptions(new AmqpRetryOptions().setTryTimeout(Duration.ofSeconds(3)));
        try (var traces = new Traces();
             var server = ServiceBusServer.builder(clients, traces.handlers()).maxConcurrentSessions(1).build().start();
             var client = ServiceBusRequestClient.builder(clients).timeout(Duration.ofSeconds(30)).build()) {
            traces.assertNotCarriedOver(client, "azureservicebus");
        }
    }
}

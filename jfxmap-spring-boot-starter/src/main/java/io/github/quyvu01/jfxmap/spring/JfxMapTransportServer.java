package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.RequestServer;
import io.github.quyvu01.jfxmap.supervision.ServerState;
import io.github.quyvu01.jfxmap.supervision.ServerSupervisor;
import io.github.quyvu01.jfxmap.supervision.SupervisorOptions;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;

import java.util.Objects;
import java.util.Optional;

/**
 * Runs the server of a message transport (NATS, RabbitMQ, Kafka, SQS, Service Bus) while the application runs: it
 * starts with the context and stops before the connections close. With supervisor options, a
 * {@link ServerSupervisor} runs it: a server that cannot start, or that fails, is started again in the background
 * instead of failing the application.
 */
public final class JfxMapTransportServer implements SmartLifecycle, DisposableBean {
    private final String name;
    private final RequestServer server;
    private final ServerSupervisor supervisor;

    /**
     * @param name    the transport, as {@code nats}
     * @param server  its server
     * @param options how it is supervised, or {@code null} to start and stop it directly
     */
    public JfxMapTransportServer(String name, RequestServer server, SupervisorOptions options) {
        this.name = Objects.requireNonNull(name, "name");
        this.server = Objects.requireNonNull(server, "server");
        this.supervisor = options == null ? null : new ServerSupervisor(options).register(name, server);
    }

    /** The transport, as {@code nats}, {@code rabbitmq} or {@code kafka}. */
    public String getName() {
        return name;
    }

    public RequestServer getServer() {
        return server;
    }

    /** The supervisor of the server, when it is supervised. */
    public Optional<ServerSupervisor> getSupervisor() {
        return Optional.ofNullable(supervisor);
    }

    /** What the supervisor knows of the server, when it is supervised. */
    public Optional<ServerState> getState() {
        return getSupervisor().map(s -> s.states().get(name));
    }

    @Override
    public void start() {
        if (supervisor != null) supervisor.start();
        else server.start();
    }

    @Override
    public void stop() {
        if (supervisor != null) supervisor.stop();
        else server.close();
    }

    /** Stops the thread of the supervisor. */
    @Override
    public void destroy() {
        if (supervisor != null) supervisor.close();
    }

    @Override
    public boolean isRunning() {
        return supervisor != null ? supervisor.isRunning() : server.isRunning();
    }

    @Override
    public String toString() {
        return "JfxMapTransportServer[" + name + "]";
    }
}

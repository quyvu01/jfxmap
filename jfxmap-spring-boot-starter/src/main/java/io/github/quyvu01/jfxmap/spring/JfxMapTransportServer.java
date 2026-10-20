package io.github.quyvu01.jfxmap.spring;

import org.springframework.context.SmartLifecycle;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Runs the server of a message transport (NATS, RabbitMQ, Kafka) while the application runs: it starts with the
 * context and stops before the connections close.
 */
public final class JfxMapTransportServer implements SmartLifecycle {
    private final String name;
    private final Runnable start;
    private final Runnable stop;
    private final BooleanSupplier running;

    public JfxMapTransportServer(String name, Runnable start, Runnable stop, BooleanSupplier running) {
        this.name = Objects.requireNonNull(name, "name");
        this.start = Objects.requireNonNull(start, "start");
        this.stop = Objects.requireNonNull(stop, "stop");
        this.running = Objects.requireNonNull(running, "running");
    }

    /** The transport, as {@code nats}, {@code rabbitmq} or {@code kafka}. */
    public String getName() {
        return name;
    }

    @Override
    public void start() {
        start.run();
    }

    @Override
    public void stop() {
        stop.run();
    }

    @Override
    public boolean isRunning() {
        return running.getAsBoolean();
    }

    @Override
    public String toString() {
        return "JfxMapTransportServer[" + name + "]";
    }
}

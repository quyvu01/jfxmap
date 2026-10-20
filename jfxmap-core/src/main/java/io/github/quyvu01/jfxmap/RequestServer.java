package io.github.quyvu01.jfxmap;

import java.util.concurrent.CompletionStage;

/**
 * Answers the requests for the keys a service owns over a transport (FxMap's {@code IRequestServer}): the NATS,
 * RabbitMQ, Kafka, SQS and Service Bus servers. A {@link io.github.quyvu01.jfxmap.supervision.ServerSupervisor} starts
 * it and starts it again when it fails.
 */
public interface RequestServer extends AutoCloseable {
    /**
     * Starts answering; does nothing when the server already runs.
     *
     * @throws RuntimeException when the server cannot start (the broker cannot be reached, a queue cannot be declared)
     */
    RequestServer start();

    boolean isRunning();

    /**
     * The end of the current run: completed when {@link #close()} stops it, failed with the cause when the server
     * stops on its own because of a failure (its queue was deleted, its consumer was cancelled...). Already completed
     * when the server does not run. A failed server can be started again.
     */
    CompletionStage<Void> whenStopped();

    /** Stops answering; does nothing when the server does not run. */
    @Override
    void close();
}

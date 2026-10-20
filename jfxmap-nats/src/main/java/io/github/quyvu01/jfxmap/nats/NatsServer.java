package io.github.quyvu01.jfxmap.nats;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.wire.WireResponder;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * Answers the requests for the keys this service owns over NATS (FxMap's NatsServer): it subscribes to the subject of
 * each key ({@link NatsSubjects}) and replies with a {@code Result} on the inbox of each request. At most
 * {@code maxConcurrentProcessing} requests are answered at once (FxMap's {@code SetMaxConcurrentProcessing}, 128 by
 * default); the others wait in the subscription. As in FxMap, the subscriptions have no queue group: every instance
 * of the service answers, and the requester takes the first reply.
 *
 * <pre>{@code
 * NatsServer server = NatsServer.builder(connection, List.of(new JpaQueryHandler<>(new UserConfig(), emf))).build();
 * server.start();
 * }</pre>
 */
public final class NatsServer implements AutoCloseable {
    /** FxMap's {@code MaxConcurrentProcessing}. */
    public static final int DEFAULT_MAX_CONCURRENT_PROCESSING = 128;

    private static final System.Logger LOGGER = System.getLogger(NatsServer.class.getName());

    private final Connection connection;
    private final WireResponder responder;
    private final String topicPrefix;
    private final Semaphore slots;
    private Dispatcher dispatcher;

    private NatsServer(Builder builder) {
        this.connection = builder.connection;
        this.responder = new WireResponder(builder.handlers, builder.timeout);
        this.topicPrefix = builder.topicPrefix;
        this.slots = new Semaphore(builder.maxConcurrentProcessing);
    }

    /**
     * @param handlers the handlers of the keys this service owns
     */
    public static Builder builder(Connection connection, Collection<? extends QueryHandler> handlers) {
        return new Builder(connection, handlers);
    }

    /** The keys this server answers for. */
    public Collection<DistributedKeyRef> keys() {
        return responder.keys();
    }

    /** The subjects this server listens on, by key. */
    public Map<DistributedKeyRef, String> subjects() {
        var subjects = new LinkedHashMap<DistributedKeyRef, String>();
        for (var key : responder.keys()) subjects.put(key, NatsSubjects.of(key, topicPrefix));
        return subjects;
    }

    /** Subscribes to the subjects; does nothing when the server already runs. */
    public synchronized NatsServer start() {
        if (dispatcher != null) return this;
        var started = connection.createDispatcher();
        subjects().forEach((key, subject) -> started.subscribe(subject, message -> receive(key, message)));
        dispatcher = started;
        return this;
    }

    public synchronized boolean isRunning() {
        return dispatcher != null;
    }

    private void receive(DistributedKeyRef key, Message message) {
        var replyTo = message.getReplyTo();
        if (replyTo == null) return;
        try {
            // Backpressure: the dispatcher waits for a slot, so the pending messages stay in the subscription
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        Thread.ofVirtual().name("jfxmap-nats").start(() -> {
            try {
                responder.answer(key, message.getData(), headers(message)).whenComplete((reply, failure) -> {
                    try {
                        if (reply != null) connection.publish(replyTo, reply);
                    } catch (RuntimeException e) {
                        LOGGER.log(System.Logger.Level.ERROR, "The reply for " + key + " could not be sent.", e);
                    } finally {
                        slots.release();
                    }
                });
            } catch (RuntimeException e) {
                slots.release();
                LOGGER.log(System.Logger.Level.ERROR, "The request for " + key + " could not be answered.", e);
            }
        });
    }

    /** The first value of each NATS header. */
    static Map<String, String> headers(Message message) {
        var headers = new LinkedHashMap<String, String>();
        var natsHeaders = message.getHeaders();
        if (natsHeaders == null) return headers;
        for (var name : natsHeaders.keySet()) {
            var value = natsHeaders.getFirst(name);
            if (value != null) headers.put(name, value);
        }
        return headers;
    }

    /** Unsubscribes; the requests being answered still get their reply. */
    @Override
    public synchronized void close() {
        if (dispatcher == null) return;
        connection.closeDispatcher(dispatcher);
        dispatcher = null;
    }

    public static final class Builder {
        private final Connection connection;
        private final List<QueryHandler> handlers;
        private String topicPrefix;
        private Duration timeout = WireResponder.DEFAULT_TIMEOUT;
        private int maxConcurrentProcessing = DEFAULT_MAX_CONCURRENT_PROCESSING;

        private Builder(Connection connection, Collection<? extends QueryHandler> handlers) {
            this.connection = Objects.requireNonNull(connection, "connection");
            this.handlers = List.copyOf(Objects.requireNonNull(handlers, "handlers"));
        }

        /** The prefix of the subjects (FxMap's {@code TopicPrefix}); none by default. */
        public Builder topicPrefix(String topicPrefix) {
            this.topicPrefix = topicPrefix;
            return this;
        }

        /** How long a handler may take before the request fails (default 30 seconds). */
        public Builder timeout(Duration timeout) {
            if (Objects.requireNonNull(timeout, "timeout").isNegative() || timeout.isZero())
                throw new IllegalArgumentException("The timeout must be positive.");
            this.timeout = timeout;
            return this;
        }

        /** How many requests are answered at once (default 128). */
        public Builder maxConcurrentProcessing(int maxConcurrentProcessing) {
            if (maxConcurrentProcessing < 1)
                throw new IllegalArgumentException("maxConcurrentProcessing must be at least 1.");
            this.maxConcurrentProcessing = maxConcurrentProcessing;
            return this;
        }

        /** @throws DistributedMapException.DistributedKeyAssignedToMultipleEntities when two handlers share a key */
        public NatsServer build() {
            return new NatsServer(this);
        }
    }
}

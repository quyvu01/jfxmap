package io.github.quyvu01.jfxmap.sqs;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.wire.DotNetKeyNames;
import io.github.quyvu01.jfxmap.wire.WireResponder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * Answers the requests for the keys this service owns over Amazon SQS (FxMap's SqsServer): it creates the queue of
 * each key ({@link SqsNames#queue}) when it does not exist, and polls each one (long polling, 10 messages at a time).
 * The received messages are deleted at once, as FxMap does, and answered with a {@code Result} on the queue of their
 * {@code ReplyTo} attribute, with their {@code CorrelationId}; at most {@code maxConcurrentProcessing} requests are
 * answered at once. The instances of a service share the queues, so each request reaches one of them.
 *
 * <pre>{@code
 * SqsServer server = SqsServer.builder(SqsClient.create(), handlers).build().start();
 * }</pre>
 */
public final class SqsServer implements AutoCloseable {
    /** FxMap's {@code MaxConcurrentProcessing}. */
    public static final int DEFAULT_MAX_CONCURRENT_PROCESSING = 128;

    private static final System.Logger LOGGER = System.getLogger(SqsServer.class.getName());

    private final SqsClient sqs;
    private final WireResponder responder;
    private final Duration waitTime;
    private final Semaphore slots;
    // Guarded by this; a new object each time the server starts, so the polls of an earlier start stop
    private Object generation;
    private final Map<DistributedKeyRef, String> queueUrls = new LinkedHashMap<>();

    private SqsServer(Builder builder) {
        this.sqs = builder.sqs;
        this.responder = new WireResponder(builder.handlers, builder.timeout);
        this.waitTime = builder.waitTime;
        this.slots = new Semaphore(builder.maxConcurrentProcessing);
    }

    /** @param handlers the handlers of the keys this service owns */
    public static Builder builder(SqsClient sqs, Collection<? extends QueryHandler> handlers) {
        return new Builder(sqs, handlers);
    }

    /** The keys this server answers for. */
    public Collection<DistributedKeyRef> keys() {
        return responder.keys();
    }

    /** The URLs of the queues this server polls, by key; empty until it starts. */
    public synchronized Map<DistributedKeyRef, String> queueUrls() {
        return Map.copyOf(queueUrls);
    }

    /**
     * Creates the queues that do not exist and starts polling them; does nothing when the server already runs or has
     * no key.
     *
     * @throws software.amazon.awssdk.core.exception.SdkException when SQS refuses to find or create a queue
     */
    public synchronized SqsServer start() {
        if (generation != null || responder.keys().isEmpty()) return this;
        queueUrls.clear();
        for (var key : responder.keys()) queueUrls.put(key, queueUrl(SqsNames.queue(key)));
        var current = new Object();
        generation = current;
        queueUrls.forEach((key, url) -> Thread.ofVirtual().name("jfxmap-sqs-" + SqsNames.queue(key))
                .start(() -> poll(current, key, url)));
        return this;
    }

    private String queueUrl(String name) {
        try {
            return sqs.getQueueUrl(b -> b.queueName(name)).queueUrl();
        } catch (QueueDoesNotExistException e) {
            return sqs.createQueue(b -> b.queueName(name).attributes(Map.of(
                    QueueAttributeName.VISIBILITY_TIMEOUT, "30",
                    QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS, "20"))).queueUrl();
        }
    }

    public synchronized boolean isRunning() {
        return generation != null;
    }

    private synchronized boolean isCurrent(Object current) {
        return generation == current;
    }

    private void poll(Object current, DistributedKeyRef key, String url) {
        while (isCurrent(current)) {
            try {
                var messages = SqsLoops.receive(sqs, url, waitTime);
                if (messages.isEmpty()) continue;
                if (!isCurrent(current)) {
                    // Closed while polling: the messages go back to the queue at once, for another server
                    release(url, messages);
                    return;
                }
                SqsLoops.delete(sqs, url, messages);
                for (var message : messages) {
                    slots.acquire();
                    Thread.ofVirtual().name("jfxmap-sqs").start(() -> answer(key, message));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                if (!isCurrent(current)) return;
                LOGGER.log(System.Logger.Level.ERROR, "The queue " + url + " could not be polled.", e);
                SqsLoops.pause();
            }
        }
    }

    private void answer(DistributedKeyRef queueKey, Message message) {
        // FxMap finds the key by the Type attribute, the assembly-qualified name of the key
        var key = DotNetKeyNames.fromAssemblyQualifiedName(SqsLoops.attribute(message, SqsNames.TYPE))
                .orElse(queueKey);
        var replyTo = SqsLoops.attribute(message, SqsNames.REPLY_TO);
        var correlationId = SqsLoops.attribute(message, SqsNames.CORRELATION_ID);
        try {
            responder.answer(key, message.body().getBytes(StandardCharsets.UTF_8), SqsLoops.headers(message))
                    .whenComplete((reply, failure) -> {
                        try {
                            if (reply != null && replyTo != null && !replyTo.isEmpty())
                                sqs.sendMessage(b -> b.queueUrl(replyTo)
                                        .messageBody(new String(reply, StandardCharsets.UTF_8))
                                        .messageAttributes(Map.of(SqsNames.CORRELATION_ID,
                                                SqsLoops.string(correlationId == null ? "Unknown" : correlationId))));
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
    }

    private void release(String url, List<Message> messages) {
        try {
            var entries = new ArrayList<ChangeMessageVisibilityBatchRequestEntry>();
            for (int i = 0; i < messages.size(); i++)
                entries.add(ChangeMessageVisibilityBatchRequestEntry.builder().id(Integer.toString(i))
                        .receiptHandle(messages.get(i).receiptHandle()).visibilityTimeout(0).build());
            sqs.changeMessageVisibilityBatch(b -> b.queueUrl(url).entries(entries));
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Messages of " + url + " stay hidden until their timeout.", e);
        }
    }

    /** Stops polling; the queues stay. A poll in progress ends within the wait time. */
    @Override
    public synchronized void close() {
        generation = null;
    }

    public static final class Builder {
        private final SqsClient sqs;
        private final List<QueryHandler> handlers;
        private Duration timeout = WireResponder.DEFAULT_TIMEOUT;
        private Duration waitTime = SqsLoops.DEFAULT_WAIT_TIME;
        private int maxConcurrentProcessing = DEFAULT_MAX_CONCURRENT_PROCESSING;

        private Builder(SqsClient sqs, Collection<? extends QueryHandler> handlers) {
            this.sqs = Objects.requireNonNull(sqs, "sqs");
            this.handlers = List.copyOf(Objects.requireNonNull(handlers, "handlers"));
        }

        /** How long a handler may take before the request fails (default 30 seconds). */
        public Builder timeout(Duration timeout) {
            if (Objects.requireNonNull(timeout, "timeout").isNegative() || timeout.isZero())
                throw new IllegalArgumentException("The timeout must be positive.");
            this.timeout = timeout;
            return this;
        }

        /** How long a poll waits for messages (0 to 20 seconds, 20 by default). */
        public Builder waitTime(Duration waitTime) {
            this.waitTime = SqsLoops.checkWaitTime(waitTime);
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
        public SqsServer build() {
            return new SqsServer(this);
        }
    }
}

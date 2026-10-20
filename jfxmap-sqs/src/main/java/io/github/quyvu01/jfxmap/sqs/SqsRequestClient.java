package io.github.quyvu01.jfxmap.sqs;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.wire.DotNetKeyNames;
import io.github.quyvu01.jfxmap.wire.PendingReplies;
import io.github.quyvu01.jfxmap.wire.WireMessages;
import io.github.quyvu01.jfxmap.wire.WireResponder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Sends requests over Amazon SQS (FxMap's SqsRequestClient and {@code AddSqs}): each request is sent to the queue of
 * its key ({@link SqsNames#queue}) with the attributes {@code CorrelationId}, {@code ReplyTo} (the URL of the response
 * queue of this client), {@code Type} (the assembly-qualified name of the key) and the headers of the mapping call.
 * The owner replies on the response queue, {@code fxmap-response-<machine>-<uuid>}, which this client creates on its
 * first request (messages kept 5 minutes) and deletes when it closes.
 *
 * <p>A key whose queue does not exist (no service owns it) fails at once. The SQS client belongs to the caller.
 *
 * <pre>{@code
 * SqsClient sqs = SqsClient.create();
 * SqsRequestClient client = SqsRequestClient.builder(sqs).build();
 * }</pre>
 */
public final class SqsRequestClient implements RequestClient, AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(SqsRequestClient.class.getName());
    private static final Executor VIRTUAL_THREADS = task -> Thread.ofVirtual().name("jfxmap-sqs").start(task);

    private final SqsClient sqs;
    private final Duration timeout;
    private final Duration waitTime;
    private final Executor executor;
    private final PendingReplies pending = new PendingReplies();
    private final Map<String, String> queueUrls = new ConcurrentHashMap<>();
    // Guarded by this
    private String responseQueueUrl;
    private volatile boolean closed;

    private SqsRequestClient(Builder builder) {
        this.sqs = builder.sqs;
        this.timeout = builder.timeout;
        this.waitTime = builder.waitTime;
        this.executor = builder.executor;
    }

    public static Builder builder(SqsClient sqs) {
        return new Builder(sqs);
    }

    @Override
    public CompletionStage<ItemsResponse> request(DistributedKeyRef key, MapRequest request, MapContext context) {
        return CompletableFuture.supplyAsync(() -> send(key, request, context), executor)
                .thenCompose(reply -> reply)
                .thenApply(WireMessages::readResult);
    }

    private CompletableFuture<byte[]> send(DistributedKeyRef key, MapRequest request, MapContext context) {
        var correlationId = UUID.randomUUID().toString();
        try {
            var replyTo = responseQueueUrl();
            var queueUrl = queueUrl(key);
            var attributes = new LinkedHashMap<String, MessageAttributeValue>();
            attributes.put(SqsNames.CORRELATION_ID, SqsLoops.string(correlationId));
            attributes.put(SqsNames.REPLY_TO, SqsLoops.string(replyTo));
            attributes.put(SqsNames.TYPE, SqsLoops.string(DotNetKeyNames.assemblyQualifiedName(key)));
            // SQS refuses an attribute without a value
            context.headers().forEach((name, value) -> {
                if (value != null && !value.isEmpty()) attributes.putIfAbsent(name, SqsLoops.string(value));
            });
            var reply = pending.register(correlationId, key, timeout);
            try {
                sqs.sendMessage(b -> b.queueUrl(queueUrl).messageAttributes(attributes)
                        .messageBody(new String(WireMessages.writeRequest(request), StandardCharsets.UTF_8)));
            } catch (QueueDoesNotExistException e) {
                // Deleted since it was found
                queueUrls.remove(SqsNames.queue(key));
                throw new DistributedMapException.NoHandlerForDistributedKey(key);
            }
            return reply;
        } catch (RuntimeException e) {
            pending.fail(correlationId, e);
            throw e;
        }
    }

    private String queueUrl(DistributedKeyRef key) {
        var name = SqsNames.queue(key);
        var known = queueUrls.get(name);
        if (known != null) return known;
        try {
            var url = sqs.getQueueUrl(b -> b.queueName(name)).queueUrl();
            queueUrls.put(name, url);
            return url;
        } catch (QueueDoesNotExistException e) {
            throw new DistributedMapException.NoHandlerForDistributedKey(key);
        }
    }

    private synchronized String responseQueueUrl() {
        if (closed) throw new IllegalStateException("The SQS request client is closed.");
        if (responseQueueUrl != null) return responseQueueUrl;
        var url = sqs.createQueue(b -> b.queueName(SqsNames.responseQueue(machineName())).attributes(Map.of(
                QueueAttributeName.MESSAGE_RETENTION_PERIOD, "300",
                QueueAttributeName.VISIBILITY_TIMEOUT, "30",
                QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS, "20"))).queueUrl();
        responseQueueUrl = url;
        Thread.ofVirtual().name("jfxmap-sqs-replies").start(() -> receiveReplies(url));
        return url;
    }

    private void receiveReplies(String url) {
        while (!closed) {
            try {
                var messages = SqsLoops.receive(sqs, url, waitTime);
                for (var message : messages)
                    pending.complete(SqsLoops.attribute(message, SqsNames.CORRELATION_ID),
                            message.body().getBytes(StandardCharsets.UTF_8));
                SqsLoops.delete(sqs, url, messages);
            } catch (RuntimeException e) {
                if (closed) return;
                LOGGER.log(System.Logger.Level.WARNING, "The replies of " + url + " could not be received.", e);
                SqsLoops.pause();
            }
        }
    }

    private static String machineName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "java";
        }
    }

    /** How many requests wait for their reply. */
    public int pendingRequests() {
        return pending.size();
    }

    /** The URL of the response queue, or null before the first request. */
    public synchronized String responseQueue() {
        return responseQueueUrl;
    }

    /** Fails the requests that still wait and deletes the response queue. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        pending.failAll(new IllegalStateException("The SQS request client is closed."));
        if (responseQueueUrl == null) return;
        try {
            var url = responseQueueUrl;
            sqs.deleteQueue(b -> b.queueUrl(url));
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "The response queue " + responseQueueUrl + " was not deleted.", e);
        }
    }

    @Override
    public String transport() {
        return "sqs";
    }

    public static final class Builder {
        private final SqsClient sqs;
        private Duration timeout = WireResponder.DEFAULT_TIMEOUT;
        private Duration waitTime = SqsLoops.DEFAULT_WAIT_TIME;
        private Executor executor = VIRTUAL_THREADS;

        private Builder(SqsClient sqs) {
            this.sqs = Objects.requireNonNull(sqs, "sqs");
        }

        /** How long a request may wait for its reply (default 30 seconds, FxMap's DefaultRequestTimeout). */
        public Builder timeout(Duration timeout) {
            if (Objects.requireNonNull(timeout, "timeout").isNegative() || timeout.isZero())
                throw new IllegalArgumentException("The timeout must be positive.");
            this.timeout = timeout;
            return this;
        }

        /** How long a poll of the response queue waits for messages (0 to 20 seconds, 20 by default). */
        public Builder waitTime(Duration waitTime) {
            this.waitTime = SqsLoops.checkWaitTime(waitTime);
            return this;
        }

        /** Sends the requests, which blocks; by default on virtual threads. */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        public SqsRequestClient build() {
            return new SqsRequestClient(this);
        }
    }
}

package io.github.quyvu01.jfxmap.azure.servicebus;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverAsyncClient;
import com.azure.messaging.servicebus.ServiceBusSenderAsyncClient;
import com.azure.messaging.servicebus.ServiceBusSessionReceiverAsyncClient;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.wire.PendingReplies;
import io.github.quyvu01.jfxmap.wire.WireMessages;
import io.github.quyvu01.jfxmap.wire.WireResponder;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends requests over Azure Service Bus (FxMap's OpenAzureServiceBusClient and {@code AddAzureServiceBus}): each
 * request goes to the request queue of its key ({@link ServiceBusNames}) with a correlation id, the reply queue of the
 * key as {@code ReplyTo}, the session id of this client, and the headers of the mapping call as application
 * properties, but the W3C trace context: Service Bus refuses it as FxMap sends it, in bytes. The owner replies on the
 * reply queue in the same session, which this client holds: the clients of a key
 * share its reply queue, each reading its own session.
 *
 * <p>A key whose request queue does not exist (no service owns it) fails at once. The owner creates both queues.
 *
 * <pre>{@code
 * ServiceBusClientBuilder clients = new ServiceBusClientBuilder().connectionString(connectionString);
 * ServiceBusRequestClient client = ServiceBusRequestClient.builder(clients).build();
 * }</pre>
 */
public final class ServiceBusRequestClient implements RequestClient, AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(ServiceBusRequestClient.class.getName());
    // The session of a client stays locked for as long as the client lives
    private static final Duration SESSION_LOCK_RENEWAL = Duration.ofDays(3650);

    private final ServiceBusClientBuilder clients;
    private final String prefix;
    private final Duration timeout;
    private final String sessionId = UUID.randomUUID().toString();
    private final PendingReplies pending = new PendingReplies();
    private final Map<String, ServiceBusSenderAsyncClient> senders = new ConcurrentHashMap<>();
    private final Map<String, ReplySession> replySessions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private ServiceBusRequestClient(Builder builder) {
        this.clients = builder.clients;
        this.prefix = builder.prefix;
        this.timeout = builder.timeout;
    }

    public static Builder builder(ServiceBusClientBuilder clients) {
        return new Builder(clients);
    }

    /** The session this client reads its replies in. */
    public String sessionId() {
        return sessionId;
    }

    @Override
    public CompletionStage<ItemsResponse> request(DistributedKeyRef key, MapRequest request, MapContext context) {
        if (closed)
            return CompletableFuture.failedFuture(new IllegalStateException("The Service Bus request client is closed."));
        var correlationId = UUID.randomUUID().toString();
        CompletableFuture<byte[]> reply;
        try {
            var replyQueue = ServiceBusNames.replyQueue(prefix, key);
            listen(replyQueue);
            reply = pending.register(correlationId, key, timeout);
            var message = new ServiceBusMessage(WireMessages.writeRequest(request))
                    .setCorrelationId(correlationId)
                    .setReplyTo(replyQueue)
                    .setSessionId(sessionId);
            context.headers().forEach((name, value) -> {
                if (value != null && ServiceBusMessages.isSent(name))
                    message.getApplicationProperties().putIfAbsent(name, value);
            });
            sender(ServiceBusNames.requestQueue(prefix, key)).sendMessage(message).toFuture()
                    .whenComplete((ignored, failure) -> {
                        if (failure == null) return;
                        pending.fail(correlationId, ServiceBusMessages.isMissingEntity(failure)
                                ? new DistributedMapException.NoHandlerForDistributedKey(key) : unwrap(failure));
                    });
        } catch (RuntimeException e) {
            pending.fail(correlationId, e);
            return CompletableFuture.failedFuture(e);
        }
        return reply.thenApply(WireMessages::readResult);
    }

    private ServiceBusSenderAsyncClient sender(String queue) {
        return senders.computeIfAbsent(queue, q -> clients.sender().queueName(q).buildAsyncClient());
    }

    // Reads the session of this client on the reply queue, once; again after the session was lost
    private void listen(String replyQueue) {
        if (replySessions.containsKey(replyQueue)) return;
        var receivers = clients.sessionReceiver().queueName(replyQueue)
                .maxAutoLockRenewDuration(SESSION_LOCK_RENEWAL).buildAsyncClient();
        var session = new ReplySession(receivers);
        if (replySessions.putIfAbsent(replyQueue, session) != null) {
            receivers.close();
            return;
        }
        session.subscription = receivers.acceptSession(sessionId)
                .flatMapMany(receiver -> {
                    session.receiver = receiver;
                    return receiver.receiveMessages();
                })
                .subscribe(message -> pending.complete(message.getCorrelationId(), message.getBody().toBytes()),
                        failure -> {
                            if (!closed) LOGGER.log(System.Logger.Level.WARNING, "The replies of " + replyQueue
                                    + " could not be received; the session is accepted again on the next request.",
                                    failure);
                            replySessions.remove(replyQueue, session);
                            session.close();
                        });
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    /** How many requests wait for their reply. */
    public int pendingRequests() {
        return pending.size();
    }

    /** Fails the requests that still wait, releases the sessions and closes the senders. */
    @Override
    public void close() {
        closed = true;
        pending.failAll(new IllegalStateException("The Service Bus request client is closed."));
        replySessions.values().forEach(ReplySession::close);
        replySessions.clear();
        senders.values().forEach(sender -> {
            try {
                sender.close();
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, "A Service Bus sender did not close.", e);
            }
        });
        senders.clear();
    }

    private static final class ReplySession {
        final ServiceBusSessionReceiverAsyncClient receivers;
        volatile ServiceBusReceiverAsyncClient receiver;
        volatile Disposable subscription;

        ReplySession(ServiceBusSessionReceiverAsyncClient receivers) {
            this.receivers = receivers;
        }

        void close() {
            try {
                if (subscription != null) subscription.dispose();
                if (receiver != null) receiver.close();
                receivers.close();
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, "A Service Bus session receiver did not close.", e);
            }
        }
    }

    @Override
    public String transport() {
        return "azureservicebus";
    }

    public static final class Builder {
        private final ServiceBusClientBuilder clients;
        private String prefix;
        private Duration timeout = WireResponder.DEFAULT_TIMEOUT;

        private Builder(ServiceBusClientBuilder clients) {
            this.clients = Objects.requireNonNull(clients, "clients");
        }

        /** The prefix of the queue names (FxMap's {@code TopicPrefix}), none by default. */
        public Builder topicPrefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        /** How long a request may wait for its reply (default 30 seconds, FxMap's DefaultRequestTimeout). */
        public Builder timeout(Duration timeout) {
            if (Objects.requireNonNull(timeout, "timeout").isNegative() || timeout.isZero())
                throw new IllegalArgumentException("The timeout must be positive.");
            this.timeout = timeout;
            return this;
        }

        public ServiceBusRequestClient build() {
            return new ServiceBusRequestClient(this);
        }
    }
}

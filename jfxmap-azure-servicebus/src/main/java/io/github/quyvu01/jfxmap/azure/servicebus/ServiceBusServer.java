package io.github.quyvu01.jfxmap.azure.servicebus;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.administration.ServiceBusAdministrationClient;
import com.azure.messaging.servicebus.administration.models.CreateQueueOptions;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.wire.WireResponder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers the requests for the keys this service owns over Azure Service Bus (FxMap's AzureServiceBusServer): for each
 * key it creates the request and reply queues ({@link ServiceBusNames}, both requiring sessions) when an
 * administration client is given and they do not exist, then reads the request queue with a session processor
 * ({@code maxConcurrentSessions} sessions at once, 8 by default, one message at a time in each). Each request is
 * answered with a {@code Result} on the queue of its {@code ReplyTo}, with its correlation id and session id, then
 * completed.
 *
 * <p>Without an administration client the queues must exist, as on the Service Bus emulator, which declares them in
 * its configuration.
 *
 * <pre>{@code
 * ServiceBusServer server = ServiceBusServer.builder(clients, handlers).administration(admin).build().start();
 * }</pre>
 */
public final class ServiceBusServer implements AutoCloseable {
    /** FxMap's default {@code MaxConcurrentSessions}. */
    public static final int DEFAULT_MAX_CONCURRENT_SESSIONS = 8;

    private static final System.Logger LOGGER = System.getLogger(ServiceBusServer.class.getName());

    private final ServiceBusClientBuilder clients;
    private final ServiceBusAdministrationClient administration;
    private final WireResponder responder;
    private final String prefix;
    private final int maxConcurrentSessions;
    private final Map<String, ServiceBusSenderClient> senders = new ConcurrentHashMap<>();
    // Guarded by this
    private final List<ServiceBusProcessorClient> processors = new ArrayList<>();

    private ServiceBusServer(Builder builder) {
        this.clients = builder.clients;
        this.administration = builder.administration;
        this.responder = new WireResponder(builder.handlers, builder.timeout);
        this.prefix = builder.prefix;
        this.maxConcurrentSessions = builder.maxConcurrentSessions;
    }

    /** @param handlers the handlers of the keys this service owns */
    public static Builder builder(ServiceBusClientBuilder clients, Collection<? extends QueryHandler> handlers) {
        return new Builder(clients, handlers);
    }

    /** The keys this server answers for. */
    public Collection<DistributedKeyRef> keys() {
        return responder.keys();
    }

    /** The request queue this server reads for each key. */
    public Map<DistributedKeyRef, String> requestQueues() {
        var queues = new LinkedHashMap<DistributedKeyRef, String>();
        for (var key : responder.keys()) queues.put(key, ServiceBusNames.requestQueue(prefix, key));
        return queues;
    }

    /**
     * Creates the missing queues (with an administration client) and starts reading the request queues; does nothing
     * when the server already runs or has no key.
     */
    public synchronized ServiceBusServer start() {
        if (!processors.isEmpty() || responder.keys().isEmpty()) return this;
        try {
            for (var key : responder.keys()) {
                var requestQueue = ServiceBusNames.requestQueue(prefix, key);
                createIfMissing(requestQueue);
                createIfMissing(ServiceBusNames.replyQueue(prefix, key));
                var processor = clients.sessionProcessor()
                        .queueName(requestQueue)
                        .maxConcurrentSessions(maxConcurrentSessions)
                        .maxConcurrentCalls(1)
                        .disableAutoComplete()
                        .processMessage(context -> answer(key, context))
                        .processError(this::failed)
                        .buildProcessorClient();
                processors.add(processor);
                processor.start();
            }
        } catch (RuntimeException e) {
            close();
            throw e;
        }
        return this;
    }

    private void createIfMissing(String queue) {
        if (administration == null) return;
        try {
            if (!administration.getQueueExists(queue))
                administration.createQueue(queue, new CreateQueueOptions().setSessionRequired(true));
        } catch (RuntimeException e) {
            // FxMap goes on too: the queue may exist without the rights to manage it
            LOGGER.log(System.Logger.Level.WARNING, "The queue " + queue + " could not be created.", e);
        }
    }

    public synchronized boolean isRunning() {
        return !processors.isEmpty();
    }

    private void answer(DistributedKeyRef key, ServiceBusReceivedMessageContext context) {
        var request = context.getMessage();
        byte[] reply;
        try {
            reply = responder.answer(key, request.getBody().toBytes(), ServiceBusMessages.headers(request)).join();
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.ERROR, "The request for " + key + " could not be answered.", e);
            reply = null;
        }
        try {
            if (reply != null && request.getReplyTo() != null)
                sender(request.getReplyTo()).sendMessage(new ServiceBusMessage(reply)
                        .setCorrelationId(request.getCorrelationId())
                        .setSessionId(request.getSessionId()));
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.ERROR, "The reply for " + key + " could not be sent.", e);
        }
        try {
            context.complete();
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.ERROR, "The request for " + key + " could not be completed.", e);
        }
    }

    private ServiceBusSenderClient sender(String queue) {
        return senders.computeIfAbsent(queue, q -> clients.sender().queueName(q).buildClient());
    }

    private void failed(ServiceBusErrorContext context) {
        LOGGER.log(System.Logger.Level.ERROR, "Azure Service Bus error on " + context.getEntityPath() + " ("
                                              + context.getErrorSource() + ")", context.getException());
    }

    /** Stops reading the request queues and closes the senders; the queues stay. */
    @Override
    public synchronized void close() {
        for (var processor : processors) {
            try {
                processor.close();
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, "A Service Bus processor did not close.", e);
            }
        }
        processors.clear();
        senders.values().forEach(sender -> {
            try {
                sender.close();
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, "A Service Bus sender did not close.", e);
            }
        });
        senders.clear();
    }

    public static final class Builder {
        private final ServiceBusClientBuilder clients;
        private final List<QueryHandler> handlers;
        private ServiceBusAdministrationClient administration;
        private String prefix;
        private Duration timeout = WireResponder.DEFAULT_TIMEOUT;
        private int maxConcurrentSessions = DEFAULT_MAX_CONCURRENT_SESSIONS;

        private Builder(ServiceBusClientBuilder clients, Collection<? extends QueryHandler> handlers) {
            this.clients = Objects.requireNonNull(clients, "clients");
            this.handlers = List.copyOf(Objects.requireNonNull(handlers, "handlers"));
        }

        /** Creates the queues that do not exist when the server starts; without it, they must exist. */
        public Builder administration(ServiceBusAdministrationClient administration) {
            this.administration = administration;
            return this;
        }

        /** The prefix of the queue names (FxMap's {@code TopicPrefix}), none by default. */
        public Builder topicPrefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        /** How long a handler may take before the request fails (default 30 seconds). */
        public Builder timeout(Duration timeout) {
            if (Objects.requireNonNull(timeout, "timeout").isNegative() || timeout.isZero())
                throw new IllegalArgumentException("The timeout must be positive.");
            this.timeout = timeout;
            return this;
        }

        /** How many sessions (clients) are answered at once on each request queue (default 8). */
        public Builder maxConcurrentSessions(int maxConcurrentSessions) {
            if (maxConcurrentSessions < 1)
                throw new IllegalArgumentException("maxConcurrentSessions must be at least 1.");
            this.maxConcurrentSessions = maxConcurrentSessions;
            return this;
        }

        /** @throws DistributedMapException.DistributedKeyAssignedToMultipleEntities when two handlers share a key */
        public ServiceBusServer build() {
            return new ServiceBusServer(this);
        }
    }
}

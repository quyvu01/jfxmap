package io.github.quyvu01.jfxmap.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
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

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

/**
 * Sends requests over RabbitMQ (FxMap's RabbitMqRequestClient and {@code AddRabbitMq}): each request is published to
 * the exchange of its key ({@link RabbitMqNames}) with a correlation id, the reply queue of this client, the
 * assembly-qualified name of the key as type, and the headers of the mapping call; the owner replies on the reply
 * queue, {@code fxmap-reply-<uuid>}, exclusive to this client.
 *
 * <p>A key whose exchange does not exist (no service declared it) fails at once. The connection belongs to the
 * caller; {@link #close()} closes only the channels of the client.
 *
 * <pre>{@code
 * Connection connection = new ConnectionFactory().newConnection();
 * RabbitMqRequestClient client = RabbitMqRequestClient.builder(connection).build();
 * }</pre>
 */
public final class RabbitMqRequestClient implements RequestClient, AutoCloseable {
    private static final Executor VIRTUAL_THREADS = task -> Thread.ofVirtual().name("jfxmap-rabbitmq").start(task);

    private final Connection connection;
    private final Duration timeout;
    private final Executor executor;
    private final PendingReplies pending = new PendingReplies();
    private final Set<String> knownExchanges = ConcurrentHashMap.newKeySet();
    // Guarded by this
    private Channel replyChannel;
    private Channel publishChannel;
    private String replyQueue;
    private boolean closed;

    private RabbitMqRequestClient(Builder builder) {
        this.connection = builder.connection;
        this.timeout = builder.timeout;
        this.executor = builder.executor;
    }

    public static Builder builder(Connection connection) {
        return new Builder(connection);
    }

    @Override
    public CompletionStage<ItemsResponse> request(DistributedKeyRef key, MapRequest request, MapContext context) {
        return CompletableFuture.supplyAsync(() -> send(key, request, context), executor)
                .thenCompose(reply -> reply)
                .thenApply(WireMessages::readResult);
    }

    private CompletableFuture<byte[]> send(DistributedKeyRef key, MapRequest request, MapContext context) {
        var exchange = RabbitMqNames.exchange(key);
        var correlationId = UUID.randomUUID().toString();
        try {
            if (!exchangeExists(exchange)) throw new DistributedMapException.NoHandlerForDistributedKey(key);
            var reply = pending.register(correlationId, key, timeout);
            var properties = new AMQP.BasicProperties.Builder()
                    .correlationId(correlationId)
                    .replyTo(replyQueue())
                    .type(DotNetKeyNames.assemblyQualifiedName(key))
                    .headers(RabbitMqHeaders.toAmqp(context.headers()))
                    .build();
            synchronized (this) {
                publishChannel().basicPublish(exchange, RabbitMqNames.ROUTING_KEY, true, properties,
                        WireMessages.writeRequest(request));
            }
            return reply;
        } catch (IOException | RuntimeException e) {
            pending.fail(correlationId, e);
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(
                    "The request for " + key + " could not be published to RabbitMQ.", e);
        }
    }

    // A passive declare on a channel of its own: it closes the channel when the exchange does not exist
    private boolean exchangeExists(String exchange) throws IOException {
        if (knownExchanges.contains(exchange)) return true;
        Channel channel = connection.createChannel();
        try {
            channel.exchangeDeclarePassive(exchange);
            knownExchanges.add(exchange);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (channel.isOpen()) {
                try {
                    channel.close();
                } catch (IOException | TimeoutException e) {
                    // Closing a channel that served its purpose
                }
            }
        }
    }

    private synchronized String replyQueue() throws IOException {
        if (closed) throw new IllegalStateException("The RabbitMQ request client is closed.");
        if (replyChannel != null && replyChannel.isOpen()) return replyQueue;
        var channel = connection.createChannel();
        // Named here, not by the broker, as FxMap does: the name stays the same when the connection recovers
        var queue = replyQueue != null ? replyQueue
                : RabbitMqNames.REPLY_QUEUE_PREFIX + "-" + UUID.randomUUID().toString().replace("-", "");
        channel.queueDeclare(queue, false, true, true, null);
        channel.basicConsume(queue, false, new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
                                       byte[] body) throws IOException {
                pending.complete(properties.getCorrelationId(), body);
                getChannel().basicAck(envelope.getDeliveryTag(), false);
            }
        });
        replyChannel = channel;
        replyQueue = queue;
        return queue;
    }

    private Channel publishChannel() throws IOException {
        if (publishChannel == null || !publishChannel.isOpen()) {
            var channel = connection.createChannel();
            // A request that no queue receives comes back: it fails at once instead of timing out
            channel.addReturnListener(returned -> pending.fail(returned.getProperties().getCorrelationId(),
                    new DistributedMapException.NoHandlerForDistributedKey(RabbitMqNames
                            .keyOfExchange(returned.getExchange()).orElse(new DistributedKeyRef(returned.getExchange())))));
            publishChannel = channel;
        }
        return publishChannel;
    }

    /** How many requests wait for their reply. */
    public int pendingRequests() {
        return pending.size();
    }

    /** Closes the channels of the client and fails the requests that still wait. */
    @Override
    public synchronized void close() {
        closed = true;
        pending.failAll(new IllegalStateException("The RabbitMQ request client is closed."));
        for (var channel : new Channel[]{publishChannel, replyChannel}) {
            if (channel == null || !channel.isOpen()) continue;
            try {
                channel.close();
            } catch (IOException | java.util.concurrent.TimeoutException | RuntimeException e) {
                // The connection may be closing too
            }
        }
        publishChannel = null;
        replyChannel = null;
    }

    public static final class Builder {
        private final Connection connection;
        private Duration timeout = WireResponder.DEFAULT_TIMEOUT;
        private Executor executor = VIRTUAL_THREADS;

        private Builder(Connection connection) {
            this.connection = Objects.requireNonNull(connection, "connection");
        }

        /** How long a request may wait for its reply (default 30 seconds, FxMap's DefaultRequestTimeout). */
        public Builder timeout(Duration timeout) {
            if (Objects.requireNonNull(timeout, "timeout").isNegative() || timeout.isZero())
                throw new IllegalArgumentException("The timeout must be positive.");
            this.timeout = timeout;
            return this;
        }

        /** Publishes the requests, which may block; by default on virtual threads. */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        public RabbitMqRequestClient build() {
            return new RabbitMqRequestClient(this);
        }
    }
}

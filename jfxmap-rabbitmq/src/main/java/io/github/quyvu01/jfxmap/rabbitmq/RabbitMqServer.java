package io.github.quyvu01.jfxmap.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.wire.DotNetKeyNames;
import io.github.quyvu01.jfxmap.wire.WireResponder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Answers the requests for the keys this service owns over RabbitMQ (FxMap's RabbitMqServer): it declares the queue of
 * the service ({@code fxmap-rpc-queue-<application name>}) and a direct exchange per key, bound to it, then consumes
 * the queue on {@code channelPoolSize} channels (5 by default, FxMap's {@code ChannelPoolSize}). Each request is
 * answered with a {@code Result} on its reply queue, with its correlation id, then acknowledged.
 *
 * <p>The application name must differ between services: two services with the same name share one queue, and
 * each gets the requests of the other.
 *
 * <pre>{@code
 * RabbitMqServer server = RabbitMqServer.builder(connection, "user-service", handlers).build().start();
 * }</pre>
 */
public final class RabbitMqServer implements AutoCloseable {
    public static final int DEFAULT_CHANNEL_POOL_SIZE = 5;
    /** FxMap's {@code MaxConcurrentProcessing}: the unacknowledged requests of each channel. */
    public static final int DEFAULT_MAX_CONCURRENT_PROCESSING = 128;

    private static final System.Logger LOGGER = System.getLogger(RabbitMqServer.class.getName());

    private final Connection connection;
    private final WireResponder responder;
    private final String queue;
    private final int channelPoolSize;
    private final int maxConcurrentProcessing;
    private final List<Channel> channels = new ArrayList<>();

    private RabbitMqServer(Builder builder) {
        this.connection = builder.connection;
        this.responder = new WireResponder(builder.handlers, builder.timeout);
        this.queue = RabbitMqNames.queue(builder.applicationName);
        this.channelPoolSize = builder.channelPoolSize;
        this.maxConcurrentProcessing = builder.maxConcurrentProcessing;
    }

    /**
     * @param applicationName the name of the service, which names its queue (FxMap uses the entry assembly's name)
     * @param handlers        the handlers of the keys this service owns
     */
    public static Builder builder(Connection connection, String applicationName,
                                  Collection<? extends QueryHandler> handlers) {
        return new Builder(connection, applicationName, handlers);
    }

    /** The keys this server answers for. */
    public Collection<DistributedKeyRef> keys() {
        return responder.keys();
    }

    /** The queue this server consumes. */
    public String queue() {
        return queue;
    }

    /**
     * Declares the queue and the exchanges, and starts consuming; does nothing when the server already runs or has no
     * key.
     *
     * @throws UncheckedIOException when RabbitMQ refuses a declaration
     */
    public synchronized RabbitMqServer start() {
        if (!channels.isEmpty() || responder.keys().isEmpty()) return this;
        try {
            try (var declare = connection.createChannel()) {
                declare.queueDeclare(queue, false, false, false, null);
                for (var key : responder.keys()) {
                    var exchange = RabbitMqNames.exchange(key);
                    declare.exchangeDeclare(exchange, BuiltinExchangeType.DIRECT);
                    declare.queueBind(queue, exchange, RabbitMqNames.ROUTING_KEY);
                }
            }
            for (int i = 0; i < channelPoolSize; i++) {
                var channel = connection.createChannel();
                channels.add(channel);
                channel.basicQos(maxConcurrentProcessing);
                channel.basicConsume(queue, false, new DefaultConsumer(channel) {
                    @Override
                    public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
                                               byte[] body) {
                        receive(getChannel(), envelope, properties, body);
                    }
                });
            }
        } catch (IOException | TimeoutException e) {
            close();
            throw new UncheckedIOException("The FxMap RabbitMQ server could not start on " + queue,
                    e instanceof IOException io ? io : new IOException(e));
        }
        return this;
    }

    public synchronized boolean isRunning() {
        return !channels.isEmpty();
    }

    private void receive(Channel channel, Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
        // FxMap finds the key by the type of the message, the assembly-qualified name of the key
        var key = DotNetKeyNames.fromAssemblyQualifiedName(properties.getType())
                .or(() -> RabbitMqNames.keyOfExchange(envelope.getExchange()))
                .orElse(null);
        Thread.ofVirtual().name("jfxmap-rabbitmq").start(() -> responder
                .answer(key, body, RabbitMqHeaders.fromAmqp(properties.getHeaders()))
                .whenComplete((reply, failure) -> {
                    try {
                        synchronized (channel) {
                            if (reply != null && properties.getReplyTo() != null)
                                channel.basicPublish("", properties.getReplyTo(), true,
                                        new AMQP.BasicProperties.Builder()
                                                .correlationId(properties.getCorrelationId()).build(), reply);
                            channel.basicAck(envelope.getDeliveryTag(), false);
                        }
                    } catch (IOException | RuntimeException e) {
                        LOGGER.log(System.Logger.Level.ERROR, "The reply for " + key + " could not be sent.", e);
                    }
                }));
    }

    /** Closes the consumer channels; the queue and the exchanges stay. */
    @Override
    public synchronized void close() {
        for (var channel : channels) {
            if (!channel.isOpen()) continue;
            try {
                channel.close();
            } catch (IOException | TimeoutException | RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, "A RabbitMQ consumer channel did not close.", e);
            }
        }
        channels.clear();
    }

    public static final class Builder {
        private final Connection connection;
        private final String applicationName;
        private final List<QueryHandler> handlers;
        private Duration timeout = WireResponder.DEFAULT_TIMEOUT;
        private int channelPoolSize = DEFAULT_CHANNEL_POOL_SIZE;
        private int maxConcurrentProcessing = DEFAULT_MAX_CONCURRENT_PROCESSING;

        private Builder(Connection connection, String applicationName, Collection<? extends QueryHandler> handlers) {
            this.connection = Objects.requireNonNull(connection, "connection");
            RabbitMqNames.queue(applicationName);
            this.applicationName = applicationName;
            this.handlers = List.copyOf(Objects.requireNonNull(handlers, "handlers"));
        }

        /** How long a handler may take before the request fails (default 30 seconds). */
        public Builder timeout(Duration timeout) {
            if (Objects.requireNonNull(timeout, "timeout").isNegative() || timeout.isZero())
                throw new IllegalArgumentException("The timeout must be positive.");
            this.timeout = timeout;
            return this;
        }

        /** How many channels consume the queue (default 5). */
        public Builder channelPoolSize(int channelPoolSize) {
            if (channelPoolSize < 1) throw new IllegalArgumentException("channelPoolSize must be at least 1.");
            this.channelPoolSize = channelPoolSize;
            return this;
        }

        /** How many requests each channel answers at once (default 128). */
        public Builder maxConcurrentProcessing(int maxConcurrentProcessing) {
            if (maxConcurrentProcessing < 1)
                throw new IllegalArgumentException("maxConcurrentProcessing must be at least 1.");
            this.maxConcurrentProcessing = maxConcurrentProcessing;
            return this;
        }

        /** @throws DistributedMapException.DistributedKeyAssignedToMultipleEntities when two handlers share a key */
        public RabbitMqServer build() {
            return new RabbitMqServer(this);
        }
    }
}

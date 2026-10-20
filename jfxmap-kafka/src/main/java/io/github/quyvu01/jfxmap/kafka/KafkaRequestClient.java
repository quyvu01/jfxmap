package io.github.quyvu01.jfxmap.kafka;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.wire.PendingReplies;
import io.github.quyvu01.jfxmap.wire.WireMessages;
import io.github.quyvu01.jfxmap.wire.WireResponder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Sends requests over Kafka (FxMap's KafkaClient and {@code AddKafka}): each request goes to the request topic of its
 * key ({@link KafkaNames}), keyed by a correlation id, wrapped with the reply topic of this client
 * ({@code fxmap-response-topic-<application>-<uuid>}, one partition, created on the first request); the owner replies
 * there with the same key. A key whose request topic does not exist (no service created it) fails at once.
 *
 * <pre>{@code
 * KafkaRequestClient client = KafkaRequestClient.builder("localhost:9092", "order-service").build();
 * }</pre>
 */
public final class KafkaRequestClient implements RequestClient, AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(KafkaRequestClient.class.getName());
    private static final Executor VIRTUAL_THREADS = task -> Thread.ofVirtual().name("jfxmap-kafka").start(task);

    private final Map<String, Object> config;
    private final String replyTopic;
    private final Duration timeout;
    private final short replicationFactor;
    private final Executor executor;
    private final PendingReplies pending = new PendingReplies();
    private final Set<String> knownTopics = ConcurrentHashMap.newKeySet();
    // Guarded by this
    private KafkaProducer<String, byte[]> producer;
    private Thread replyLoop;
    private volatile KafkaConsumer<String, byte[]> consumer;
    private volatile boolean closed;

    private KafkaRequestClient(Builder builder) {
        this.config = Map.copyOf(builder.config);
        this.replyTopic = KafkaNames.newResponseTopic(builder.applicationName);
        this.timeout = builder.timeout;
        this.replicationFactor = builder.replicationFactor;
        this.executor = builder.executor;
    }

    /**
     * @param bootstrapServers the Kafka brokers ({@code host:port,host:port})
     * @param applicationName  the name of the service, in the name of its reply topic
     */
    public static Builder builder(String bootstrapServers, String applicationName) {
        return new Builder(bootstrapServers, applicationName);
    }

    /** The topic this client receives its replies on. */
    public String replyTopic() {
        return replyTopic;
    }

    @Override
    public CompletionStage<ItemsResponse> request(DistributedKeyRef key, MapRequest request, MapContext context) {
        return CompletableFuture.supplyAsync(() -> send(key, request, context), executor)
                .thenCompose(reply -> reply)
                .thenApply(WireMessages::readResult);
    }

    private CompletableFuture<byte[]> send(DistributedKeyRef key, MapRequest request, MapContext context) {
        var topic = KafkaNames.requestTopic(key);
        if (!knownTopics.contains(topic)) {
            if (!KafkaTopics.exists(config, topic)) throw new DistributedMapException.NoHandlerForDistributedKey(key);
            knownTopics.add(topic);
        }
        var producer = start();
        var correlationId = UUID.randomUUID().toString();
        var reply = pending.register(correlationId, key, timeout);
        var record = new ProducerRecord<>(topic, null, correlationId, KafkaMessages.writeRequest(request, replyTopic),
                KafkaMessages.toKafka(context.headers()));
        producer.send(record, (metadata, failure) -> {
            if (failure != null) pending.fail(correlationId, failure);
        });
        return reply;
    }

    // The reply topic, its consumer and the producer, on the first request
    private synchronized KafkaProducer<String, byte[]> start() {
        if (closed) throw new IllegalStateException("The Kafka request client is closed.");
        if (producer != null) return producer;
        KafkaTopics.create(config, List.of(replyTopic), 1, replicationFactor);
        var consumerConfig = new HashMap<>(config);
        // The topic is this client's alone: it is assigned, not subscribed, so no group has to settle first
        consumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var replies = new KafkaConsumer<>(consumerConfig, new StringDeserializer(), new ByteArrayDeserializer());
        replies.assign(List.of(new TopicPartition(replyTopic, 0)));
        replies.seekToBeginning(List.of(new TopicPartition(replyTopic, 0)));
        consumer = replies;
        replyLoop = Thread.ofPlatform().daemon().name("jfxmap-kafka-replies").start(this::readReplies);
        producer = new KafkaProducer<>(new HashMap<>(config), new StringSerializer(), new ByteArraySerializer());
        return producer;
    }

    private void readReplies() {
        var replies = consumer;
        try {
            while (!closed) {
                for (var record : replies.poll(Duration.ofMillis(200))) pending.complete(record.key(), record.value());
            }
        } catch (WakeupException e) {
            // close() stops the loop
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.ERROR, "The Kafka replies of " + replyTopic + " cannot be read.", e);
            pending.failAll(e);
        } finally {
            replies.close();
        }
    }

    /** How many requests wait for their reply. */
    public int pendingRequests() {
        return pending.size();
    }

    /** Stops reading replies, closes the producer and fails the requests that still wait. The reply topic stays. */
    @Override
    public void close() {
        Thread loop;
        KafkaProducer<String, byte[]> sender;
        synchronized (this) {
            if (closed) return;
            closed = true;
            loop = replyLoop;
            sender = producer;
        }
        if (consumer != null) consumer.wakeup();
        if (loop != null) {
            try {
                loop.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (sender != null) sender.close(Duration.ofSeconds(5));
        pending.failAll(new IllegalStateException("The Kafka request client is closed."));
    }

    public static final class Builder {
        private final Map<String, Object> config = new HashMap<>();
        private final String applicationName;
        private Duration timeout = WireResponder.DEFAULT_TIMEOUT;
        private short replicationFactor = 1;
        private Executor executor = VIRTUAL_THREADS;

        private Builder(String bootstrapServers, String applicationName) {
            if (bootstrapServers == null || bootstrapServers.isBlank())
                throw new IllegalArgumentException("The Kafka bootstrap servers are blank.");
            KafkaNames.newResponseTopic(applicationName);
            this.applicationName = applicationName;
            config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        }

        /** A property of the Kafka clients (SSL, SASL...), given to the producer, the consumer and the admin. */
        public Builder property(String name, Object value) {
            config.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** How long a request may wait for its reply (default 30 seconds, FxMap's DefaultRequestTimeout). */
        public Builder timeout(Duration timeout) {
            if (Objects.requireNonNull(timeout, "timeout").isNegative() || timeout.isZero())
                throw new IllegalArgumentException("The timeout must be positive.");
            this.timeout = timeout;
            return this;
        }

        /** The replication factor of the reply topic (default 1, as in FxMap). */
        public Builder replicationFactor(short replicationFactor) {
            if (replicationFactor < 1) throw new IllegalArgumentException("The replication factor must be at least 1.");
            this.replicationFactor = replicationFactor;
            return this;
        }

        /** Sends the requests, which may block; by default on virtual threads. */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        public KafkaRequestClient build() {
            return new KafkaRequestClient(this);
        }
    }
}

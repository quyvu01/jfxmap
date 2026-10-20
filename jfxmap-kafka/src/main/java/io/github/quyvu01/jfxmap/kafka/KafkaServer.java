package io.github.quyvu01.jfxmap.kafka;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.wire.WireResponder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Answers the requests for the keys this service owns over Kafka (FxMap's KafkaServer): it creates the request topic
 * of each key ({@link KafkaNames}; {@code partitions} partitions, 1 by default) and reads them in the consumer group
 * {@value KafkaNames#SERVER_GROUP_ID}, as FxMap does, so the instances of a service share the partitions. Each
 * request is answered with a {@code Result} on its reply topic, keyed by its correlation id; the offsets are committed
 * once the requests of a poll are answered.
 *
 * <pre>{@code
 * KafkaServer server = KafkaServer.builder("localhost:9092", handlers).build().start();
 * }</pre>
 */
public final class KafkaServer implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(KafkaServer.class.getName());

    private final Map<String, Object> config;
    private final WireResponder responder;
    private final Map<String, DistributedKeyRef> keysByTopic = new LinkedHashMap<>();
    private final int partitions;
    private final short replicationFactor;
    private final int maxPollRecords;
    // Guarded by this
    private Thread loop;
    private volatile KafkaConsumer<String, byte[]> consumer;
    private volatile boolean running;

    private KafkaServer(Builder builder) {
        this.config = Map.copyOf(builder.config);
        this.responder = new WireResponder(builder.handlers, builder.timeout);
        for (var key : responder.keys()) keysByTopic.put(KafkaNames.requestTopic(key), key);
        this.partitions = builder.partitions;
        this.replicationFactor = builder.replicationFactor;
        this.maxPollRecords = builder.maxConcurrentProcessing;
    }

    /**
     * @param bootstrapServers the Kafka brokers ({@code host:port,host:port})
     * @param handlers         the handlers of the keys this service owns
     */
    public static Builder builder(String bootstrapServers, Collection<? extends QueryHandler> handlers) {
        return new Builder(bootstrapServers, handlers);
    }

    /** The keys this server answers for. */
    public Collection<DistributedKeyRef> keys() {
        return responder.keys();
    }

    /** The request topics this server reads, by key. */
    public Map<DistributedKeyRef, String> topics() {
        var topics = new LinkedHashMap<DistributedKeyRef, String>();
        keysByTopic.forEach((topic, key) -> topics.put(key, topic));
        return topics;
    }

    /** Creates the request topics and starts reading them; does nothing when it already runs or has no key. */
    public synchronized KafkaServer start() {
        if (running || keysByTopic.isEmpty()) return this;
        KafkaTopics.create(config, keysByTopic.keySet(), partitions, replicationFactor);
        var consumerConfig = new HashMap<>(config);
        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, KafkaNames.SERVER_GROUP_ID);
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerConfig.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        var requests = new KafkaConsumer<>(consumerConfig, new StringDeserializer(), new ByteArrayDeserializer());
        requests.subscribe(keysByTopic.keySet());
        var replies = new KafkaProducer<>(new HashMap<>(config), new StringSerializer(), new ByteArraySerializer());
        consumer = requests;
        running = true;
        loop = Thread.ofPlatform().daemon().name("jfxmap-kafka-server").start(() -> serve(requests, replies));
        return this;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    private void serve(KafkaConsumer<String, byte[]> requests, KafkaProducer<String, byte[]> replies) {
        try {
            while (running) {
                var records = requests.poll(Duration.ofMillis(200));
                if (records.isEmpty()) continue;
                var answers = new ArrayList<CompletableFuture<?>>();
                for (var record : records) answers.add(answer(record, replies));
                CompletableFuture.allOf(answers.toArray(CompletableFuture[]::new)).join();
                replies.flush();
                // Committed whether each request succeeded or failed: it was answered, as in FxMap
                requests.commitSync();
            }
        } catch (WakeupException e) {
            // close() stops the loop
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.ERROR, "The FxMap Kafka server stopped.", e);
        } finally {
            requests.close();
            replies.close(Duration.ofSeconds(5));
            running = false;
        }
    }

    private CompletableFuture<?> answer(ConsumerRecord<String, byte[]> record, KafkaProducer<String, byte[]> replies) {
        var key = keysByTopic.get(record.topic());
        KafkaMessages.Received received;
        try {
            received = KafkaMessages.readRequest(record.value());
        } catch (IllegalArgumentException e) {
            LOGGER.log(System.Logger.Level.ERROR, "A request on " + record.topic() + " is not FxMap's: dropped.", e);
            return CompletableFuture.completedFuture(null);
        }
        if (received.replyTo() == null || received.replyTo().isBlank()) {
            LOGGER.log(System.Logger.Level.WARNING, "A request on " + record.topic() + " has no ReplyTo: dropped.");
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> null, task -> Thread.ofVirtual().name("jfxmap-kafka").start(task))
                .thenCompose(ignored -> responder.answer(key, received.request(),
                        KafkaMessages.fromKafka(record.headers())))
                .thenAccept(reply -> replies.send(new ProducerRecord<>(received.replyTo(), record.key(), reply),
                        (metadata, failure) -> {
                            if (failure != null)
                                LOGGER.log(System.Logger.Level.ERROR, "The reply for " + key + " was not sent.",
                                        failure);
                        }))
                .exceptionally(failure -> {
                    LOGGER.log(System.Logger.Level.ERROR, "The request for " + key + " was not answered.", failure);
                    return null;
                });
    }

    /** Stops reading; the requests of the current poll are answered first. */
    @Override
    public void close() {
        Thread running;
        synchronized (this) {
            if (!this.running) return;
            this.running = false;
            running = loop;
        }
        var requests = consumer;
        if (requests != null) requests.wakeup();
        if (running != null) {
            try {
                running.join(Duration.ofSeconds(15));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static final class Builder {
        private final Map<String, Object> config = new HashMap<>();
        private final List<QueryHandler> handlers;
        private Duration timeout = WireResponder.DEFAULT_TIMEOUT;
        private int partitions = 1;
        private short replicationFactor = 1;
        private int maxConcurrentProcessing = 128;

        private Builder(String bootstrapServers, Collection<? extends QueryHandler> handlers) {
            if (bootstrapServers == null || bootstrapServers.isBlank())
                throw new IllegalArgumentException("The Kafka bootstrap servers are blank.");
            config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            this.handlers = List.copyOf(Objects.requireNonNull(handlers, "handlers"));
        }

        /** A property of the Kafka clients (SSL, SASL...), given to the consumer, the producer and the admin. */
        public Builder property(String name, Object value) {
            config.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** How long a handler may take before the request fails (default 30 seconds). */
        public Builder timeout(Duration timeout) {
            if (Objects.requireNonNull(timeout, "timeout").isNegative() || timeout.isZero())
                throw new IllegalArgumentException("The timeout must be positive.");
            this.timeout = timeout;
            return this;
        }

        /**
         * The partitions of each request topic this server creates (default 1, FxMap's {@code Partitions}); a topic
         * that exists is not changed. More partitions let more instances share the requests.
         */
        public Builder partitions(int partitions) {
            if (partitions < 1) throw new IllegalArgumentException("The partitions must be at least 1.");
            this.partitions = partitions;
            return this;
        }

        /** The replication factor of the topics this server creates (default 1, as in FxMap). */
        public Builder replicationFactor(short replicationFactor) {
            if (replicationFactor < 1) throw new IllegalArgumentException("The replication factor must be at least 1.");
            this.replicationFactor = replicationFactor;
            return this;
        }

        /** How many requests are answered at once: the records of one poll (default 128). */
        public Builder maxConcurrentProcessing(int maxConcurrentProcessing) {
            if (maxConcurrentProcessing < 1)
                throw new IllegalArgumentException("maxConcurrentProcessing must be at least 1.");
            this.maxConcurrentProcessing = maxConcurrentProcessing;
            return this;
        }

        /** @throws DistributedMapException.DistributedKeyAssignedToMultipleEntities when two handlers share a key */
        public KafkaServer build() {
            return new KafkaServer(this);
        }
    }
}

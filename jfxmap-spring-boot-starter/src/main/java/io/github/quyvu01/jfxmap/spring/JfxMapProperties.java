package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.FxMap;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The {@code jfxmap.*} properties. */
@ConfigurationProperties("jfxmap")
public class JfxMapProperties {
    /** Throw mapping failures instead of logging them and leaving the properties alone. */
    private boolean throwIfException;

    /** How many levels of mapped objects inside mapped objects are followed. */
    private int maxNestingDepth = FxMap.DEFAULT_MAX_NESTING_DEPTH;

    /**
     * The name of this service, in the names of its RabbitMQ queue and its Kafka reply topic (FxMap uses the name of
     * the entry assembly); {@code spring.application.name} when it is not set.
     */
    private String applicationName;

    private final Grpc grpc = new Grpc();
    private final Nats nats = new Nats();
    private final RabbitMq rabbitmq = new RabbitMq();
    private final Kafka kafka = new Kafka();

    public boolean isThrowIfException() {
        return throwIfException;
    }

    public void setThrowIfException(boolean throwIfException) {
        this.throwIfException = throwIfException;
    }

    public int getMaxNestingDepth() {
        return maxNestingDepth;
    }

    public void setMaxNestingDepth(int maxNestingDepth) {
        this.maxNestingDepth = maxNestingDepth;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public Grpc getGrpc() {
        return grpc;
    }

    public Nats getNats() {
        return nats;
    }

    public RabbitMq getRabbitmq() {
        return rabbitmq;
    }

    public Kafka getKafka() {
        return kafka;
    }

    /** {@code jfxmap.grpc.*}: needs jfxmap-grpc on the classpath. */
    public static class Grpc {
        private final Client client = new Client();
        private final Server server = new Server();

        public Client getClient() {
            return client;
        }

        public Server getServer() {
            return server;
        }
    }

    public static class Client {
        /** The hosts of the services that own keys ({@code http://host:port}, {@code https://host:port}). */
        private List<String> hosts = new ArrayList<>();

        /** How long a call may take. */
        private Duration timeout = Duration.ofSeconds(3);

        public List<String> getHosts() {
            return hosts;
        }

        public void setHosts(List<String> hosts) {
            this.hosts = hosts;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class Server {
        /** The port of the gRPC server that answers for this service's keys; no server is started without it. */
        private Integer port;

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }
    }

    /** What every message transport has: how long a request may take, and how many are answered at once. */
    public abstract static class Transport {
        /** How long a request waits for its reply, and how long the owner may take to answer it. */
        private Duration timeout = Duration.ofSeconds(30);

        /** How many requests this service answers at once. */
        private int maxConcurrentProcessing = 128;

        /** Whether this service answers its own keys over the transport (it always sends its requests over it). */
        private boolean serverEnabled = true;

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxConcurrentProcessing() {
            return maxConcurrentProcessing;
        }

        public void setMaxConcurrentProcessing(int maxConcurrentProcessing) {
            this.maxConcurrentProcessing = maxConcurrentProcessing;
        }

        public boolean isServerEnabled() {
            return serverEnabled;
        }

        public void setServerEnabled(boolean serverEnabled) {
            this.serverEnabled = serverEnabled;
        }
    }

    /** {@code jfxmap.nats.*}: needs jfxmap-nats; on when {@code url} is set or a NATS Connection bean exists. */
    public static class Nats extends Transport {
        /** The NATS server ({@code nats://host:4222}). */
        private String url;

        /** The prefix of the subjects (FxMap's {@code TopicPrefix}). */
        private String topicPrefix;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getTopicPrefix() {
            return topicPrefix;
        }

        public void setTopicPrefix(String topicPrefix) {
            this.topicPrefix = topicPrefix;
        }
    }

    /** {@code jfxmap.rabbitmq.*}: needs jfxmap-rabbitmq; on when {@code host} is set. */
    public static class RabbitMq extends Transport {
        private String host;
        private int port = 5672;
        private String virtualHost = "/";
        private String username = "guest";
        private String password = "guest";

        /** Whether the connection uses TLS. */
        private boolean ssl;

        /** How many channels consume the queue of this service (FxMap's {@code ChannelPoolSize}). */
        private int channelPoolSize = 5;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getVirtualHost() {
            return virtualHost;
        }

        public void setVirtualHost(String virtualHost) {
            this.virtualHost = virtualHost;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public boolean isSsl() {
            return ssl;
        }

        public void setSsl(boolean ssl) {
            this.ssl = ssl;
        }

        public int getChannelPoolSize() {
            return channelPoolSize;
        }

        public void setChannelPoolSize(int channelPoolSize) {
            this.channelPoolSize = channelPoolSize;
        }
    }

    /** {@code jfxmap.kafka.*}: needs jfxmap-kafka; on when {@code bootstrap-servers} is set. */
    public static class Kafka extends Transport {
        /** The brokers ({@code host:9092,host:9092}). */
        private String bootstrapServers;

        /** The partitions of the request topics this service creates (FxMap's {@code Partitions}). */
        private int partitions = 1;

        /** The replication factor of the topics this service creates. */
        private short replicationFactor = 1;

        /** More properties of the Kafka clients (SSL, SASL...). */
        private Map<String, String> properties = new LinkedHashMap<>();

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public int getPartitions() {
            return partitions;
        }

        public void setPartitions(int partitions) {
            this.partitions = partitions;
        }

        public short getReplicationFactor() {
            return replicationFactor;
        }

        public void setReplicationFactor(short replicationFactor) {
            this.replicationFactor = replicationFactor;
        }

        public Map<String, String> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, String> properties) {
            this.properties = properties;
        }
    }
}

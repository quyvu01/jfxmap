package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.FxMap;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** The {@code jfxmap.*} properties. */
@ConfigurationProperties("jfxmap")
public class JfxMapProperties {
    /** Throw mapping failures instead of logging them and leaving the properties alone. */
    private boolean throwIfException;

    /** How many levels of mapped objects inside mapped objects are followed. */
    private int maxNestingDepth = FxMap.DEFAULT_MAX_NESTING_DEPTH;

    private final Grpc grpc = new Grpc();

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

    public Grpc getGrpc() {
        return grpc;
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
}

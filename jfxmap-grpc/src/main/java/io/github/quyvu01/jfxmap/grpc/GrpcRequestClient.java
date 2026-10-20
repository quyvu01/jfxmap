package io.github.quyvu01.jfxmap.grpc;

import com.google.protobuf.StringValue;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.grpc.proto.FxMapItemsGrpcResponse;
import io.github.quyvu01.jfxmap.grpc.proto.FxMapTransportServiceGrpc;
import io.github.quyvu01.jfxmap.grpc.proto.GetDistributedKeysQuery;
import io.github.quyvu01.jfxmap.grpc.proto.GetFxMapGrpcQuery;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ExpressionError;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Sends requests over gRPC to the services that own the keys (FxMap's GrpcRequestClient and AddGrpcClients).
 *
 * <p>It is given the hosts of those services. The first time a key is requested, it asks each host it has not reached
 * yet which keys it handles ({@code GetDistributedKeys}), and from then on sends the requests for that key to that
 * host. A host that cannot be reached is asked again on the next unknown key.
 *
 * <pre>{@code
 * GrpcRequestClient client = GrpcRequestClient.builder()
 *         .hosts("http://user-service:9090", "https://product-service:9443")
 *         .build();
 * }</pre>
 */
public final class GrpcRequestClient implements RequestClient, AutoCloseable {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    private static final System.Logger LOGGER = System.getLogger(GrpcRequestClient.class.getName());
    private static final Executor VIRTUAL_THREADS = task -> Thread.ofVirtual().name("jfxmap-grpc").start(task);

    private final List<String> hosts;
    private final Duration timeout;
    private final Function<String, ManagedChannel> channelFactory;
    private final Executor executor;
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();
    // Guarded by this: the keys of each host that answered GetDistributedKeys
    private final Map<String, Set<String>> keysByHost = new LinkedHashMap<>();

    private GrpcRequestClient(Builder builder) {
        this.hosts = List.copyOf(builder.hosts);
        this.timeout = builder.timeout;
        this.channelFactory = builder.channelFactory;
        this.executor = builder.executor;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The hosts this client sends requests to. */
    public List<String> hosts() {
        return hosts;
    }

    @Override
    public CompletionStage<ItemsResponse> request(DistributedKeyRef key, MapRequest request, MapContext context) {
        var wireName = DistributedKeyWireNames.toWire(key);
        return CompletableFuture.supplyAsync(() -> hostOf(key, wireName), executor)
                .thenCompose(host -> getItems(host, wireName, request, context));
    }

    private synchronized String hostOf(DistributedKeyRef key, String wireName) {
        var host = knownHostOf(wireName);
        if (host != null) return host;
        RuntimeException lastFailure = null;
        for (var candidate : hosts) {
            if (keysByHost.containsKey(candidate)) continue;
            try {
                var channel = channel(candidate);
                // A host that was down is tried now, not after the channel's reconnect backoff
                channel.resetConnectBackoff();
                var response = FxMapTransportServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                        .getDistributedKeys(GetDistributedKeysQuery.getDefaultInstance());
                keysByHost.put(candidate, Set.copyOf(response.getDistributedKeyTypesList()));
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, "The FxMap gRPC host " + candidate
                        + " did not tell its distributed keys; it is asked again on the next unknown key.", e);
                lastFailure = e;
            }
        }
        host = knownHostOf(wireName);
        if (host != null) return host;
        var notFound = new DistributedMapException.NoHandlerForDistributedKey(key);
        if (lastFailure != null) notFound.addSuppressed(lastFailure);
        throw notFound;
    }

    private String knownHostOf(String wireName) {
        for (var entry : keysByHost.entrySet())
            if (entry.getValue().contains(wireName)) return entry.getKey();
        return null;
    }

    private CompletableFuture<ItemsResponse> getItems(String host, String wireName, MapRequest request,
                                                      MapContext context) {
        var query = GetFxMapGrpcQuery.newBuilder()
                .addAllSelectorIds(request.selectorIds())
                .setExpression(StringValue.of(ExpressionsJson.write(request.expressions())))
                .setDistributedKeyAssemblyType(StringValue.of(wireName))
                .build();
        var future = new CompletableFuture<ItemsResponse>();
        FxMapTransportServiceGrpc.newStub(channel(host))
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(GrpcHeaders.toMetadata(context.headers())))
                .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .getItems(query, new StreamObserver<>() {
                    @Override
                    public void onNext(FxMapItemsGrpcResponse value) {
                        future.complete(fromGrpc(value));
                    }

                    @Override
                    public void onError(Throwable t) {
                        future.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                        // Unary: onNext already completed the future
                    }
                });
        return future;
    }

    static ItemsResponse fromGrpc(FxMapItemsGrpcResponse response) {
        var items = new ArrayList<DataResponse>(response.getItemsCount());
        for (var item : response.getItemsList()) {
            var values = new ArrayList<ValueResponse>(item.getFxmapValuesCount());
            for (var value : item.getFxmapValuesList())
                values.add(new ValueResponse(value.hasExpression() ? value.getExpression().getValue() : null,
                        value.hasValue() ? value.getValue().getValue() : null));
            items.add(new DataResponse(item.getId(), values));
        }
        var errors = new ArrayList<ExpressionError>(response.getErrorsCount());
        for (var error : response.getErrorsList())
            errors.add(new ExpressionError(error.hasExpression() ? error.getExpression().getValue() : null,
                    error.hasMessage() ? error.getMessage().getValue() : null));
        return new ItemsResponse(items, errors);
    }

    private ManagedChannel channel(String host) {
        return channels.computeIfAbsent(host, channelFactory);
    }

    /** Shuts the channels down. */
    @Override
    public void close() {
        channels.values().forEach(ManagedChannel::shutdown);
        for (var channel : channels.values()) {
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) channel.shutdownNow();
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        channels.clear();
    }

    /**
     * The channel to a host: {@code http://host:port} is plaintext, {@code https://host:port} uses TLS, and a bare
     * {@code host:port} is plaintext.
     */
    static ManagedChannel defaultChannel(String host) {
        String target;
        ChannelCredentials credentials;
        if (host.contains("://")) {
            var uri = URI.create(host);
            var tls = "https".equalsIgnoreCase(uri.getScheme());
            if (!tls && !"http".equalsIgnoreCase(uri.getScheme()))
                throw new IllegalArgumentException("The gRPC host must be http://, https:// or host:port: " + host);
            var port = uri.getPort() != -1 ? uri.getPort() : tls ? 443 : 80;
            target = uri.getHost() + ":" + port;
            credentials = tls ? TlsChannelCredentials.create() : InsecureChannelCredentials.create();
        } else {
            target = host;
            credentials = InsecureChannelCredentials.create();
        }
        return Grpc.newChannelBuilder(target, credentials).build();
    }

    @Override
    public String transport() {
        return "grpc";
    }

    public static final class Builder {
        private final Set<String> hosts = new LinkedHashSet<>();
        private Duration timeout = DEFAULT_TIMEOUT;
        private Function<String, ManagedChannel> channelFactory = GrpcRequestClient::defaultChannel;
        private Executor executor = VIRTUAL_THREADS;

        private Builder() {
        }

        /** The hosts of the services that own keys ({@code http://host:port}, {@code https://host:port}). */
        public Builder hosts(String... hosts) {
            return hosts(List.of(hosts));
        }

        public Builder hosts(Collection<String> hosts) {
            for (var host : hosts) {
                if (host == null || host.isBlank()) throw new IllegalArgumentException("A gRPC host is blank.");
                this.hosts.add(host.trim());
            }
            return this;
        }

        /** How long a call may take (default 3 seconds, as in FxMap). */
        public Builder timeout(Duration timeout) {
            if (Objects.requireNonNull(timeout, "timeout").isNegative() || timeout.isZero())
                throw new IllegalArgumentException("The timeout must be positive.");
            this.timeout = timeout;
            return this;
        }

        /** Opens the channel to a host; by default from its URL. */
        public Builder channelFactory(Function<String, ManagedChannel> channelFactory) {
            this.channelFactory = Objects.requireNonNull(channelFactory, "channelFactory");
            return this;
        }

        /** Runs the host lookups, which may block; by default on virtual threads. */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        public GrpcRequestClient build() {
            if (hosts.isEmpty()) throw new IllegalArgumentException("Give the gRPC client at least one host.");
            return new GrpcRequestClient(this);
        }
    }
}

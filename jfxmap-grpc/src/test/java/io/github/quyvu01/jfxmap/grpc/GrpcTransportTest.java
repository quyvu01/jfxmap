package io.github.quyvu01.jfxmap.grpc;

import com.google.protobuf.StringValue;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.FxMap;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import io.github.quyvu01.jfxmap.grpc.proto.DistributedKeyTypeResponse;
import io.github.quyvu01.jfxmap.grpc.proto.FxMapItemsGrpcResponse;
import io.github.quyvu01.jfxmap.grpc.proto.FxMapTransportServiceGrpc;
import io.github.quyvu01.jfxmap.grpc.proto.GetDistributedKeysQuery;
import io.github.quyvu01.jfxmap.grpc.proto.GetFxMapGrpcQuery;
import io.github.quyvu01.jfxmap.provider.InMemoryQueryHandler;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ExpressionError;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import io.grpc.BindableService;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrpcTransportTest {
    record User(int id, String name, String email) {
    }

    static class UserConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::id);
            defaultProperty(User::name);
            useDistributedKey("UserOf");
            exposedName(User::email, "UserEmail");
        }
    }

    static class OrderDto {
        int userId;
        String userName;
        String userEmail;

        OrderDto(int userId) {
            this.userId = userId;
        }

        int userId() {
            return userId;
        }

        String userName() {
            return userName;
        }

        String userEmail() {
            return userEmail;
        }
    }

    static class OrderDtoProfile extends ProfileOf<OrderDto> {
        @Override
        protected void configure() {
            useDistributedKey("UserOf")
                    .of(OrderDto::userId)
                    .map(OrderDto::userName)
                    .map(OrderDto::userEmail, "UserEmail");
        }
    }

    private final List<Server> servers = new ArrayList<>();
    private final List<GrpcRequestClient> clients = new ArrayList<>();

    @AfterEach
    void stop() {
        clients.forEach(GrpcRequestClient::close);
        servers.forEach(Server::shutdownNow);
    }

    private String serve(BindableService service) throws IOException {
        var name = InProcessServerBuilder.generateName();
        servers.add(InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start());
        return name;
    }

    private GrpcRequestClient client(Duration timeout, String... hosts) {
        var client = GrpcRequestClient.builder()
                .hosts(hosts)
                .timeout(timeout)
                .channelFactory(host -> InProcessChannelBuilder.forName(host).directExecutor().build())
                .build();
        clients.add(client);
        return client;
    }

    private GrpcRequestClient client(String... hosts) {
        return client(GrpcRequestClient.DEFAULT_TIMEOUT, hosts);
    }

    private static FxMapGrpcService userService() {
        return new FxMapGrpcService(List.of(new InMemoryQueryHandler<>(new UserConfig(),
                List.of(new User(1, "Alice", "alice@example.com"), new User(2, "Bob", "bob@example.com")))));
    }

    private static ItemsResponse request(GrpcRequestClient client, String key, MapContext context, List<String> ids,
                                         String... expressions) {
        return client.request(DistributedKeyRef.of(key), new MapRequest(ids, Arrays.asList(expressions)), context)
                .toCompletableFuture().join();
    }

    @Test
    void mapsADtoThroughGrpc() throws IOException {
        var host = serve(userService());
        var mapper = FxMap.builder().addProfiles(new OrderDtoProfile()).requestClient(client(host))
                .throwIfException().build();
        var dtos = List.of(new OrderDto(1), new OrderDto(2), new OrderDto(3));

        mapper.map(dtos);

        assertThat(dtos).extracting(d -> d.userName).containsExactly("Alice", "Bob", null);
        assertThat(dtos).extracting(d -> d.userEmail).containsExactly("alice@example.com", "bob@example.com", null);
    }

    @Test
    void carriesTheValuesTheErrorsAndTheHeaders() throws IOException {
        var seen = new AtomicReference<MapContext>();
        QueryHandler recording = new QueryHandler() {
            @Override
            public DistributedKeyRef key() {
                return DistributedKeyRef.of("UserOf");
            }

            @Override
            public Class<?> entityType() {
                return User.class;
            }

            @Override
            public CompletionStage<ItemsResponse> handle(MapRequest request, MapContext context) {
                seen.set(context);
                return CompletableFuture.completedFuture(new ItemsResponse(
                        List.of(new DataResponse("1", List.of(new ValueResponse(null, "\"Alice\""),
                                new ValueResponse("Missing", null)))),
                        List.of(new ExpressionError("Bad:", "does not parse"), new ExpressionError(null, null))));
            }
        };
        var host = serve(new FxMapGrpcService(List.of(recording)));

        var response = request(client(host), "UserOf", new MapContext(Map.of("x-tenant", "acme"), null),
                List.of("1"), null, "Missing");

        assertThat(response.items()).containsExactly(new DataResponse("1",
                List.of(new ValueResponse(null, "\"Alice\""), new ValueResponse("Missing", null))));
        assertThat(response.errors()).containsExactly(new ExpressionError("Bad:", "does not parse"),
                new ExpressionError(null, null));
        assertThat(seen.get().headers()).containsEntry("x-tenant", "acme");
    }

    @Test
    void sendsTheKeyAndTheExpressionsAsFxMapDoes() throws IOException {
        var received = new AtomicReference<GetFxMapGrpcQuery>();
        var host = serve(new FakeService(List.of("UserOf, " + DistributedKeyWireNames.DYNAMIC_ASSEMBLY)) {
            @Override
            public void getItems(GetFxMapGrpcQuery request, StreamObserver<FxMapItemsGrpcResponse> observer) {
                received.set(request);
                observer.onNext(FxMapItemsGrpcResponse.getDefaultInstance());
                observer.onCompleted();
            }
        });

        request(client(host), "UserOf", MapContext.empty(), List.of("7", "8"), null, "UserEmail");

        assertThat(received.get().getSelectorIdsList()).containsExactly("7", "8");
        assertThat(received.get().getExpression().getValue()).isEqualTo("[null,\"UserEmail\"]");
        assertThat(received.get().getDistributedKeyAssemblyType().getValue())
                .isEqualTo("UserOf, FxMap.DynamicDistributedKeys, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null");
    }

    @Test
    void asksEachHostForItsKeysOnceAndRoutesByKey() throws IOException {
        var userService = new CountingService(userService());
        var otherService = new CountingService(new FxMapGrpcService(List.of(new InMemoryQueryHandler<>(
                new EntityConfigureOf<>(User.class) {
                    @Override
                    protected void configure() {
                        id(User::id);
                        defaultProperty(User::email);
                        useDistributedKey("ContactOf");
                    }
                }, List.of(new User(1, "Alice", "alice@example.com"))))));
        var client = client(serve(otherService), serve(userService));

        assertThat(request(client, "UserOf", MapContext.empty(), List.of("1")).items()).hasSize(1);
        assertThat(request(client, "UserOf", MapContext.empty(), List.of("2")).items()).hasSize(1);
        var contact = request(client, "ContactOf", MapContext.empty(), List.of("1"), (String) null);

        assertThat(contact.items().getFirst().values().getFirst().value()).isEqualTo("\"alice@example.com\"");
        assertThat(userService.keyQueries).hasValue(1);
        assertThat(otherService.keyQueries).hasValue(1);
        assertThat(client.hosts()).hasSize(2);
    }

    @Test
    void asksAHostThatWasDownAgainOnTheNextUnknownKey() throws IOException {
        var name = InProcessServerBuilder.generateName();
        var client = client(name);

        assertThatThrownBy(() -> request(client, "UserOf", MapContext.empty(), List.of("1")))
                .isInstanceOf(CompletionException.class)
                .cause().isInstanceOf(DistributedMapException.NoHandlerForDistributedKey.class)
                .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));

        servers.add(InProcessServerBuilder.forName(name).directExecutor().addService(userService()).build().start());

        assertThat(request(client, "UserOf", MapContext.empty(), List.of("1")).items()).hasSize(1);
    }

    @Test
    void failsAKeyNoHostHandles() throws IOException {
        var client = client(serve(userService()));

        assertThatThrownBy(() -> request(client, "ProductOf", MapContext.empty(), List.of("1")))
                .cause().isInstanceOf(DistributedMapException.NoHandlerForDistributedKey.class)
                .hasMessageContaining("ProductOf");
    }

    @Test
    void failsWhenTheServerFailsOrTimesOut() throws IOException {
        var host = serve(new FakeService(List.of(DistributedKeyWireNames.toWire(DistributedKeyRef.of("UserOf")))) {
            @Override
            public void getItems(GetFxMapGrpcQuery request, StreamObserver<FxMapItemsGrpcResponse> observer) {
                if (request.getSelectorIdsList().contains("slow")) return;
                observer.onError(Status.UNAVAILABLE.asRuntimeException());
            }
        });
        var client = client(Duration.ofMillis(200), host);

        assertThatThrownBy(() -> request(client, "UserOf", MapContext.empty(), List.of("1")))
                .cause().isInstanceOf(StatusRuntimeException.class).hasMessageContaining("UNAVAILABLE");
        assertThatThrownBy(() -> request(client, "UserOf", MapContext.empty(), List.of("slow")))
                .cause().isInstanceOf(StatusRuntimeException.class).hasMessageContaining("DEADLINE_EXCEEDED");
    }

    @Test
    void serverListsItsKeysAsFxMapNamesThem() throws IOException {
        var channel = channel(serve(userService()));

        var keys = FxMapTransportServiceGrpc.newBlockingStub(channel)
                .getDistributedKeys(GetDistributedKeysQuery.getDefaultInstance());

        assertThat(keys.getDistributedKeyTypesList())
                .containsExactly("UserOf, FxMap.DynamicDistributedKeys, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null");
        assertThat(userService().keys()).containsExactly(DistributedKeyRef.of("UserOf"));
    }

    @Test
    void serverAnswersABareKeyNameAndNoExpressions() throws IOException {
        var stub = FxMapTransportServiceGrpc.newBlockingStub(channel(serve(userService())));

        var response = stub.getItems(GetFxMapGrpcQuery.newBuilder().addSelectorIds("1")
                .setDistributedKeyAssemblyType(StringValue.of("UserOf")).build());

        assertThat(response.getItemsList()).hasSize(1);
        assertThat(response.getItems(0).getFxmapValuesCount()).isZero();
    }

    @Test
    void serverRejectsAnUnknownKeyAndMalformedExpressions() throws IOException {
        var stub = FxMapTransportServiceGrpc.newBlockingStub(channel(serve(userService())));

        assertThatThrownBy(() -> stub.getItems(GetFxMapGrpcQuery.newBuilder()
                .setDistributedKeyAssemblyType(StringValue.of("Contracts.UserOf, Contracts")).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("NOT_FOUND").hasMessageContaining("Contracts.UserOf");
        assertThatThrownBy(() -> stub.getItems(GetFxMapGrpcQuery.getDefaultInstance()))
                .hasMessageContaining("NOT_FOUND");
        assertThatThrownBy(() -> stub.getItems(GetFxMapGrpcQuery.newBuilder()
                .setDistributedKeyAssemblyType(StringValue.of("UserOf"))
                .setExpression(StringValue.of("Name")).build()))
                .hasMessageContaining("INVALID_ARGUMENT");
    }

    @Test
    void serverReportsAHandlerFailureAsInternal() throws IOException {
        var stub = FxMapTransportServiceGrpc.newBlockingStub(channel(serve(new FxMapGrpcService(List.of(
                failing("FailedOf", CompletableFuture.failedFuture(new IllegalStateException("database down"))),
                failing("ThrowingOf", null))))));

        for (var key : List.of("FailedOf", "ThrowingOf"))
            assertThatThrownBy(() -> stub.getItems(GetFxMapGrpcQuery.newBuilder()
                    .setDistributedKeyAssemblyType(StringValue.of(key)).build()))
                    .hasMessageContaining("INTERNAL").hasMessageContaining("database down");
    }

    @Test
    void serverRejectsTwoHandlersForOneKey() {
        var handler = failing("UserOf", null);

        assertThatThrownBy(() -> new FxMapGrpcService(List.of(handler, handler)))
                .isInstanceOf(DistributedMapException.DistributedKeyAssignedToMultipleEntities.class);
    }

    @Test
    void keepsTheProtoContractOfFxMap() {
        assertThat(FxMapTransportServiceGrpc.SERVICE_NAME).isEqualTo("FxMapTransport.FxMapTransportService");
        assertThat(GetFxMapGrpcQuery.getDescriptor().findFieldByName("distributed_key_assembly_type").getNumber())
                .isEqualTo(3);
    }

    @Test
    void worksOverARealNettyServer() throws IOException {
        var server = Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create()).addService(userService())
                .build().start();
        servers.add(server);
        var client = GrpcRequestClient.builder().hosts("http://localhost:" + server.getPort()).build();
        clients.add(client);

        assertThat(request(client, "UserOf", MapContext.empty(), List.of("2"), (String) null).items().getFirst()
                .values().getFirst().value()).isEqualTo("\"Bob\"");
    }

    @Test
    void opensChannelsFromHostUrls() {
        for (var host : List.of("http://localhost:9090", "https://localhost:9443", "http://localhost",
                "https://localhost", "localhost:9090")) {
            var channel = GrpcRequestClient.defaultChannel(host);
            assertThat(channel.authority()).startsWith("localhost");
            channel.shutdownNow();
        }
        assertThatThrownBy(() -> GrpcRequestClient.defaultChannel("ftp://localhost:21"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builderRejectsAMissingHostOrTimeout() {
        assertThatThrownBy(() -> GrpcRequestClient.builder().build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrpcRequestClient.builder().hosts(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrpcRequestClient.builder().timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrpcRequestClient.builder().timeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrpcRequestClient.builder().channelFactory(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> GrpcRequestClient.builder().executor(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void runsHostLookupsOnTheGivenExecutor() throws IOException {
        var runs = new AtomicInteger();
        var host = serve(userService());
        var client = GrpcRequestClient.builder().hosts(host)
                .channelFactory(h -> InProcessChannelBuilder.forName(h).directExecutor().build())
                .executor(task -> {
                    runs.incrementAndGet();
                    task.run();
                })
                .build();
        clients.add(client);

        request(client, "UserOf", MapContext.empty(), List.of("1"));

        assertThat(runs).hasValue(1);
    }

    private ManagedChannel channel(String name) {
        var channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        var client = GrpcRequestClient.builder().hosts(name).channelFactory(h -> channel).build();
        clients.add(client);
        return channel;
    }

    private static QueryHandler failing(String key, CompletionStage<ItemsResponse> result) {
        return new QueryHandler() {
            @Override
            public DistributedKeyRef key() {
                return DistributedKeyRef.of(key);
            }

            @Override
            public Class<?> entityType() {
                return User.class;
            }

            @Override
            public CompletionStage<ItemsResponse> handle(MapRequest request, MapContext context) {
                if (result == null) throw new IllegalStateException("database down");
                return result;
            }
        };
    }

    /** A server that lists the given keys and answers nothing else. */
    static class FakeService implements FxMapTransportServiceGrpc.AsyncService, BindableService {
        private final List<String> keys;

        FakeService(List<String> keys) {
            this.keys = keys;
        }

        @Override
        public void getDistributedKeys(GetDistributedKeysQuery request,
                                       StreamObserver<DistributedKeyTypeResponse> observer) {
            observer.onNext(DistributedKeyTypeResponse.newBuilder().addAllDistributedKeyTypes(keys).build());
            observer.onCompleted();
        }

        @Override
        public io.grpc.ServerServiceDefinition bindService() {
            return FxMapTransportServiceGrpc.bindService(this);
        }
    }

    /** Counts the GetDistributedKeys calls of a service. */
    static final class CountingService implements FxMapTransportServiceGrpc.AsyncService, BindableService {
        final AtomicInteger keyQueries = new AtomicInteger();
        private final FxMapGrpcService delegate;

        CountingService(FxMapGrpcService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void getDistributedKeys(GetDistributedKeysQuery request,
                                       StreamObserver<DistributedKeyTypeResponse> observer) {
            keyQueries.incrementAndGet();
            delegate.getDistributedKeys(request, observer);
        }

        @Override
        public void getItems(GetFxMapGrpcQuery request, StreamObserver<FxMapItemsGrpcResponse> observer) {
            delegate.getItems(request, observer);
        }

        @Override
        public io.grpc.ServerServiceDefinition bindService() {
            return FxMapTransportServiceGrpc.bindService(this);
        }
    }
}

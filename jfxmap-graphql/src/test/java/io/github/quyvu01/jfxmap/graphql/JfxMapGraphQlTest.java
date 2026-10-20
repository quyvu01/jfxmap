package io.github.quyvu01.jfxmap.graphql;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.DistributedMapper;
import io.github.quyvu01.jfxmap.FxMap;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import io.github.quyvu01.jfxmap.provider.InMemoryQueryHandler;
import io.github.quyvu01.jfxmap.provider.LocalRequestClient;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import org.dataloader.DataLoaderRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JfxMapGraphQlTest {
    record User(int id, String name, String email, int provinceId) {
    }

    record Province(int id, String name) {
    }

    static class UserConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::id);
            defaultProperty(User::name);
            useDistributedKey("UserOf");
            exposedName(User::email, "UserEmail");
            exposedName(User::provinceId, "ProvinceId");
        }
    }

    static class ProvinceConfig extends EntityConfigureOf<Province> {
        @Override
        protected void configure() {
            id(Province::id);
            defaultProperty(Province::name);
            useDistributedKey("ProvinceOf");
        }
    }

    public static class OrderDto {
        private final int id;
        private final int userId;
        private String userName;
        private String userEmail;
        private Integer provinceId;
        private String provinceName;

        OrderDto(int id, int userId) {
            this.id = id;
            this.userId = userId;
        }

        public int getId() {
            return id;
        }

        public int getUserId() {
            return userId;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public String getUserEmail() {
            return userEmail;
        }

        public void setUserEmail(String userEmail) {
            this.userEmail = userEmail;
        }

        public Integer getProvinceId() {
            return provinceId;
        }

        public void setProvinceId(Integer provinceId) {
            this.provinceId = provinceId;
        }

        public String getProvinceName() {
            return provinceName;
        }

        public void setProvinceName(String provinceName) {
            this.provinceName = provinceName;
        }
    }

    static class OrderProfile extends ProfileOf<OrderDto> {
        @Override
        protected void configure() {
            useDistributedKey("UserOf")
                    .of(OrderDto::getUserId)
                    .map(OrderDto::getUserName)
                    .map(OrderDto::getUserEmail, "UserEmail")
                    .map(OrderDto::getProvinceId, "ProvinceId");
            useDistributedKey("ProvinceOf")
                    .of(OrderDto::getProvinceId)
                    .map(OrderDto::getProvinceName);
        }
    }

    public record Customer(String name, List<OrderDto> orders) {
    }

    private static final String SDL = """
            type Query { orders: [Order] customers: [Customer] }
            type Customer { name: String orders: [Order] }
            type Order { id: Int userId: Int userName: String userEmail: String provinceName: String }
            """;

    /** Answers in process and records every request. */
    static final class Recording implements RequestClient {
        final List<MapRequest> sent = Collections.synchronizedList(new ArrayList<>());
        final List<DistributedKeyRef> keys = Collections.synchronizedList(new ArrayList<>());
        final List<MapContext> contexts = Collections.synchronizedList(new ArrayList<>());
        final LocalRequestClient local = new LocalRequestClient(
                new InMemoryQueryHandler<>(new UserConfig(), List.of(new User(1, "Alice", "alice@example.com", 7),
                        new User(2, "Bob", "bob@example.com", 8))),
                new InMemoryQueryHandler<>(new ProvinceConfig(), List.of(new Province(7, "Hanoi"),
                        new Province(8, "Osaka"))));

        @Override
        public CompletionStage<ItemsResponse> request(DistributedKeyRef key, MapRequest request, MapContext context) {
            keys.add(key);
            sent.add(request);
            contexts.add(context);
            return local.request(key, request, context);
        }
    }

    private final Recording client = new Recording();
    private final DistributedMapper mapper = FxMap.builder().addProfiles(new OrderProfile()).requestClient(client)
            .throwIfException().build();

    private static List<OrderDto> orders() {
        return List.of(new OrderDto(1, 1), new OrderDto(2, 2), new OrderDto(3, 1), new OrderDto(4, 3));
    }

    private GraphQL graphQl(JfxMapGraphQl jfxMap) {
        var builder = RuntimeWiring.newRuntimeWiring()
                .type("Query", t -> t.dataFetcher("orders", e -> orders())
                        .dataFetcher("customers", e -> List.of(
                                new Customer("a", List.of(new OrderDto(1, 1), new OrderDto(2, 2))),
                                new Customer("b", List.of(new OrderDto(3, 2))))));
        jfxMap.configure(builder);
        var schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(SDL), builder.build());
        return GraphQL.newGraphQL(schema).build();
    }

    private static ExecutionResult execute(GraphQL graphQl, String query) {
        return execute(graphQl, query, Map.of());
    }

    private static ExecutionResult execute(GraphQL graphQl, String query, Map<String, String> headers) {
        return graphQl.execute(ExecutionInput.newExecutionInput(query)
                .dataLoaderRegistry(new DataLoaderRegistry())
                .graphQLContext(Map.of(JfxMapGraphQl.HEADERS, headers))
                .build());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(ExecutionResult result, String field) {
        assertThat(result.getErrors()).isEmpty();
        return (List<Map<String, Object>>) result.<Map<String, Object>>getData().get(field);
    }

    @Test
    void resolvesTheSelectedFieldsInOneRequestPerKey() {
        var orders = list(execute(graphQl(JfxMapGraphQl.builder(mapper).typeName(OrderDto.class, "Order").build()),
                "{ orders { id userName } }"), "orders");

        assertThat(orders).extracting(o -> o.get("userName")).containsExactly("Alice", "Bob", "Alice", null);
        assertThat(client.sent).hasSize(1);
        assertThat(client.sent.getFirst().selectorIds()).containsExactlyInAnyOrder("1", "2", "3");
        assertThat(client.sent.getFirst().expressions()).containsExactly((String) null);
    }

    @Test
    void aFieldWhoseKeyAnotherRuleFillsGetsThatRuleFirst() {
        var orders = list(execute(graphQl(JfxMapGraphQl.builder(mapper).typeName(OrderDto.class, "Order").build()),
                "{ orders { provinceName } }"), "orders");

        assertThat(orders).extracting(o -> o.get("provinceName")).containsExactly("Hanoi", "Osaka", "Hanoi", null);
        assertThat(client.keys).containsExactly(DistributedKeyRef.of("UserOf"), DistributedKeyRef.of("ProvinceOf"));
        assertThat(client.sent.getFirst().expressions()).containsExactly("ProvinceId");
    }

    @Test
    void batchesTheObjectsOfEveryParentOfALevel() {
        var result = execute(graphQl(JfxMapGraphQl.builder(mapper).typeName(OrderDto.class, "Order").build()),
                "{ customers { name orders { userName userEmail } } }");

        var customers = list(result, "customers");
        assertThat(customers).hasSize(2);
        assertThat(client.sent).hasSize(1);
        assertThat(client.sent.getFirst().expressions()).containsExactlyInAnyOrder(null, "UserEmail");
        assertThat(result.toSpecification().toString()).contains("userName=Alice", "userEmail=bob@example.com");
    }

    @Test
    void sendsTheHeadersOfTheGraphQlContext() {
        execute(graphQl(JfxMapGraphQl.builder(mapper).typeName(OrderDto.class, "Order").build()),
                "{ orders { userName } }", Map.of("authorization", "Bearer x"));

        assertThat(client.contexts.getFirst().headers()).containsEntry("authorization", "Bearer x");
    }

    @Test
    void withoutADataLoaderRegistryEachFieldIsMappedOnItsOwn() {
        var result = graphQl(JfxMapGraphQl.builder(mapper).typeName(OrderDto.class, "Order").build())
                .execute("{ orders { userName } }");

        assertThat(list(result, "orders")).extracting(o -> o.get("userName"))
                .containsExactly("Alice", "Bob", "Alice", null);
        assertThat(client.sent).hasSize(4);
    }

    @Test
    void instrumentsASchemaBuiltWithoutTheWiring() {
        var jfxMap = JfxMapGraphQl.builder(mapper).typeName(OrderDto.class, "Order").build();
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(
                        "type Query { orders: [Order] } type Order { id: Int userEmail: String }"),
                RuntimeWiring.newRuntimeWiring().type("Query", t -> t.dataFetcher("orders", e -> orders())).build());

        var result = execute(GraphQL.newGraphQL(jfxMap.instrument(schema)).build(), "{ orders { id userEmail } }");

        assertThat(list(result, "orders")).extracting(o -> o.get("userEmail"))
                .containsExactly("alice@example.com", "bob@example.com", "alice@example.com", null);
    }

    @Test
    void aSchemaWithoutTheTypeIsLeftAsItIs() {
        var jfxMap = JfxMapGraphQl.builder(mapper).build();
        var schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(
                "type Query { orderDto: OrderDto } type OrderDto { id: Int }"), RuntimeWiring.newRuntimeWiring()
                .type("Query", t -> t.dataFetcher("orderDto", e -> new OrderDto(1, 1))).build());

        var result = execute(GraphQL.newGraphQL(jfxMap.instrument(schema)).build(), "{ orderDto { id } }");

        assertThat(result.getErrors()).isEmpty();
        assertThat(client.sent).isEmpty();
    }

    @Test
    void aFailedMappingIsAnErrorOfTheField() {
        var failing = FxMap.builder().addProfiles(new OrderProfile()).requestClient((key, request, context) ->
                CompletableFuture.failedFuture(new IllegalStateException("owner down"))).throwIfException().build();

        var result = execute(graphQl(JfxMapGraphQl.builder(failing).typeName(OrderDto.class, "Order").build()),
                "{ orders { id userName } }");

        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().getFirst().getMessage()).contains("owner down");
    }

    @Test
    void namesTheTypesAfterTheClassesUnlessToldOtherwise() {
        assertThat(JfxMapGraphQl.builder(mapper).build().fields())
                .containsOnlyKeys("OrderDto")
                .containsEntry("OrderDto", java.util.Set.of("userName", "userEmail", "provinceId", "provinceName"));
        assertThat(JfxMapGraphQl.builder(mapper).typeName(OrderDto.class, "Order").build().fields())
                .containsOnlyKeys("Order");
    }

    @Test
    void theBuilderRejectsMissingValues() {
        assertThatThrownBy(() -> JfxMapGraphQl.builder(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> JfxMapGraphQl.builder(mapper).typeName(null, "Order"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> JfxMapGraphQl.builder(mapper).typeName(OrderDto.class, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JfxMapGraphQl.builder(mapper).build().dataFetcher(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aFieldKeyComparesTheObjectByIdentity() {
        var order = new OrderDto(1, 1);
        var key = new JfxMapGraphQl.FieldKey(order, "userName");

        assertThat(key).isEqualTo(new JfxMapGraphQl.FieldKey(order, "userName"))
                .hasSameHashCodeAs(new JfxMapGraphQl.FieldKey(order, "userName"))
                .isNotEqualTo(new JfxMapGraphQl.FieldKey(new OrderDto(1, 1), "userName"))
                .isNotEqualTo(new JfxMapGraphQl.FieldKey(order, "userEmail"))
                .isNotEqualTo("userName");
    }

    @Test
    void aFieldKeyNeedsAnObjectAndAProperty() {
        assertThatThrownBy(() -> new JfxMapGraphQl.FieldKey(null, "userName"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JfxMapGraphQl.FieldKey(new OrderDto(1, 1), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void loadsTheValuesOfFieldsAndLeavesOutTheNullOnes() {
        var alice = new OrderDto(1, 1);
        var nobody = new OrderDto(2, 9);
        var keys = List.of(new JfxMapGraphQl.FieldKey(alice, "userName"),
                new JfxMapGraphQl.FieldKey(alice, "userEmail"), new JfxMapGraphQl.FieldKey(nobody, "userName"));

        var values = JfxMapGraphQl.builder(mapper).build().load(keys, null).join();

        assertThat(values).containsOnly(Map.entry(keys.get(0), "Alice"), Map.entry(keys.get(1), "alice@example.com"));
        assertThat(client.sent).hasSize(1);
        assertThat(client.contexts.getFirst().headers()).isEmpty();
    }

    @Test
    void usesTheDataLoaderTheApplicationRegistered() {
        var jfxMap = JfxMapGraphQl.builder(mapper).typeName(OrderDto.class, "Order").build();
        var registry = new DataLoaderRegistry();
        var batches = new java.util.concurrent.atomic.AtomicInteger();
        registry.register(JfxMapGraphQl.DATA_LOADER, org.dataloader.DataLoaderFactory.newMappedDataLoader(
                (java.util.Set<JfxMapGraphQl.FieldKey> keys) -> {
                    batches.incrementAndGet();
                    return jfxMap.load(keys, Map.of("from", "application"));
                }));

        var result = graphQl(jfxMap).execute(ExecutionInput.newExecutionInput("{ orders { userName } }")
                .dataLoaderRegistry(registry).build());

        assertThat(list(result, "orders")).extracting(o -> o.get("userName"))
                .containsExactly("Alice", "Bob", "Alice", null);
        assertThat(batches).hasValue(1);
        assertThat(client.contexts.getFirst().headers()).containsEntry("from", "application");
    }
}

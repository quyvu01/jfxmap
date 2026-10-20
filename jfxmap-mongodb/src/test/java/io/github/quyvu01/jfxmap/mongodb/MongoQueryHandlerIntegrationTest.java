package io.github.quyvu01.jfxmap.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.github.quyvu01.jfxmap.FxMap;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import io.github.quyvu01.jfxmap.provider.LocalRequestClient;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import org.bson.UuidRepresentation;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.bson.Document;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mongodb.MongoDBContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The handler against a real MongoDB server. */
class MongoQueryHandlerIntegrationTest {
    // A MongoDB container that is up and answers a ping, with a client on it
    record Mongo(MongoDBContainer container, MongoClient client) {
        static Mongo start() {
            // mongod logs this once it accepts connections, which a bound port alone does not promise. MongoDB 8.x
            // refuses to start on Linux 6.19 and newer (SERVER-121912), which GitHub's runners now run: 7.0 here
            var container = new MongoDBContainer("mongo:7.0")
                    .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 1));
            container.start();
            // getConnectionString, not getReplicaSetUrl: that one asks Docker whether the container still runs
            // and throws "MongoDBContainer should be started first" when that Docker call fails or says no
            return new Mongo(container, MongoClients.create(MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(container.getConnectionString()))
                    .applyToClusterSettings(b -> b.serverSelectionTimeout(30, TimeUnit.SECONDS))
                    .uuidRepresentation(UuidRepresentation.STANDARD)
                    .codecRegistry(CodecRegistries.fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())))
                    .build()));
        }

        void ping() {
            try {
                client.getDatabase("admin").runCommand(new Document("ping", 1));
            } catch (RuntimeException e) {
                throw new IllegalStateException("MongoDB did not answer a ping (container running: "
                        + container.isRunning() + "), its log:\n" + container.getLogs(), e);
            }
        }

        void stop() {
            client.close();
            container.stop();
        }
    }

    // A POJO with an ObjectId stored as _id, an embedded document and a list of embedded documents
    public static class Country {
        private String name;

        public Country() {
        }

        public Country(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class Order {
        private BigDecimal total;
        private String status;

        public Order() {
        }

        public Order(BigDecimal total, String status) {
            this.total = total;
            this.status = status;
        }

        public BigDecimal getTotal() {
            return total;
        }

        public void setTotal(BigDecimal total) {
            this.total = total;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    public static class User {
        private ObjectId id;
        private String name;
        private String email;
        private Country country;
        private List<Order> orders = new ArrayList<>();

        public User() {
        }

        User(ObjectId id, String name, String email, Country country, List<Order> orders) {
            this.id = id;
            this.name = name;
            this.email = email;
            this.country = country;
            this.orders = orders;
        }

        public ObjectId getId() {
            return id;
        }

        public void setId(ObjectId id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public Country getCountry() {
            return country;
        }

        public void setCountry(Country country) {
            this.country = country;
        }

        public List<Order> getOrders() {
            return orders;
        }

        public void setOrders(List<Order> orders) {
            this.orders = orders;
        }
    }

    static class UserConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::getId);
            defaultProperty(User::getName);
            useDistributedKey("UserOf");
            exposedName(User::getEmail, "UserEmail");
        }
    }

    // A record whose id is not the stored _id
    public record Product(ObjectId id, @BsonProperty("sku_code") String sku, String title) {
    }

    static class ProductConfig extends EntityConfigureOf<Product> {
        @Override
        protected void configure() {
            id(Product::sku);
            defaultProperty(Product::title);
            useDistributedKey("ProductOf");
        }
    }

    public record Ticket(@BsonId UUID id, String title) {
    }

    static class TicketConfig extends EntityConfigureOf<Ticket> {
        @Override
        protected void configure() {
            id(Ticket::id);
            defaultProperty(Ticket::title);
            useDistributedKey("TicketOf");
        }
    }

    public record Counter(int id, String label) {
    }

    static class CounterConfig extends EntityConfigureOf<Counter> {
        @Override
        protected void configure() {
            id(Counter::id);
            defaultProperty(Counter::label);
            useDistributedKey("CounterOf");
        }
    }

    private static final ObjectId ANN = new ObjectId();
    private static final ObjectId BOB = new ObjectId();

    private static Mongo mongo;
    private static MongoDatabase database;

    @BeforeAll
    static void connect() {
        mongo = StartAttempts.start(3, Mongo::start, Mongo::ping, Mongo::stop);
        database = mongo.client().getDatabase("jfxmap");
    }

    @AfterAll
    static void disconnect() {
        mongo.stop();
    }

    @BeforeEach
    void seed() {
        database.drop();
        database.getCollection("users", User.class).insertMany(List.of(
                new User(ANN, "Ann", "ann@x.io", new Country("Vietnam"),
                        List.of(new Order(new BigDecimal("10.5"), "Done"), new Order(new BigDecimal("4"), "Open"),
                                new Order(new BigDecimal("2"), "Done"))),
                new User(BOB, "Bob", "bob@x.io", null, List.of())));
    }

    @Test
    void answersTheDefaultPropertyAndExpressionsOnTheStoredDocuments() throws Exception {
        var handler = new MongoQueryHandler<>(new UserConfig(), database.getCollection("users"));

        var response = handler.handle(new MapRequest(List.of(ANN.toHexString(), BOB.toHexString()),
                        Arrays.asList(null, "UserEmail", "Country?.Name", "Orders:count",
                                "Orders:where(Status = 'Done'):sum(Total)", "Orders:max(Total)")),
                MapContext.empty()).toCompletableFuture().get();

        assertThat(response.errors()).isEmpty();
        assertThat(response.items()).containsExactlyInAnyOrder(
                new DataResponse(ANN.toHexString(), List.of(new ValueResponse(null, "\"Ann\""),
                        new ValueResponse("UserEmail", "\"ann@x.io\""),
                        new ValueResponse("Country?.Name", "\"Vietnam\""),
                        new ValueResponse("Orders:count", "3"),
                        new ValueResponse("Orders:where(Status = 'Done'):sum(Total)", "12.5"),
                        new ValueResponse("Orders:max(Total)", "10.5"))),
                new DataResponse(BOB.toHexString(), List.of(new ValueResponse(null, "\"Bob\""),
                        new ValueResponse("UserEmail", "\"bob@x.io\""),
                        new ValueResponse("Country?.Name", null),
                        new ValueResponse("Orders:count", "0"),
                        new ValueResponse("Orders:where(Status = 'Done'):sum(Total)", "0"),
                        new ValueResponse("Orders:max(Total)", null))));
    }

    @Test
    void unknownAndInvalidIdsAreNotFound() throws Exception {
        var handler = new MongoQueryHandler<>(new UserConfig(), database.getCollection("users"));

        var response = handler.handle(new MapRequest(List.of(new ObjectId().toHexString(), "not-an-object-id"),
                Arrays.asList((String) null)), MapContext.empty()).toCompletableFuture().get();

        assertThat(response.items()).isEmpty();
    }

    @Test
    void anExpressionThatFailsIsReportedAndTheOthersAreAnswered() throws Exception {
        var handler = new MongoQueryHandler<>(new UserConfig(), database.getCollection("users"));

        var response = handler.handle(new MapRequest(List.of(ANN.toHexString()), List.of("Name", "Nope:(")),
                MapContext.empty()).toCompletableFuture().get();

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().values()).contains(new ValueResponse("Name", "\"Ann\""));
        assertThat(response.errors()).extracting(e -> e.expression()).containsExactly("Nope:(");
    }

    @Test
    void readsRecordsByAnIdThatIsStoredUnderAnotherName() throws Exception {
        database.getCollection("products", Product.class).insertMany(List.of(
                new Product(new ObjectId(), "SKU-1", "Pen"), new Product(new ObjectId(), "SKU-2", "Ink")));
        var handler = new MongoQueryHandler<>(new ProductConfig(), database.getCollection("products"));

        var response = handler.handle(new MapRequest(List.of("SKU-2"), Arrays.asList((String) null)),
                MapContext.empty()).toCompletableFuture().get();

        assertThat(handler.idField()).isEqualTo("sku_code");
        assertThat(response.items()).containsExactly(
                new DataResponse("SKU-2", List.of(new ValueResponse(null, "\"Ink\""))));
    }

    @Test
    void convertsUuidIds() throws Exception {
        var id = UUID.randomUUID();
        database.getCollection("tickets", Ticket.class).insertOne(new Ticket(id, "Broken"));
        var handler = new MongoQueryHandler<>(new TicketConfig(), database.getCollection("tickets"));

        var response = handler.handle(new MapRequest(List.of(id.toString().toUpperCase()),
                Arrays.asList((String) null)), MapContext.empty()).toCompletableFuture().get();

        assertThat(handler.idField()).isEqualTo("_id");
        assertThat(response.items()).extracting(DataResponse::values)
                .containsExactly(List.of(new ValueResponse(null, "\"Broken\"")));
    }

    @Test
    void readsMoreIdsThanOneBatch() throws Exception {
        var counters = IntStream.range(0, MongoQueryHandler.DEFAULT_BATCH_SIZE + 500)
                .mapToObj(i -> new Counter(i, "c" + i)).toList();
        database.getCollection("counters", Counter.class).insertMany(counters);
        var handler = new MongoQueryHandler<>(new CounterConfig(), database.getCollection("counters"));
        var ids = counters.stream().map(c -> Integer.toString(c.id())).toList();

        var response = handler.handle(new MapRequest(ids, Arrays.asList((String) null)), MapContext.empty())
                .toCompletableFuture().get();

        assertThat(response.items()).hasSize(counters.size());
    }

    // A mapper on top of the handler: what a service that owns the users and maps its own DTOs sees
    static class OrderDto {
        String userId;
        String userName;
        String userEmail;

        OrderDto(String userId) {
            this.userId = userId;
        }

        String userId() {
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

    @Test
    void mapsDtosThroughTheMapper() {
        var mapper = FxMap.builder()
                .addProfiles(new OrderDtoProfile())
                .requestClient(new LocalRequestClient(
                        new MongoQueryHandler<>(new UserConfig(), database.getCollection("users"))))
                .throwIfException()
                .build();
        var dto = new OrderDto(BOB.toHexString());

        mapper.map(dto);

        assertThat(dto.userName).isEqualTo("Bob");
        assertThat(dto.userEmail).isEqualTo("bob@x.io");
    }
}

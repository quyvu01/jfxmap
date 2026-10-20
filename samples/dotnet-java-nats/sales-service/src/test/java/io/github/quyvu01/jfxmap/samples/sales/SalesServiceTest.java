package io.github.quyvu01.jfxmap.samples.sales;

import com.jayway.jsonpath.JsonPath;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.nats.NatsRequestClient;
import io.github.quyvu01.jfxmap.nats.NatsServer;
import io.github.quyvu01.jfxmap.provider.InMemoryQueryHandler;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import io.nats.client.Connection;
import io.nats.client.Nats;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The sales service on a real PostgreSQL (its Flyway migrations and seed) and a real NATS, against a stand-in
 * catalog-service: any FxMap owner of "ProductOf" on NATS.
 */
class SalesServiceTest {
    static final UUID ALICE = UUID.fromString("0b6e1b9a-55b1-4c5e-9f57-0a3c3a0d7e11");
    static final UUID CAROL = UUID.fromString("c3a1e5b2-0d4f-4e6a-9b8c-7d2e1f0a3b4c");

    record Category(String name) {
    }

    record Product(int id, String name, BigDecimal price, Category category) {
    }

    static class ProductConfig extends EntityConfigureOf<Product> {
        @Override
        protected void configure() {
            id(Product::id);
            defaultProperty(Product::name);
            useDistributedKey("ProductOf");
            exposedName(Product::price, "UnitPrice");
        }
    }

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("sales");
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.11")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    static Connection nats;
    static NatsServer products;
    static ConfigurableApplicationContext context;

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();
        NATS.start();
        var natsUrl = "nats://localhost:" + NATS.getMappedPort(4222);
        nats = Nats.connect(natsUrl);
        // Knows products 1 and 4 only: the orders of the other products stay without product fields
        products = NatsServer.builder(nats, List.of(new InMemoryQueryHandler<>(new ProductConfig(), List.of(
                new Product(1, "Laptop", new BigDecimal("1000.50"), new Category("Laptops")),
                new Product(4, "Dock", new BigDecimal("100"), new Category("Accessories")))))).build().start();
        context = SalesServiceApplication.run("--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--jfxmap.nats.url=" + natsUrl,
                "--jfxmap.throw-if-exception=false");
    }

    @AfterAll
    static void stop() throws Exception {
        if (context != null) context.close();
        if (products != null) products.close();
        if (nats != null) nats.close();
        NATS.stop();
        POSTGRES.stop();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        var port = context.getEnvironment().getProperty("local.server.port");
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void flywayCreatesAndSeedsTheCustomers() throws Exception {
        var body = get("/customers").body();

        assertThat(JsonPath.<List<String>>read(body, "$[*].name"))
                .containsExactly("Alice Nguyen", "Bob Martin", "Carol Tran");
        assertThat(JsonPath.<String>read(body, "$[0].id")).isEqualTo(ALICE.toString());
        assertThat(JsonPath.<String>read(body, "$[0].city")).isEqualTo("Hanoi");
    }

    @Test
    void fillsTheProductFieldsOfEveryOrderOverNats() throws Exception {
        var body = get("/orders").body();

        assertThat(JsonPath.<List<Integer>>read(body, "$[*].id")).containsExactly(1, 2, 3, 4, 5);
        assertThat(JsonPath.<String>read(body, "$[0].customerName")).isEqualTo("Alice Nguyen");
        assertThat(JsonPath.<String>read(body, "$[0].productName")).isEqualTo("Laptop");
        assertThat(JsonPath.<Double>read(body, "$[0].unitPrice")).isEqualTo(1000.50);
        assertThat(JsonPath.<String>read(body, "$[0].categoryName")).isEqualTo("Laptops");
        assertThat(JsonPath.<Double>read(body, "$[0].total")).isEqualTo(1000.50);
        // Order 2 is two docks
        assertThat(JsonPath.<String>read(body, "$[1].productName")).isEqualTo("Dock");
        assertThat(JsonPath.<Integer>read(body, "$[1].total")).isEqualTo(200);
        // Product 3 is unknown to the stand-in: its fields stay empty
        assertThat(JsonPath.<String>read(body, "$[2].productName")).isNull();
        assertThat(JsonPath.<Object>read(body, "$[2].total")).isNull();
    }

    @Test
    void oneOrder() throws Exception {
        var response = get("/orders/4");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.customerName")).isEqualTo("Carol Tran");
        assertThat(JsonPath.<Integer>read(response.body(), "$.quantity")).isEqualTo(3);
        assertThat(get("/orders/99").statusCode()).isEqualTo(404);
    }

    @Test
    void answersCustomerOfOverNatsFromJpa() throws Exception {
        var client = NatsRequestClient.builder(nats).timeout(Duration.ofSeconds(10)).build();

        var response = client.request(DistributedKeyRef.of("CustomerOf"),
                new MapRequest(List.of(ALICE.toString(), CAROL.toString()),
                        Arrays.asList(null, "CustomerEmail", "City", "Orders:count")),
                MapContext.empty()).toCompletableFuture().get();

        assertThat(response.errors()).isEmpty();
        assertThat(response.items()).extracting(DataResponse::id)
                .containsExactlyInAnyOrder(ALICE.toString(), CAROL.toString());
        var alice = response.items().stream().filter(i -> i.id().equals(ALICE.toString())).findFirst().orElseThrow();
        assertThat(alice.values()).extracting(ValueResponse::expression, ValueResponse::value).contains(
                tuple(null, "\"Alice Nguyen\""),
                tuple("CustomerEmail", "\"alice@example.com\""),
                tuple("City", "\"Hanoi\""),
                tuple("Orders:count", "3"));
    }
}

package io.github.quyvu01.jfxmap.samples.nats;

import com.jayway.jsonpath.JsonPath;
import io.github.quyvu01.jfxmap.samples.sales.SalesServiceApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two services of the sample, as they run: catalog-service (.NET FxMap 5.1.1, EF Core) and sales-service (jfxmap,
 * JPA), each on its own database of a real PostgreSQL with its own migrations and seed, mapping each other's data
 * over a real NATS.
 *
 * <p>Runs when {@code JFXMAP_SAMPLE_CATALOG} gives the command that starts catalog-service (CI publishes it first).
 */
@EnabledIfEnvironmentVariable(named = "JFXMAP_SAMPLE_CATALOG", matches = ".+")
class DotnetJavaNatsTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("sales");
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.11")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static Process catalog;
    static String catalogUrl;
    static ConfigurableApplicationContext sales;

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();
        NATS.start();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE catalog");
        }
        var natsUrl = "nats://localhost:" + NATS.getMappedPort(4222);

        catalogUrl = "http://localhost:" + freePort();
        var command = Arrays.asList(System.getenv("JFXMAP_SAMPLE_CATALOG").trim().split("\\s+"));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("Urls", catalogUrl);
        builder.environment().put("ConnectionStrings__Catalog", "Host=localhost;Port="
                + POSTGRES.getMappedPort(5432) + ";Database=catalog;Username=" + POSTGRES.getUsername()
                + ";Password=" + POSTGRES.getPassword());
        builder.environment().put("FxMap__Nats__Url", natsUrl);
        catalog = builder.start();
        Thread.ofPlatform().daemon().start(() -> {
            try (var lines = new BufferedReader(new InputStreamReader(catalog.getInputStream(), UTF_8))) {
                lines.lines().forEach(line -> System.out.println("[catalog-service] " + line));
            } catch (IOException e) {
                // The process stopped
            }
        });

        sales = SalesServiceApplication.run("--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--jfxmap.nats.url=" + natsUrl);
        // catalog-service is up once it has migrated and seeded its database
        waitFor(catalogUrl + "/products");
    }

    @AfterAll
    static void stop() throws Exception {
        if (sales != null) sales.close();
        if (catalog != null) {
            catalog.destroy();
            if (!catalog.waitFor(30, TimeUnit.SECONDS)) catalog.destroyForcibly();
        }
        NATS.stop();
        POSTGRES.stop();
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void waitFor(String url) throws Exception {
        var deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (Instant.now().isBefore(deadline)) {
            assertThat(catalog.isAlive()).as("catalog-service stopped while starting").isTrue();
            try {
                if (get(url).statusCode() == 200) return;
            } catch (IOException e) {
                // Not listening yet
            }
            Thread.sleep(500);
        }
        throw new AssertionError("catalog-service did not answer " + url);
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String salesUrl() {
        return "http://localhost:" + sales.getEnvironment().getProperty("local.server.port");
    }

    @Test
    void javaFillsItsOrdersWithTheProductsOfDotnet() throws Exception {
        var body = get(salesUrl() + "/orders").body();

        assertThat(JsonPath.<List<String>>read(body, "$[*].productName")).containsExactly(
                "ThinkPad X1 Carbon", "USB-C Dock", "Pixel 10", "Mechanical Keyboard", "MacBook Air 15");
        assertThat(JsonPath.<List<String>>read(body, "$[*].categoryName")).containsExactly(
                "Laptops", "Accessories", "Phones", "Accessories", "Laptops");
        // Order 2: two docks at 129.90
        assertThat(JsonPath.<Double>read(body, "$[1].unitPrice")).isEqualTo(129.90);
        assertThat(JsonPath.<Double>read(body, "$[1].total")).isEqualTo(259.80);
        assertThat(JsonPath.<String>read(body, "$[1].customerName")).isEqualTo("Alice Nguyen");
    }

    @Test
    void dotnetFillsItsReviewsWithTheCustomersOfJava() throws Exception {
        var body = get(catalogUrl + "/reviews").body();

        assertThat(JsonPath.<List<String>>read(body, "$[*].customerName")).containsExactly(
                "Alice Nguyen", "Alice Nguyen", "Bob Martin", "Carol Tran");
        assertThat(JsonPath.<String>read(body, "$[0].customerEmail")).isEqualTo("alice@example.com");
        assertThat(JsonPath.<String>read(body, "$[0].customerCity")).isEqualTo("Hanoi");
        // Counted by jfxmap's JPA provider: Alice has 3 orders in sales-service, Carol 1 and no city
        assertThat(JsonPath.<Integer>read(body, "$[0].customerOrderCount")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(body, "$[3].customerOrderCount")).isEqualTo(1);
        assertThat(JsonPath.<Object>read(body, "$[3].customerCity")).isNull();
    }
}

package io.github.quyvu01.jfxmap.samples.order;

import com.jayway.jsonpath.JsonPath;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.grpc.FxMapGrpcService;
import io.github.quyvu01.jfxmap.provider.InMemoryQueryHandler;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The order service against a stand-in user service: any FxMap owner of "UserOf" on gRPC. */
class OrderServiceTest {
    record Country(String name) {
    }

    record User(UUID id, String name, String email, Country country, List<String> addresses) {
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

    static Server users;
    static ConfigurableApplicationContext context;

    @BeforeAll
    static void start() throws Exception {
        users = Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create())
                .addService(new FxMapGrpcService(List.of(new InMemoryQueryHandler<>(new UserConfig(), List.of(
                        new User(SampleOrders.ALICE, "Alice", "alice@test", new Country("Vietnam"),
                                List.of("Hanoi")))))))
                .build().start();
        context = OrderServiceApplication.run("--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:orders-test;DB_CLOSE_DELAY=-1",
                "--jfxmap.grpc.client.hosts=http://localhost:" + users.getPort());
    }

    @AfterAll
    static void stop() {
        context.close();
        users.shutdownNow();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        var port = context.getEnvironment().getProperty("local.server.port");
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void fillsTheUserFieldsOfEveryOrder() throws Exception {
        var body = get("/orders").body();

        assertThat(JsonPath.<List<Object>>read(body, "$[*].id")).hasSize(5);
        assertThat(JsonPath.<String>read(body, "$[0].userName")).isEqualTo("Alice");
        assertThat(JsonPath.<String>read(body, "$[0].userEmail")).isEqualTo("alice@test");
        assertThat(JsonPath.<String>read(body, "$[0].userCountry")).isEqualTo("Vietnam");
        assertThat(JsonPath.<String>read(body, "$[0].userNameUpper")).isEqualTo("ALICE");
        assertThat(JsonPath.<Integer>read(body, "$[0].userAddressCount")).isEqualTo(1);
        // Bob is unknown to the stand-in: his fields stay empty
        assertThat(JsonPath.<Object>read(body, "$[1].userName")).isNull();
    }

    @Test
    void fillsOneOrderAndAnswers404ForAMissingOne() throws Exception {
        assertThat(JsonPath.<String>read(get("/orders/3").body(), "$.userName")).isEqualTo("Alice");
        assertThat(get("/orders/99").statusCode()).isEqualTo(404);
    }

    @Test
    void seedsOnlyOnce() throws Exception {
        context.getBean(SampleOrders.class).run(null);

        assertThat(context.getBean(OrderRepository.class).count()).isEqualTo(5);
    }
}

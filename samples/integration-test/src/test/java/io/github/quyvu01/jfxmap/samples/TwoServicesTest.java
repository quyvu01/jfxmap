package io.github.quyvu01.jfxmap.samples;

import com.jayway.jsonpath.JsonPath;
import io.github.quyvu01.jfxmap.samples.order.OrderServiceApplication;
import io.github.quyvu01.jfxmap.samples.user.UserServiceApplication;
import io.github.quyvu01.jfxmap.spring.JfxMapGrpcServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both sample services, each with its own database, talking over gRPC as they would in production: the order
 * service shows each order with its user's fields, read by jfxmap from the user service's JPA entities.
 */
class TwoServicesTest {
    static ConfigurableApplicationContext userService;
    static ConfigurableApplicationContext orderService;

    @BeforeAll
    static void start() {
        userService = UserServiceApplication.run("--server.port=0", "--jfxmap.grpc.server.port=0");
        var grpcPort = userService.getBean(JfxMapGrpcServer.class).getPort();
        orderService = OrderServiceApplication.run("--server.port=0",
                "--jfxmap.grpc.client.hosts=http://localhost:" + grpcPort);
    }

    @AfterAll
    static void stop() {
        if (orderService != null) orderService.close();
        if (userService != null) userService.close();
    }

    private static String get(ConfigurableApplicationContext service, String path) throws Exception {
        var port = service.getEnvironment().getProperty("local.server.port");
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    @Test
    void ordersShowTheirUsersFromTheUserService() throws Exception {
        List<Map<String, Object>> orders = JsonPath.read(get(orderService, "/orders"), "$");

        assertThat(orders).extracting(o -> o.get("product"))
                .containsExactly("Keyboard", "Monitor", "Mouse", "Headset", "Cable");
        assertThat(orders).extracting(o -> o.get("userName"))
                .containsExactly("Alice Nguyen", "Bob Martin", "Alice Nguyen", "Carol Tran", null);
        assertThat(orders).extracting(o -> o.get("userEmail"))
                .containsExactly("alice@example.com", "bob@example.com", "alice@example.com", "carol@example.com", null);
        assertThat(orders).extracting(o -> o.get("userCountry"))
                .containsExactly("Vietnam", "France", "Vietnam", null, null);
        assertThat(orders).extracting(o -> o.get("userNameUpper"))
                .containsExactly("ALICE NGUYEN", "BOB MARTIN", "ALICE NGUYEN", "CAROL TRAN", null);
        assertThat(orders).extracting(o -> o.get("userAddressCount")).containsExactly(2, 1, 2, 0, null);
    }

    @Test
    void oneOrderShowsItsUser() throws Exception {
        var order = get(orderService, "/orders/2");

        assertThat(JsonPath.<String>read(order, "$.userName")).isEqualTo("Bob Martin");
        assertThat(JsonPath.<String>read(order, "$.userCountry")).isEqualTo("France");
    }

    @Test
    void theUserServiceStillServesItsOwnUsers() throws Exception {
        List<String> names = JsonPath.read(get(userService, "/users"), "$[*].name");

        assertThat(names).containsExactlyInAnyOrder("Alice Nguyen", "Bob Martin", "Carol Tran");
    }
}

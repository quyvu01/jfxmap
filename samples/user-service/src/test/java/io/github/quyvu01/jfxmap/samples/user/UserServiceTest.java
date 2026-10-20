package io.github.quyvu01.jfxmap.samples.user;

import com.jayway.jsonpath.JsonPath;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.grpc.GrpcRequestClient;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.spring.JfxMapGrpcServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The user service as a client sees it: its "UserOf" key over gRPC, and its REST endpoint. */
class UserServiceTest {
    static ConfigurableApplicationContext context;
    static GrpcRequestClient client;

    @BeforeAll
    static void start() {
        context = UserServiceApplication.run("--server.port=0", "--jfxmap.grpc.server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:users-test;DB_CLOSE_DELAY=-1");
        client = GrpcRequestClient.builder()
                .hosts("http://localhost:" + context.getBean(JfxMapGrpcServer.class).getPort())
                .build();
    }

    @AfterAll
    static void stop() {
        client.close();
        context.close();
    }

    @Test
    void answersTheUserOfKeyFromTheDatabase() {
        var request = new MapRequest(List.of(SampleUsers.ALICE.toString(), SampleUsers.CAROL.toString()),
                Arrays.asList(null, "UserEmail", "Country?.Name", "Addresses:count", "Name:upper"));

        var response = client.request(DistributedKeyRef.of("UserOf"), request, MapContext.empty())
                .toCompletableFuture().join();

        assertThat(response.errors()).isEmpty();
        assertThat(response.items().get(0).values()).extracting(ValueResponse::value)
                .containsExactly("\"Alice Nguyen\"", "\"alice@example.com\"", "\"Vietnam\"", "2", "\"ALICE NGUYEN\"");
        assertThat(response.items().get(1).values()).extracting(ValueResponse::value)
                .containsExactly("\"Carol Tran\"", "\"carol@example.com\"", null, "0", "\"CAROL TRAN\"");
    }

    @Test
    void listsTheUsers() throws Exception {
        var port = context.getEnvironment().getProperty("local.server.port");
        var body = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/users")).build(),
                HttpResponse.BodyHandlers.ofString()).body();

        List<String> names = JsonPath.read(body, "$[*].name");
        assertThat(names).containsExactlyInAnyOrder("Alice Nguyen", "Bob Martin", "Carol Tran");
        List<String> countries = JsonPath.read(body, "$[?(@.name == 'Bob Martin')].country");
        assertThat(countries).containsExactly("France");
    }

    @Test
    void seedsOnlyOnce() throws Exception {
        context.getBean(SampleUsers.class).run(null);

        assertThat(context.getBean(UserRepository.class).count()).isEqualTo(3);
    }
}

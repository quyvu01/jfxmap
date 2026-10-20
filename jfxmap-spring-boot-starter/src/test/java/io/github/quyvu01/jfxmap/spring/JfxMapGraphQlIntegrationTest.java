package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import io.github.quyvu01.jfxmap.graphql.JfxMapGraphQl;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.graphql.autoconfigure.GraphQlAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.support.DefaultExecutionGraphQlRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

/** Spring for GraphQL resolving the fields of a profiled type through the mapper, batched per request. */
class JfxMapGraphQlIntegrationTest {
    public static class OrderView {
        private final long userId;
        private String userName;
        private String userEmail;

        OrderView(long userId) {
            this.userId = userId;
        }

        public long getUserId() {
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
    }

    public static class OrderViewProfile extends ProfileOf<OrderView> {
        @Override
        protected void configure() {
            useDistributedKey("UserOf")
                    .of(OrderView::getUserId)
                    .map(OrderView::getUserName)
                    .map(OrderView::getUserEmail, "UserEmail");
        }
    }

    /** The users, recording what each request asked. */
    static final class RecordingUsers implements QueryHandler {
        final QueryHandler users = Fixtures.inMemoryUsers();
        final List<MapRequest> requests = Collections.synchronizedList(new ArrayList<>());
        final List<MapContext> contexts = Collections.synchronizedList(new ArrayList<>());

        @Override
        public DistributedKeyRef key() {
            return users.key();
        }

        @Override
        public Class<?> entityType() {
            return users.entityType();
        }

        @Override
        public CompletionStage<ItemsResponse> handle(MapRequest request, MapContext context) {
            requests.add(request);
            contexts.add(context);
            return users.handle(request, context);
        }
    }

    private final RecordingUsers users = new RecordingUsers();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JfxMapAutoConfiguration.class, GraphQlAutoConfiguration.class))
            .withBean(OrderViewProfile.class)
            .withBean(QueryHandler.class, () -> users)
            .withBean(RuntimeWiringConfigurer.class, () -> wiring -> wiring.type("Query", type -> type
                    .dataFetcher("orders", environment -> List.of(new OrderView(1), new OrderView(2),
                            new OrderView(1)))))
            .withPropertyValues("jfxmap.graphql.type-names[" + OrderView.class.getName() + "]=Order",
                    "jfxmap.graphql.forwarded-headers=authorization");

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> orders(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("orders");
    }

    @Test
    void resolvesTheSelectedFieldsOfTheSchemaInOneRequest() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(JfxMapGraphQl.class).fields()).containsOnlyKeys("Order");
            var response = context.getBean(ExecutionGraphQlService.class).execute(new DefaultExecutionGraphQlRequest(
                    "{ orders { userId userName } }", null, null, null, "1", null)).block();

            assertThat(response.getErrors()).isEmpty();
            assertThat(orders(response.getData())).extracting(o -> o.get("userName"))
                    .containsExactly("Alice (memory)", null, "Alice (memory)");
            assertThat(users.requests).hasSize(1);
            assertThat(users.requests.getFirst().expressions()).containsExactly((String) null);
        });
    }

    @Test
    void theRequestsCarryTheForwardedHttpHeaders() {
        runner.run(context -> {
            var handler = WebGraphQlHandler.builder(context.getBean(ExecutionGraphQlService.class))
                    .interceptors(context.getBeanProvider(WebGraphQlInterceptor.class).orderedStream().toList())
                    .build();
            var headers = new HttpHeaders();
            headers.add("authorization", "Bearer x");
            headers.add("cookie", "secret");

            var response = handler.handleRequest(new WebGraphQlRequest(URI.create("http://localhost/graphql"),
                    headers, new LinkedMultiValueMap<>(), (java.net.InetSocketAddress) null, Map.of(),
                    Map.of("query", "{ orders { userEmail } }"), "1", Locale.ROOT)).block();

            assertThat(response.getErrors()).isEmpty();
            assertThat(orders(response.getData())).extracting(o -> o.get("userEmail"))
                    .containsExactly("alice@memory", null, "alice@memory");
            assertThat(users.contexts.getFirst().headers()).containsExactly(Map.entry("authorization", "Bearer x"));
        });
    }

    @Test
    void aTypeNameOfAClassThatDoesNotExistFailsTheContext() {
        runner.withPropertyValues("jfxmap.graphql.type-names[com.example.Missing]=Order")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("jfxmap.graphql.type-names names a class that cannot be loaded"));
    }

    @Test
    void withoutJfxmapGraphQlNothingIsWired() {
        runner.withClassLoader(new FilteredClassLoader(JfxMapGraphQl.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("jfxMapRuntimeWiringConfigurer")
                        .doesNotHaveBean("jfxMapHeadersInterceptor"));
    }
}

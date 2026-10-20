package io.github.quyvu01.jfxmap.pipeline;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelinesTest {
    private static final DistributedKeyRef USER = DistributedKeyRef.of("UserOf");
    private static final MapRequest REQUEST = new MapRequest(List.of("1"), List.of("Name"));
    private static final ItemsResponse ANSWER = new ItemsResponse(List.of(new DataResponse("1", List.of())));

    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<MapContext> seen = new AtomicReference<>();

    private final RequestClient client = new RequestClient() {
        @Override
        public CompletionStage<ItemsResponse> request(DistributedKeyRef key, MapRequest request, MapContext context) {
            calls.add("transport " + key + " " + request.selectorIds());
            seen.set(context);
            return CompletableFuture.completedFuture(ANSWER);
        }

        @Override
        public String transport() {
            return "nats";
        }
    };

    private final QueryHandler handler = new QueryHandler() {
        @Override
        public DistributedKeyRef key() {
            return USER;
        }

        @Override
        public Class<?> entityType() {
            return String.class;
        }

        @Override
        public String dbSystem() {
            return "jpa";
        }

        @Override
        public CompletionStage<ItemsResponse> handle(MapRequest request, MapContext context) {
            calls.add("handler " + request.selectorIds());
            seen.set(context);
            return CompletableFuture.completedFuture(ANSWER);
        }
    };

    private SendBehavior sendStep(String name) {
        return (request, next) -> {
            calls.add(name + " before");
            return next.proceed(request).thenApply(response -> {
                calls.add(name + " after");
                return response;
            });
        };
    }

    private ReceiveBehavior receiveStep(String name) {
        return (request, next) -> {
            calls.add(name + " before");
            return next.proceed(request).thenApply(response -> {
                calls.add(name + " after");
                return response;
            });
        };
    }

    private static MapContext context() {
        return new MapContext(Map.of("x-caller", "orders"), null);
    }

    @Test
    void sendRunsTheBehaviorsInTheirOrderAroundTheTransport() {
        var piped = Pipelines.send(client, List.of(sendStep("first"), sendStep("second")));

        var response = piped.request(USER, REQUEST, context()).toCompletableFuture().join();

        assertThat(response).isEqualTo(ANSWER);
        assertThat(calls).containsExactly("first before", "second before", "transport UserOf [1]", "second after",
                "first after");
    }

    @Test
    void aSendBehaviorCanChangeTheHeadersAndTheRequest() {
        SendBehavior tenant = (request, next) -> next.proceed(request.withHeader("x-tenant", "acme")
                .withHeader("x-caller", null)
                .withRequest(new MapRequest(List.of("2"), request.request().expressions())));

        Pipelines.send(client, List.of(tenant)).request(USER, REQUEST, context()).toCompletableFuture().join();

        assertThat(seen.get().headers()).containsExactly(Map.entry("x-tenant", "acme"));
        assertThat(calls).containsExactly("transport UserOf [2]");
    }

    @Test
    void aSendBehaviorCanAnswerWithoutTheTransport() {
        var cached = new ItemsResponse(List.of());
        SendBehavior cache = (request, next) -> CompletableFuture.completedFuture(cached);

        var response = Pipelines.send(client, List.of(cache)).request(USER, REQUEST, context())
                .toCompletableFuture().join();

        assertThat(response).isSameAs(cached);
        assertThat(calls).isEmpty();
    }

    @Test
    void aBehaviorThatThrowsFailsTheRequestInsteadOfThrowing() {
        SendBehavior broken = (request, next) -> {
            throw new IllegalStateException("broken");
        };

        var response = Pipelines.send(client, List.of(broken)).request(USER, REQUEST, context());

        assertThatThrownBy(() -> response.toCompletableFuture().join()).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class).hasMessageContaining("broken");
    }

    @Test
    void aBehaviorThatReturnsNoStageFailsTheRequest() {
        SendBehavior none = (request, next) -> null;

        var response = Pipelines.send(client, List.of(none)).request(USER, REQUEST, context());

        assertThatThrownBy(() -> response.toCompletableFuture().join()).hasCauseInstanceOf(NullPointerException.class);
    }

    @Test
    void aTransportThatThrowsFailsTheRequest() {
        RequestClient throwing = (key, request, context) -> {
            throw new IllegalArgumentException("down");
        };

        var response = Pipelines.send(throwing, List.of(sendStep("only"))).request(USER, REQUEST, context());

        assertThatThrownBy(() -> response.toCompletableFuture().join())
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sendKeepsTheNameOfTheTransport() {
        assertThat(Pipelines.send(client, List.of(sendStep("only"))).transport()).isEqualTo("nats");
    }

    @Test
    void withoutBehaviorsTheClientAndTheHandlerAreReturnedAsTheyAre() {
        assertThat(Pipelines.send(client, List.of())).isSameAs(client);
        assertThat(Pipelines.receive(handler, List.of())).isSameAs(handler);
    }

    @Test
    void receiveRunsTheBehaviorsInTheirOrderAroundTheHandler() {
        var piped = Pipelines.receive(handler, List.of(receiveStep("first"), receiveStep("second")));

        var response = piped.handle(REQUEST, context()).toCompletableFuture().join();

        assertThat(response).isEqualTo(ANSWER);
        assertThat(calls).containsExactly("first before", "second before", "handler [1]", "second after",
                "first after");
    }

    @Test
    void receiveKeepsTheKeyTheEntityAndTheDatabaseOfTheHandler() {
        var piped = Pipelines.receive(handler, List.of(receiveStep("only")));

        assertThat(piped.key()).isEqualTo(USER);
        assertThat(piped.entityType()).isEqualTo(String.class);
        assertThat(piped.dbSystem()).isEqualTo("jpa");
    }

    @Test
    void aReceiveBehaviorSeesTheKeyAndCanRefuseTheRequest() {
        var refused = new AtomicReference<DistributedKeyRef>();
        ReceiveBehavior guard = (request, next) -> {
            refused.set(request.key());
            if (!request.headers().containsKey("x-tenant"))
                return CompletableFuture.failedFuture(new SecurityException("no tenant"));
            return next.proceed(request);
        };

        var response = Pipelines.receive(handler, List.of(guard)).handle(REQUEST, context());

        assertThatThrownBy(() -> response.toCompletableFuture().join()).hasCauseInstanceOf(SecurityException.class);
        assertThat(refused.get()).isEqualTo(USER);
        assertThat(calls).isEmpty();
    }

    @Test
    void receiveWrapsEachHandlerOfAList() {
        var piped = Pipelines.receive(List.of(handler, handler), List.of(receiveStep("only")));

        assertThat(piped).hasSize(2);
        piped.getLast().handle(REQUEST, context()).toCompletableFuture().join();
        assertThat(calls).containsExactly("only before", "handler [1]", "only after");
    }

    @Test
    void theRequestContextNeedsAKeyARequestAndAContext() {
        assertThatThrownBy(() -> new RequestContext(null, REQUEST, context())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RequestContext(USER, null, context())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RequestContext(USER, REQUEST, null)).isInstanceOf(NullPointerException.class);
        assertThat(new RequestContext(USER, REQUEST, context()).headers()).containsEntry("x-caller", "orders");
    }
}

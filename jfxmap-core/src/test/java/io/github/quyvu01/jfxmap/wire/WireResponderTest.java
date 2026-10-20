package io.github.quyvu01.jfxmap.wire;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WireResponderTest {
    private static final DistributedKeyRef USER_OF = DistributedKeyRef.of("UserOf");
    private static final ItemsResponse ANN = new ItemsResponse(
            List.of(new DataResponse("1", List.of(new ValueResponse(null, "\"Ann\"")))));

    private static QueryHandler handler(String key, Class<?> type,
                                        BiFunction<MapRequest, MapContext, CompletionStage<ItemsResponse>> answer) {
        return new QueryHandler() {
            @Override
            public DistributedKeyRef key() {
                return DistributedKeyRef.of(key);
            }

            @Override
            public Class<?> entityType() {
                return type;
            }

            @Override
            public CompletionStage<ItemsResponse> handle(MapRequest request, MapContext context) {
                return answer.apply(request, context);
            }
        };
    }

    @Test
    void answersWithTheHandlerOfTheKeyAndPassesTheHeaders() {
        var seen = new AtomicReference<MapContext>();
        var seenRequest = new AtomicReference<MapRequest>();
        var responder = new WireResponder(List.of(handler("UserOf", String.class, (request, context) -> {
            seenRequest.set(request);
            seen.set(context);
            return CompletableFuture.completedFuture(ANN);
        })), Duration.ofSeconds(1));

        var reply = responder.answer(USER_OF, "{\"SelectorIds\":[\"1\"],\"Expressions\":[null]}".getBytes(UTF_8),
                Map.of("tenant", "a")).join();

        assertThat(WireMessages.readResult(reply)).isEqualTo(ANN);
        assertThat(seenRequest.get().selectorIds()).containsExactly("1");
        assertThat(seen.get().headers()).containsEntry("tenant", "a");
        assertThat(responder.keys()).containsExactly(USER_OF);
    }

    @Test
    void anUnknownKeyIsAFault() {
        var responder = new WireResponder(List.of(), Duration.ofSeconds(1));

        var reply = responder.answer(USER_OF, new MapRequest(List.of("1"), List.of()), Map.of()).join();

        assertThatThrownBy(() -> WireMessages.readResult(reply))
                .isInstanceOf(DistributedMapException.ReceivedFault.class)
                .hasMessageContaining("NoHandlerForDistributedKey");
    }

    @Test
    void aMissingKeyIsAFault() {
        var responder = new WireResponder(List.of(), Duration.ofSeconds(1));

        var reply = responder.answer(null, new MapRequest(List.of("1"), List.of()), Map.of()).join();

        assertThatThrownBy(() -> WireMessages.readResult(reply)).hasMessageContaining("names no distributed key");
    }

    @Test
    void aRequestThatIsNotJsonIsAFault() {
        var responder = new WireResponder(List.of(handler("UserOf", String.class,
                (request, context) -> CompletableFuture.completedFuture(ANN))), Duration.ofSeconds(1));

        var reply = responder.answer(USER_OF, "nope".getBytes(UTF_8), Map.of()).join();

        assertThatThrownBy(() -> WireMessages.readResult(reply)).hasMessageContaining("not JSON");
    }

    @Test
    void aHandlerThatFailsOrThrowsIsAFault() {
        var failing = new WireResponder(List.of(handler("UserOf", String.class,
                (request, context) -> CompletableFuture.failedFuture(new IllegalStateException("db down")))),
                Duration.ofSeconds(1));
        var throwing = new WireResponder(List.of(handler("UserOf", String.class, (request, context) -> {
            throw new IllegalStateException("bug");
        })), Duration.ofSeconds(1));

        var request = new MapRequest(List.of("1"), List.of());
        assertThatThrownBy(() -> WireMessages.readResult(failing.answer(USER_OF, request, Map.of()).join()))
                .hasMessageContaining("db down");
        assertThatThrownBy(() -> WireMessages.readResult(throwing.answer(USER_OF, request, Map.of()).join()))
                .hasMessageContaining("bug");
    }

    @Test
    void aHandlerThatDoesNotAnswerInTimeIsATimeoutFault() {
        var responder = new WireResponder(List.of(handler("UserOf", String.class,
                (request, context) -> new CompletableFuture<>())), Duration.ofMillis(50));

        var reply = responder.answer(USER_OF, new MapRequest(List.of("1"), List.of()), Map.of()).join();

        assertThatThrownBy(() -> WireMessages.readResult(reply))
                .hasMessageContaining("java.util.concurrent.TimeoutException: Request timeout for UserOf");
    }

    @Test
    void twoHandlersOfOneKeyOrABadTimeoutAreRejected() {
        var first = handler("UserOf", String.class, (request, context) -> null);
        var second = handler("UserOf", Integer.class, (request, context) -> null);

        assertThatThrownBy(() -> new WireResponder(List.of(first, second), Duration.ofSeconds(1)))
                .isInstanceOf(DistributedMapException.DistributedKeyAssignedToMultipleEntities.class);
        assertThatThrownBy(() -> new WireResponder(List.of(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WireResponder(List.of(), null)).isInstanceOf(NullPointerException.class);
    }
}

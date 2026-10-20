package io.github.quyvu01.jfxmap;

import io.github.quyvu01.jfxmap.Models.UserOf;
import io.github.quyvu01.jfxmap.Models.UserSummaryDto;
import io.github.quyvu01.jfxmap.Models.UserSummaryProfile;
import io.github.quyvu01.jfxmap.pipeline.RetryPolicy;
import io.github.quyvu01.jfxmap.pipeline.SendBehavior;
import io.github.quyvu01.jfxmap.provider.LocalRequestClient;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The send behaviors and the retry policy of {@link FxMap.Builder}, around the transport of the mapper. */
class MapperPipelineTest {
    private static final DistributedKeyRef USER = DistributedKeyRef.of(UserOf.class);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private final FakeRequestClient users = new FakeRequestClient().value(USER, ALICE, null, "\"Alice\"");
    private final AtomicInteger attempts = new AtomicInteger();
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    /** Fails the first {@code failures} requests, then answers from {@link #users}. */
    private RequestClient flaky(int failures) {
        return (key, request, context) -> {
            calls.add("transport " + new TreeMap<>(context.headers()));
            if (attempts.incrementAndGet() <= failures)
                return CompletableFuture.failedFuture(new IllegalStateException("broker down"));
            return users.request(key, request, context);
        };
    }

    @Test
    void theRetryPolicyRecoversAFailedRequest() {
        var mapper = FxMap.builder().addProfiles(new UserSummaryProfile()).requestClient(flaky(2))
                .retryPolicy(RetryPolicy.retries(3).delay(Duration.ofMillis(10))).throwIfException().build();
        var dto = new UserSummaryDto(ALICE);

        mapper.map(dto);

        assertThat(dto.getUserName()).isEqualTo("Alice");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void withoutARetryPolicyAFailedRequestFailsTheMapping() {
        var mapper = FxMap.builder().addProfiles(new UserSummaryProfile()).requestClient(flaky(1))
                .throwIfException().build();

        assertThatThrownBy(() -> mapper.map(new UserSummaryDto(ALICE))).isInstanceOf(IllegalStateException.class)
                .hasMessage("broker down");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void whenTheRetriesAreUsedUpTheLastFailureIsLoggedOrThrownAsBefore() {
        var logging = FxMap.builder().addProfiles(new UserSummaryProfile()).requestClient(flaky(10))
                .retryPolicy(RetryPolicy.retries(1)).build();
        var dto = new UserSummaryDto(ALICE);

        logging.map(dto);

        assertThat(dto.getUserName()).isNull();
        assertThat(attempts).hasValue(2);
    }

    @Test
    void theBehaviorsRunInTheirOrderOnEachAttemptInsideTheRetries() {
        SendBehavior tenant = (request, next) -> {
            calls.add("tenant");
            return next.proceed(request.withHeader("x-tenant", "acme"));
        };
        SendBehavior timing = (request, next) -> {
            calls.add("timing " + new TreeMap<>(request.headers()));
            return next.proceed(request);
        };
        var mapper = FxMap.builder().addProfiles(new UserSummaryProfile()).requestClient(flaky(1))
                .retryPolicy(RetryPolicy.retries(1)).addSendBehaviors(tenant, timing).throwIfException().build();

        mapper.map(new UserSummaryDto(ALICE), Map.of("x-caller", "orders"));

        assertThat(calls).containsExactly(
                "tenant", "timing {x-caller=orders, x-tenant=acme}", "transport {x-caller=orders, x-tenant=acme}",
                "tenant", "timing {x-caller=orders, x-tenant=acme}", "transport {x-caller=orders, x-tenant=acme}");
    }

    @Test
    void fetchAsyncGoesThroughTheBehaviorsToo() {
        SendBehavior tenant = (request, next) -> next.proceed(request.withHeader("x-tenant", "acme"));
        var mapper = FxMap.builder().requestClient(users).addSendBehaviors(tenant).build();

        mapper.fetchAsync(USER, new MapRequest(List.of(ALICE.toString()), List.of()), Map.of()).join();

        assertThat(users.sent.getFirst().context().headers()).containsEntry("x-tenant", "acme");
    }

    @Test
    void theBuilderRejectsNullBehaviorsAndPolicies() {
        var builder = FxMap.builder();
        assertThatThrownBy(() -> builder.addSendBehaviors((SendBehavior) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.retryPolicy(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theContextGetsAHeaderWithoutChanging() {
        var context = new MapContext(Map.of("a", "1"), type -> "service");

        var changed = context.withHeader("b", "2").withHeader("a", null);

        assertThat(context.headers()).containsExactly(Map.entry("a", "1"));
        assertThat(changed.headers()).containsExactly(Map.entry("b", "2"));
        assertThat(changed.service(String.class)).isEqualTo("service");
        assertThatThrownBy(() -> context.withHeader(null, "x")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void transportsAndHandlersNameThemselves() {
        RequestClient custom = (key, request, context) -> CompletableFuture.completedFuture(ItemsResponse.empty());
        QueryHandler handler = new QueryHandler() {
            @Override
            public DistributedKeyRef key() {
                return USER;
            }

            @Override
            public Class<?> entityType() {
                return Object.class;
            }

            @Override
            public CompletionStage<ItemsResponse> handle(MapRequest request, MapContext context) {
                return CompletableFuture.completedFuture(ItemsResponse.empty());
            }
        };

        assertThat(custom.transport()).isEqualTo("custom");
        assertThat(new LocalRequestClient().transport()).isEqualTo("local");
        assertThat(handler.dbSystem()).isNull();
    }
}

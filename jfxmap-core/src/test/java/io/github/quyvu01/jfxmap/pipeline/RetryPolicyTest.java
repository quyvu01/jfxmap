package io.github.quyvu01.jfxmap.pipeline;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {
    private static final RequestContext REQUEST = new RequestContext(DistributedKeyRef.of("UserOf"),
            new MapRequest(List.of("1"), List.of("Name")), new MapContext(Map.of(), null));
    private static final ItemsResponse ANSWER = new ItemsResponse(List.of());

    private final AtomicInteger attempts = new AtomicInteger();
    // The delays the scheduler was asked for; it runs the retries at once
    private final List<Duration> waited = Collections.synchronizedList(new ArrayList<>());
    private final RetryPolicy.Scheduler scheduler = delay -> {
        waited.add(delay);
        return Runnable::run;
    };

    /** Fails the first {@code failures} attempts with {@code failure}, then answers. */
    private Next failing(int failures, RuntimeException failure) {
        return request -> {
            if (attempts.incrementAndGet() <= failures) return CompletableFuture.failedFuture(failure);
            return CompletableFuture.completedFuture(ANSWER);
        };
    }

    private static ItemsResponse join(CompletionStage<ItemsResponse> stage) {
        return stage.toCompletableFuture().join();
    }

    @Test
    void retriesUntilAnAttemptSucceeds() {
        var policy = RetryPolicy.retries(3);

        var response = join(policy.asBehavior(scheduler).send(REQUEST, failing(2, new IllegalStateException("down"))));

        assertThat(response).isSameAs(ANSWER);
        assertThat(attempts).hasValue(3);
        assertThat(waited).isEmpty();
    }

    @Test
    void givesTheLastFailureWhenTheRetriesAreUsedUp() {
        var policy = RetryPolicy.retries(2);

        var response = policy.asBehavior(scheduler).send(REQUEST, failing(10, new IllegalStateException("down")));

        assertThatThrownBy(() -> join(response)).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("down");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void waitsTheDelayOfEachRetryAndCallsOnRetryBeforeIt() {
        var retried = Collections.synchronizedList(new ArrayList<String>());
        var policy = RetryPolicy.retries(3)
                .delay(retry -> Duration.ofMillis(100L * retry))
                .onRetry((failure, delay) -> retried.add(failure.getMessage() + " " + delay.toMillis()));

        join(policy.asBehavior(scheduler).send(REQUEST, failing(3, new IllegalStateException("down"))));

        assertThat(waited).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(300));
        assertThat(retried).containsExactly("down 100", "down 200", "down 300");
    }

    @Test
    void doesNotRetryWhatShouldRetryRefuses() {
        var policy = RetryPolicy.retries(3).shouldRetry(e -> !(e instanceof IllegalArgumentException));

        var response = policy.asBehavior(scheduler).send(REQUEST, failing(1, new IllegalArgumentException("bad")));

        assertThatThrownBy(() -> join(response)).hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void neverRetriesAnError() {
        var policy = RetryPolicy.retries(3);
        Next next = request -> {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(new OutOfMemoryError("full"));
        };

        var response = policy.asBehavior(scheduler).send(REQUEST, next);

        assertThatThrownBy(() -> join(response)).hasCauseInstanceOf(OutOfMemoryError.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void retriesAStepThatThrowsInsteadOfFailing() {
        var policy = RetryPolicy.retries(1);
        Next next = request -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("thrown");
            return CompletableFuture.completedFuture(ANSWER);
        };

        assertThat(join(policy.asBehavior(scheduler).send(REQUEST, next))).isSameAs(ANSWER);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void zeroRetriesMakesOneAttempt() {
        var response = RetryPolicy.retries(0).asBehavior(scheduler)
                .send(REQUEST, failing(1, new IllegalStateException("down")));

        assertThatThrownBy(() -> join(response)).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void waitsForRealWithTheDefaultScheduler() {
        var policy = RetryPolicy.retries(1).delay(Duration.ofMillis(150));
        var started = System.nanoTime();

        join(policy.asBehavior().send(REQUEST, failing(1, new IllegalStateException("down"))));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(150));
        assertThat(attempts).hasValue(2);
    }

    @Test
    void aFixedDelayIsTheSameForEachRetry() {
        var policy = RetryPolicy.retries(2).delay(Duration.ofSeconds(1));

        assertThat(policy.delay().apply(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delay().apply(2)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void anExponentialDelayDoublesUpToItsMaximum() {
        var policy = RetryPolicy.retries(10).exponentialDelay(Duration.ofMillis(100), Duration.ofSeconds(1));

        assertThat(List.of(1, 2, 3, 4, 5, 60).stream().map(policy.delay()::apply).map(Duration::toMillis))
                .containsExactly(100L, 200L, 400L, 800L, 1000L, 1000L);
    }

    @Test
    void aNullDelayMeansNone() {
        var policy = RetryPolicy.retries(1).delay(retry -> null);

        join(policy.asBehavior(scheduler).send(REQUEST, failing(1, new IllegalStateException("down"))));

        assertThat(waited).isEmpty();
        assertThat(attempts).hasValue(2);
    }

    @Test
    void rejectsNegativeRetriesAndMissingFunctions() {
        assertThatThrownBy(() -> RetryPolicy.retries(-1)).isInstanceOf(IllegalArgumentException.class);
        var policy = RetryPolicy.retries(1);
        assertThatThrownBy(() -> policy.delay((Duration) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> policy.onRetry(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> policy.shouldRetry(null)).isInstanceOf(NullPointerException.class);
    }
}

package io.github.quyvu01.jfxmap.pipeline;

import io.github.quyvu01.jfxmap.response.ItemsResponse;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * How failed requests are sent again (FxMap's {@code SetRetryPolicy}), set with
 * {@link io.github.quyvu01.jfxmap.FxMap.Builder#retryPolicy(RetryPolicy)}:
 * <ul>
 *   <li>at most {@code retries} retries after the first attempt: {@code retries(3)} makes at most 4 attempts;</li>
 *   <li>the delay is waited <b>before</b> each retry, not after the last failure;</li>
 *   <li>{@code onRetry} is called before each retry, with the failure of the attempt that failed and the delay;</li>
 *   <li>{@code shouldRetry} can refuse a retry: the failure is returned at once;</li>
 *   <li>when the attempts are used up, the last failure is returned.</li>
 * </ul>
 * An {@link Error} is never retried. An expression the owner could not evaluate is part of its answer, not a failure,
 * and is not retried either.
 *
 * <pre>{@code
 * RetryPolicy.retries(3)
 *         .delay(retry -> Duration.ofMillis(200L << (retry - 1)))   // 200 ms, 400 ms, 800 ms
 *         .shouldRetry(e -> !(e instanceof DistributedMapException.NoHandlerForDistributedKey))
 * }</pre>
 *
 * @param retries     the retries after the first attempt
 * @param delay       the delay before each retry, by its number (1 for the first retry); none when {@code null}
 * @param onRetry     called before each retry, or {@code null}
 * @param shouldRetry whether a failure is worth a retry; every failure when {@code null}
 */
public record RetryPolicy(int retries, IntFunction<Duration> delay, BiConsumer<Throwable, Duration> onRetry,
                          Predicate<Throwable> shouldRetry) {
    public RetryPolicy {
        if (retries < 0) throw new IllegalArgumentException("The retries must not be negative.");
    }

    /** {@code retries} retries without a delay. */
    public static RetryPolicy retries(int retries) {
        return new RetryPolicy(retries, null, null, null);
    }

    /** The same policy with this delay before each retry, by its number (1 for the first retry). */
    public RetryPolicy delay(IntFunction<Duration> delay) {
        return new RetryPolicy(retries, Objects.requireNonNull(delay, "delay"), onRetry, shouldRetry);
    }

    /** The same policy with the same delay before each retry. */
    public RetryPolicy delay(Duration delay) {
        Objects.requireNonNull(delay, "delay");
        return delay(retry -> delay);
    }

    /**
     * The same policy with a delay that doubles at each retry, from {@code first} up to {@code max}: 1, 2, 4... times
     * {@code first}.
     */
    public RetryPolicy exponentialDelay(Duration first, Duration max) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(max, "max");
        return delay(retry -> {
            var factor = 1L << Math.min(retry - 1, 30);
            var millis = first.toMillis() > max.toMillis() / factor ? max.toMillis() : first.toMillis() * factor;
            return Duration.ofMillis(Math.min(millis, max.toMillis()));
        });
    }

    /** The same policy, calling {@code onRetry} before each retry. */
    public RetryPolicy onRetry(BiConsumer<Throwable, Duration> onRetry) {
        return new RetryPolicy(retries, delay, Objects.requireNonNull(onRetry, "onRetry"), shouldRetry);
    }

    /** The same policy, retrying only the failures {@code shouldRetry} accepts. */
    public RetryPolicy shouldRetry(Predicate<Throwable> shouldRetry) {
        return new RetryPolicy(retries, delay, onRetry, Objects.requireNonNull(shouldRetry, "shouldRetry"));
    }

    /** This policy as the behavior that runs it. */
    public SendBehavior asBehavior() {
        return asBehavior(null);
    }

    // The scheduler of the delays; the default delayed executor when null (tests give their own)
    SendBehavior asBehavior(Scheduler scheduler) {
        return (request, next) -> attempt(request, next, 0, scheduler);
    }

    interface Scheduler {
        Executor after(Duration delay);
    }

    private CompletionStage<ItemsResponse> attempt(
            RequestContext request, Next next, int retry, Scheduler scheduler) {
        CompletionStage<ItemsResponse> sent;
        try {
            sent = next.proceed(request);
        } catch (RuntimeException e) {
            sent = CompletableFuture.failedFuture(e);
        }
        return sent.handle((response, failure) -> {
            if (failure == null) return CompletableFuture.completedFuture(response);
            var cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            if (retry >= retries || cause instanceof Error || shouldRetry != null && !shouldRetry.test(cause))
                return CompletableFuture.<ItemsResponse>failedFuture(cause);
            var number = retry + 1;
            var wait = delay == null ? Duration.ZERO : Objects.requireNonNullElse(delay.apply(number), Duration.ZERO);
            if (onRetry != null) onRetry.accept(cause, wait);
            if (wait.isNegative() || wait.isZero()) return attempt(request, next, number, scheduler);
            var executor = scheduler != null ? scheduler.after(wait)
                    : CompletableFuture.delayedExecutor(wait.toNanos(), TimeUnit.NANOSECONDS);
            return CompletableFuture.supplyAsync(() -> null, executor)
                    .thenCompose(ignored -> attempt(request, next, number, scheduler));
        }).thenCompose(stage -> stage);
    }
}

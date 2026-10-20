package io.github.quyvu01.jfxmap.supervision;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

/**
 * How a {@link ServerSupervisor} handles failures (FxMap's {@code SupervisorOptions}), with FxMap's defaults: restart
 * only the server that failed, at most 32 times a minute, after a backoff from 1 second doubling up to 5 minutes; after
 * 3 failures in a row the circuit breaker opens for a minute.
 *
 * @param strategy                which servers start again when one fails
 * @param maxRestarts             the restarts allowed within {@code maxRestartWindow}; beyond, the server stops for good
 * @param maxRestartWindow        the window the restarts are counted in; they are forgotten after it
 * @param initialBackoff          the delay before the first restart
 * @param maxBackoff              the longest delay between restarts
 * @param backoffMultiplier       what the delay is multiplied by after each restart
 * @param circuitBreaker          whether failures in a row open the circuit breaker
 * @param circuitBreakerThreshold the failures in a row that open it
 * @param circuitBreakerResetTime how long it stays open
 * @param directives              the directive of each failure type; the first type the failure is an instance of
 *                                wins, the exact type first; {@link SupervisorDirective#RESTART} for the others
 */
public record SupervisorOptions(SupervisionStrategy strategy, int maxRestarts, Duration maxRestartWindow,
                                Duration initialBackoff, Duration maxBackoff, double backoffMultiplier,
                                boolean circuitBreaker, int circuitBreakerThreshold, Duration circuitBreakerResetTime,
                                Map<Class<? extends Throwable>, SupervisorDirective> directives) {
    public SupervisorOptions {
        Objects.requireNonNull(strategy, "strategy");
        if (maxRestarts < 0) throw new IllegalArgumentException("maxRestarts must not be negative.");
        positive(maxRestartWindow, "maxRestartWindow");
        notNegative(initialBackoff, "initialBackoff");
        notNegative(maxBackoff, "maxBackoff");
        if (!(backoffMultiplier >= 1)) throw new IllegalArgumentException("backoffMultiplier must be at least 1.");
        if (circuitBreakerThreshold < 1)
            throw new IllegalArgumentException("circuitBreakerThreshold must be at least 1.");
        notNegative(circuitBreakerResetTime, "circuitBreakerResetTime");
        directives = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(directives, "directives")));
    }

    /** FxMap's defaults. */
    public static SupervisorOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The directive of a failure; a {@link CompletionException} counts as its cause. */
    public SupervisorDirective directiveOf(Throwable failure) {
        var cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        var exact = directives.get(cause.getClass());
        if (exact != null) return exact;
        for (var entry : directives.entrySet())
            if (entry.getKey().isInstance(cause)) return entry.getValue();
        return SupervisorDirective.RESTART;
    }

    private static void positive(Duration duration, String name) {
        if (Objects.requireNonNull(duration, name).isNegative() || duration.isZero())
            throw new IllegalArgumentException(name + " must be positive.");
    }

    private static void notNegative(Duration duration, String name) {
        if (Objects.requireNonNull(duration, name).isNegative())
            throw new IllegalArgumentException(name + " must not be negative.");
    }

    public static final class Builder {
        private SupervisionStrategy strategy = SupervisionStrategy.ONE_FOR_ONE;
        private int maxRestarts = 32;
        private Duration maxRestartWindow = Duration.ofMinutes(1);
        private Duration initialBackoff = Duration.ofSeconds(1);
        private Duration maxBackoff = Duration.ofMinutes(5);
        private double backoffMultiplier = 2.0;
        private boolean circuitBreaker = true;
        private int circuitBreakerThreshold = 3;
        private Duration circuitBreakerResetTime = Duration.ofMinutes(1);
        private final Map<Class<? extends Throwable>, SupervisorDirective> directives = new LinkedHashMap<>();

        private Builder() {
            // FxMap stops on a cancellation and escalates what the process cannot recover from
            directives.put(CancellationException.class, SupervisorDirective.STOP);
            directives.put(InterruptedException.class, SupervisorDirective.STOP);
            directives.put(VirtualMachineError.class, SupervisorDirective.ESCALATE);
        }

        public Builder strategy(SupervisionStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public Builder maxRestarts(int maxRestarts) {
            this.maxRestarts = maxRestarts;
            return this;
        }

        public Builder maxRestartWindow(Duration maxRestartWindow) {
            this.maxRestartWindow = maxRestartWindow;
            return this;
        }

        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        public Builder backoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }

        public Builder circuitBreaker(boolean circuitBreaker) {
            this.circuitBreaker = circuitBreaker;
            return this;
        }

        public Builder circuitBreakerThreshold(int circuitBreakerThreshold) {
            this.circuitBreakerThreshold = circuitBreakerThreshold;
            return this;
        }

        public Builder circuitBreakerResetTime(Duration circuitBreakerResetTime) {
            this.circuitBreakerResetTime = circuitBreakerResetTime;
            return this;
        }

        /** The directive of the failures of this type (and its subtypes, unless they have their own). */
        public Builder directive(Class<? extends Throwable> failureType, SupervisorDirective directive) {
            directives.put(Objects.requireNonNull(failureType, "failureType"),
                    Objects.requireNonNull(directive, "directive"));
            return this;
        }

        public SupervisorOptions build() {
            return new SupervisorOptions(strategy, maxRestarts, maxRestartWindow, initialBackoff, maxBackoff,
                    backoffMultiplier, circuitBreaker, circuitBreakerThreshold, circuitBreakerResetTime, directives);
        }
    }
}

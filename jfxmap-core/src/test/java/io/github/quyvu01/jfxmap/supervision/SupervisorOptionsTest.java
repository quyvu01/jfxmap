package io.github.quyvu01.jfxmap.supervision;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupervisorOptionsTest {
    @Test
    void hasTheDefaultsOfFxMap() {
        var options = SupervisorOptions.defaults();

        assertThat(options.strategy()).isEqualTo(SupervisionStrategy.ONE_FOR_ONE);
        assertThat(options.maxRestarts()).isEqualTo(32);
        assertThat(options.maxRestartWindow()).isEqualTo(Duration.ofMinutes(1));
        assertThat(options.initialBackoff()).isEqualTo(Duration.ofSeconds(1));
        assertThat(options.maxBackoff()).isEqualTo(Duration.ofMinutes(5));
        assertThat(options.backoffMultiplier()).isEqualTo(2.0);
        assertThat(options.circuitBreaker()).isTrue();
        assertThat(options.circuitBreakerThreshold()).isEqualTo(3);
        assertThat(options.circuitBreakerResetTime()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void restartsByDefaultAndStopsOnACancellation() {
        var options = SupervisorOptions.defaults();

        assertThat(options.directiveOf(new IllegalStateException())).isEqualTo(SupervisorDirective.RESTART);
        assertThat(options.directiveOf(new CancellationException())).isEqualTo(SupervisorDirective.STOP);
        assertThat(options.directiveOf(new InterruptedException())).isEqualTo(SupervisorDirective.STOP);
        assertThat(options.directiveOf(new OutOfMemoryError())).isEqualTo(SupervisorDirective.ESCALATE);
        assertThat(options.directiveOf(new StackOverflowError())).isEqualTo(SupervisorDirective.ESCALATE);
    }

    @Test
    void theExactTypeWinsThenTheFirstSupertype() {
        var options = SupervisorOptions.builder()
                .directive(RuntimeException.class, SupervisorDirective.RESUME)
                .directive(UncheckedIOException.class, SupervisorDirective.STOP)
                .build();

        assertThat(options.directiveOf(new UncheckedIOException(new IOException()))).isEqualTo(SupervisorDirective.STOP);
        assertThat(options.directiveOf(new IllegalStateException())).isEqualTo(SupervisorDirective.RESUME);
        assertThat(options.directiveOf(new IOException())).isEqualTo(SupervisorDirective.RESTART);
    }

    @Test
    void aCompletionExceptionCountsAsItsCause() {
        var options = SupervisorOptions.builder().directive(SecurityException.class, SupervisorDirective.STOP).build();

        assertThat(options.directiveOf(new CompletionException(new SecurityException())))
                .isEqualTo(SupervisorDirective.STOP);
    }

    @Test
    void rejectsValuesThatMakeNoSense() {
        assertThatThrownBy(() -> SupervisorOptions.builder().maxRestarts(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupervisorOptions.builder().maxRestartWindow(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupervisorOptions.builder().initialBackoff(Duration.ofSeconds(-1)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupervisorOptions.builder().maxBackoff(Duration.ofSeconds(-1)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupervisorOptions.builder().backoffMultiplier(0.5).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupervisorOptions.builder().backoffMultiplier(Double.NaN).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupervisorOptions.builder().circuitBreakerThreshold(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupervisorOptions.builder().circuitBreakerResetTime(Duration.ofSeconds(-1)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupervisorOptions.builder().strategy(null).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SupervisorOptions.builder().directive(null, SupervisorDirective.STOP))
                .isInstanceOf(NullPointerException.class);
    }
}

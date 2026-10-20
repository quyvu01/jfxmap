package io.github.quyvu01.jfxmap.mongodb;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartAttemptsTest {
    @Test
    void returnsTheFirstStartThatPassesTheCheck() {
        var starts = new AtomicInteger();
        var stopped = new ArrayList<Integer>();

        var started = StartAttempts.start(3, starts::incrementAndGet, n -> {
            if (n < 2) {
                throw new IllegalStateException("not ready " + n);
            }
        }, stopped::add);

        assertThat(started).isEqualTo(2);
        assertThat(stopped).containsExactly(1);
    }

    @Test
    void doesNotStopWhatPassedTheCheck() {
        var stopped = new ArrayList<String>();

        assertThat(StartAttempts.start(3, () -> "up", s -> {
        }, stopped::add)).isEqualTo("up");
        assertThat(stopped).isEmpty();
    }

    @Test
    void triesAgainWhenTheStartItselfThrowsAndHasNothingToStop() {
        var starts = new AtomicInteger();
        var stopped = new ArrayList<Integer>();

        var started = StartAttempts.start(2, () -> {
            if (starts.incrementAndGet() == 1) {
                throw new IllegalStateException("pull failed");
            }
            return starts.get();
        }, n -> {
        }, stopped::add);

        assertThat(started).isEqualTo(2);
        assertThat(stopped).isEmpty();
    }

    @Test
    void throwsTheFirstFailureWithTheOthersSuppressedWhenNoAttemptWorks() {
        var starts = new AtomicInteger();
        var stopped = new ArrayList<Integer>();

        assertThatThrownBy(() -> StartAttempts.start(3, starts::incrementAndGet, n -> {
            throw new IllegalStateException("down " + n);
        }, stopped::add))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("down 1")
                .satisfies(e -> assertThat(e.getSuppressed()).extracting(Throwable::getMessage)
                        .containsExactly("down 2", "down 3"));
        assertThat(stopped).containsExactly(1, 2, 3);
    }

    @Test
    void keepsAFailureToStopWithTheAttemptItBelongsTo() {
        assertThatThrownBy(() -> StartAttempts.start(1, () -> "up", s -> {
            throw new IllegalStateException("down");
        }, s -> {
            throw new IllegalArgumentException("stop failed");
        }))
                .hasMessage("down")
                .satisfies(e -> assertThat(e.getSuppressed()).extracting(Throwable::getMessage)
                        .containsExactly("stop failed"));
    }

    @Test
    void needsAtLeastOneAttempt() {
        List<String> calls = new ArrayList<>();

        assertThatThrownBy(() -> StartAttempts.start(0, () -> calls.add("start"), b -> {
        }, b -> {
        })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 1");
        assertThat(calls).isEmpty();
    }
}

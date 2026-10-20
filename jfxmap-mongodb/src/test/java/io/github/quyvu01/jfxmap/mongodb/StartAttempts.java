package io.github.quyvu01.jfxmap.mongodb;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Starts something the tests need (a server in Docker) again when it does not come up usable, so that one container
 * that dies right after its start on a busy CI runner does not fail the whole test class.
 */
final class StartAttempts {
    private StartAttempts() {
    }

    /**
     * Calls {@code start}, then {@code check} on what it gave; when either throws, calls {@code stop} on what was
     * started and tries again, up to {@code attempts} times in all. The first failure is thrown, with the later ones
     * suppressed in it, when no attempt works.
     */
    static <T> T start(int attempts, Supplier<T> start, Consumer<? super T> check, Consumer<? super T> stop) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, was " + attempts);
        }
        RuntimeException failure = null;
        for (var attempt = 0; attempt < attempts; attempt++) {
            T started = null;
            try {
                started = start.get();
                check.accept(started);
                return started;
            } catch (RuntimeException e) {
                if (started != null) {
                    try {
                        stop.accept(started);
                    } catch (RuntimeException stopFailure) {
                        e.addSuppressed(stopFailure);
                    }
                }
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        throw failure;
    }
}

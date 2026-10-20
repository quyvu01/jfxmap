package io.github.quyvu01.jfxmap.supervision;

import io.github.quyvu01.jfxmap.RequestServer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/** A server that counts its starts and stops, and fails when a test says so. */
final class FakeServer implements RequestServer {
    final AtomicInteger starts = new AtomicInteger();
    final AtomicInteger closes = new AtomicInteger();
    // The failures of the next starts, in order
    private final Deque<RuntimeException> startFailures = new ArrayDeque<>();
    private CompletableFuture<Void> run = CompletableFuture.completedFuture(null);
    private boolean running;

    synchronized FakeServer failNextStarts(RuntimeException... failures) {
        for (var failure : failures) startFailures.add(failure);
        return this;
    }

    @Override
    public synchronized FakeServer start() {
        if (running) return this;
        starts.incrementAndGet();
        var failure = startFailures.poll();
        if (failure != null) throw failure;
        running = true;
        run = new CompletableFuture<>();
        return this;
    }

    /** Stops on its own, as a server whose consumer was cancelled. */
    void fail(Throwable failure) {
        CompletableFuture<Void> ended;
        synchronized (this) {
            if (!running) return;
            running = false;
            ended = run;
        }
        ended.completeExceptionally(failure);
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    @Override
    public synchronized CompletionStage<Void> whenStopped() {
        return run;
    }

    @Override
    public void close() {
        CompletableFuture<Void> ended;
        synchronized (this) {
            closes.incrementAndGet();
            if (!running) return;
            running = false;
            ended = run;
        }
        ended.complete(null);
    }
}

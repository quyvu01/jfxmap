package io.github.quyvu01.jfxmap.wire;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The requests a client of a message transport waits for, by correlation id (RabbitMQ, Kafka): a request completes
 * with its reply, or fails when the reply does not come in time.
 */
public final class PendingReplies {
    private final Map<String, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();

    /**
     * Waits for the reply of a request.
     *
     * @return the reply, which fails with {@link DistributedMapException.RequestTimedOut} after the timeout
     */
    public CompletableFuture<byte[]> register(String correlationId, DistributedKeyRef key, Duration timeout) {
        var reply = new CompletableFuture<byte[]>();
        if (pending.putIfAbsent(correlationId, reply) != null)
            throw new IllegalStateException("A request already waits for the correlation id " + correlationId);
        var timed = reply.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return timed.handle((bytes, failure) -> {
            pending.remove(correlationId, reply);
            if (failure instanceof TimeoutException) throw new DistributedMapException.RequestTimedOut(key, timeout);
            if (failure != null) throw failure instanceof RuntimeException runtime ? runtime
                    : new CompletionException(failure);
            return bytes;
        });
    }

    /** Completes the request of a reply; a reply that no request waits for is dropped. */
    public boolean complete(String correlationId, byte[] reply) {
        var request = correlationId == null ? null : pending.remove(correlationId);
        return request != null && request.complete(reply);
    }

    /** Fails the request of a correlation id, when it was not sent. */
    public void fail(String correlationId, Throwable failure) {
        var request = correlationId == null ? null : pending.remove(correlationId);
        if (request != null) request.completeExceptionally(failure);
    }

    /** Fails every request that still waits, when the client closes. */
    public void failAll(Throwable failure) {
        for (var correlationId : pending.keySet()) fail(correlationId, failure);
    }

    /** How many requests wait. */
    public int size() {
        return pending.size();
    }
}

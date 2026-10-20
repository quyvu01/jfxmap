package io.github.quyvu01.jfxmap.wire;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.provider.QueryHandler;
import io.github.quyvu01.jfxmap.response.MapRequest;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The server side shared by the message transports: answers a received request with the {@link QueryHandler} of its
 * key, and always gives the bytes of a reply, a {@code Result} of FxMap: the answer, or the failure (an unknown key, a
 * request that is not JSON, a handler that failed or did not answer within the timeout, as FxMap's
 * {@code DefaultRequestTimeout}).
 */
public final class WireResponder {
    /** FxMap's {@code DefaultRequestTimeout}. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final System.Logger LOGGER = System.getLogger(WireResponder.class.getName());

    private final Map<DistributedKeyRef, QueryHandler> handlers;
    private final Duration timeout;

    /**
     * @param handlers the handlers of the keys this service owns
     * @param timeout  how long a handler may take before the request fails
     * @throws DistributedMapException.DistributedKeyAssignedToMultipleEntities when two handlers share a key
     */
    public WireResponder(Collection<? extends QueryHandler> handlers, Duration timeout) {
        var byKey = new LinkedHashMap<DistributedKeyRef, QueryHandler>();
        for (var handler : handlers) {
            var existing = byKey.putIfAbsent(handler.key(), handler);
            if (existing != null)
                throw new DistributedMapException.DistributedKeyAssignedToMultipleEntities(handler.key(),
                        List.of(existing.entityType(), handler.entityType()));
        }
        this.handlers = Map.copyOf(byKey);
        if (Objects.requireNonNull(timeout, "timeout").isNegative() || timeout.isZero())
            throw new IllegalArgumentException("The timeout must be positive.");
        this.timeout = timeout;
    }

    /** The keys this service answers for. */
    public Collection<DistributedKeyRef> keys() {
        return handlers.keySet();
    }

    /** The reply to a request given as JSON ({@code {"SelectorIds":[...],"Expressions":[...]}}). */
    public CompletableFuture<byte[]> answer(DistributedKeyRef key, byte[] requestJson, Map<String, String> headers) {
        MapRequest request;
        try {
            request = WireMessages.readRequest(requestJson);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(failure(key, e));
        }
        return answer(key, request, headers);
    }

    /** The reply to a request. */
    public CompletableFuture<byte[]> answer(DistributedKeyRef key, MapRequest request, Map<String, String> headers) {
        var handler = key == null ? null : handlers.get(key);
        if (handler == null)
            return CompletableFuture.completedFuture(failure(key, key == null
                    ? new IllegalArgumentException("The request names no distributed key.")
                    : new DistributedMapException.NoHandlerForDistributedKey(key)));
        CompletableFuture<byte[]> reply;
        try {
            reply = handler.handle(request, new MapContext(headers, null)).toCompletableFuture()
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .thenApply(WireMessages::writeSuccess);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(failure(key, e));
        }
        return reply.exceptionally(failure -> failure(key, failure));
    }

    private byte[] failure(DistributedKeyRef key, Throwable failure) {
        var cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null)
            cause = cause.getCause();
        if (cause instanceof TimeoutException)
            cause = new TimeoutException("Request timeout for " + key + " after " + timeout);
        LOGGER.log(System.Logger.Level.ERROR, "The request for the distributed key " + key + " failed.", cause);
        return WireMessages.writeFailure(cause);
    }
}

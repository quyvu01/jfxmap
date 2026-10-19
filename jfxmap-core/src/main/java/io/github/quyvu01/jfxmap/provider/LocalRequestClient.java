package io.github.quyvu01.jfxmap.provider;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link RequestClient} that answers in the same process, from registered {@link QueryHandler}s: for tests, and
 * for a service that is both the consumer and the owner of an entity.
 */
public final class LocalRequestClient implements RequestClient {
    private final Map<DistributedKeyRef, QueryHandler> handlers = new ConcurrentHashMap<>();

    public LocalRequestClient(QueryHandler... handlers) {
        for (var handler : handlers) register(handler);
    }

    /**
     * Registers a handler.
     *
     * @throws DistributedMapException.DistributedKeyAssignedToMultipleEntities when its key already has a handler
     */
    public LocalRequestClient register(QueryHandler handler) {
        var existing = handlers.putIfAbsent(handler.key(), handler);
        if (existing != null)
            throw new DistributedMapException.DistributedKeyAssignedToMultipleEntities(handler.key(),
                    List.of(existing.entityType(), handler.entityType()));
        return this;
    }

    @Override
    public CompletionStage<ItemsResponse> request(DistributedKeyRef key, MapRequest request, MapContext context) {
        var handler = handlers.get(key);
        if (handler == null)
            return CompletableFuture.failedFuture(new DistributedMapException.NoHandlerForDistributedKey(key));
        return handler.handle(request, context);
    }
}

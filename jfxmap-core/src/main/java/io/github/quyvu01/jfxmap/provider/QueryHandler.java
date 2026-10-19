package io.github.quyvu01.jfxmap.provider;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;

import java.util.concurrent.CompletionStage;

/**
 * Answers the requests for one distributed key, on the service that owns the entity (FxMap's
 * {@code IQueryOfHandler}). A data provider (JPA, MongoDB...) implements it; {@link InMemoryQueryHandler} answers
 * from objects in memory.
 */
public interface QueryHandler {
    DistributedKeyRef key();

    Class<?> entityType();

    CompletionStage<ItemsResponse> handle(MapRequest request, MapContext context);
}

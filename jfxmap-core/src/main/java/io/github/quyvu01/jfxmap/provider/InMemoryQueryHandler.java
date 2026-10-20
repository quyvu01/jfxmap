package io.github.quyvu01.jfxmap.provider;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.internal.BeanIntrospector;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;

import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Answers requests from entities held in memory, as configured by an {@link EntityConfigureOf}: for tests, samples,
 * and services whose data fits in memory. The expressions are evaluated by an {@link EntityExpressionEvaluator}, so
 * the whole FxMap expression language is supported.
 *
 * @param <T> the entity type
 */
public final class InMemoryQueryHandler<T> implements QueryHandler {
    private final EntityConfigureOf<T> config;
    private final Supplier<? extends Collection<? extends T>> entities;
    private final EntityExpressionEvaluator<T> evaluator;

    /**
     * @param config   the entity configuration
     * @param entities gives the entities on each request
     * @param clock    gives today for {@code :daysAgo}
     */
    public InMemoryQueryHandler(EntityConfigureOf<T> config, Supplier<? extends Collection<? extends T>> entities,
                                Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.evaluator = new EntityExpressionEvaluator<>(config, clock);
    }

    public InMemoryQueryHandler(EntityConfigureOf<T> config, Supplier<? extends Collection<? extends T>> entities) {
        this(config, entities, Clock.systemDefaultZone());
    }

    public InMemoryQueryHandler(EntityConfigureOf<T> config, Collection<? extends T> entities) {
        this(config, () -> entities);
    }

    @Override
    public DistributedKeyRef key() {
        return config.key();
    }

    @Override
    public Class<?> entityType() {
        return config.entityType();
    }

    @Override
    public CompletionStage<ItemsResponse> handle(MapRequest request, MapContext context) {
        try {
            return CompletableFuture.completedFuture(answer(request));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private ItemsResponse answer(MapRequest request) {
        var idProperty = BeanIntrospector.property(config.entityType(), config.idProperty());
        var byId = new HashMap<String, T>();
        for (T entity : entities.get()) {
            var id = idProperty.get(entity);
            if (id != null) byId.putIfAbsent(id.toString(), entity);
        }
        return evaluator.answer(request, byId::get);
    }
}

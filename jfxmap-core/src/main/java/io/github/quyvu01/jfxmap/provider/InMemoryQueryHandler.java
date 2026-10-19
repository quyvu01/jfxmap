package io.github.quyvu01.jfxmap.provider;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.expressions.evaluation.BeanPropertyAccessor;
import io.github.quyvu01.jfxmap.expressions.evaluation.ExpressionEvaluationException;
import io.github.quyvu01.jfxmap.expressions.evaluation.ExpressionEvaluator;
import io.github.quyvu01.jfxmap.expressions.evaluation.PropertyAccessor;
import io.github.quyvu01.jfxmap.expressions.nodes.ExpressionNode;
import io.github.quyvu01.jfxmap.expressions.parsing.ExpressionParseException;
import io.github.quyvu01.jfxmap.expressions.parsing.ExpressionParser;
import io.github.quyvu01.jfxmap.expressions.tokens.ExpressionTokenizeException;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.internal.BeanIntrospector;
import io.github.quyvu01.jfxmap.internal.Json;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ExpressionError;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Answers requests from entities held in memory, as configured by an {@link EntityConfigureOf}: for tests, samples,
 * and services whose data fits in memory.
 *
 * <p>It evaluates the whole FxMap expression language with the {@link ExpressionEvaluator} of jfxmap-expression:
 * property paths ({@code Country?.Name}), functions ({@code Name:upper}), filters, aggregations
 * ({@code Orders:sum(Total)}), projections ({@code {Id, Name}}) and the rest. A {@code null} expression gives the
 * default property. An exposed name ({@code UserEmail}) stands for its property on the entity type, wherever the
 * entity appears in the expression. An expression that does not parse or cannot be evaluated on one of the entities
 * is reported as an {@link ExpressionError}; the other expressions of the request are still answered.
 *
 * @param <T> the entity type
 */
public final class InMemoryQueryHandler<T> implements QueryHandler {
    private final EntityConfigureOf<T> config;
    private final Supplier<? extends Collection<? extends T>> entities;
    private final ExpressionEvaluator evaluator;

    /**
     * @param config   the entity configuration
     * @param entities gives the entities on each request
     * @param clock    gives today for {@code :daysAgo}
     */
    public InMemoryQueryHandler(EntityConfigureOf<T> config, Supplier<? extends Collection<? extends T>> entities,
                                Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.entities = Objects.requireNonNull(entities, "entities");
        // Fail now on an incomplete configuration rather than on the first request
        config.key();
        this.evaluator = new ExpressionEvaluator(
                new ExposedNameAccessor(config.entityType(), config.exposedNames(), new BeanPropertyAccessor()),
                Objects.requireNonNull(clock, "clock"));
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

        var errors = new LinkedHashMap<String, ExpressionError>();
        // Each expression is parsed once per request; null stands for the default property
        var parsed = new HashMap<String, ExpressionNode>();
        for (var expression : request.expressions()) {
            if (expression == null) {
                if (config.defaultProperty() == null)
                    errors.put(null, new ExpressionError(null, "%s has no default property: give the rule an expression."
                            .formatted(config.entityType().getSimpleName())));
                continue;
            }
            try {
                parsed.put(expression, ExpressionParser.parse(expression));
            } catch (ExpressionTokenizeException | ExpressionParseException e) {
                errors.put(expression, new ExpressionError(expression, e.getMessage()));
            }
        }

        var items = new ArrayList<DataResponse>();
        for (var id : request.selectorIds()) {
            var entity = byId.get(id);
            if (entity == null) continue;
            var values = new ArrayList<ValueResponse>(request.expressions().size());
            for (var expression : request.expressions()) {
                if (errors.containsKey(expression)) continue;
                try {
                    var value = expression == null
                            ? BeanIntrospector.property(config.entityType(), config.defaultProperty()).get(entity)
                            : evaluator.evaluate(parsed.get(expression), entity);
                    values.add(new ValueResponse(expression, value == null ? null : Json.write(value)));
                } catch (ExpressionEvaluationException e) {
                    errors.put(expression, new ExpressionError(expression, e.getMessage()));
                }
            }
            items.add(new DataResponse(id, values));
        }
        // An expression that failed on a later entity is dropped from the earlier ones too
        if (!errors.isEmpty())
            items.replaceAll(item -> new DataResponse(item.id(), item.values().stream()
                    .filter(v -> !errors.containsKey(v.expression())).toList()));
        return new ItemsResponse(items, List.copyOf(errors.values()));
    }

    /**
     * Resolves the exposed names of the entity type to their properties, as FxMap's TypeAccessor does, and the other
     * names as the delegate does. The property names stay usable next to their exposed names.
     */
    private record ExposedNameAccessor(Class<?> entityType, Map<String, String> exposedNames,
                                       PropertyAccessor delegate) implements PropertyAccessor {
        @Override
        public Property find(Class<?> type, String name) {
            if (entityType.isAssignableFrom(type)) {
                var property = exposedNames.get(name);
                if (property != null) return delegate.find(type, property);
            }
            return delegate.find(type, name);
        }
    }
}

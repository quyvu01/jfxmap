package io.github.quyvu01.jfxmap.provider;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.internal.BeanIntrospector;
import io.github.quyvu01.jfxmap.internal.Json;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ExpressionError;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Answers requests from entities held in memory, as configured by an {@link EntityConfigureOf}: for tests, samples,
 * and services whose data fits in memory.
 *
 * <p>It evaluates property paths only: the default property ({@code null} expression), a property or exposed name
 * ({@code Name}, {@code UserEmail}) and navigations ({@code Country.Name}, {@code Country?.Name}). The rest of the
 * FxMap expression language (functions, filters, aggregations, projections) is reported as an
 * {@link ExpressionError} for that expression; the other expressions of the request are still answered.
 *
 * @param <T> the entity type
 */
public final class InMemoryQueryHandler<T> implements QueryHandler {
    private static final Pattern PROPERTY_PATH =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*\\??(\\.[A-Za-z_][A-Za-z0-9_]*\\??)*$");

    private final EntityConfigureOf<T> config;
    private final Supplier<? extends Collection<? extends T>> entities;

    public InMemoryQueryHandler(EntityConfigureOf<T> config, Supplier<? extends Collection<? extends T>> entities) {
        this.config = Objects.requireNonNull(config, "config");
        this.entities = Objects.requireNonNull(entities, "entities");
        // Fail now on an incomplete configuration rather than on the first request
        config.key();
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
        var items = new ArrayList<DataResponse>();
        for (var id : request.selectorIds()) {
            var entity = byId.get(id);
            if (entity == null) continue;
            var values = new ArrayList<ValueResponse>(request.expressions().size());
            for (var expression : request.expressions()) {
                if (errors.containsKey(expression)) continue;
                try {
                    var value = evaluate(entity, expression);
                    values.add(new ValueResponse(expression, value == null ? null : Json.write(value)));
                } catch (UnsupportedExpression e) {
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

    private Object evaluate(T entity, String expression) {
        if (expression == null) {
            if (config.defaultProperty() == null)
                throw new UnsupportedExpression("%s has no default property: give the rule an expression."
                        .formatted(config.entityType().getSimpleName()));
            return read(entity, config.defaultProperty(), null);
        }
        var trimmed = expression.strip();
        if (!PROPERTY_PATH.matcher(trimmed).matches())
            throw new UnsupportedExpression(("'%s' is not a property path; the in-memory handler does not evaluate "
                    + "functions, filters, aggregations or projections.").formatted(expression));

        var segments = trimmed.split("\\.");
        Object current = entity;
        for (int i = 0; i < segments.length; i++) {
            if (current == null) return null;
            var name = segments[i].endsWith("?") ? segments[i].substring(0, segments[i].length() - 1) : segments[i];
            if (i == 0) name = config.exposedNames().getOrDefault(name, name);
            current = read(current, name, expression);
        }
        return current;
    }

    // FxMap expressions name properties as .NET does (Name, Country); Java properties are camelCase (name, country).
    private static Object read(Object target, String name, String expression) {
        var properties = BeanIntrospector.properties(target.getClass());
        var property = properties.get(name);
        if (property == null) {
            property = properties.values().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst()
                    .orElseThrow(() -> new UnsupportedExpression("'%s': %s has no property '%s'."
                            .formatted(expression, target.getClass().getSimpleName(), name)));
        }
        return property.get(target);
    }

    private static final class UnsupportedExpression extends RuntimeException {
        UnsupportedExpression(String message) {
            super(message, null, false, false);
        }
    }
}

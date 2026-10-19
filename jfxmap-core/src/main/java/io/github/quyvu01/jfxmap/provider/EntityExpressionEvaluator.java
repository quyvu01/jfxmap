package io.github.quyvu01.jfxmap.provider;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Answers a {@link MapRequest} from entities already loaded, as configured by an {@link EntityConfigureOf}. The
 * {@link InMemoryQueryHandler} and the data providers (jfxmap-jpa) share it, so that every owner evaluates
 * expressions the same way.
 *
 * <p>It evaluates the whole FxMap expression language with the {@link ExpressionEvaluator} of jfxmap-expression. A
 * {@code null} expression gives the default property. An exposed name ({@code UserEmail}) stands for its property on
 * the entity type, wherever the entity appears in the expression. An expression that does not parse or cannot be
 * evaluated on one of the entities is reported as an {@link ExpressionError}; the other expressions of the request
 * are still answered.
 *
 * @param <T> the entity type
 */
public final class EntityExpressionEvaluator<T> {
    private final EntityConfigureOf<T> config;
    private final ExpressionEvaluator evaluator;

    /**
     * @param config the entity configuration
     * @param clock  gives today for {@code :daysAgo}
     */
    public EntityExpressionEvaluator(EntityConfigureOf<T> config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        // Fail now on an incomplete configuration rather than on the first request
        config.key();
        this.evaluator = new ExpressionEvaluator(
                new ExposedNameAccessor(config.entityType(), config.exposedNames(), new BeanPropertyAccessor()),
                Objects.requireNonNull(clock, "clock"));
    }

    public EntityConfigureOf<T> config() {
        return config;
    }

    /**
     * Evaluates the expressions of the request on the entity of each requested id.
     *
     * @param request the request
     * @param lookup  gives the entity of a requested id, or {@code null} when there is none; the ids without an
     *                entity are left out of the response
     */
    public ItemsResponse answer(MapRequest request, Function<String, ? extends T> lookup) {
        Objects.requireNonNull(lookup, "lookup");
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
            T entity = lookup.apply(id);
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

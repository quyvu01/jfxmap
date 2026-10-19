package io.github.quyvu01.jfxmap.fluent;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.PropertyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.internal.PropertyNames;

import java.util.Objects;
import java.util.function.Consumer;

/** Declares the properties filled from one distributed key (FxMap's {@code .For(...)}). */
public final class PropertyRuleBuilder<T> {
    private final Class<T> modelType;
    private final DistributedKeyRef key;
    private final String selectorProperty;
    private final Consumer<MappingRule> sink;

    PropertyRuleBuilder(Class<T> modelType, DistributedKeyRef key, String selectorProperty,
                        Consumer<MappingRule> sink) {
        this.modelType = modelType;
        this.key = key;
        this.selectorProperty = selectorProperty;
        this.sink = sink;
    }

    /** Fills {@code target} with the default property of the entity. */
    public PropertyRuleBuilder<T> map(PropertyRef<T, ?> target) {
        return map(target, (String) null);
    }

    /** Fills {@code target} with {@code expression} evaluated on the entity, such as {@code "Country.Name"}. */
    public PropertyRuleBuilder<T> map(PropertyRef<T, ?> target, String expression) {
        sink.accept(new MappingRule(key, selectorProperty, nameOf(target), expression, null));
        return this;
    }

    /** Fills {@code target} with an expression chosen when the mapping runs. */
    public PropertyRuleBuilder<T> map(PropertyRef<T, ?> target, Consumer<ConditionalExpressionBuilder> conditional) {
        Objects.requireNonNull(conditional, "conditional");
        var builder = new ConditionalExpressionBuilder();
        conditional.accept(builder);
        sink.accept(new MappingRule(key, selectorProperty, nameOf(target), null, builder.build()));
        return this;
    }

    private String nameOf(PropertyRef<T, ?> target) {
        Objects.requireNonNull(target, "target");
        return PropertyNames.of(target).orElseThrow(() -> new DistributedMapException.InvalidProfileSelector(
                modelType, "map(...) needs a method reference such as Dto::getUserName, not a lambda."));
    }
}

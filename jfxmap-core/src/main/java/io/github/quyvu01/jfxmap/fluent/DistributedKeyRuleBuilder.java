package io.github.quyvu01.jfxmap.fluent;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.PropertyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.internal.PropertyNames;

import java.util.Objects;
import java.util.function.Consumer;

/** The step after {@code useDistributedKey(...)}: name the property that holds the id. */
public final class DistributedKeyRuleBuilder<T> {
    private final Class<T> modelType;
    private final DistributedKeyRef key;
    private final Consumer<MappingRule> sink;

    DistributedKeyRuleBuilder(Class<T> modelType, DistributedKeyRef key, Consumer<MappingRule> sink) {
        this.modelType = modelType;
        this.key = key;
        this.sink = sink;
    }

    /**
     * The property of the model that holds the id of the entity (the foreign key), such as {@code Dto::getUserId}.
     */
    public PropertyRuleBuilder<T> of(PropertyRef<T, ?> selector) {
        Objects.requireNonNull(selector, "selector");
        var name = PropertyNames.of(selector).orElseThrow(() -> new DistributedMapException.InvalidProfileSelector(
                modelType, "of(...) needs a method reference such as Dto::getUserId, not a lambda."));
        return new PropertyRuleBuilder<>(modelType, key, name, sink);
    }
}

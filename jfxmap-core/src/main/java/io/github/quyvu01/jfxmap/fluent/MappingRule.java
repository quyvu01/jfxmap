package io.github.quyvu01.jfxmap.fluent;

import io.github.quyvu01.jfxmap.DistributedKeyRef;

/**
 * One {@code map(...)} of a profile: fill {@code targetProperty} with {@code expression} evaluated on the entity
 * whose id is in {@code selectorProperty}.
 *
 * @param key              the distributed key of the entity
 * @param selectorProperty the property holding the id (the foreign key)
 * @param targetProperty   the property to fill
 * @param expression       the expression, or {@code null} for the default property of the entity
 * @param conditional      the expression chosen at mapping time, or {@code null} when {@code expression} is fixed
 */
public record MappingRule(DistributedKeyRef key, String selectorProperty, String targetProperty, String expression,
                          ConditionalExpression conditional) {
}

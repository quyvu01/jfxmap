package io.github.quyvu01.jfxmap.internal;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.fluent.ConditionalExpression;
import io.github.quyvu01.jfxmap.fluent.MappingRule;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the mapper needs to know about a profiled type, computed once: the rules with their resolved properties and
 * their order, and the other properties that may hold objects to map. Not part of the public API.
 *
 * @param modelType the profiled type
 * @param rules     the rules, each with the order it runs in
 * @param walks     the properties, other than rule targets and keys, that may hold objects with profiles
 */
public record ProfilePlan(Class<?> modelType, List<Rule> rules, List<BeanProperty> walks) {

    /**
     * A rule ready to run.
     *
     * @param order 0 when its key is a plain property of the model, n when its key is filled by a rule of order n-1
     */
    public record Rule(DistributedKeyRef key, BeanProperty selector, BeanProperty target, String expression,
                       ConditionalExpression conditional, int order) {
    }

    /** The rules of {@code targets}, and the rules that fill the keys they read, transitively. */
    public List<Rule> rulesFor(Set<String> targets) {
        var byTarget = new HashMap<String, Rule>();
        for (var rule : rules) byTarget.put(rule.target().name(), rule);
        var needed = new HashSet<Rule>();
        var pending = new ArrayList<Rule>();
        for (var target : targets) {
            var rule = byTarget.get(target);
            if (rule != null) pending.add(rule);
        }
        while (!pending.isEmpty()) {
            var rule = pending.removeLast();
            if (!needed.add(rule)) continue;
            var dependency = byTarget.get(rule.selector().name());
            if (dependency != null) pending.add(dependency);
        }
        return rules.stream().filter(needed::contains).toList();
    }

    public static ProfilePlan of(ProfileOf<?> profile) {
        var modelType = profile.modelType();
        var properties = BeanIntrospector.properties(modelType);
        var declared = profile.rules();

        var byTarget = new HashMap<String, MappingRule>();
        for (var rule : declared) byTarget.put(rule.targetProperty(), rule);

        var rules = new ArrayList<Rule>(declared.size());
        var withinKeys = new HashSet<String>();
        for (var rule : declared) {
            var selector = properties.get(rule.selectorProperty());
            if (selector == null)
                throw new DistributedMapException.InvalidProfileSelector(modelType,
                        "the key property '%s' cannot be read.".formatted(rule.selectorProperty()));
            var target = properties.get(rule.targetProperty());
            if (target == null || !target.isWritable())
                throw new DistributedMapException.InvalidProfileSelector(modelType,
                        "the property '%s' cannot be written: give it a setter or a non-final field."
                                .formatted(rule.targetProperty()));
            rules.add(new Rule(rule.key(), selector, target, rule.expression(), rule.conditional(),
                    orderOf(rule, byTarget, new HashSet<>())));
            withinKeys.add(rule.selectorProperty());
            withinKeys.add(rule.targetProperty());
        }

        var walks = properties.values().stream()
                .filter(p -> !withinKeys.contains(p.name()))
                .filter(p -> TypeClassifier.shouldWalk(p.genericType()))
                .toList();
        return new ProfilePlan(modelType, List.copyOf(rules), walks);
    }

    // The number of rules to run before this one: its key may be the target of another rule, whose key may be the
    // target of yet another... A cycle stops the chain.
    private static int orderOf(MappingRule rule, Map<String, MappingRule> byTarget, Set<String> visited) {
        if (!visited.add(rule.targetProperty())) return 0;
        var dependency = byTarget.get(rule.selectorProperty());
        if (dependency == null || dependency == rule) return 0;
        return 1 + orderOf(dependency, byTarget, visited);
    }
}

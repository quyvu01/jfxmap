package io.github.quyvu01.jfxmap;

import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.internal.Json;
import io.github.quyvu01.jfxmap.internal.ProfilePlan;
import io.github.quyvu01.jfxmap.internal.TypeClassifier;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The mapping engine, ported from FxMap's {@code DistributedMapper}:
 * <ol>
 *   <li>walk the object graph and collect every property that has a rule;</li>
 *   <li>by dependency order, send one request per distributed key with the distinct ids and expressions;</li>
 *   <li>write each value back to the properties whose key and expression it answers;</li>
 *   <li>repeat on the objects just written, up to the maximum nesting depth.</li>
 * </ol>
 */
final class DefaultDistributedMapper implements DistributedMapper {
    private static final System.Logger LOGGER = System.getLogger(DistributedMapper.class.getName());
    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

    private final Map<Class<?>, ProfilePlan> plans;
    private final Map<Class<?>, Optional<ProfilePlan>> plansByRuntimeType = new ConcurrentHashMap<>();
    private final RequestClient requestClient;
    private final boolean throwIfException;
    private final int maxNestingDepth;
    private final Function<Class<?>, Object> services;

    DefaultDistributedMapper(Map<Class<?>, ProfilePlan> plans, RequestClient requestClient, boolean throwIfException,
                             int maxNestingDepth, Function<Class<?>, Object> services) {
        this.plans = plans;
        this.requestClient = requestClient;
        this.throwIfException = throwIfException;
        this.maxNestingDepth = maxNestingDepth;
        this.services = services;
    }

    @Override
    public CompletableFuture<Void> mapAsync(Object value, Map<String, String> headers) {
        var context = new MapContext(headers, services);
        try {
            return mapLevel(value, context, 0);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<Void> mapPropertiesAsync(Collection<?> objects, Set<String> properties,
                                                      Map<String, String> headers) {
        var context = new MapContext(headers, services);
        try {
            var descriptors = new ArrayList<Descriptor>();
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            for (var object : objects) {
                if (object == null || !visited.add(object)) continue;
                var plan = planOf(object.getClass());
                if (plan == null) continue;
                for (var rule : plan.rulesFor(properties)) descriptors.add(new Descriptor(rule, object));
            }
            return mapOrders(descriptors, context);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public Map<Class<?>, Set<String>> mappedProperties() {
        var mapped = new LinkedHashMap<Class<?>, Set<String>>();
        plans.forEach((type, plan) -> {
            var targets = new LinkedHashSet<String>();
            for (var rule : plan.rules()) targets.add(rule.target().name());
            mapped.put(type, Collections.unmodifiableSet(targets));
        });
        return Collections.unmodifiableMap(mapped);
    }

    @Override
    public CompletableFuture<ItemsResponse> fetchAsync(DistributedKeyRef key, MapRequest request,
                                                       Map<String, String> headers) {
        if (requestClient == null) return CompletableFuture.failedFuture(new DistributedMapException.NoRequestClient());
        var ids = new LinkedHashSet<>(request.selectorIds());
        ids.remove(null);
        var distinct = new MapRequest(List.copyOf(ids), new ArrayList<>(new LinkedHashSet<>(request.expressions())));
        return send(key, distinct, new MapContext(headers, services));
    }

    private CompletableFuture<Void> mapLevel(Object root, MapContext context, int depth) {
        var descriptors = discover(root);
        // Nothing left to map (e.g. the next level only holds empty collections): done, whatever the depth.
        if (descriptors.isEmpty()) return DONE;
        if (depth >= maxNestingDepth) {
            if (throwIfException)
                return CompletableFuture.failedFuture(new DistributedMapException.MaxNestingDepthReached(maxNestingDepth));
            return DONE;
        }

        return mapOrders(descriptors, context).thenCompose(ignored -> {
            var next = new ArrayList<>();
            for (var descriptor : descriptors) {
                if (TypeClassifier.isLeaf(descriptor.rule.target().type())) continue;
                var value = descriptor.rule.target().get(descriptor.model);
                if (value != null) next.add(value);
            }
            return next.isEmpty() ? DONE : mapLevel(next, context, depth + 1);
        });
    }

    // The rules of one level of objects, by dependency order.
    private CompletableFuture<Void> mapOrders(List<Descriptor> descriptors, MapContext context) {
        var byOrder = new TreeMap<Integer, List<Descriptor>>();
        for (var descriptor : descriptors)
            byOrder.computeIfAbsent(descriptor.rule.order(), o -> new ArrayList<>()).add(descriptor);

        // An order reads the keys that the previous one wrote, so the orders run one after the other.
        var chain = DONE;
        for (var level : byOrder.values()) chain = chain.thenCompose(ignored -> mapOrder(level, context));
        return chain;
    }

    private CompletableFuture<Void> mapOrder(List<Descriptor> descriptors, MapContext context) {
        var byKey = new LinkedHashMap<DistributedKeyRef, List<Descriptor>>();
        for (var descriptor : descriptors)
            byKey.computeIfAbsent(descriptor.rule.key(), k -> new ArrayList<>()).add(descriptor);

        var fetches = new ArrayList<CompletableFuture<KeyResponse>>(byKey.size());
        byKey.forEach((key, group) -> fetches.add(fetchGroup(key, group, context)));
        return CompletableFuture.allOf(fetches.toArray(CompletableFuture[]::new))
                .thenAccept(ignored -> writeBack(descriptors, fetches.stream().map(CompletableFuture::join).toList()));
    }

    private CompletableFuture<KeyResponse> fetchGroup(DistributedKeyRef key, List<Descriptor> group,
                                                      MapContext context) {
        // Each distinct id once, whatever the number of objects that share it.
        var ids = new LinkedHashSet<String>();
        for (var descriptor : group) {
            var id = idOf(descriptor);
            if (id != null) ids.add(id);
        }

        var resolutions = new ArrayList<CompletableFuture<?>>();
        for (var descriptor : group) {
            if (descriptor.rule.conditional() == null) {
                descriptor.expression = descriptor.rule.expression();
                continue;
            }
            resolutions.add(descriptor.rule.conditional().resolve(context).toCompletableFuture()
                    .thenAccept(expression -> descriptor.expression = expression));
        }

        return CompletableFuture.allOf(resolutions.toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
            if (ids.isEmpty()) return CompletableFuture.completedFuture(new KeyResponse(key, ItemsResponse.empty()));
            var expressions = new LinkedHashSet<String>();
            for (var descriptor : group) expressions.add(descriptor.expression);
            return send(key, new MapRequest(List.copyOf(ids), new ArrayList<>(expressions)), context)
                    .thenApply(response -> new KeyResponse(key, response));
        });
    }

    private CompletableFuture<ItemsResponse> send(DistributedKeyRef key, MapRequest request, MapContext context) {
        CompletableFuture<ItemsResponse> sent;
        try {
            sent = requestClient.request(key, request, context).toCompletableFuture();
        } catch (RuntimeException e) {
            sent = CompletableFuture.failedFuture(e);
        }
        return sent.handle((response, failure) -> {
            if (failure == null) return response == null ? ItemsResponse.empty() : response;
            var cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            if (throwIfException) throw cause instanceof RuntimeException runtime
                    ? runtime : new CompletionException(cause);
            LOGGER.log(System.Logger.Level.WARNING,
                    "The request for the distributed key " + key + " failed; its properties are not mapped.", cause);
            return ItemsResponse.empty();
        });
    }

    private void writeBack(List<Descriptor> descriptors, List<KeyResponse> responses) {
        // The owner could not evaluate some expressions: throw with all of them, or log them and leave the properties
        // of these expressions alone.
        var failed = new HashSet<KeyAndExpression>();
        var rows = new HashMap<DistributedKeyRef, Map<String, DataResponse>>();
        for (var response : responses) {
            var errors = response.response.errors();
            if (!errors.isEmpty()) {
                if (throwIfException) throw new DistributedMapException.ExpressionsFailed(response.key, errors);
                for (var error : errors) {
                    failed.add(new KeyAndExpression(response.key, error.expression()));
                    LOGGER.log(System.Logger.Level.WARNING, "{0} (distributed key {1}); the properties of this "
                            + "expression are not mapped.", error.message(), response.key);
                }
            }
            var byId = rows.computeIfAbsent(response.key, k -> new HashMap<>());
            for (var item : response.response.items()) byId.putIfAbsent(item.id(), item);
        }

        // The keys are read before any value is written: a rule may fill a property that the key of another rule of
        // the same level reads.
        var ids = new ArrayList<String>(descriptors.size());
        for (var descriptor : descriptors) ids.add(idOf(descriptor));

        for (int i = 0; i < descriptors.size(); i++) {
            var descriptor = descriptors.get(i);
            var id = ids.get(i);
            if (id == null) continue;
            var key = descriptor.rule.key();
            if (failed.contains(new KeyAndExpression(key, descriptor.expression))) continue;
            var row = rows.getOrDefault(key, Map.of()).get(id);
            if (row == null) continue;
            var value = row.values().stream()
                    .filter(v -> Objects.equals(v.expression(), descriptor.expression))
                    .findFirst()
                    .map(ValueResponse::value)
                    .orElse(null);
            if (value == null) continue;
            var target = descriptor.rule.target();
            try {
                target.set(descriptor.model, Json.read(value, target.genericType()));
            } catch (RuntimeException e) {
                if (throwIfException) throw new DistributedMapException.ValueWriteFailed(target.owner(), target.name(), e);
                LOGGER.log(System.Logger.Level.WARNING, "The value of " + target + " could not be written.", e);
            }
        }
    }

    /**
     * Every property with a rule in the object graph below {@code root}. Iterative, so the depth of the graph is not
     * limited by the call stack; each object is visited once, whatever the number of paths leading to it.
     */
    private List<Descriptor> discover(Object root) {
        var descriptors = new ArrayList<Descriptor>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<Object>();
        pushIfWalkable(pending, root);

        while (!pending.isEmpty()) {
            var current = pending.pop();
            if (current instanceof Map<?, ?> map) {
                map.values().forEach(v -> pushIfWalkable(pending, v));
                continue;
            }
            if (current instanceof Iterable<?> iterable) {
                iterable.forEach(v -> pushIfWalkable(pending, v));
                continue;
            }
            if (current instanceof Object[] array) {
                for (var item : array) pushIfWalkable(pending, item);
                continue;
            }
            if (!visited.add(current)) continue;

            var plan = planOf(current.getClass());
            if (plan == null) continue;
            for (var rule : plan.rules()) descriptors.add(new Descriptor(rule, current));
            for (var walk : plan.walks()) pushIfWalkable(pending, walk.get(current));
        }
        return descriptors;
    }

    private static void pushIfWalkable(ArrayDeque<Object> pending, Object value) {
        if (value != null && !TypeClassifier.isLeaf(value.getClass())) pending.push(value);
    }

    // The profile of the type, or else of its nearest superclass that has one.
    private ProfilePlan planOf(Class<?> type) {
        return plansByRuntimeType.computeIfAbsent(type, t -> {
            for (var c = t; c != null; c = c.getSuperclass()) {
                var plan = plans.get(c);
                if (plan != null) return Optional.of(plan);
            }
            return Optional.empty();
        }).orElse(null);
    }

    private static String idOf(Descriptor descriptor) {
        var id = descriptor.rule.selector().get(descriptor.model);
        return id == null ? null : id.toString();
    }

    private static final class Descriptor {
        final ProfilePlan.Rule rule;
        final Object model;
        // The expression for this call: the rule's own, or the one its condition chose.
        volatile String expression;

        Descriptor(ProfilePlan.Rule rule, Object model) {
            this.rule = rule;
            this.model = model;
        }
    }

    private record KeyResponse(DistributedKeyRef key, ItemsResponse response) {
    }

    private record KeyAndExpression(DistributedKeyRef key, String expression) {
    }
}

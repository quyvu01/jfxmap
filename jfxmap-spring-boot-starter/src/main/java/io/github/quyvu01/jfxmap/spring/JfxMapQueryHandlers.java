package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.provider.QueryHandler;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The keys this application owns, each with its {@link QueryHandler}: the {@code QueryHandler} beans, then a handler
 * from the first {@link EntityQueryHandlerFactory} that holds the entity of each other {@link EntityConfigureOf} bean.
 */
public final class JfxMapQueryHandlers {
    private static final System.Logger LOGGER = System.getLogger(JfxMapQueryHandlers.class.getName());

    private final Map<DistributedKeyRef, QueryHandler> handlers;

    /**
     * @throws DistributedMapException.DistributedKeyAssignedToMultipleEntities when two handlers share a key
     */
    public JfxMapQueryHandlers(Collection<? extends QueryHandler> handlers, Collection<? extends EntityConfigureOf<?>> configs,
                               Collection<? extends EntityQueryHandlerFactory> factories) {
        var byKey = new LinkedHashMap<DistributedKeyRef, QueryHandler>();
        for (var handler : handlers) add(byKey, handler);
        for (var config : configs) {
            if (byKey.containsKey(config.key())) continue;
            var handler = factories.stream().map(factory -> factory.create(config))
                    .flatMap(Optional::stream).findFirst();
            if (handler.isPresent()) add(byKey, handler.get());
            else LOGGER.log(System.Logger.Level.WARNING, "No data provider holds " + config.entityType().getName()
                    + ": the distributed key " + config.key() + " is not answered. Add jfxmap-jpa with the entity in "
                    + "the persistence unit, or declare a QueryHandler bean.");
        }
        this.handlers = Map.copyOf(byKey);
    }

    private static void add(Map<DistributedKeyRef, QueryHandler> byKey, QueryHandler handler) {
        var existing = byKey.putIfAbsent(handler.key(), handler);
        if (existing != null)
            throw new DistributedMapException.DistributedKeyAssignedToMultipleEntities(handler.key(),
                    List.of(existing.entityType(), handler.entityType()));
    }

    /** The handler of a key, or {@code null} when this application does not own it. */
    public QueryHandler get(DistributedKeyRef key) {
        return handlers.get(key);
    }

    public Collection<QueryHandler> all() {
        return handlers.values();
    }
}

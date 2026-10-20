package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.provider.QueryHandler;

import java.util.Optional;

/**
 * Creates the query handler of an entity configuration, for a data provider (FxMap's {@code AddEntityFrameworkCore}).
 * The auto-configuration asks each factory bean in order, for each {@link EntityConfigureOf} bean that no
 * {@link QueryHandler} bean answers for.
 */
@FunctionalInterface
public interface EntityQueryHandlerFactory {
    /** The handler, or empty when this provider does not hold the entity. */
    Optional<QueryHandler> create(EntityConfigureOf<?> config);
}

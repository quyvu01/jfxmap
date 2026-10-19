package io.github.quyvu01.jfxmap.jpa;

import io.github.quyvu01.jfxmap.exception.DistributedMapException;

/** The failures of the JPA data provider. */
public abstract class JpaMapException extends DistributedMapException {
    protected JpaMapException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The entity type is not an entity of the persistence unit. */
    public static final class EntityNotManaged extends JpaMapException {
        public EntityNotManaged(Class<?> entityType, Throwable cause) {
            super("%s is not an entity of this persistence unit.".formatted(entityType.getName()), cause);
        }
    }

    /** The configured id is not an attribute of the entity. */
    public static final class IdAttributeNotFound extends JpaMapException {
        public IdAttributeNotFound(Class<?> entityType, String idProperty, Throwable cause) {
            super("%s has no persistent attribute '%s', which its configuration uses as the id."
                    .formatted(entityType.getName(), idProperty), cause);
        }
    }

    /** The id attribute has a type that requested ids cannot be converted to. */
    public static final class UnsupportedIdType extends JpaMapException {
        public UnsupportedIdType(Class<?> entityType, Class<?> idType) {
            super("The id of %s is a %s: jfxmap-jpa supports String, UUID, the integer types, BigInteger, BigDecimal and enums."
                    .formatted(entityType.getName(), idType.getName()), null);
        }
    }
}

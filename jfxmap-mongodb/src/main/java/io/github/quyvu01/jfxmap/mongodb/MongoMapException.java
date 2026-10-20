package io.github.quyvu01.jfxmap.mongodb;

import io.github.quyvu01.jfxmap.exception.DistributedMapException;

/** A MongoDB collection that cannot answer for an entity configuration. */
public abstract class MongoMapException extends DistributedMapException {
    protected MongoMapException(String message, Throwable cause) {
        super(message, cause);
    }

    public static final class IdPropertyNotFound extends MongoMapException {
        public IdPropertyNotFound(Class<?> entityType, String idProperty, Throwable cause) {
            super("The id property '%s' is not a readable property of %s.".formatted(idProperty, entityType.getName()),
                    cause);
        }
    }

    public static final class UnsupportedIdType extends MongoMapException {
        public UnsupportedIdType(Class<?> entityType, Class<?> idType) {
            super(("The id of %s is a %s: the MongoDB provider supports String, ObjectId, UUID, the integer types, "
                   + "BigInteger, BigDecimal and enums.").formatted(entityType.getName(), idType.getName()), null);
        }
    }
}

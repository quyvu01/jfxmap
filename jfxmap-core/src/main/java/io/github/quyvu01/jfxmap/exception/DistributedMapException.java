package io.github.quyvu01.jfxmap.exception;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.response.ExpressionError;

import java.util.List;
import java.util.stream.Collectors;

/** The base of every exception thrown by jfxmap. Each failure has its own subclass. */
public abstract class DistributedMapException extends RuntimeException {
    protected DistributedMapException(String message) {
        super(message);
    }

    protected DistributedMapException(String message, Throwable cause) {
        super(message, cause);
    }

    public static final class DistributedKeyNullOrEmpty extends DistributedMapException {
        public DistributedKeyNullOrEmpty() {
            super("The distributed key must not be null or empty.");
        }
    }

    public static final class InvalidDistributedKeyName extends DistributedMapException {
        public InvalidDistributedKeyName(String key) {
            super("The distributed key '%s' is not valid: it must start with a letter or an underscore and contain only letters, digits, underscores and dots."
                    .formatted(key));
        }
    }

    public static final class InvalidDistributedKeyConfiguration extends DistributedMapException {
        public InvalidDistributedKeyConfiguration(Class<?> configType) {
            super("%s must call useDistributedKey(...) exactly once.".formatted(configType.getName()));
        }
    }

    public static final class EntityIdNotConfigured extends DistributedMapException {
        public EntityIdNotConfigured(Class<?> entityType) {
            super("The entity %s has no id: call id(...) in configure().".formatted(entityType.getName()));
        }
    }

    public static final class InvalidEntitySelector extends DistributedMapException {
        public InvalidEntitySelector(Class<?> entityType, String selectorName, String reason) {
            super("The %s selector of %s is not valid: %s".formatted(selectorName, entityType.getName(), reason));
        }
    }

    public static final class InvalidProfileSelector extends DistributedMapException {
        public InvalidProfileSelector(Class<?> modelType, String reason) {
            super("A selector of the profile of %s is not valid: %s".formatted(modelType.getName(), reason));
        }
    }

    public static final class DuplicatedNameByExposedName extends DistributedMapException {
        public DuplicatedNameByExposedName(Class<?> entityType, String exposedName) {
            super("The exposed name '%s' is used more than once on %s.".formatted(exposedName, entityType.getName()));
        }
    }

    public static final class AmbiguousProfileConfiguration extends DistributedMapException {
        public AmbiguousProfileConfiguration(Class<?> modelType) {
            super("More than one profile is registered for %s.".formatted(modelType.getName()));
        }
    }

    public static final class DistributedKeyAssignedToMultipleEntities extends DistributedMapException {
        public DistributedKeyAssignedToMultipleEntities(DistributedKeyRef key, List<Class<?>> entityTypes) {
            super("The distributed key %s is assigned to several entities: %s."
                    .formatted(key, entityTypes.stream().map(Class::getName).collect(Collectors.joining(", "))));
        }
    }

    public static final class MaxNestingDepthReached extends DistributedMapException {
        public MaxNestingDepthReached(int maxNestingDepth) {
            super("The maximum nesting depth (%d) was reached while mapping.".formatted(maxNestingDepth));
        }
    }

    public static final class NoRequestClient extends DistributedMapException {
        public NoRequestClient() {
            super("No request client is configured: set one with FxMap.builder().requestClient(...).");
        }
    }

    public static final class NoHandlerForDistributedKey extends DistributedMapException {
        public NoHandlerForDistributedKey(DistributedKeyRef key) {
            super("No handler is registered for the distributed key %s.".formatted(key));
        }
    }

    public static final class ExpressionsFailed extends DistributedMapException {
        private final transient List<ExpressionError> errors;

        public ExpressionsFailed(DistributedKeyRef key, List<ExpressionError> errors) {
            super("The owner of the distributed key %s could not evaluate: %s".formatted(key,
                    errors.stream().map(e -> "'%s' (%s)".formatted(e.expression(), e.message()))
                            .collect(Collectors.joining(", "))));
            this.errors = List.copyOf(errors);
        }

        public List<ExpressionError> errors() {
            return errors;
        }
    }

    public static final class ValueWriteFailed extends DistributedMapException {
        public ValueWriteFailed(Class<?> modelType, String property, Throwable cause) {
            super("The value of %s.%s could not be written: %s".formatted(modelType.getName(), property,
                    cause.getMessage()), cause);
        }
    }
}

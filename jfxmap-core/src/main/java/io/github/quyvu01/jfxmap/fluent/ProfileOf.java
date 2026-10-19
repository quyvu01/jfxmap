package io.github.quyvu01.jfxmap.fluent;

import io.github.quyvu01.jfxmap.DistributedKey;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.internal.TypeTokens;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Declares how the properties of a DTO are filled from distributed keys (consumer side).
 *
 * <pre>{@code
 * public class UserSummaryProfile extends ProfileOf<UserSummaryDto> {
 *     @Override
 *     protected void configure() {
 *         useDistributedKey(UserOf.class)
 *                 .of(UserSummaryDto::getUserId)
 *                 .map(UserSummaryDto::getUserName)                    // the default property of the entity
 *                 .map(UserSummaryDto::getUserEmail, "UserEmail")      // an exposed name
 *                 .map(UserSummaryDto::getCountryName, "Country.Name"); // a navigation
 *     }
 * }
 * }</pre>
 *
 * A rule may read a key that another rule fills ({@code of(Dto::getProvinceId)} after
 * {@code map(Dto::getProvinceId, ...)}): the rules are ordered by these dependencies, whatever their order in
 * {@link #configure()}.
 *
 * @param <T> the DTO type
 */
public abstract class ProfileOf<T> {
    private final Class<T> modelType;
    private List<MappingRule> rules;

    @SuppressWarnings("unchecked")
    protected ProfileOf() {
        this.modelType = (Class<T>) TypeTokens.typeArgument(getClass(), ProfileOf.class);
    }

    /** For a profile whose model type cannot be read from the class declaration. */
    protected ProfileOf(Class<T> modelType) {
        this.modelType = Objects.requireNonNull(modelType, "modelType");
    }

    /** Declare the mapping rules with {@link #useDistributedKey(Class)} or {@link #useDistributedKey(String)}. */
    protected abstract void configure();

    /** Starts the rules of an entity identified by a shared key type. */
    protected final DistributedKeyRuleBuilder<T> useDistributedKey(Class<? extends DistributedKey> keyType) {
        return new DistributedKeyRuleBuilder<>(modelType, DistributedKeyRef.of(keyType), this::add);
    }

    /** Starts the rules of an entity identified by a string key, when the services share no key type. */
    protected final DistributedKeyRuleBuilder<T> useDistributedKey(String key) {
        return new DistributedKeyRuleBuilder<>(modelType, DistributedKeyRef.of(key), this::add);
    }

    public final Class<T> modelType() {
        return modelType;
    }

    /** The rules declared in {@link #configure()}, which runs once, on the first call. */
    public final synchronized List<MappingRule> rules() {
        if (rules == null) {
            var collected = new ArrayList<MappingRule>();
            rules = collected;
            configure();
            rules = List.copyOf(collected);
        }
        return rules;
    }

    private void add(MappingRule rule) {
        if (!(rules instanceof ArrayList<MappingRule> collecting))
            throw new IllegalStateException("Rules can only be declared inside configure().");
        // The last rule for a property wins, as in FxMap
        collecting.removeIf(r -> r.targetProperty().equals(rule.targetProperty()));
        collecting.add(rule);
    }
}

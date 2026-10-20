package io.github.quyvu01.jfxmap;

import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Fills the properties declared by the profiles, on an object, a collection or a whole object graph.
 * Created with {@link FxMap#builder()}.
 *
 * <p>The ids of every object are batched: one request per distributed key and dependency level, whatever the number
 * of objects, carrying each distinct id once and each distinct expression once.
 */
public interface DistributedMapper {

    /** Maps {@code value} (an object, an array, an {@link Iterable} or a {@link Map} of them). */
    default CompletableFuture<Void> mapAsync(Object value) {
        return mapAsync(value, Map.of());
    }

    /** Maps {@code value}, sending {@code headers} with every request. */
    CompletableFuture<Void> mapAsync(Object value, Map<String, String> headers);

    /** Maps {@code value} and waits for the result. Failures are thrown as they are, not wrapped. */
    default void map(Object value) {
        map(value, Map.of());
    }

    /** Maps {@code value} with {@code headers} and waits for the result. */
    default void map(Object value, Map<String, String> headers) {
        try {
            mapAsync(value, headers).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw e;
        }
    }

    /**
     * Maps only {@code properties} of {@code objects}, with the rules whose keys they read: the objects the values
     * hold are left alone. A property without a rule on the profile of an object is ignored. This is what a GraphQL
     * field needs: only the selected fields, the nested objects being resolved by their own fields.
     *
     * <p>By default, maps every property of the objects.
     */
    default CompletableFuture<Void> mapPropertiesAsync(Collection<?> objects, Set<String> properties,
                                                       Map<String, String> headers) {
        return mapAsync(objects, headers);
    }

    /** The properties that have a rule, by profiled type. */
    default Map<Class<?>, Set<String>> mappedProperties() {
        return Map.of();
    }

    /** Sends one request to the owner of {@code key}, without a profile. */
    CompletableFuture<ItemsResponse> fetchAsync(DistributedKeyRef key, MapRequest request,
                                                Map<String, String> headers);
}

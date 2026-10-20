package io.github.quyvu01.jfxmap.graphql;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.TypeRuntimeWiring;
import io.github.quyvu01.jfxmap.DistributedMapper;
import io.github.quyvu01.jfxmap.internal.BeanIntrospector;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves the fields that the profiles declare through jfxmap, in graphql-java (FxMap.HotChocolate's counterpart):
 * a GraphQL object type named after a profiled class (its simple name, or the name given with
 * {@link Builder#typeName}) gets a data fetcher for each of its fields named after a property with a rule. The fields
 * of every object of a request are batched by a {@link DataLoader}: one call of
 * {@link DistributedMapper#mapPropertiesAsync} per level of the query, which sends one request per distributed key
 * with only the selected fields, whatever the number of objects.
 *
 * <pre>{@code
 * JfxMapGraphQl jfxMap = JfxMapGraphQl.builder(mapper).build();
 * RuntimeWiring.Builder wiring = RuntimeWiring.newRuntimeWiring().type(...);
 * jfxMap.configure(wiring);
 * // or, for a schema built in code: schema = jfxMap.instrument(schema);
 * }</pre>
 *
 * <p>The batching needs a {@link org.dataloader.DataLoaderRegistry} in the execution input, where the data loader
 * {@value #DATA_LOADER} is created on the first field, unless the application registered one that calls
 * {@link #load}; without a registry each field is mapped on its own. The headers of the requests are read from the
 * {@link graphql.GraphQLContext} under {@value #HEADERS}, a {@code Map<String, String>}.
 */
public final class JfxMapGraphQl {
    /** The name of the data loader in the registry of a request. */
    public static final String DATA_LOADER = "jfxmap";
    /** The key of the headers in the {@link graphql.GraphQLContext}: a {@code Map<String, String>}. */
    public static final String HEADERS = "jfxmap.headers";

    private final DistributedMapper mapper;
    private final Map<String, Set<String>> fieldsByType;

    private JfxMapGraphQl(Builder builder) {
        this.mapper = builder.mapper;
        var fields = new LinkedHashMap<String, Set<String>>();
        builder.mapper.mappedProperties().forEach((type, properties) -> fields.merge(
                builder.typeNames.getOrDefault(type, type.getSimpleName()), properties, (a, b) -> {
                    var union = new LinkedHashSet<>(a);
                    union.addAll(b);
                    return union;
                }));
        this.fieldsByType = Collections.unmodifiableMap(fields);
    }

    public static Builder builder(DistributedMapper mapper) {
        return new Builder(mapper);
    }

    /** The fields resolved through jfxmap, by GraphQL type name. */
    public Map<String, Set<String>> fields() {
        return fieldsByType;
    }

    /** Wires the data fetchers of the mapped fields, for a schema built from SDL. */
    public void configure(RuntimeWiring.Builder wiring) {
        fieldsByType.forEach((type, fields) -> {
            var typeWiring = TypeRuntimeWiring.newTypeWiring(type);
            for (var field : fields) typeWiring.dataFetcher(field, dataFetcher(field));
            wiring.type(typeWiring);
        });
    }

    /** The schema with the data fetchers of the mapped fields that it has, for a schema built in code. */
    public GraphQLSchema instrument(GraphQLSchema schema) {
        return schema.transform(builder -> builder.codeRegistry(schema.getCodeRegistry().transform(registry ->
                fieldsByType.forEach((type, fields) -> {
                    if (!(schema.getType(type) instanceof GraphQLObjectType objectType)) return;
                    for (var field : fields)
                        if (objectType.getFieldDefinition(field) != null)
                            registry.dataFetcher(FieldCoordinates.coordinates(type, field), dataFetcher(field));
                }))));
    }

    /** The data fetcher of a mapped property: its value once the objects of the level are mapped. */
    public DataFetcher<CompletableFuture<Object>> dataFetcher(String property) {
        Objects.requireNonNull(property, "property");
        return environment -> {
            Object source = environment.getSource();
            if (source == null) return CompletableFuture.completedFuture(null);
            DataLoader<FieldKey, Object> loader;
            try {
                loader = environment.getDataLoaderRegistry()
                        .computeIfAbsent(DATA_LOADER, name -> newDataLoader(headers(environment)));
            } catch (RuntimeException e) {
                // No registry of its own (graphql-java's empty one): this field alone
                return mapper.mapPropertiesAsync(List.of(source), Set.of(property), headers(environment))
                        .thenApply(ignored -> read(source, property));
            }
            return loader.load(new FieldKey(source, property));
        };
    }

    private DataLoader<FieldKey, Object> newDataLoader(Map<String, String> headers) {
        return DataLoaderFactory.newDataLoader(keys -> load(keys, headers).thenApply(values -> {
            var ordered = new ArrayList<>(keys.size());
            for (var key : keys) ordered.add(values.get(key));
            return ordered;
        }));
    }

    /**
     * The values of fields, once their objects are mapped together: what the data loader of a request does, for a
     * framework that registers data loaders itself. A field whose value is null is missing from the map.
     */
    public CompletableFuture<Map<FieldKey, Object>> load(Collection<FieldKey> keys, Map<String, String> headers) {
        Set<Object> sources = Collections.newSetFromMap(new IdentityHashMap<>());
        var properties = new LinkedHashSet<String>();
        for (var key : keys) {
            sources.add(key.source());
            properties.add(key.property());
        }
        return mapper.mapPropertiesAsync(new ArrayList<>(sources), properties, headers == null ? Map.of() : headers)
                .thenApply(ignored -> {
                    var values = new HashMap<FieldKey, Object>();
                    for (var key : keys) {
                        var value = read(key.source(), key.property());
                        if (value != null) values.put(key, value);
                    }
                    return values;
                });
    }

    private static Map<String, String> headers(DataFetchingEnvironment environment) {
        var context = environment.getGraphQlContext();
        Map<String, String> headers = context == null ? null : context.get(HEADERS);
        return headers == null ? Map.of() : headers;
    }

    private static Object read(Object source, String property) {
        var accessor = BeanIntrospector.property(source.getClass(), property);
        return accessor == null ? null : accessor.get(source);
    }

    /** A field of an object: the object by identity, since mapping changes it. */
    public record FieldKey(Object source, String property) {
        public FieldKey {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(property, "property");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FieldKey key && key.source == source && key.property.equals(property);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(source) * 31 + property.hashCode();
        }
    }

    public static final class Builder {
        private final DistributedMapper mapper;
        private final Map<Class<?>, String> typeNames = new LinkedHashMap<>();

        private Builder(DistributedMapper mapper) {
            this.mapper = Objects.requireNonNull(mapper, "mapper");
        }

        /** The GraphQL type of a profiled class, when it is not named after the class. */
        public Builder typeName(Class<?> type, String typeName) {
            Objects.requireNonNull(type, "type");
            if (typeName == null || typeName.isBlank()) throw new IllegalArgumentException("The type name is blank.");
            typeNames.put(type, typeName);
            return this;
        }

        public JfxMapGraphQl build() {
            return new JfxMapGraphQl(this);
        }
    }
}

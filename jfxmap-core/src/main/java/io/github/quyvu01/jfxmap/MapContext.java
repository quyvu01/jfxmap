package io.github.quyvu01.jfxmap;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * What a mapping call carries with it: the headers that travel with each request, and a lookup for the services that
 * conditional expressions may need ({@code when(ctx -> ctx.service(FeatureFlags.class).isOn("x"))}).
 */
public final class MapContext {
    private static final Function<Class<?>, Object> NO_SERVICES = type -> null;

    private final Map<String, String> headers;
    private final Function<Class<?>, Object> services;

    public MapContext(Map<String, String> headers, Function<Class<?>, Object> services) {
        this.headers = Map.copyOf(Objects.requireNonNullElse(headers, Map.of()));
        this.services = Objects.requireNonNullElse(services, NO_SERVICES);
    }

    public static MapContext empty() {
        return new MapContext(Map.of(), NO_SERVICES);
    }

    /** The headers sent with every request of this mapping call. */
    public Map<String, String> headers() {
        return headers;
    }

    /** The service of the given type, or {@code null} when none is registered. */
    public <T> T service(Class<T> type) {
        return type.cast(services.apply(type));
    }
}

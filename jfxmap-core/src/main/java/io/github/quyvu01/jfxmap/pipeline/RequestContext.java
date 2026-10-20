package io.github.quyvu01.jfxmap.pipeline;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.response.MapRequest;

import java.util.Map;
import java.util.Objects;

/**
 * A request on its way through a pipeline (FxMap's {@code RequestContext}): to the transport on the client side, to
 * the {@link io.github.quyvu01.jfxmap.provider.QueryHandler} on the owner's side. It does not change: a behavior that
 * adds a header passes a new context to the next step.
 *
 * @param key     the distributed key asked for
 * @param request the ids and the expressions
 * @param context the headers and the services of the mapping call
 */
public record RequestContext(DistributedKeyRef key, MapRequest request, MapContext context) {
    public RequestContext {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
    }

    /** The headers that travel with the request. */
    public Map<String, String> headers() {
        return context.headers();
    }

    /** The same request with one more header, or a new value for it; a {@code null} value removes it. */
    public RequestContext withHeader(String name, String value) {
        return new RequestContext(key, request, context.withHeader(name, value));
    }

    /** The same context with other ids or expressions. */
    public RequestContext withRequest(MapRequest request) {
        return new RequestContext(key, request, context);
    }
}

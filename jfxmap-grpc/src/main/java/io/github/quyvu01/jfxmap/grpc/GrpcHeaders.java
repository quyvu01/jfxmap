package io.github.quyvu01.jfxmap.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

/** The headers of a mapping call, carried as gRPC metadata, as FxMap does. */
final class GrpcHeaders {
    static final Context.Key<Map<String, String>> HEADERS = Context.key("jfxmap-headers");

    private GrpcHeaders() {
    }

    /** The ASCII metadata of the headers. A name that gRPC does not accept, or a binary one, is left out. */
    static Metadata toMetadata(Map<String, String> headers) {
        var metadata = new Metadata();
        headers.forEach((name, value) -> {
            if (value == null || name.toLowerCase().endsWith(Metadata.BINARY_HEADER_SUFFIX)) return;
            try {
                metadata.put(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER), value);
            } catch (IllegalArgumentException e) {
                // Not a valid header name or value: it cannot travel
            }
        });
        return metadata;
    }

    /** The ASCII metadata as headers; the first value of a repeated name wins. */
    static Map<String, String> fromMetadata(Metadata metadata) {
        var headers = new LinkedHashMap<String, String>();
        for (var name : metadata.keys()) {
            if (name.endsWith(Metadata.BINARY_HEADER_SUFFIX)) continue;
            var values = metadata.getAll(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER));
            if (values != null && values.iterator().hasNext()) headers.putIfAbsent(name, values.iterator().next());
        }
        return headers;
    }

    /** Makes the headers of each call available to the service through {@link #HEADERS}. */
    static final class Interceptor implements ServerInterceptor {
        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata metadata,
                                                          ServerCallHandler<Q, R> next) {
            var context = Context.current().withValue(HEADERS, Map.copyOf(fromMetadata(metadata)));
            return Contexts.interceptCall(context, call, metadata, next);
        }
    }
}

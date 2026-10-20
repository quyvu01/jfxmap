package io.github.quyvu01.jfxmap.grpc;

import io.grpc.Metadata;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcHeadersTest {
    private static Metadata.Key<String> key(String name) {
        return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
    }

    @Test
    void writesAsciiHeadersAndSkipsTheOthers() {
        var headers = new LinkedHashMap<String, String>();
        headers.put("X-Tenant", "acme");
        headers.put("trace-bin", "x");
        headers.put("bad name", "x");
        headers.put("x-null", null);

        var metadata = GrpcHeaders.toMetadata(headers);

        assertThat(metadata.keys()).containsExactly("x-tenant");
        assertThat(metadata.get(key("x-tenant"))).isEqualTo("acme");
    }

    @Test
    void readsTheFirstValueOfEachAsciiHeader() {
        var metadata = new Metadata();
        metadata.put(key("x-tenant"), "acme");
        metadata.put(key("x-tenant"), "other");
        metadata.put(Metadata.Key.of("trace-bin", Metadata.BINARY_BYTE_MARSHALLER), new byte[]{1});

        assertThat(GrpcHeaders.fromMetadata(metadata)).isEqualTo(new HashMap<>(java.util.Map.of("x-tenant", "acme")));
    }
}

package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.RequestClient;
import io.github.quyvu01.jfxmap.provider.QueryHandler;

/**
 * Puts telemetry around the transport that sends the requests and around the handlers of the owned keys; with
 * jfxmap-opentelemetry, the auto-configuration declares one backed by the application's OpenTelemetry.
 */
public interface JfxMapInstrumentation {
    RequestClient instrument(RequestClient client);

    QueryHandler instrument(QueryHandler handler);
}

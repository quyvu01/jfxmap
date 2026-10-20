package io.github.quyvu01.jfxmap.opentelemetry;

import io.opentelemetry.api.common.AttributeKey;

import java.util.List;

/**
 * The names jfxmap gives its spans, metrics and attributes: those of FxMap's telemetry ({@code Constants.Telemetry}),
 * so the traces of Java and .NET services read alike.
 */
public final class TelemetryNames {
    /** The instrumentation scope, FxMap's {@code ActivitySource}. */
    public static final String INSTRUMENTATION_NAME = "FxMap";

    /** The span of a request sent to the owner of a key. */
    public static final String SPAN_REQUEST = "fxmap.request";
    /** The span of a request answered by the owner. */
    public static final String SPAN_PROCESS = "fxmap.process";

    /** The duration of the requests sent, in seconds. */
    public static final String METRIC_CLIENT_DURATION = "fxmap.client.request.duration";
    /** The duration of the requests answered, in seconds. */
    public static final String METRIC_SERVER_DURATION = "fxmap.server.request.duration";

    public static final AttributeKey<String> DISTRIBUTED_KEY = AttributeKey.stringKey("fxmap.distributed_key");
    public static final AttributeKey<String> TRANSPORT = AttributeKey.stringKey("fxmap.transport");
    public static final AttributeKey<String> VERSION = AttributeKey.stringKey("fxmap.version");
    public static final AttributeKey<List<String>> EXPRESSIONS = AttributeKey.stringArrayKey("fxmap.expressions");
    public static final AttributeKey<Long> SELECTOR_COUNT = AttributeKey.longKey("fxmap.selector_count");
    public static final AttributeKey<String> SELECTOR_IDS = AttributeKey.stringKey("fxmap.selector_ids");
    public static final AttributeKey<Long> ITEM_COUNT = AttributeKey.longKey("fxmap.item_count");
    public static final AttributeKey<Long> EXPRESSION_ERROR_COUNT =
            AttributeKey.longKey("fxmap.expression_error_count");
    public static final AttributeKey<String> MESSAGING_SYSTEM = AttributeKey.stringKey("messaging.system");
    public static final AttributeKey<String> DB_SYSTEM = AttributeKey.stringKey("db.system");
    public static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

    /** The W3C trace context headers, which FxMap sends and reads on every transport. */
    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";

    private TelemetryNames() {
    }
}

package io.github.quyvu01.jfxmap.wire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ExpressionError;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The JSON messages of the message transports (NATS, RabbitMQ, Kafka), as FxMap 5.1.1 writes them with
 * {@code System.Text.Json}: PascalCase names.
 *
 * <ul>
 *   <li>A request ({@code DistributedMapRequest}): {@code {"SelectorIds":["1"],"Expressions":[null,"Email"]}}.</li>
 *   <li>A reply ({@code Result}): {@code {"IsSuccess":true,"Data":{"Items":[{"Id":"1","Values":[{"Expression":null,
 *       "Value":"\"Ann\""}]}],"Errors":[]},"Fault":null}}, or {@code IsSuccess} false with a {@code Fault} that lists
 *       the exceptions, outermost first.</li>
 * </ul>
 *
 * Names are read without case, and missing or null members are read as empty, as .NET may leave nulls out.
 */
public final class WireMessages {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final int MAX_EXCEPTION_DEPTH = 16;

    private WireMessages() {
    }

    /** The JSON object of a request. */
    public static ObjectNode requestNode(MapRequest request) {
        var node = NODES.objectNode();
        var ids = node.putArray("SelectorIds");
        request.selectorIds().forEach(ids::add);
        var expressions = node.putArray("Expressions");
        request.expressions().forEach(expressions::add);
        return node;
    }

    public static byte[] writeRequest(MapRequest request) {
        return write(requestNode(request));
    }

    /** @throws IllegalArgumentException when the bytes are not a JSON request */
    public static MapRequest readRequest(byte[] json) {
        return readRequest(read(json));
    }

    /** @throws IllegalArgumentException when the node is not a request */
    public static MapRequest readRequest(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("The request is not a JSON object.");
        return new MapRequest(strings(member(node, "SelectorIds")), strings(member(node, "Expressions")));
    }

    /** The reply of a request that was answered. */
    public static byte[] writeSuccess(ItemsResponse response) {
        var node = NODES.objectNode();
        node.put("IsSuccess", true);
        node.set("Data", itemsNode(response));
        node.putNull("Fault");
        return write(node);
    }

    /** The reply of a request that failed: a {@code Fault} with the exception and its causes. */
    public static byte[] writeFailure(Throwable failure) {
        var node = NODES.objectNode();
        node.put("IsSuccess", false);
        node.putNull("Data");
        node.set("Fault", faultNode(unwrap(failure)));
        return write(node);
    }

    /**
     * The answer of a reply.
     *
     * @throws DistributedMapException.ReceivedFault when the owner failed
     * @throws IllegalArgumentException              when the bytes are not a reply
     */
    public static ItemsResponse readResult(byte[] json) {
        var node = read(json);
        if (!node.isObject()) throw new IllegalArgumentException("The reply is not a JSON object.");
        if (!member(node, "IsSuccess").asBoolean(false)) throw fault(member(node, "Fault"));
        return items(member(node, "Data"));
    }

    static ObjectNode itemsNode(ItemsResponse response) {
        var node = NODES.objectNode();
        var items = node.putArray("Items");
        for (var item : response.items()) {
            var itemNode = items.addObject();
            itemNode.put("Id", item.id());
            var values = itemNode.putArray("Values");
            for (var value : item.values()) {
                var valueNode = values.addObject();
                valueNode.put("Expression", value.expression());
                valueNode.put("Value", value.value());
            }
        }
        var errors = node.putArray("Errors");
        for (var error : response.errors()) {
            var errorNode = errors.addObject();
            errorNode.put("Expression", error.expression());
            errorNode.put("Message", error.message());
        }
        return node;
    }

    static ItemsResponse items(JsonNode node) {
        var items = new ArrayList<DataResponse>();
        for (var item : elements(member(node, "Items"))) {
            var values = new ArrayList<ValueResponse>();
            for (var value : elements(member(item, "Values")))
                values.add(new ValueResponse(text(member(value, "Expression")), text(member(value, "Value"))));
            items.add(new DataResponse(text(member(item, "Id")), values));
        }
        var errors = new ArrayList<ExpressionError>();
        for (var error : elements(member(node, "Errors")))
            errors.add(new ExpressionError(text(member(error, "Expression")), text(member(error, "Message"))));
        return new ItemsResponse(items, errors);
    }

    static ObjectNode faultNode(Throwable failure) {
        var node = NODES.objectNode();
        node.put("FaultId", UUID.randomUUID().toString());
        node.putNull("FaultedMessageId");
        node.put("Timestamp", Instant.now().toString());
        var exceptions = node.putArray("Exceptions");
        var current = failure;
        for (int depth = 0; current != null && depth < MAX_EXCEPTION_DEPTH; depth++, current = current.getCause()) {
            var exception = exceptions.addObject();
            exception.put("ExceptionType", current.getClass().getName());
            exception.put("Message", current.getMessage() == null ? current.getClass().getName() : current.getMessage());
            exception.put("StackTrace", stackTrace(current));
            exception.putNull("Source");
        }
        var host = node.putObject("Host");
        host.put("MachineName", machineName());
        host.put("ProcessName", "java");
        host.put("ProcessId", ProcessHandle.current().pid());
        host.put("Assembly", ManagementFactory.getRuntimeMXBean().getName());
        return node;
    }

    static DistributedMapException.ReceivedFault fault(JsonNode fault) {
        var exceptions = new ArrayList<DistributedMapException.ReceivedFault.ExceptionInfo>();
        for (var exception : elements(member(fault, "Exceptions")))
            exceptions.add(new DistributedMapException.ReceivedFault.ExceptionInfo(
                    text(member(exception, "ExceptionType")), text(member(exception, "Message")),
                    text(member(exception, "StackTrace"))));
        return new DistributedMapException.ReceivedFault(text(member(fault, "FaultId")), exceptions);
    }

    private static Throwable unwrap(Throwable failure) {
        var current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
               && current.getCause() != null)
            current = current.getCause();
        return current;
    }

    private static String stackTrace(Throwable failure) {
        var builder = new StringBuilder();
        for (var element : failure.getStackTrace()) builder.append("   at ").append(element).append('\n');
        return builder.toString().stripTrailing();
    }

    private static String machineName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    /** The member of an object, matched without case; a missing node when there is none. */
    static JsonNode member(JsonNode node, String name) {
        if (node == null || !node.isObject()) return MissingNode.getInstance();
        var exact = node.get(name);
        if (exact != null) return exact;
        for (var field : node.properties())
            if (field.getKey().equalsIgnoreCase(name)) return field.getValue();
        return MissingNode.getInstance();
    }

    private static List<JsonNode> elements(JsonNode node) {
        var elements = new ArrayList<JsonNode>();
        if (node != null && node.isArray()) node.forEach(elements::add);
        return elements;
    }

    private static List<String> strings(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException("Expected a JSON array of strings: " + node);
        var strings = new ArrayList<String>();
        for (var element : (ArrayNode) node) strings.add(text(element));
        return strings;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    public static byte[] write(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @throws IllegalArgumentException when the bytes are not JSON */
    public static JsonNode read(byte[] json) {
        if (json == null || json.length == 0) throw new IllegalArgumentException("The message is empty.");
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("The message is not JSON: " + e.getMessage(), e);
        }
    }
}

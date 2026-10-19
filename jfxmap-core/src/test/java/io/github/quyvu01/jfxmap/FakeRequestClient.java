package io.github.quyvu01.jfxmap;

import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Answers from a table of (key, id, expression) to JSON values, and records every request. */
final class FakeRequestClient implements RequestClient {
    record Sent(DistributedKeyRef key, MapRequest request, MapContext context) {
    }

    private final Map<DistributedKeyRef, Map<String, Map<String, String>>> values = new HashMap<>();
    final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());

    FakeRequestClient value(DistributedKeyRef key, Object id, String expression, String json) {
        values.computeIfAbsent(key, k -> new HashMap<>())
                .computeIfAbsent(id.toString(), i -> new HashMap<>())
                .put(expression, json);
        return this;
    }

    @Override
    public CompletionStage<ItemsResponse> request(DistributedKeyRef key, MapRequest request, MapContext context) {
        sent.add(new Sent(key, request, context));
        var byId = values.getOrDefault(key, Map.of());
        var items = new ArrayList<DataResponse>();
        for (var id : request.selectorIds()) {
            var row = byId.get(id);
            if (row == null) continue;
            var answered = new ArrayList<ValueResponse>();
            for (var expression : request.expressions())
                if (row.containsKey(expression)) answered.add(new ValueResponse(expression, row.get(expression)));
            items.add(new DataResponse(id, answered));
        }
        return CompletableFuture.completedFuture(new ItemsResponse(items));
    }
}

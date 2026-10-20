package io.github.quyvu01.jfxmap.benchmarks;

import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import io.github.quyvu01.jfxmap.wire.WireMessages;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/** The JSON that travels on every transport: a request for a batch of ids, and its reply. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class WireMessagesBenchmark {
    static final List<String> EXPRESSIONS = Arrays.asList(null, "UserEmail", "Country.Name",
            "Orders:where(Status = 'Done'):sum(Total)");

    /** The number of ids in the request, and of items in the reply. */
    @Param({"10", "1000"})
    public int ids;

    MapRequest request;
    ItemsResponse response;
    byte[] requestJson;
    byte[] responseJson;

    @Setup
    public void setUp() {
        var selectorIds = IntStream.range(0, ids).mapToObj(i -> "00000000-0000-0000-0000-" + String.format("%012d", i))
                .toList();
        request = new MapRequest(selectorIds, EXPRESSIONS);
        response = new ItemsResponse(selectorIds.stream().map(id -> new DataResponse(id, List.of(
                new ValueResponse(null, "\"User " + id + "\""),
                new ValueResponse("UserEmail", "\"user" + id + "@example.com\""),
                new ValueResponse("Country.Name", "\"Vietnam\""),
                new ValueResponse("Orders:where(Status = 'Done'):sum(Total)", "1234.5")))).toList());
        requestJson = WireMessages.writeRequest(request);
        responseJson = WireMessages.writeSuccess(response);
    }

    @Benchmark
    public byte[] writeRequest() {
        return WireMessages.writeRequest(request);
    }

    @Benchmark
    public MapRequest readRequest() {
        return WireMessages.readRequest(requestJson);
    }

    @Benchmark
    public byte[] writeSuccess() {
        return WireMessages.writeSuccess(response);
    }

    @Benchmark
    public ItemsResponse readResult() {
        return WireMessages.readResult(responseJson);
    }
}

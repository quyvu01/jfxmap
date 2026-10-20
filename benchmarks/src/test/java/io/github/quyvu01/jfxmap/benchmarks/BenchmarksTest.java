package io.github.quyvu01.jfxmap.benchmarks;

import io.github.quyvu01.jfxmap.expressions.nodes.ProjectionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.PropertyNode;
import io.github.quyvu01.jfxmap.wire.WireMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Each benchmark's code run once: what it measures works and gives the expected result. */
class BenchmarksTest {
    @ParameterizedTest
    @ValueSource(strings = {"simple", "medium", "complex"})
    void parsesEachExpression(String complexity) {
        var benchmark = new ExpressionParserBenchmark();
        benchmark.complexity = complexity;
        benchmark.setUp();

        assertThat(benchmark.parse()).isNotNull();
    }

    @Test
    void theExpressionsGrowInComplexity() {
        var benchmark = new ExpressionParserBenchmark();
        benchmark.complexity = "simple";
        benchmark.setUp();
        assertThat(benchmark.parse()).isInstanceOf(PropertyNode.class);

        benchmark.complexity = "complex";
        benchmark.setUp();
        assertThat(benchmark.parse()).isInstanceOf(ProjectionNode.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100, 10_000})
    void mapsEveryRuleOfEveryDto(int dtos) {
        var benchmark = new MappingBenchmark();
        benchmark.dtos = dtos;
        benchmark.setUp();

        var result = benchmark.map();

        assertThat(result).hasSize(dtos);
        var last = result.getLast();
        var user = last.getUserId();
        assertThat(last.getUserName()).isEqualTo("User " + user);
        assertThat(last.getUserEmail()).isEqualTo("user" + user + "@example.com");
        assertThat(last.getCountryName()).isEqualTo(user % 10 == 0 ? null : "Country " + user % 7);
        assertThat(last.getDoneTotal()).isEqualTo(user + 2.5);
        // The chained rule: the province id from the user, then its name from the province
        assertThat(last.getProvinceId()).isEqualTo(user % MappingBenchmark.PROVINCES);
        assertThat(last.getProvinceName()).isEqualTo("Province " + user % MappingBenchmark.PROVINCES);
        // The nested DTOs, mapped by their own profile
        assertThat(result).flatMap(MappingBenchmark.OrderDto::getLines)
                .allSatisfy(line -> assertThat(line.getProductName()).isEqualTo("Product " + line.getProductId()));
    }

    @Test
    void theWireMessagesReadBackToWhatWasWritten() {
        var benchmark = new WireMessagesBenchmark();
        benchmark.ids = 10;
        benchmark.setUp();

        assertThat(WireMessages.readRequest(benchmark.writeRequest())).isEqualTo(benchmark.request);
        assertThat(benchmark.readRequest()).isEqualTo(benchmark.request);
        assertThat(WireMessages.readResult(benchmark.writeSuccess())).isEqualTo(benchmark.response);
        assertThat(benchmark.readResult()).isEqualTo(benchmark.response);
        assertThat(benchmark.readResult().items()).hasSize(10);
    }
}

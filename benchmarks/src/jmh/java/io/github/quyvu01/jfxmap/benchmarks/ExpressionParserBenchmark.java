package io.github.quyvu01.jfxmap.benchmarks;

import io.github.quyvu01.jfxmap.expressions.nodes.ExpressionNode;
import io.github.quyvu01.jfxmap.expressions.parsing.ExpressionParser;
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

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Tokenizing and parsing an expression into its syntax tree, from a property name to a projection over a filter. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ExpressionParserBenchmark {
    static final Map<String, String> EXPRESSIONS = Map.of(
            "simple", "Name",
            "medium", "Country?.Name ?? 'Unknown'",
            "complex", "Orders:where(Status = 'Done' && Total > 100):orderBy(Total desc):limit(10)"
                    + ".{Id, Total, Customer.Name:upper as CustomerName, (Nickname ?? Name) as DisplayName}");

    @Param({"simple", "medium", "complex"})
    public String complexity;

    private String expression;

    @Setup
    public void setUp() {
        expression = EXPRESSIONS.get(complexity);
    }

    @Benchmark
    public ExpressionNode parse() {
        return ExpressionParser.parse(expression);
    }
}

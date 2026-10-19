package io.github.quyvu01.jfxmap.fluent;

import io.github.quyvu01.jfxmap.MapContext;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * An expression chosen when the mapping runs: {@code thenExpression} when the condition holds, else
 * {@code elseExpression}. Built with {@link ConditionalExpressionBuilder}.
 */
public final class ConditionalExpression {
    private final Function<MapContext, CompletionStage<Boolean>> condition;
    private final Function<MapContext, CompletionStage<String>> thenExpression;
    private final Function<MapContext, CompletionStage<String>> elseExpression;

    ConditionalExpression(Function<MapContext, CompletionStage<Boolean>> condition,
                          Function<MapContext, CompletionStage<String>> thenExpression,
                          Function<MapContext, CompletionStage<String>> elseExpression) {
        this.condition = Objects.requireNonNull(condition, "when(...) is required");
        this.thenExpression = Objects.requireNonNull(thenExpression, "expression(...) is required");
        this.elseExpression = Objects.requireNonNull(elseExpression, "otherwise(...) is required");
    }

    /** The expression to request for this mapping call. */
    public CompletionStage<String> resolve(MapContext context) {
        return condition.apply(context)
                .thenCompose(holds -> Boolean.TRUE.equals(holds)
                        ? thenExpression.apply(context)
                        : elseExpression.apply(context));
    }
}

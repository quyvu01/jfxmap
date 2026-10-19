package io.github.quyvu01.jfxmap.fluent;

import io.github.quyvu01.jfxmap.MapContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Builds a {@link ConditionalExpression}, the Java form of FxMap's {@code If(...).Expression(...).Else(...)}:
 *
 * <pre>{@code
 * .map(UserDto::getUserName, c -> c
 *         .when(ctx -> ctx.headers().containsKey("x-admin"))
 *         .expression("UserEmail:upper")
 *         .otherwise("Name"))
 * }</pre>
 *
 * Each step has an {@code ...Async} form taking a function that returns a {@link CompletionStage}.
 */
public final class ConditionalExpressionBuilder {
    private Function<MapContext, CompletionStage<Boolean>> condition;
    private Function<MapContext, CompletionStage<String>> thenExpression;
    private Function<MapContext, CompletionStage<String>> elseExpression;

    ConditionalExpressionBuilder() {
    }

    public ExpressionStep when(Predicate<MapContext> condition) {
        return whenAsync(ctx -> CompletableFuture.completedFuture(condition.test(ctx)));
    }

    public ExpressionStep whenAsync(Function<MapContext, CompletionStage<Boolean>> condition) {
        this.condition = condition;
        return new ExpressionStep();
    }

    ConditionalExpression build() {
        return new ConditionalExpression(condition, thenExpression, elseExpression);
    }

    /** The expression used when the condition holds. */
    public final class ExpressionStep {
        private ExpressionStep() {
        }

        public ElseStep expression(String expression) {
            return expressionAsync(ctx -> CompletableFuture.completedFuture(expression));
        }

        public ElseStep expression(Function<MapContext, String> expression) {
            return expressionAsync(ctx -> CompletableFuture.completedFuture(expression.apply(ctx)));
        }

        public ElseStep expressionAsync(Function<MapContext, CompletionStage<String>> expression) {
            thenExpression = expression;
            return new ElseStep();
        }
    }

    /** The expression used when the condition does not hold. */
    public final class ElseStep {
        private ElseStep() {
        }

        public void otherwise(String expression) {
            otherwiseAsync(ctx -> CompletableFuture.completedFuture(expression));
        }

        public void otherwise(Function<MapContext, String> expression) {
            otherwiseAsync(ctx -> CompletableFuture.completedFuture(expression.apply(ctx)));
        }

        public void otherwiseAsync(Function<MapContext, CompletionStage<String>> expression) {
            elseExpression = expression;
        }
    }
}

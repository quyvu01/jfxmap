package io.github.quyvu01.jfxmap.expressions.nodes;

import java.util.List;

/**
 * A function applied to a source: Name:count, Name:upper, Name:substring(0, 3), Items:orderBy(Name desc).
 *
 * @param source       the source of the function
 * @param functionName the function
 * @param argument     a property argument of an aggregate (sum(Total)), kept for compatibility; may be null
 * @param arguments    the arguments (substring(0, 3), replace('a', 'b'), the sort keys of orderBy); never null
 */
public record FunctionNode(ExpressionNode source, FunctionType functionName, String argument,
                           List<ExpressionNode> arguments) implements ExpressionNode {
    public FunctionNode {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    public FunctionNode(ExpressionNode source, FunctionType functionName) {
        this(source, functionName, null, List.of());
    }

    public FunctionNode(ExpressionNode source, FunctionType functionName, List<ExpressionNode> arguments) {
        this(source, functionName, null, arguments);
    }

    /**
     * All arguments as nodes: {@link #arguments()} when there are some, otherwise the legacy {@link #argument()}.
     */
    public List<ExpressionNode> getArguments() {
        if (!arguments.isEmpty()) return arguments;
        if (argument != null) return List.of(new PropertyNode(argument));
        return List.of();
    }

    @Override
    public <R, C> R accept(ExpressionNodeVisitor<R, C> visitor, C context) {
        return visitor.visitFunction(this, context);
    }
}

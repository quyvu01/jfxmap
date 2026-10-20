package io.github.quyvu01.jfxmap.validation;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;

/**
 * Finds the calls of the fluent API that take an expression, and reports the constant expressions that do not parse.
 */
final class ExpressionCallScanner extends TreePathScanner<Void, Void> {
    private static final String FLUENT_PACKAGE = "io.github.quyvu01.jfxmap.fluent.";

    private final Trees trees;
    private final Diagnostic.Kind kind;

    ExpressionCallScanner(Trees trees, Diagnostic.Kind kind) {
        this.trees = trees;
        this.kind = kind;
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
        // The receiver first, so that the calls of a chain are reported in source order
        super.visitMethodInvocation(node, unused);
        if (trees.getElement(getCurrentPath()) instanceof ExecutableElement method) {
            var index = expressionArgument(method);
            if (index >= 0 && index < node.getArguments().size())
                check(new TreePath(getCurrentPath(), node.getArguments().get(index)));
        }
        return null;
    }

    private void check(TreePath argument) {
        if (!(constant(argument) instanceof String expression) || expression.isBlank()) return;
        ExpressionSyntax.error(expression).ifPresent(error -> trees.printMessage(kind,
                ExpressionSyntax.DIAGNOSTIC_ID + ": " + ExpressionSyntax.message(expression, error),
                argument.getLeaf(), argument.getCompilationUnit()));
    }

    /**
     * The index of the expression argument of {@code method}, or -1 when it is not a method of the fluent API that
     * takes an expression string.
     */
    static int expressionArgument(ExecutableElement method) {
        if (!(method.getEnclosingElement() instanceof TypeElement owner)) return -1;
        var type = owner.getQualifiedName().toString();
        var name = method.getSimpleName().toString();
        int index;
        int arity;
        if (type.equals(FLUENT_PACKAGE + "PropertyRuleBuilder") && name.equals("map")) {
            index = 1;
            arity = 2;
        } else if ((type.equals(FLUENT_PACKAGE + "ConditionalExpressionBuilder.ExpressionStep")
                && name.equals("expression"))
                || (type.equals(FLUENT_PACKAGE + "ConditionalExpressionBuilder.ElseStep") && name.equals("otherwise"))) {
            index = 0;
            arity = 1;
        } else {
            return -1;
        }
        var parameters = method.getParameters();
        if (parameters.size() != arity) return -1;
        // The overloads taking a function or a consumer are skipped: only the String one has a static expression
        return parameters.get(index).asType().toString().equals("java.lang.String") ? index : -1;
    }

    /**
     * The value of a compile-time constant: a literal, a constant variable, or a string concatenation of them;
     * {@code null} when the expression is not one of these.
     */
    private Object constant(TreePath path) {
        Tree tree = path.getLeaf();
        if (tree instanceof LiteralTree literal) return literal.getValue();
        if (tree instanceof ParenthesizedTree parenthesized)
            return constant(new TreePath(path, parenthesized.getExpression()));
        if (tree.getKind() == Tree.Kind.IDENTIFIER || tree.getKind() == Tree.Kind.MEMBER_SELECT)
            return trees.getElement(path) instanceof VariableElement variable ? variable.getConstantValue() : null;
        if (tree.getKind() == Tree.Kind.PLUS && tree instanceof BinaryTree binary) {
            var left = constant(new TreePath(path, binary.getLeftOperand()));
            var right = constant(new TreePath(path, binary.getRightOperand()));
            if (left == null || right == null) return null;
            // Only a string concatenation is folded; a numeric sum is not an expression anyway
            return left instanceof String || right instanceof String ? String.valueOf(left) + right : null;
        }
        return null;
    }
}

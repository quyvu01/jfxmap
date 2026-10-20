package io.github.quyvu01.jfxmap.validation;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;

import javax.lang.model.element.NestingKind;
import javax.tools.Diagnostic;
import java.util.Locale;

/**
 * The javac plugin {@code JfxMapExpressions}, FxMap.Analyzers' {@code OFX001} for Java: reports an expression of the
 * fluent API that does not parse, when the class is compiled rather than when the mapping first runs.
 *
 * <p>It checks the expression argument of {@code PropertyRuleBuilder.map(target, expression)},
 * {@code ExpressionStep.expression(expression)} and {@code ElseStep.otherwise(expression)}, when the argument is a
 * string literal or a compile-time constant. A null, blank or non-constant argument is not checked. The methods are
 * matched by the method javac resolved, so a {@code map(x, "...")} of another class is never reported.
 *
 * <p>Put jfxmap-validation on the annotation processor path and pass {@code -Xplugin:JfxMapExpressions}. An invalid
 * expression is a compile error; {@code -Xplugin:"JfxMapExpressions warn"} reports it as a warning instead.
 */
public final class JfxMapExpressionsPlugin implements Plugin {
    /** The name given to {@code -Xplugin:}. */
    public static final String NAME = "JfxMapExpressions";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void init(JavacTask task, String... args) {
        var trees = Trees.instance(task);
        Diagnostic.Kind kind;
        try {
            kind = severity(args);
        } catch (IllegalArgumentException e) {
            // Thrown from here, it would crash javac with a stack trace: report it on the first file instead
            task.addTaskListener(new TaskListener() {
                private boolean reported;

                @Override
                public void finished(TaskEvent event) {
                    if (reported || event.getKind() != TaskEvent.Kind.PARSE || event.getCompilationUnit() == null)
                        return;
                    reported = true;
                    trees.printMessage(Diagnostic.Kind.ERROR, e.getMessage(), event.getCompilationUnit(),
                            event.getCompilationUnit());
                }
            });
            return;
        }
        task.addTaskListener(new TaskListener() {
            @Override
            public void finished(TaskEvent event) {
                // One event per top-level class once it is attributed; its nested classes are scanned with it
                if (event.getKind() != TaskEvent.Kind.ANALYZE) return;
                var type = event.getTypeElement();
                if (type == null || type.getNestingKind() != NestingKind.TOP_LEVEL) return;
                var path = trees.getPath(type);
                if (path != null) new ExpressionCallScanner(trees, kind).scan(path, null);
            }
        });
    }

    /** The diagnostic kind chosen by the plugin arguments: {@code error} (the default) or {@code warn}. */
    static Diagnostic.Kind severity(String... args) {
        var kind = Diagnostic.Kind.ERROR;
        for (var arg : args) {
            switch (arg.trim().toLowerCase(Locale.ROOT)) {
                case "" -> {
                }
                case "error" -> kind = Diagnostic.Kind.ERROR;
                case "warn", "warning" -> kind = Diagnostic.Kind.WARNING;
                default -> throw new IllegalArgumentException(
                        "%s: unknown argument '%s', expected 'error' or 'warn'.".formatted(NAME, arg));
            }
        }
        return kind;
    }
}

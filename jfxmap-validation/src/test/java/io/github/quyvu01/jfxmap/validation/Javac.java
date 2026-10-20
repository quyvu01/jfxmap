package io.github.quyvu01.jfxmap.validation;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compiles sources in memory with the system compiler, the plugin and jfxmap-core coming from the test classpath, as
 * a build would give them on the processor path and the classpath.
 */
final class Javac {
    private Javac() {
    }

    /**
     * The result of a compilation.
     *
     * @param success     whether javac succeeded
     * @param diagnostics everything javac reported
     */
    record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        /** The OFX001 diagnostics of {@code kind}. */
        List<Diagnostic<? extends JavaFileObject>> reported(Diagnostic.Kind kind) {
            return diagnostics.stream()
                    .filter(d -> d.getKind() == kind && d.getMessage(null).startsWith("OFX001"))
                    .toList();
        }

        List<String> messages(Diagnostic.Kind kind) {
            return reported(kind).stream().map(d -> d.getMessage(null)).toList();
        }

        /** Every error, OFX001 or not, to show when a compilation fails unexpectedly. */
        List<String> errors() {
            return diagnostics.stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> d.getLineNumber() + ": " + d.getMessage(null)).toList();
        }
    }

    /** Compiles {@code sources} (file name to content) with {@code -Xplugin:<plugin>}, or without it when null. */
    static Result compile(String plugin, Map<String, String> sources) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();
        var classpath = System.getProperty("java.class.path");
        try {
            var output = Files.createTempDirectory("jfxmap-validation-javac");
            var options = new ArrayList<>(List.of("-classpath", classpath, "-processorpath", classpath,
                    "-d", output.toString(), "-proc:none"));
            if (plugin != null) options.add("-Xplugin:" + plugin);
            var units = sources.entrySet().stream().map(e -> source(e.getKey(), e.getValue())).toList();
            try (var fileManager = compiler.getStandardFileManager(collector, null, null)) {
                var success = compiler.getTask(null, fileManager, collector, options, null, units).call();
                return new Result(success, List.copyOf(collector.getDiagnostics()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JavaFileObject source(String name, String content) {
        return new SimpleJavaFileObject(URI.create("string:///" + name), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }
}

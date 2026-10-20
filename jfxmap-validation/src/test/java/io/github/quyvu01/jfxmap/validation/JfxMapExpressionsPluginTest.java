package io.github.quyvu01.jfxmap.validation;

import com.sun.source.util.Plugin;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JfxMapExpressionsPluginTest {
    @Test
    void isRegisteredAsAJavacPlugin() {
        assertThat(ServiceLoader.load(Plugin.class).stream().map(ServiceLoader.Provider::get))
                .anySatisfy(plugin -> {
                    assertThat(plugin).isInstanceOf(JfxMapExpressionsPlugin.class);
                    assertThat(plugin.getName()).isEqualTo("JfxMapExpressions");
                });
    }

    @Test
    void reportsErrorsByDefault() {
        assertThat(JfxMapExpressionsPlugin.severity()).isEqualTo(Diagnostic.Kind.ERROR);
        assertThat(JfxMapExpressionsPlugin.severity("")).isEqualTo(Diagnostic.Kind.ERROR);
        assertThat(JfxMapExpressionsPlugin.severity("error")).isEqualTo(Diagnostic.Kind.ERROR);
    }

    @Test
    void reportsWarningsWithTheWarnArgument() {
        assertThat(JfxMapExpressionsPlugin.severity("warn")).isEqualTo(Diagnostic.Kind.WARNING);
        assertThat(JfxMapExpressionsPlugin.severity("WARNING")).isEqualTo(Diagnostic.Kind.WARNING);
        assertThat(JfxMapExpressionsPlugin.severity("error", "warn")).isEqualTo(Diagnostic.Kind.WARNING);
    }

    @Test
    void rejectsAnUnknownArgument() {
        assertThatThrownBy(() -> JfxMapExpressionsPlugin.severity("loud"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'loud'");
    }
}

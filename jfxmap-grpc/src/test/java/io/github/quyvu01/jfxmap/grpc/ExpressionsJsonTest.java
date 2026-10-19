package io.github.quyvu01.jfxmap.grpc;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpressionsJsonTest {
    @Test
    void writesAJsonArrayWithNullForTheDefaultProperty() {
        assertThat(ExpressionsJson.write(Arrays.asList(null, "Email", "Name ?? 'x'")))
                .isEqualTo("[null,\"Email\",\"Name ?? 'x'\"]");
    }

    @Test
    void readsWhatFxMapWrites() {
        assertThat(ExpressionsJson.read("[null,\"Email\"]")).containsExactly(null, "Email");
        assertThat(ExpressionsJson.read("null")).isEmpty();
        assertThat(ExpressionsJson.read(null)).isEmpty();
        assertThat(ExpressionsJson.read(" ")).isEmpty();
        assertThat(ExpressionsJson.read("[]")).isEqualTo(List.of());
    }

    @Test
    void rejectsAnythingElse() {
        assertThatThrownBy(() -> ExpressionsJson.read("Email")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON array");
        assertThatThrownBy(() -> ExpressionsJson.read("{\"a\":1}")).isInstanceOf(IllegalArgumentException.class);
    }
}

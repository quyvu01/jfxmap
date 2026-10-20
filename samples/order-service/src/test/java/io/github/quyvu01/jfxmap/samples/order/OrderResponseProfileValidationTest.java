package io.github.quyvu01.jfxmap.samples.order;

import io.github.quyvu01.jfxmap.validation.JfxMapValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The startup check of jfxmap-validation on the real profile; the javac plugin already checked it at compile time. */
class OrderResponseProfileValidationTest {
    @Test
    void theProfileAndTheStandInUserConfigurationAreValid() {
        assertThat(JfxMapValidator.validate(new OrderResponseProfile())).isEmpty();
        assertThat(JfxMapValidator.validateEntities(new OrderServiceTest.UserConfig())).isEmpty();
    }
}

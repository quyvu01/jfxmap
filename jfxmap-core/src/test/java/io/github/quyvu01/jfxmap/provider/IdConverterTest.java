package io.github.quyvu01.jfxmap.provider;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdConverterTest {
    enum Color { RED }

    @Test
    void convertsToEachSupportedType() {
        assertThat(IdConverter.of(String.class).convert(" a ")).isEqualTo(" a ");
        assertThat(IdConverter.of(UUID.class).convert("6F9619FF-8B86-D011-B42D-00C04FC964FF"))
                .isEqualTo(UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff"));
        assertThat(IdConverter.of(Long.class).convert(" 12 ")).isEqualTo(12L);
        assertThat(IdConverter.of(long.class).convert("12")).isEqualTo(12L);
        assertThat(IdConverter.of(Integer.class).convert("12")).isEqualTo(12);
        assertThat(IdConverter.of(int.class).convert("12")).isEqualTo(12);
        assertThat(IdConverter.of(Short.class).convert("12")).isEqualTo((short) 12);
        assertThat(IdConverter.of(short.class).convert("12")).isEqualTo((short) 12);
        assertThat(IdConverter.of(Byte.class).convert("12")).isEqualTo((byte) 12);
        assertThat(IdConverter.of(byte.class).convert("12")).isEqualTo((byte) 12);
        assertThat(IdConverter.of(BigInteger.class).convert("12")).isEqualTo(BigInteger.valueOf(12));
        assertThat(IdConverter.of(BigDecimal.class).convert("1.5")).isEqualTo(new BigDecimal("1.5"));
        assertThat(IdConverter.of(Color.class).convert("RED")).isEqualTo(Color.RED);
    }

    @Test
    void givesNullForAStringThatIsNotAnId() {
        assertThat(IdConverter.of(Long.class).convert("x")).isNull();
        assertThat(IdConverter.of(UUID.class).convert("x")).isNull();
        assertThat(IdConverter.of(Color.class).convert("BLUE")).isNull();
        assertThat(IdConverter.of(BigDecimal.class).convert("x")).isNull();
        assertThat(IdConverter.of(Long.class).convert(null)).isNull();
    }

    @Test
    void doesNotSupportOtherTypes() {
        assertThat(IdConverter.of(LocalDate.class)).isNull();
    }

    @Test
    void usesTheGivenParser() {
        var converter = IdConverter.using(s -> {
            if (!s.startsWith("#")) throw new IllegalArgumentException(s);
            return s.substring(1);
        });

        assertThat(converter.convert("#7")).isEqualTo("7");
        assertThat(converter.convert("7")).isNull();
        assertThat(converter.convert(null)).isNull();
    }
}

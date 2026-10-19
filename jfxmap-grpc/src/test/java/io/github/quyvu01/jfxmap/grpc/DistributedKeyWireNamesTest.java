package io.github.quyvu01.jfxmap.grpc;

import io.github.quyvu01.jfxmap.DistributedKey;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DistributedKeyWireNamesTest {
    static final class UserOf implements DistributedKey {
    }

    @Test
    void writesWhatFxMapSendsForAStringKey() {
        assertThat(DistributedKeyWireNames.toWire(DistributedKeyRef.of("UserOf")))
                .isEqualTo("UserOf, FxMap.DynamicDistributedKeys, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null");
        assertThat(DistributedKeyWireNames.toWire(DistributedKeyRef.of("Sales.OrderOf")))
                .startsWith("Sales.OrderOf, FxMap.DynamicDistributedKeys,");
    }

    @Test
    void writesAClassKeyByItsClassName() {
        assertThat(DistributedKeyWireNames.toWire(DistributedKeyRef.of(UserOf.class)))
                .isEqualTo(UserOf.class.getName() + ", " + DistributedKeyWireNames.DYNAMIC_ASSEMBLY);
        assertThatThrownBy(() -> DistributedKeyWireNames.toWire(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void readsTheAssemblyQualifiedNameAndTheBareName() {
        assertThat(DistributedKeyWireNames.fromWire(
                " UserOf, FxMap.DynamicDistributedKeys, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null "))
                .contains(new DistributedKeyRef("UserOf"));
        assertThat(DistributedKeyWireNames.fromWire("UserOf")).contains(new DistributedKeyRef("UserOf"));
    }

    @Test
    void ignoresTypesOfOtherAssembliesAndEmptyNames() {
        assertThat(DistributedKeyWireNames.fromWire("Contracts.UserOf, Contracts, Version=1.0.0.0, Culture=neutral, PublicKeyToken=null"))
                .isEmpty();
        assertThat(DistributedKeyWireNames.fromWire(null)).isEmpty();
        assertThat(DistributedKeyWireNames.fromWire("  ")).isEmpty();
        assertThat(DistributedKeyWireNames.fromWire(", " + DistributedKeyWireNames.DYNAMIC_ASSEMBLY)).isEmpty();
    }
}

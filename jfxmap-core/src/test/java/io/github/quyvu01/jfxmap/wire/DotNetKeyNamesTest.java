package io.github.quyvu01.jfxmap.wire;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DotNetKeyNamesTest {
    @Test
    void aKeyWithoutDotsHasNoNamespace() {
        var key = DistributedKeyRef.of("UserOf");

        assertThat(DotNetKeyNames.fullName(key)).isEqualTo("UserOf");
        assertThat(DotNetKeyNames.namespace(key)).isEmpty();
        assertThat(DotNetKeyNames.name(key)).isEqualTo("UserOf");
        assertThat(DotNetKeyNames.assemblyQualifiedName(key)).isEqualTo(
                "UserOf, FxMap.DynamicDistributedKeys, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null");
    }

    @Test
    void theNamespaceOfADottedKeyIsWhatComesBeforeTheLastDot() {
        var key = DistributedKeyRef.of("Shop.Keys.UserOf");

        assertThat(DotNetKeyNames.namespace(key)).isEqualTo("Shop.Keys");
        assertThat(DotNetKeyNames.name(key)).isEqualTo("UserOf");
        assertThat(DotNetKeyNames.fullName(key)).isEqualTo("Shop.Keys.UserOf");
    }

    @Test
    void readsAnAssemblyQualifiedNameOrABareName() {
        assertThat(DotNetKeyNames.fromAssemblyQualifiedName(
                "UserOf, FxMap.DynamicDistributedKeys, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null"))
                .contains(new DistributedKeyRef("UserOf"));
        assertThat(DotNetKeyNames.fromAssemblyQualifiedName(" UserOf ")).contains(new DistributedKeyRef("UserOf"));
    }

    @Test
    void aNameOfAnotherAssemblyOrNothingGivesNoKey() {
        assertThat(DotNetKeyNames.fromAssemblyQualifiedName("Shop.UserOf, Shop.Contracts, Version=1.0.0.0")).isEmpty();
        assertThat(DotNetKeyNames.fromAssemblyQualifiedName("  ")).isEmpty();
        assertThat(DotNetKeyNames.fromAssemblyQualifiedName(null)).isEmpty();
    }
}

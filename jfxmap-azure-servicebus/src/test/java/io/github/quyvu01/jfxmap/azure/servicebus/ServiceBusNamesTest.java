package io.github.quyvu01.jfxmap.azure.servicebus;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceBusNamesTest {
    private static final DistributedKeyRef USER_OF = DistributedKeyRef.of("UserOf");

    @Test
    void aStringKeyWithoutNamespaceHasAnEmptySegment() {
        assertThat(ServiceBusNames.requestQueue(null, USER_OF)).isEqualTo("fxmap-request--userof");
        assertThat(ServiceBusNames.replyQueue("", USER_OF)).isEqualTo("fxmap-reply--userof");
    }

    @Test
    void theDotsOfTheNamespaceBecomeDashes() {
        assertThat(ServiceBusNames.requestQueue(null, DistributedKeyRef.of("Shop.Keys.UserOf")))
                .isEqualTo("fxmap-request-shop-keys-userof");
    }

    @Test
    void aPrefixComesFirstInLowerCase() {
        assertThat(ServiceBusNames.requestQueue("Tenant.A", USER_OF)).isEqualTo("tenant-a-fxmap-request--userof");
        assertThat(ServiceBusNames.replyQueue("tenant", USER_OF)).isEqualTo("tenant-fxmap-reply--userof");
    }
}

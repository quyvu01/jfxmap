package io.github.quyvu01.jfxmap.rabbitmq;

import io.github.quyvu01.jfxmap.DistributedKeyRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RabbitMqNamesTest {
    @Test
    void theExchangeOfAKeyIsNamedAsFxMapNamesIt() {
        assertThat(RabbitMqNames.exchange(DistributedKeyRef.of("UserOf"))).isEqualTo("FxMap-:UserOf");
        assertThat(RabbitMqNames.exchange(DistributedKeyRef.of("Shop.Keys.UserOf"))).isEqualTo("FxMap-Shop.Keys:UserOf");
    }

    @Test
    void anExchangeNameGivesBackItsKey() {
        assertThat(RabbitMqNames.keyOfExchange("FxMap-:UserOf")).contains(new DistributedKeyRef("UserOf"));
        assertThat(RabbitMqNames.keyOfExchange("FxMap-Shop.Keys:UserOf"))
                .contains(new DistributedKeyRef("Shop.Keys.UserOf"));
    }

    @Test
    void otherExchangeNamesGiveNoKey() {
        assertThat(RabbitMqNames.keyOfExchange("amq.direct")).isEmpty();
        assertThat(RabbitMqNames.keyOfExchange("FxMap-NoColon")).isEmpty();
        assertThat(RabbitMqNames.keyOfExchange("FxMap-Shop:")).isEmpty();
        assertThat(RabbitMqNames.keyOfExchange(null)).isEmpty();
    }

    @Test
    void theQueueOfAServiceIsItsNameInLowerCase() {
        assertThat(RabbitMqNames.queue("UserService")).isEqualTo("fxmap-rpc-queue-userservice");
        assertThatThrownBy(() -> RabbitMqNames.queue(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RabbitMqNames.queue(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRoutingKeyKeepsFxMapsSpelling() {
        assertThat(RabbitMqNames.ROUTING_KEY).isEqualTo("fxmap-rpc-rounting-key");
    }
}

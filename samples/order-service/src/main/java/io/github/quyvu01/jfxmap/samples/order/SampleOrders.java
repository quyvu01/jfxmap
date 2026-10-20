package io.github.quyvu01.jfxmap.samples.order;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Seeds orders of the user service's sample users, and one of a user it does not know. */
@Component
public class SampleOrders implements ApplicationRunner {
    static final UUID ALICE = UUID.fromString("0b6e1b9a-55b1-4c5e-9f57-0a3c3a0d7e11");
    static final UUID BOB = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff");
    static final UUID CAROL = UUID.fromString("c3a1e5b2-0d4f-4e6a-9b8c-7d2e1f0a3b4c");
    static final UUID UNKNOWN = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final OrderRepository orders;

    public SampleOrders(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (orders.count() > 0) return;
        orders.saveAll(List.of(
                new Order(1L, "Keyboard", new BigDecimal("49.90"), ALICE),
                new Order(2L, "Monitor", new BigDecimal("219.00"), BOB),
                new Order(3L, "Mouse", new BigDecimal("19.50"), ALICE),
                new Order(4L, "Headset", new BigDecimal("89.00"), CAROL),
                new Order(5L, "Cable", new BigDecimal("5.00"), UNKNOWN)));
    }
}

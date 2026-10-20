package io.github.quyvu01.jfxmap.samples.order;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Owns the orders, which only hold a user id. jfxmap fills each order's user fields from the user service over
 * gRPC, one request per page of orders.
 */
@SpringBootApplication
public class OrderServiceApplication {
    public static void main(String[] args) {
        run(args);
    }

    /** Starts the service with its own configuration file, order-service.properties. */
    public static ConfigurableApplicationContext run(String... args) {
        return new SpringApplicationBuilder(OrderServiceApplication.class)
                .properties("spring.config.name=order-service")
                .run(args);
    }
}

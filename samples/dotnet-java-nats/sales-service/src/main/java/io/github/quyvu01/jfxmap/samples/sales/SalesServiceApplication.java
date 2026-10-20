package io.github.quyvu01.jfxmap.samples.sales;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The Java half of the sample: owns the customers and their orders (JPA, PostgreSQL, Flyway). Over NATS, jfxmap answers
 * catalog-service (.NET FxMap) for "CustomerOf" and asks it for "ProductOf".
 */
@SpringBootApplication
public class SalesServiceApplication {
    public static void main(String[] args) {
        run(args);
    }

    /** Starts the service with its own configuration file, sales-service.properties. */
    public static ConfigurableApplicationContext run(String... args) {
        return new SpringApplicationBuilder(SalesServiceApplication.class)
                .properties("spring.config.name=sales-service")
                .run(args);
    }
}

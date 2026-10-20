package io.github.quyvu01.jfxmap.samples.user;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Owns the users. Their data lives in JPA (H2 here); jfxmap answers the {@code UserOf} key from it over gRPC, for
 * the order service or any FxMap service, Java or .NET.
 */
@SpringBootApplication
public class UserServiceApplication {
    public static void main(String[] args) {
        run(args);
    }

    /** Starts the service with its own configuration file, user-service.properties. */
    public static ConfigurableApplicationContext run(String... args) {
        return new SpringApplicationBuilder(UserServiceApplication.class)
                .properties("spring.config.name=user-service")
                .run(args);
    }
}

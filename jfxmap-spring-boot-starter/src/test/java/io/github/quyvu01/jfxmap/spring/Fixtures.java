package io.github.quyvu01.jfxmap.spring;

import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import io.github.quyvu01.jfxmap.provider.InMemoryQueryHandler;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceConfiguration;

import java.util.List;

/** The beans of the auto-configuration tests. */
final class Fixtures {
    private Fixtures() {
    }

    @Entity
    public static class User {
        @Id
        private long id;
        private String name;
        private String email;

        protected User() {
        }

        User(long id, String name, String email) {
            this.id = id;
            this.name = name;
            this.email = email;
        }

        public long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getEmail() {
            return email;
        }
    }

    public static class UserConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::getId);
            defaultProperty(User::getName);
            useDistributedKey("UserOf");
            exposedName(User::getEmail, "UserEmail");
        }
    }

    public static class OrderDto {
        long userId;
        String userName;
        String userEmail;

        OrderDto(long userId) {
            this.userId = userId;
        }

        long getUserId() {
            return userId;
        }

        String getUserName() {
            return userName;
        }

        String getUserEmail() {
            return userEmail;
        }
    }

    public static class OrderDtoProfile extends ProfileOf<OrderDto> {
        @Override
        protected void configure() {
            useDistributedKey("UserOf")
                    .of(OrderDto::getUserId)
                    .map(OrderDto::getUserName)
                    .map(OrderDto::getUserEmail, c -> c
                            .when(ctx -> ctx.service(EmailVisibility.class) != null
                                    && ctx.service(EmailVisibility.class).visible())
                            .expression("UserEmail")
                            .otherwise("'hidden'"));
        }
    }

    /** A service a conditional expression asks the application for. */
    public record EmailVisibility(boolean visible) {
    }

    static InMemoryQueryHandler<User> inMemoryUsers() {
        return new InMemoryQueryHandler<>(new UserConfig(), List.of(new User(1, "Alice (memory)", "alice@memory")));
    }

    static EntityManagerFactory usersDatabase(String name) {
        var emf = new PersistenceConfiguration(name)
                .managedClass(User.class)
                .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1")
                .property(PersistenceConfiguration.JDBC_USER, "sa")
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
                .createEntityManagerFactory();
        emf.runInTransaction(em -> {
            em.persist(new User(1, "Alice", "alice@example.com"));
            em.persist(new User(2, "Bob", "bob@example.com"));
        });
        return emf;
    }
}

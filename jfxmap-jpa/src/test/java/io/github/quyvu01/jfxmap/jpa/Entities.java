package io.github.quyvu01.jfxmap.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.EntityManagerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The entities of the tests, in an H2 database. */
final class Entities {
    private Entities() {
    }

    static EntityManagerFactory entityManagerFactory(String name) {
        return new PersistenceConfiguration(name)
                .managedClass(Country.class)
                .managedClass(User.class)
                .managedClass(PurchaseOrder.class)
                .managedClass(Product.class)
                .managedClass(Holiday.class)
                .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1")
                .property(PersistenceConfiguration.JDBC_USER, "sa")
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
                .createEntityManagerFactory();
    }

    @Entity
    public static class Country {
        @Id
        private long id;
        private String name;

        protected Country() {
        }

        Country(long id, String name) {
            this.id = id;
            this.name = name;
        }

        public long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    @Entity
    @Table(name = "app_user")
    public static class User {
        @Id
        private UUID id;
        private String name;
        private String email;
        @ManyToOne(fetch = FetchType.LAZY)
        private Country country;
        @OneToMany(mappedBy = "user")
        private List<PurchaseOrder> orders = new ArrayList<>();

        protected User() {
        }

        User(UUID id, String name, String email, Country country) {
            this.id = id;
            this.name = name;
            this.email = email;
            this.country = country;
        }

        public UUID getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getEmail() {
            return email;
        }

        public Country getCountry() {
            return country;
        }

        public List<PurchaseOrder> getOrders() {
            return orders;
        }

        @Transient
        public String getNickname() {
            return name.toLowerCase();
        }
    }

    @Entity
    public static class PurchaseOrder {
        @Id
        private long id;
        private String status;
        private BigDecimal total;
        @ManyToOne(fetch = FetchType.LAZY)
        private User user;

        protected PurchaseOrder() {
        }

        PurchaseOrder(long id, String status, BigDecimal total, User user) {
            this.id = id;
            this.status = status;
            this.total = total;
            this.user = user;
        }

        public long getId() {
            return id;
        }

        public String getStatus() {
            return status;
        }

        public BigDecimal getTotal() {
            return total;
        }

        public User getUser() {
            return user;
        }
    }

    /** Found by a natural key, which is not its {@code @Id}. */
    @Entity
    public static class Product {
        @Id
        private int id;
        private String code;
        private String title;

        protected Product() {
        }

        Product(int id, String code, String title) {
            this.id = id;
            this.code = code;
            this.title = title;
        }

        public int getId() {
            return id;
        }

        public String getCode() {
            return code;
        }

        public String getTitle() {
            return title;
        }
    }

    /** An id type that requested ids are not converted to. */
    @Entity
    public static class Holiday {
        @Id
        private LocalDate day;

        protected Holiday() {
        }

        public LocalDate getDay() {
            return day;
        }
    }

    /** Not part of the persistence unit. */
    public static class Unmapped {
        public long getId() {
            return 0;
        }
    }
}

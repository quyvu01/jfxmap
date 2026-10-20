package io.github.quyvu01.jfxmap.jpa;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The entities of the tests, in an H2 database. */
final class Entities {
    private Entities() {
    }

    static EntityManagerFactory entityManagerFactory(String name) {
        return entityManagerFactory(name, "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    /** The persistence unit on a database; its SQL statements are recorded by {@link SqlLog}. */
    static EntityManagerFactory entityManagerFactory(String name, String url, String user, String password) {
        return new PersistenceConfiguration(name)
                .managedClass(Country.class)
                .managedClass(User.class)
                .managedClass(PurchaseOrder.class)
                .managedClass(Product.class)
                .managedClass(Holiday.class)
                .property(PersistenceConfiguration.JDBC_URL, url)
                .property(PersistenceConfiguration.JDBC_USER, user)
                .property(PersistenceConfiguration.JDBC_PASSWORD, password)
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
                .property("hibernate.session_factory.statement_inspector", SqlLog.class.getName())
                .createEntityManagerFactory();
    }

    public enum Level {
        SILVER,
        GOLD
    }

    @Embeddable
    public static class Address {
        private String city;

        protected Address() {
        }

        Address(String city) {
            this.city = city;
        }

        public String getCity() {
            return city;
        }
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
        private int age;
        private LocalDate birthDate;
        private LocalDateTime createdAt;
        private Double score;
        @Enumerated(EnumType.STRING)
        private Level level;
        @Embedded
        private Address address;
        @ElementCollection
        private List<String> tags = new ArrayList<>();

        protected User() {
        }

        User details(int age, LocalDate birthDate, LocalDateTime createdAt, Double score, Level level, Address address,
                     List<String> tags) {
            this.age = age;
            this.birthDate = birthDate;
            this.createdAt = createdAt;
            this.score = score;
            this.level = level;
            this.address = address;
            this.tags = new ArrayList<>(tags);
            return this;
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

        public int getAge() {
            return age;
        }

        public LocalDate getBirthDate() {
            return birthDate;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public Double getScore() {
            return score;
        }

        public Level getLevel() {
            return level;
        }

        public Address getAddress() {
            return address;
        }

        public List<String> getTags() {
            return tags;
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
        private int quantity;
        @ManyToOne(fetch = FetchType.LAZY)
        private User user;
        @ManyToOne(fetch = FetchType.LAZY)
        private Product product;

        protected PurchaseOrder() {
        }

        PurchaseOrder(long id, String status, BigDecimal total, User user) {
            this.id = id;
            this.status = status;
            this.total = total;
            this.user = user;
        }

        PurchaseOrder(long id, String status, BigDecimal total, User user, int quantity, Product product) {
            this(id, status, total, user);
            this.quantity = quantity;
            this.product = product;
        }

        public int getQuantity() {
            return quantity;
        }

        public Product getProduct() {
            return product;
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

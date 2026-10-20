package io.github.quyvu01.jfxmap.jpa;

import io.github.quyvu01.jfxmap.FxMap;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import io.github.quyvu01.jfxmap.jpa.Entities.Country;
import io.github.quyvu01.jfxmap.jpa.Entities.Holiday;
import io.github.quyvu01.jfxmap.jpa.Entities.Product;
import io.github.quyvu01.jfxmap.jpa.Entities.PurchaseOrder;
import io.github.quyvu01.jfxmap.jpa.Entities.Unmapped;
import io.github.quyvu01.jfxmap.jpa.Entities.User;
import io.github.quyvu01.jfxmap.provider.LocalRequestClient;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ExpressionError;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JpaQueryHandlerTest {
    static final UUID ALICE = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff");
    static final UUID BOB = UUID.fromString("0b6e1b9a-55b1-4c5e-9f57-0a3c3a0d7e11");
    static final UUID NOBODY = UUID.fromString("00000000-0000-0000-0000-000000000001");

    static EntityManagerFactory emf;

    static class UserConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::getId);
            defaultProperty(User::getName);
            useDistributedKey("UserOf");
            exposedName(User::getEmail, "UserEmail");
        }
    }

    static class ProductConfig extends EntityConfigureOf<Product> {
        @Override
        protected void configure() {
            id(Product::getCode);
            defaultProperty(Product::getTitle);
            useDistributedKey("ProductOf");
        }
    }

    static class HolidayConfig extends EntityConfigureOf<Holiday> {
        @Override
        protected void configure() {
            id(Holiday::getDay);
            useDistributedKey("HolidayOf");
        }
    }

    static class UnmappedConfig extends EntityConfigureOf<Unmapped> {
        @Override
        protected void configure() {
            id(Unmapped::getId);
            useDistributedKey("UnmappedOf");
        }
    }

    static class NicknameConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::getNickname);
            useDistributedKey("NicknameOf");
        }
    }

    @BeforeAll
    static void seed() {
        emf = Entities.entityManagerFactory("jpa-handler");
        emf.runInTransaction(em -> {
            var vietnam = new Country(1, "Vietnam");
            var france = new Country(2, "France");
            em.persist(vietnam);
            em.persist(france);
            var alice = new User(ALICE, "Alice", "alice@example.com", vietnam);
            var bob = new User(BOB, "Bob", "bob@example.com", null);
            em.persist(alice);
            em.persist(bob);
            em.persist(new PurchaseOrder(1, "Done", new BigDecimal("10.50"), alice));
            em.persist(new PurchaseOrder(2, "Done", new BigDecimal("4.50"), alice));
            em.persist(new PurchaseOrder(3, "Open", new BigDecimal("100"), alice));
            em.persist(new Product(7, "P-7", "Pen"));
        });
    }

    @AfterAll
    static void close() {
        emf.close();
    }

    private static ItemsResponse handle(JpaQueryHandler<?> handler, List<String> ids, String... expressions) {
        return handler.handle(new MapRequest(ids, Arrays.asList(expressions)), MapContext.empty())
                .toCompletableFuture().join();
    }

    @Test
    void answersTheDefaultPropertyAndExposedNamesInTheRequestedOrder() {
        var handler = new JpaQueryHandler<>(new UserConfig(), emf);

        var response = handle(handler, List.of(BOB.toString(), NOBODY.toString(), ALICE.toString()), null,
                "UserEmail");

        assertThat(response.items()).containsExactly(
                new DataResponse(BOB.toString(), List.of(new ValueResponse(null, "\"Bob\""),
                        new ValueResponse("UserEmail", "\"bob@example.com\""))),
                new DataResponse(ALICE.toString(), List.of(new ValueResponse(null, "\"Alice\""),
                        new ValueResponse("UserEmail", "\"alice@example.com\""))));
        assertThat(response.errors()).isEmpty();
        assertThat(handler.key().name()).isEqualTo("UserOf");
        assertThat(handler.entityType()).isEqualTo(User.class);
    }

    @Test
    void readsNavigationsAndCollectionsThroughLazyAssociations() {
        var handler = new JpaQueryHandler<>(new UserConfig(), emf);

        var response = handle(handler, List.of(ALICE.toString(), BOB.toString()),
                "Country?.Name", "Orders:where(Status = 'Done'):sum(Total)", "Orders:count", "Orders:orderBy(Total desc):first.{Id, Total}");

        assertThat(response.errors()).isEmpty();
        assertThat(response.items().get(0).values()).extracting(ValueResponse::value)
                .containsExactly("\"Vietnam\"", "15.00", "3", "{\"Id\":3,\"Total\":100.00}");
        assertThat(response.items().get(1).values()).extracting(ValueResponse::value)
                .containsExactly(null, "0", "0", "{\"Id\":null,\"Total\":null}");
    }

    @Test
    void keepsTheRequestedSpellingOfAnId() {
        var handler = new JpaQueryHandler<>(new UserConfig(), emf);

        var upper = ALICE.toString().toUpperCase();
        var response = handle(handler, List.of(upper, "not-a-uuid"), (String) null);

        assertThat(response.items()).extracting(DataResponse::id).containsExactly(upper);
    }

    @Test
    void findsByTheConfiguredIdWhenItIsNotTheJpaId() {
        var handler = new JpaQueryHandler<>(new ProductConfig(), emf);

        var response = handle(handler, List.of("P-7", "7"), (String) null);

        assertThat(response.items()).containsExactly(
                new DataResponse("P-7", List.of(new ValueResponse(null, "\"Pen\""))));
    }

    @Test
    void reportsAnExpressionThatFailsAndAnswersTheOthers() {
        var handler = new JpaQueryHandler<>(new UserConfig(), emf);

        var response = handle(handler, List.of(ALICE.toString()), "Name", "Name:", "Missing");

        assertThat(response.items().getFirst().values()).containsExactly(new ValueResponse("Name", "\"Alice\""));
        assertThat(response.errors()).extracting(ExpressionError::expression).containsExactly("Name:", "Missing");
    }

    @Test
    void answersAnEmptyRequestWithoutQuerying() {
        var handler = new JpaQueryHandler<>(new UserConfig(), emf);

        assertThat(handle(handler, List.of(), "Name")).isEqualTo(ItemsResponse.empty());
    }

    @Test
    void queriesManyIdsInChunks() {
        var handler = new JpaQueryHandler<>(new UserConfig(), emf);
        var ids = new ArrayList<String>();
        IntStream.range(0, JpaQueryHandler.DEFAULT_BATCH_SIZE * 2 + 5)
                .forEach(i -> ids.add(new UUID(0, i + 100).toString()));
        ids.add(ALICE.toString());

        var response = handle(handler, ids, null, "Orders:count", "Orders:orderBy(Id).{Id}");

        assertThat(response.items()).containsExactly(new DataResponse(ALICE.toString(), List.of(
                new ValueResponse(null, "\"Alice\""), new ValueResponse("Orders:count", "3"),
                new ValueResponse("Orders:orderBy(Id).{Id}", "[{\"Id\":1},{\"Id\":2},{\"Id\":3}]"))));
    }

    @Test
    void runsOnTheGivenExecutor() {
        var runs = new AtomicInteger();
        var handler = new JpaQueryHandler<>(new UserConfig(), emf, task -> {
            runs.incrementAndGet();
            task.run();
        }, Clock.systemUTC());

        handle(handler, List.of(ALICE.toString()), (String) null);

        assertThat(runs).hasValue(1);
    }

    @Test
    void failsTheFutureWhenTheQueryFails() {
        var closed = Entities.entityManagerFactory("jpa-closed");
        var handler = new JpaQueryHandler<>(new UserConfig(), closed);
        closed.close();

        var future = handler.handle(new MapRequest(List.of(ALICE.toString()), List.of("Name")), MapContext.empty())
                .toCompletableFuture();

        assertThatThrownBy(future::join).isInstanceOf(CompletionException.class);
    }

    @Test
    void mapsADtoEndToEndThroughTheEngine() {
        var mapper = FxMap.builder()
                .addProfiles(new OrderDtoProfile())
                .requestClient(new LocalRequestClient(new JpaQueryHandler<>(new UserConfig(), emf)))
                .throwIfException()
                .build();
        var dto = new OrderDto(ALICE);

        mapper.map(dto);

        assertThat(dto.userName).isEqualTo("Alice");
        assertThat(dto.userEmail).isEqualTo("alice@example.com");
        assertThat(dto.countryName).isEqualTo("Vietnam");
    }

    @Test
    void rejectsAConfigurationThatDoesNotFitThePersistenceUnit() {
        assertThatThrownBy(() -> new JpaQueryHandler<>(new UnmappedConfig(), emf))
                .isInstanceOf(JpaMapException.EntityNotManaged.class)
                .hasMessageContaining("Unmapped");
        assertThatThrownBy(() -> new JpaQueryHandler<>(new NicknameConfig(), emf))
                .isInstanceOf(JpaMapException.IdAttributeNotFound.class)
                .hasMessageContaining("nickname");
        assertThatThrownBy(() -> new JpaQueryHandler<>(new HolidayConfig(), emf))
                .isInstanceOf(JpaMapException.UnsupportedIdType.class)
                .hasMessageContaining("LocalDate");
    }

    @Test
    void tellsWhetherAnEntityTypeIsManaged() {
        assertThat(JpaQueryHandler.isManaged(emf, User.class)).isTrue();
        assertThat(JpaQueryHandler.isManaged(emf, Unmapped.class)).isFalse();
    }

    static class OrderDto {
        UUID userId;
        String userName;
        String userEmail;
        String countryName;

        OrderDto(UUID userId) {
            this.userId = userId;
        }

        UUID getUserId() {
            return userId;
        }

        String getUserName() {
            return userName;
        }

        String getUserEmail() {
            return userEmail;
        }

        String getCountryName() {
            return countryName;
        }
    }

    static class OrderDtoProfile extends ProfileOf<OrderDto> {
        @Override
        protected void configure() {
            useDistributedKey("UserOf")
                    .of(OrderDto::getUserId)
                    .map(OrderDto::getUserName)
                    .map(OrderDto::getUserEmail, "UserEmail")
                    .map(OrderDto::getCountryName, "Country.Name");
        }
    }
}

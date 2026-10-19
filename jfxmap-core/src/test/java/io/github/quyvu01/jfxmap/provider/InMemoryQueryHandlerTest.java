package io.github.quyvu01.jfxmap.provider;

import io.github.quyvu01.jfxmap.DistributedKey;
import io.github.quyvu01.jfxmap.DistributedKeyRef;
import io.github.quyvu01.jfxmap.FxMap;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.exception.DistributedMapException;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The engine end to end: a profile on one side, an entity configuration on the other, in one process. */
class InMemoryQueryHandlerTest {
    static final class UserOf implements DistributedKey {
    }

    record Country(String name) {
    }

    record Order(String id, String status, double total) {
    }

    record User(int id, String name, String email, Country country, List<Order> orders) {
    }

    static class UserConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::id);
            defaultProperty(User::name);
            useDistributedKey(UserOf.class);
            exposedName(User::email, "UserEmail");
        }
    }

    static class OrderDto {
        int userId;
        String userName;
        String userEmail;
        String countryName;
        Country country;

        OrderDto() {
        }

        OrderDto(int userId) {
            this.userId = userId;
        }

        int userId() {
            return userId;
        }

        String userName() {
            return userName;
        }

        String userEmail() {
            return userEmail;
        }

        String countryName() {
            return countryName;
        }

        Country country() {
            return country;
        }
    }

    static class OrderProfile extends ProfileOf<OrderDto> {
        @Override
        protected void configure() {
            useDistributedKey(UserOf.class)
                    .of(OrderDto::userId)
                    .map(OrderDto::userName)
                    .map(OrderDto::userEmail, "UserEmail")
                    .map(OrderDto::countryName, "Country?.Name")
                    .map(OrderDto::country, "Country");
        }
    }

    private final List<User> users = List.of(
            new User(1, "Alice", "alice@example.com", new Country("Vietnam"), List.of(
                    new Order("A1", "Done", 10.5), new Order("A2", "Open", 4), new Order("A3", "Done", 20))),
            new User(2, "Bob", "bob@example.com", null, List.of()));

    @Test
    void mapsFromEntitiesInMemory() {
        var client = new LocalRequestClient(new InMemoryQueryHandler<>(new UserConfig(), users));
        var mapper = FxMap.builder().addProfiles(new OrderProfile()).requestClient(client).throwIfException().build();
        var orders = List.of(new OrderDto(1), new OrderDto(2), new OrderDto(3));

        mapper.map(orders);

        var alice = orders.get(0);
        assertThat(alice.userName()).isEqualTo("Alice");
        assertThat(alice.userEmail()).isEqualTo("alice@example.com");
        assertThat(alice.countryName()).isEqualTo("Vietnam");
        assertThat(alice.country()).isEqualTo(new Country("Vietnam"));
        var bob = orders.get(1);
        assertThat(bob.userName()).isEqualTo("Bob");
        assertThat(bob.countryName()).isNull();
        assertThat(orders.get(2).userName()).isNull();
    }

    record OrderSummary(String id, double total) {
    }

    static class StatsDto {
        int userId;
        String upperName;
        String emailDomain;
        double doneTotal;
        int orderCount;
        String label;
        List<OrderSummary> doneOrders;

        StatsDto() {
        }

        StatsDto(int userId) {
            this.userId = userId;
        }

        int userId() {
            return userId;
        }

        String upperName() {
            return upperName;
        }

        String emailDomain() {
            return emailDomain;
        }

        double doneTotal() {
            return doneTotal;
        }

        int orderCount() {
            return orderCount;
        }

        String label() {
            return label;
        }

        List<OrderSummary> doneOrders() {
            return doneOrders;
        }
    }

    static class StatsProfile extends ProfileOf<StatsDto> {
        @Override
        protected void configure() {
            useDistributedKey(UserOf.class)
                    .of(StatsDto::userId)
                    .map(StatsDto::upperName, "Name:upper")
                    .map(StatsDto::emailDomain, "UserEmail:lower:substring(6)")
                    .map(StatsDto::doneTotal, "Orders:where(Status = 'Done'):sum(Total)")
                    .map(StatsDto::orderCount, "Orders:count")
                    .map(StatsDto::label, "Orders:any ? 'customer' : 'none'")
                    .map(StatsDto::doneOrders, "Orders:where(Status = 'Done'):orderBy(Total desc).{Id, Total}");
        }
    }

    @Test
    void evaluatesTheExpressionLanguage() {
        var client = new LocalRequestClient(new InMemoryQueryHandler<>(new UserConfig(), users));
        var mapper = FxMap.builder().addProfiles(new StatsProfile()).requestClient(client).throwIfException().build();
        var alice = new StatsDto(1);
        var bob = new StatsDto(2);

        mapper.map(List.of(alice, bob));

        assertThat(alice.upperName()).isEqualTo("ALICE");
        assertThat(alice.emailDomain()).isEqualTo("example.com");
        assertThat(alice.doneTotal()).isEqualTo(30.5);
        assertThat(alice.orderCount()).isEqualTo(3);
        assertThat(alice.label()).isEqualTo("customer");
        assertThat(alice.doneOrders()).containsExactly(new OrderSummary("A3", 20), new OrderSummary("A1", 10.5));
        assertThat(bob.upperName()).isEqualTo("BOB");
        assertThat(bob.doneTotal()).isZero();
        assertThat(bob.orderCount()).isZero();
        assertThat(bob.label()).isEqualTo("none");
        assertThat(bob.doneOrders()).isEmpty();
    }

    @Test
    void reportsExpressionsItCannotEvaluateAndAnswersTheOthers() {
        var handler = new InMemoryQueryHandler<>(new UserConfig(), users);

        var response = handler.handle(new MapRequest(List.of("1", "2"),
                        Arrays.asList(null, "Name:upper", "Missing", "Name:(", "Country.Name", "Country?.Name")),
                MapContext.empty()).toCompletableFuture().join();

        // Country.Name fails on Bob, who has no country, so it is dropped for Alice too
        assertThat(response.errors()).extracting(e -> e.expression())
                .containsExactly("Name:(", "Missing", "Country.Name");
        assertThat(response.items()).extracting(item -> item.values()).containsExactly(
                List.of(new ValueResponse(null, "\"Alice\""), new ValueResponse("Name:upper", "\"ALICE\""),
                        new ValueResponse("Country?.Name", "\"Vietnam\"")),
                List.of(new ValueResponse(null, "\"Bob\""), new ValueResponse("Name:upper", "\"BOB\""),
                        new ValueResponse("Country?.Name", null)));
    }

    @Test
    void reportsTheDefaultPropertyWhenThereIsNone() {
        class NoDefaultConfig extends EntityConfigureOf<User> {
            @Override
            protected void configure() {
                id(User::id);
                useDistributedKey(UserOf.class);
            }
        }
        var handler = new InMemoryQueryHandler<>(new NoDefaultConfig(), users);

        var response = handler.handle(new MapRequest(List.of("1"), Arrays.asList(null, "Name")),
                MapContext.empty()).toCompletableFuture().join();

        assertThat(response.errors()).singleElement().satisfies(e -> {
            assertThat(e.expression()).isNull();
            assertThat(e.message()).contains("no default property");
        });
        assertThat(response.items()).singleElement().satisfies(item -> assertThat(item.values())
                .containsExactly(new ValueResponse("Name", "\"Alice\"")));
    }

    @Test
    void resolvesExposedNamesOnTheEntityTypeOnly() {
        var handler = new InMemoryQueryHandler<>(new UserConfig(), users);

        var response = handler.handle(new MapRequest(List.of("1"),
                        List.of("UserEmail:upper", "Email", "{Id, UserEmail}", "Country.UserEmail")),
                MapContext.empty()).toCompletableFuture().join();

        assertThat(response.errors()).extracting(e -> e.expression()).containsExactly("Country.UserEmail");
        assertThat(response.items()).singleElement().satisfies(item -> assertThat(item.values()).containsExactly(
                new ValueResponse("UserEmail:upper", "\"ALICE@EXAMPLE.COM\""),
                new ValueResponse("Email", "\"alice@example.com\""),
                new ValueResponse("{Id, UserEmail}", "{\"Id\":1,\"UserEmail\":\"alice@example.com\"}")));
    }

    @Test
    void routesByKeyAndRejectsTwoEntitiesForOneKey() {
        var client = new LocalRequestClient(new InMemoryQueryHandler<>(new UserConfig(), users));

        assertThatThrownBy(() -> client.register(new InMemoryQueryHandler<>(new UserConfig(), List.of())))
                .isInstanceOf(DistributedMapException.DistributedKeyAssignedToMultipleEntities.class);
        assertThatThrownBy(() -> client.request(DistributedKeyRef.of("Nobody"),
                new MapRequest(List.of("1"), List.of("Name")), MapContext.empty()).toCompletableFuture().join())
                .hasCauseInstanceOf(DistributedMapException.NoHandlerForDistributedKey.class);
    }
}

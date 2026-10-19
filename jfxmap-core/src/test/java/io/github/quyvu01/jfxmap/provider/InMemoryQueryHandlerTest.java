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

    record User(int id, String name, String email, Country country) {
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
            new User(1, "Alice", "alice@example.com", new Country("Vietnam")),
            new User(2, "Bob", "bob@example.com", null));

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

    @Test
    void reportsExpressionsItCannotEvaluateAndAnswersTheOthers() {
        var handler = new InMemoryQueryHandler<>(new UserConfig(), users);

        var response = handler.handle(new MapRequest(List.of("1"), Arrays.asList(null, "Name:upper", "Missing")),
                MapContext.empty()).toCompletableFuture().join();

        assertThat(response.errors()).extracting(e -> e.expression()).containsExactly("Name:upper", "Missing");
        assertThat(response.items()).singleElement().satisfies(item -> assertThat(item.values())
                .containsExactly(new ValueResponse(null, "\"Alice\"")));
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

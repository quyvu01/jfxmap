package io.github.quyvu01.jfxmap.provider;

import io.github.quyvu01.jfxmap.DistributedKey;
import io.github.quyvu01.jfxmap.MapContext;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.response.ItemsResponse;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How {@link InMemoryQueryHandler} parses and evaluates the expressions of a request. */
class InMemoryQueryHandlerEvaluationTest {
    static final class MemberOf implements DistributedKey {
    }

    record Member(int id, String name, String email, LocalDate joined, Member manager) {
    }

    static class MemberConfig extends EntityConfigureOf<Member> {
        @Override
        protected void configure() {
            id(Member::id);
            defaultProperty(Member::name);
            useDistributedKey(MemberOf.class);
            exposedName(Member::email, "MemberEmail");
        }
    }

    static class Faulty {
        public int getId() {
            return 1;
        }

        public String getName() {
            throw new IllegalStateException("database is down");
        }
    }

    static class FaultyConfig extends EntityConfigureOf<Faulty> {
        @Override
        protected void configure() {
            id(Faulty::getId);
            useDistributedKey("FaultyOf");
        }
    }

    private static final Member BOSS = new Member(1, "Ann", "ann@example.com", LocalDate.of(2026, 1, 1), null);
    private static final Member DEV = new Member(2, "Ben", "ben@example.com", LocalDate.of(2026, 1, 8), BOSS);

    private static ItemsResponse ask(InMemoryQueryHandler<?> handler, List<String> ids, List<String> expressions) {
        return handler.handle(new MapRequest(ids, expressions), MapContext.empty()).toCompletableFuture().join();
    }

    @Test
    void givesTodayForDaysAgoFromTheClock() {
        var clock = Clock.fixed(Instant.parse("2026-01-10T12:00:00Z"), ZoneOffset.UTC);
        var handler = new InMemoryQueryHandler<>(new MemberConfig(), () -> List.of(BOSS, DEV), clock);

        var response = ask(handler, List.of("1", "2"), List.of("Joined:daysAgo"));

        assertThat(response.errors()).isEmpty();
        assertThat(response.items()).extracting(item -> item.values().getFirst().value())
                .containsExactly("9", "2");
    }

    @Test
    void readsTheEntitiesOnEachRequest() {
        var members = new ArrayList<Member>();
        var handler = new InMemoryQueryHandler<>(new MemberConfig(), () -> members);

        assertThat(ask(handler, List.of("1"), List.of("Name")).items()).isEmpty();
        members.add(BOSS);
        assertThat(ask(handler, List.of("1"), List.of("Name")).items()).singleElement()
                .satisfies(item -> assertThat(item.values()).containsExactly(new ValueResponse("Name", "\"Ann\"")));
    }

    @Test
    void reportsAnExpressionThatDoesNotTokenize() {
        var handler = new InMemoryQueryHandler<>(new MemberConfig(), List.of(BOSS));

        var response = ask(handler, List.of("1"), List.of("Name = 'Ann", "Name"));

        assertThat(response.errors()).singleElement().satisfies(e -> {
            assertThat(e.expression()).isEqualTo("Name = 'Ann");
            assertThat(e.message()).contains("Unterminated string");
        });
        assertThat(response.items()).singleElement().satisfies(item -> assertThat(item.values())
                .containsExactly(new ValueResponse("Name", "\"Ann\"")));
    }

    @Test
    void resolvesExposedNamesOnNestedEntitiesOfTheSameType() {
        var handler = new InMemoryQueryHandler<>(new MemberConfig(), List.of(BOSS, DEV));

        var response = ask(handler, List.of("2"), List.of("Manager.MemberEmail", "Manager?.Manager?.MemberEmail"));

        assertThat(response.errors()).isEmpty();
        assertThat(response.items()).singleElement().satisfies(item -> assertThat(item.values()).containsExactly(
                new ValueResponse("Manager.MemberEmail", "\"ann@example.com\""),
                new ValueResponse("Manager?.Manager?.MemberEmail", null)));
    }

    @Test
    void failsTheRequestWhenAPropertyThrows() {
        var handler = new InMemoryQueryHandler<>(new FaultyConfig(), List.of(new Faulty()));

        assertThatThrownBy(() -> ask(handler, List.of("1"), List.of("Name")))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("database is down");
    }
}

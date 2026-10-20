package io.github.quyvu01.jfxmap.provider;

import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.response.DataResponse;
import io.github.quyvu01.jfxmap.response.ExpressionError;
import io.github.quyvu01.jfxmap.response.MapRequest;
import io.github.quyvu01.jfxmap.response.ValueResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityExpressionEvaluatorTest {
    record Item(long id, String name, String code) {
    }

    static class ItemConfig extends EntityConfigureOf<Item> {
        @Override
        protected void configure() {
            id(Item::id);
            defaultProperty(Item::name);
            useDistributedKey("ItemOf");
            exposedName(Item::code, "ItemCode");
        }
    }

    static class NoKeyConfig extends EntityConfigureOf<Item> {
        @Override
        protected void configure() {
            id(Item::id);
        }
    }

    private final EntityExpressionEvaluator<Item> evaluator =
            new EntityExpressionEvaluator<>(new ItemConfig(), Clock.systemUTC());

    @Test
    void answersFromTheLookupInTheRequestedOrder() {
        var items = Map.of("1", new Item(1, "Pen", "P-1"), "2", new Item(2, "Ink", "I-2"));

        var response = evaluator.answer(new MapRequest(List.of("2", "9", "1"), Arrays.asList(null, "ItemCode")),
                items::get);

        assertThat(response.items()).containsExactly(
                new DataResponse("2", List.of(new ValueResponse(null, "\"Ink\""), new ValueResponse("ItemCode", "\"I-2\""))),
                new DataResponse("1", List.of(new ValueResponse(null, "\"Pen\""), new ValueResponse("ItemCode", "\"P-1\""))));
        assertThat(response.errors()).isEmpty();
    }

    @Test
    void reportsAnExpressionThatDoesNotParseWithoutAnyEntity() {
        var response = evaluator.answer(new MapRequest(List.of("1"), List.of("Name:")), id -> null);

        assertThat(response.items()).isEmpty();
        assertThat(response.errors()).extracting(ExpressionError::expression).containsExactly("Name:");
    }

    @Test
    void exposesItsConfiguration() {
        assertThat(evaluator.config()).isInstanceOf(ItemConfig.class);
    }

    @Test
    void rejectsAnIncompleteConfigurationAndAMissingLookup() {
        assertThatThrownBy(() -> new EntityExpressionEvaluator<>(new NoKeyConfig(), Clock.systemUTC()))
                .hasMessageContaining("useDistributedKey");
        assertThatThrownBy(() -> evaluator.answer(new MapRequest(List.of(), List.of()), null))
                .isInstanceOf(NullPointerException.class);
    }
}

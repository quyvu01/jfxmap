package io.github.quyvu01.jfxmap.expressions.evaluation;

import io.github.quyvu01.jfxmap.expressions.nodes.FunctionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FunctionType;
import io.github.quyvu01.jfxmap.expressions.nodes.OrderDirection;
import io.github.quyvu01.jfxmap.expressions.nodes.SortKeyNode;
import io.github.quyvu01.jfxmap.expressions.parsing.ExpressionParseException;
import io.github.quyvu01.jfxmap.expressions.parsing.ExpressionParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * :orderBy, :offset and :limit on a collection. Ported from FxMap's CollectionFunctionTests.
 */
class CollectionFunctionsTest {
    record Entry(String name, int day, String by) {
    }

    record Owner(String id, List<Entry> entries) {
    }

    private static final ExpressionEvaluator EVALUATOR = new ExpressionEvaluator();

    private static final Owner DATA = new Owner("O1", List.of(
            new Entry("c", 2, "z"),
            new Entry("a", 2, "y"),
            new Entry("b", 1, "x"),
            new Entry("d", 3, "w")));

    private static String names(String expression) {
        return Canonical.list(EVALUATOR.evaluate(expression, DATA)).stream()
                .map(e -> ((Entry) e).name())
                .collect(Collectors.joining(","));
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", value = {
            "Entries:orderBy(Name) => 'a,b,c,d'",
            "Entries:orderBy(Name asc) => 'a,b,c,d'",
            "Entries:orderBy(Name desc) => 'd,c,b,a'",
            "Entries:orderBy(Day, By) => 'b,a,c,d'",
            "Entries:orderBy(Day, By desc) => 'b,c,a,d'",
            "Entries:orderBy(Day desc, Name asc) => 'd,a,c,b'",
            "Entries:orderBy(Day, Name, By desc) => 'b,a,c,d'"})
    void orderByOrdersByTheFirstKeyThenByTheOthers(String expression, String expected) {
        assertThat(names(expression)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", emptyValue = "", value = {
            "Entries:offset(0) => 'c,a,b,d'",
            "Entries:offset(1) => 'a,b,d'",
            "Entries:offset(4) => ''",
            "Entries:offset(10) => ''",
            "Entries:limit(0) => ''",
            "Entries:limit(2) => 'c,a'",
            "Entries:limit(10) => 'c,a,b,d'"})
    void offsetAndLimit(String expression, String expected) {
        assertThat(names(expression)).isEqualTo(expected);
    }

    private static Entry one(String expression) {
        return (Entry) EVALUATOR.evaluate(expression, DATA);
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", value = {
            "Entries:first => c",
            "Entries:last => d",
            "Entries:orderBy(Name):first => a",
            "Entries:orderBy(Name):last => d",
            "Entries:orderBy(Day desc, Name):first => d",
            "Entries:orderBy(Day, By desc):last => d",
            "Entries:offset(1):first => a",
            "Entries:limit(2):last => a"})
    void firstAndLastGiveOneItem(String expression, String expected) {
        assertThat(one(expression).name()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Entries:first", "Entries:last"})
    void firstAndLastOfAnEmptyCollectionAreNull(String expression) {
        var empty = new Owner("O2", List.of());

        assertThat(EVALUATOR.evaluate(expression, empty)).isNull();
    }

    @Test
    void offsetAndLimitAreAPageOfTheOrderedCollection() {
        assertThat(names("Entries:orderBy(Name desc):offset(1):limit(2)")).isEqualTo("c,b");
    }

    @Test
    void offsetBeforeOrderByOrdersOnlyWhatIsLeft() {
        assertThat(names("Entries:offset(1):orderBy(Name)")).isEqualTo("a,b,d");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Entries:offset(-1)",
            "Entries:limit(1.5)",
            "Entries:limit(Name)",
            "Entries:orderBy(Missing)",
            "Entries:first(Name)"})
    void aWrongArgumentIsRejected(String expression) {
        assertThatThrownBy(() -> names(expression)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void anOrderByOfAnEmptyCollectionStillChecksTheProperty() {
        var empty = new Owner("O2", List.of());

        assertThatThrownBy(() -> EVALUATOR.evaluate("Entries:orderBy(Missing)", empty))
                .isInstanceOf(ExpressionEvaluationException.class);
    }

    @Test
    void orderByParsesTheKeysWithTheirDirections() {
        var node = (FunctionNode) ExpressionParser.parse("Entries:orderBy(Day, By desc)");

        assertThat(node.functionName()).isEqualTo(FunctionType.ORDER_BY);
        assertThat(node.getArguments()).containsExactly(
                new SortKeyNode("Day", OrderDirection.ASC), new SortKeyNode("By", OrderDirection.DESC));
    }

    private static String json(String expression) {
        return Canonical.of(EVALUATOR.evaluate(expression, DATA));
    }

    // Data, in the order of the list: c(Day 2, z), a(Day 2, y), b(Day 1, x), d(Day 3, w)
    @ParameterizedTest
    @CsvSource(delimiterString = " => ", quoteCharacter = '`', value = {
            // order, page, then project the page
            "Entries:orderBy(Day, By desc):offset(1):limit(2).{Name, Day} => [{\"Name\":\"c\",\"Day\":2},{\"Name\":\"a\",\"Day\":2}]",
            "Entries:orderBy(Name):limit(2).{Name} => [{\"Name\":\"a\"},{\"Name\":\"b\"}]",
            // one item, then project it or read one property of it
            "Entries:orderBy(Name desc):first.{Name, Day} => {\"Name\":\"d\",\"Day\":3}",
            "Entries:orderBy(Name):first.{Name} => {\"Name\":\"a\"}",
            "Entries:orderBy(Name):first.Name => \"a\"",
            // a filter before the order
            "Entries:where(Day > 1):orderBy(Name desc):limit(1) => [{\"Name\":\"d\",\"Day\":3,\"By\":\"w\"}]",
            // a filter before the order, then the projection or a property of the item
            "Entries:where(Day > 1):orderBy(Name desc):first.{Name} => {\"Name\":\"d\"}",
            "Entries:where(Day > 1):orderBy(Name desc):first.{Name, Day} => {\"Name\":\"d\",\"Day\":3}",
            "Entries:where(Day > 1):orderBy(Name):limit(2).{Name} => [{\"Name\":\"a\"},{\"Name\":\"c\"}]",
            "Entries:where(Day > 1):first.{Name} => {\"Name\":\"c\"}",
            "Entries:where(Day > 1):orderBy(Name desc):first.Name => \"d\"",
            // where can follow other functions, and a function or a second where can follow it
            "Entries:orderBy(Name):where(Day > 1).{Name} => [{\"Name\":\"a\"},{\"Name\":\"c\"},{\"Name\":\"d\"}]",
            "Entries:where(Day > 1):where(By != 'z').{Name} => [{\"Name\":\"a\"},{\"Name\":\"d\"}]",
            "Entries:where(Day > 1, By != 'z'):count => 2",
            "Entries:limit(3):where(Day > 1):count => 2",
            "Entries:where(Name startswith 'a' || Name = 'd'):orderBy(Name desc).{Name} => [{\"Name\":\"d\"},{\"Name\":\"a\"}]",
            "Entries:where(Day > 5):first => null",
            "Entries:where(Day > 5):count => 0",
            // aggregate or distinct values of a page
            "Entries:orderBy(Name):limit(2):count => 2",
            "Entries:orderBy(Name):limit(3):sum(Day) => 5",
            "Entries:orderBy(Name):offset(1):limit(2):distinct(By) => [\"x\",\"z\"]"})
    void functionsChainWithProjectionsAndAggregates(String expression, String expected) {
        assertThat(json(expression)).isEqualTo(Canonical.ofJson(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Entries:where(Day > 1):orderBy(Name) garbage",
            "Entries:first )",
            "Entries:limit(2) Name",
            "Name }"})
    void textThatIsLeftAfterTheExpressionIsAnError(String expression) {
        assertThatThrownBy(() -> ExpressionParser.parse(expression)).isInstanceOf(ExpressionParseException.class);
    }
}

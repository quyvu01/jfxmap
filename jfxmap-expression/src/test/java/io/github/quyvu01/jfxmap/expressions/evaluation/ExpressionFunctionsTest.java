package io.github.quyvu01.jfxmap.expressions.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every function of the expression language, evaluated in memory. The expected values are the ones that
 * docs/expression.md gives, not what the code happens to answer. Ported from FxMap's ExpressionFunctionTests.
 */
class ExpressionFunctionsTest {
    record Line(String status, BigDecimal total, int year, int quantity) {
    }

    record Address(String city) {
    }

    record Person(String id, String name, String lastName, String padded, String tags, String nick,
                  LocalDateTime createdAt, LocalDateTime born, BigDecimal price, BigDecimal tax, BigDecimal balance,
                  int value, int quantity, int score, boolean active, Address address, List<Line> orders,
                  List<Line> noOrders) {
    }

    // Today is 2026-01-10 for :daysAgo
    private static final Clock CLOCK = Clock.fixed(LocalDateTime.of(2026, 1, 10, 12, 0).toInstant(ZoneOffset.UTC),
            ZoneOffset.UTC);

    private static final ExpressionEvaluator EVALUATOR = new ExpressionEvaluator(new BeanPropertyAccessor(), CLOCK);

    private static Person data(Address address) {
        return new Person("P1", "John", "Doe", "  john  ", "a,b,c", null,
                LocalDateTime.of(2024, 12, 25, 14, 30, 45), // a Wednesday
                LocalDateTime.of(2026, 1, 5, 0, 0), // 5 days before today
                new BigDecimal("10.456"), new BigDecimal("2"), new BigDecimal("-100"), 10, 3, 85, true, address,
                List.of(new Line("Done", new BigDecimal("100"), 2024, 1),
                        new Line("Done", new BigDecimal("200"), 2024, 2),
                        new Line("Pending", new BigDecimal("300"), 2025, 5)),
                List.of());
    }

    private static Object evaluate(String expression, Person person) {
        return EVALUATOR.evaluate(expression, person);
    }

    private static void check(String expression, String expected) {
        assertThat(Canonical.of(evaluate(expression, data(null))))
                .as(expression)
                .isEqualTo(Canonical.ofJson(expected));
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", quoteCharacter = '`', value = {
            "Name:upper => \"JOHN\"",
            "Name:lower => \"john\"",
            "Padded:trim => \"john\"",
            "Name:substring(0, 3) => \"Joh\"",
            "Name:substring(1) => \"ohn\"",
            "Name:substring(1, 2) => \"oh\"",
            "Name:replace('o', 'a') => \"Jahn\"",
            "Name:concat(' ', LastName) => \"John Doe\"",
            "Name:concat('-', 'x', '-', LastName) => \"John-x-Doe\"",
            "Tags:split(',') => [\"a\",\"b\",\"c\"]"})
    void stringFunctions(String expression, String expected) {
        check(expression, expected);
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", quoteCharacter = '`', value = {
            "Padded:trim:upper:substring(0, 3) => \"JOH\"",
            "Name:upper:lower => \"john\"",
            "Name:concat(' ', LastName):upper => \"JOHN DOE\"",
            "Name:replace('o', '0'):upper => \"J0HN\""})
    void stringFunctionsChain(String expression, String expected) {
        check(expression, expected);
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", quoteCharacter = '`', value = {
            "CreatedAt:year => 2024",
            "CreatedAt:month => 12",
            "CreatedAt:day => 25",
            "CreatedAt:hour => 14",
            "CreatedAt:minute => 30",
            "CreatedAt:second => 45",
            "CreatedAt:dayOfWeek => 3", // Sunday is 0
            "Born:daysAgo => 5",
            "CreatedAt:format('yyyy-MM-dd') => \"2024-12-25\"",
            "CreatedAt:format('dd/MM/yyyy HH:mm:ss') => \"25/12/2024 14:30:45\"",
            "CreatedAt:year:add(1) => 2025"})
    void dateFunctions(String expression, String expected) {
        check(expression, expected);
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", quoteCharacter = '`', value = {
            "Price:round(2) => 10.46",
            "Price:round => 10",
            "Price:round(0) => 10",
            "Price:floor => 10",
            "Price:ceil => 11",
            "Balance:abs => 100",
            "Price:add(Tax) => 12.456",
            "Price:add(1) => 11.456",
            "Price:subtract(Tax) => 8.456",
            "Price:subtract(10) => 0.456",
            "Price:multiply(Quantity) => 31.368",
            "Price:multiply(2) => 20.912",
            "Price:divide(Tax) => 5.228",
            "Price:divide(2) => 5.228",
            "Value:mod(3) => 1",
            "Value:pow(2) => 100",
            "Price:add(Tax):round(2) => 12.46",
            "Value:add(5):multiply(2) => 30"})
    void mathFunctions(String expression, String expected) {
        check(expression, expected);
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", quoteCharacter = '`', value = {
            "Orders:count => 3",
            "Orders:sum(Total) => 600",
            "Orders:avg(Total) => 200",
            "Orders:min(Total) => 100",
            "Orders:max(Total) => 300",
            "Orders:where(Status = 'Done'):count => 2",
            "Orders:where(Status = 'Done'):sum(Total) => 300",
            "Orders:where(Year = 2025):avg(Total) => 300",
            "NoOrders:count => 0",
            "NoOrders:sum(Total) => 0",
            "Name:count => 4"})
    void aggregations(String expression, String expected) {
        check(expression, expected);
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", quoteCharacter = '`', value = {
            "Orders:any => true",
            "NoOrders:any => false",
            "Orders:any(Status = 'Done') => true",
            "Orders:any(Total > 1000) => false",
            "Orders:all(Total > 0) => true",
            "Orders:all(Total > 100) => false",
            "Orders:all(Status != 'Cancelled') => true",
            "NoOrders:all(Total > 0) => true"})
    void booleanFunctions(String expression, String expected) {
        check(expression, expected);
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", quoteCharacter = '`', value = {
            "Orders:where(Total >= 200 && Status = 'Done'):count => 1",
            "Orders:where(Status = 'Done' || Total > 250):count => 3",
            "Orders:where(Status = 'Done', Total > 150):count => 1", // a comma is AND
            "Orders:where(Status != 'Done'):count => 1",
            "Orders:where(Status contains 'on'):count => 2",
            "Orders:where(Status startswith 'Do'):count => 2",
            "Orders:where(Status endswith 'ing'):count => 1",
            "Orders:where(Total < 200):count => 1",
            "Orders:where(Total <= 200):count => 2",
            "Orders:where(Total > 100 && Total < 300):count => 1",
            "Orders:where(Status:lower = 'done'):count => 2",
            "Orders:where(Status:upper = 'PENDING'):count => 1",
            "Orders:where(Quantity > 1):sum(Total) => 500"})
    void filters(String expression, String expected) {
        check(expression, expected);
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", quoteCharacter = '`', value = {
            "Nick ?? Name => \"John\"",
            "Nick ?? Name ?? 'x' => \"John\"",
            "Nick ?? 'Anonymous' => \"Anonymous\"",
            "Address?.City ?? 'Unknown' => \"Unknown\"",
            "Active = true ? 'Yes' : 'No' => \"Yes\"",
            "Active = false ? 'Yes' : 'No' => \"No\"",
            "Score >= 90 ? 'A' : Score >= 80 ? 'B' : Score >= 70 ? 'C' : 'F' => \"B\"",
            "Score >= 95 ? 'A' : Score >= 80 ? 'B' : 'F' => \"B\"",
            "Orders:any(Status = 'Pending') ? 'Has' : 'None' => \"Has\"",
            "Orders:any(Status = 'Cancelled') ? 'Has' : 'None' => \"None\"",
            "Orders:count > 2 ? 'Many' : 'Few' => \"Many\"",
            "Name = 'John' && Score > 80 ? 'Hit' : 'Miss' => \"Hit\"",
            "Name = 'John' && Score > 90 ? 'Hit' : 'Miss' => \"Miss\"",
            "Name = 'Jane' || Score > 80 ? 'Hit' : 'Miss' => \"Hit\"",
            "Name = 'Jane' || Score > 90 ? 'Hit' : 'Miss' => \"Miss\"",
            "Name = 'Jane' && Score > 80 || Active = true ? 'Hit' : 'Miss' => \"Hit\"", // && binds tighter than ||
            "Name = 'John' || Name = 'Jane' && Score > 90 ? 'Hit' : 'Miss' => \"Hit\""})
    void coalesceAndTernary(String expression, String expected) {
        check(expression, expected);
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", quoteCharacter = '`', value = {
            "{Id, Name} => {\"Id\":\"P1\",\"Name\":\"John\"}",
            "{Id, Name as Given} => {\"Id\":\"P1\",\"Given\":\"John\"}",
            "{Name:upper as Loud, Padded:trim as Clean} => {\"Loud\":\"JOHN\",\"Clean\":\"john\"}",
            "{(Nick ?? Name) as DisplayName} => {\"DisplayName\":\"John\"}",
            "{(Active = true ? 'Yes' : 'No') as IsActive} => {\"IsActive\":\"Yes\"}",
            "{(Score >= 80 ? 'High' : 'Low') as Tier} => {\"Tier\":\"High\"}",
            "{CreatedAt:format('yyyy-MM-dd') as Date} => {\"Date\":\"2024-12-25\"}",
            "{Orders:count as N, Orders:sum(Total) as Sum} => {\"N\":3,\"Sum\":600}",
            "Orders.{Status, Total} => [{\"Status\":\"Done\",\"Total\":100},{\"Status\":\"Done\",\"Total\":200},{\"Status\":\"Pending\",\"Total\":300}]",
            "Orders.{Status, Total:multiply(2) as Double} => [{\"Status\":\"Done\",\"Double\":200},{\"Status\":\"Done\",\"Double\":400},{\"Status\":\"Pending\",\"Double\":600}]"})
    void projections(String expression, String expected) {
        check(expression, expected);
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", quoteCharacter = '`', value = {
            "Orders:distinct(Status) => [\"Done\",\"Pending\"]",
            "Orders:distinct(Year) => [2024,2025]",
            "Orders:distinct(Status):count => 2",
            "Orders:where(Year = 2024):distinct(Status) => [\"Done\"]"})
    void distinct(String expression, String expected) {
        check(expression, expected);
    }

    @ParameterizedTest
    @CsvSource(delimiterString = " => ", quoteCharacter = '`', value = {
            "Orders:groupBy(Status).{Status, :count as Count} => [{\"Status\":\"Done\",\"Count\":2},{\"Status\":\"Pending\",\"Count\":1}]",
            "Orders:groupBy(Status).{Status, :count as Count, :sum(Total) as Revenue} => [{\"Status\":\"Done\",\"Count\":2,\"Revenue\":300},{\"Status\":\"Pending\",\"Count\":1,\"Revenue\":300}]",
            "Orders:groupBy(Status).{Status, :avg(Total) as Avg, :min(Total) as Min, :max(Total) as Max} => [{\"Status\":\"Done\",\"Avg\":150,\"Min\":100,\"Max\":200},{\"Status\":\"Pending\",\"Avg\":300,\"Min\":300,\"Max\":300}]",
            "Orders:groupBy(Year, Status).{Year, Status, :count as N} => [{\"Year\":2024,\"Status\":\"Done\",\"N\":2},{\"Year\":2025,\"Status\":\"Pending\",\"N\":1}]",
            "Orders:where(Total > 100):groupBy(Status).{Status, :count as N} => [{\"Status\":\"Done\",\"N\":1},{\"Status\":\"Pending\",\"N\":1}]"})
    void groupBy(String expression, String expected) {
        check(expression, expected);
    }

    @Test
    void aNullSafeNavigationGivesNullWhenTheNestedObjectIsMissing() {
        // (Address.City without the `?` is not null-safe: it fails, as LINQ over objects does)
        check("Address?.City", "null");
        var withAddress = data(new Address("Hanoi"));
        assertThat(Canonical.of(evaluate("Address.City", withAddress))).isEqualTo("\"Hanoi\"");
        assertThat(Canonical.of(evaluate("Address?.City", withAddress))).isEqualTo("\"Hanoi\"");
        assertThat(Canonical.of(evaluate("Address?.City ?? 'Unknown'", withAddress))).isEqualTo("\"Hanoi\"");
        assertThatThrownBy(() -> evaluate("Address.City", data(null)))
                .isInstanceOf(ExpressionEvaluationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Missing",
            "Name:nope",
            "Orders:sum(Nope)",
            "Orders:where(Nope = 1)",
            "Name:substring('x')",
            "NoOrders:sum(Nope)"})
    void aWrongExpressionFailsInsteadOfAnsweringSomething(String expression) {
        assertThatThrownBy(() -> evaluate(expression, data(null))).isInstanceOf(RuntimeException.class);
    }
}

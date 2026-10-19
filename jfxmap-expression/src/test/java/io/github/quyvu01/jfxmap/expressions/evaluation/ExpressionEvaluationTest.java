package io.github.quyvu01.jfxmap.expressions.evaluation;

import io.github.quyvu01.jfxmap.expressions.nodes.AggregationNode;
import io.github.quyvu01.jfxmap.expressions.nodes.AggregationType;
import io.github.quyvu01.jfxmap.expressions.nodes.FilterNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FunctionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FunctionType;
import io.github.quyvu01.jfxmap.expressions.nodes.NavigationNode;
import io.github.quyvu01.jfxmap.expressions.nodes.OrderDirection;
import io.github.quyvu01.jfxmap.expressions.nodes.SortKeyNode;
import io.github.quyvu01.jfxmap.expressions.parsing.ExpressionParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The expression features over a graph of objects: filters, ordering and paging, projections, functions and chains.
 * Ported from FxMap's ExpressionIntegrationTests, which run the same expressions on an in-memory database; the parts
 * that test FxMap's projection builder (the id and default property columns, the metadata, the transformer) are not
 * part of the expression module.
 */
class ExpressionEvaluationTest {
    record TestCountry(String id, String name, String code, int population, List<TestProvince> provinces) {
    }

    record TestProvince(String id, String name, String code, int area, List<TestCity> cities) {
    }

    record TestCity(String id, String name, int population, boolean isCapital, int foundedYear) {
    }

    record TestCustomer(String id, String name, String email, int age, boolean isVip, LocalDateTime createdAt,
                        List<TestOrder> orders) {
    }

    record TestOrder(String id, String status, BigDecimal total, LocalDateTime orderDate, List<TestOrderItem> items) {
    }

    record TestOrderItem(String id, String productName, int quantity, BigDecimal price, String category) {
    }

    private static final ExpressionEvaluator EVALUATOR = new ExpressionEvaluator();

    // Test data

    private static final LocalDateTime NOW = LocalDateTime.now();

    private static final List<TestCountry> COUNTRIES = List.of(
            new TestCountry("country-usa", "United States", "US", 331000000, List.of(
                    new TestProvince("province-ca", "California", "CA", 423967, List.of(
                            new TestCity("city-la", "Los Angeles", 3900000, false, 1781),
                            new TestCity("city-sf", "San Francisco", 870000, false, 1776),
                            new TestCity("city-sac", "Sacramento", 500000, true, 1850))),
                    new TestProvince("province-ny", "New York", "NY", 141297, List.of(
                            new TestCity("city-nyc", "New York City", 8400000, false, 1624),
                            new TestCity("city-alb", "Albany", 98000, true, 1614))),
                    new TestProvince("province-tx", "Texas", "TX", 695662, List.of(
                            new TestCity("city-hou", "Houston", 2300000, false, 1837),
                            new TestCity("city-dal", "Dallas", 1300000, false, 1841),
                            new TestCity("city-aus", "Austin", 950000, true, 1839),
                            new TestCity("city-sat", "San Antonio", 1500000, false, 1718))))),
            new TestCountry("country-jpn", "Japan", "JP", 126000000, List.of(
                    new TestProvince("province-tokyo", "Tokyo", "TK", 2194, List.of(
                            new TestCity("city-tokyo", "Tokyo", 13960000, true, 1457),
                            new TestCity("city-shibuya", "Shibuya", 230000, false, 1932))),
                    new TestProvince("province-osaka", "Osaka", "OS", 1905, List.of(
                            new TestCity("city-osaka", "Osaka", 2750000, false, 1583))))),
            // No provinces, for the edge cases
            new TestCountry("country-empty", "Empty Country", "XX", 0, List.of()));

    private static final List<TestCustomer> CUSTOMERS = List.of(
            new TestCustomer("customer-john", "John Doe", "john@example.com", 35, true, NOW.minusYears(3), List.of(
                    new TestOrder("order-1", "Completed", new BigDecimal("1500.00"), NOW.minusDays(30), List.of(
                            item("item-1-1", "Laptop", 1, "1200.00", "Electronics"),
                            item("item-1-2", "Mouse", 2, "150.00", "Electronics"))),
                    new TestOrder("order-2", "Completed", new BigDecimal("350.00"), NOW.minusDays(20), List.of(
                            item("item-2-1", "Book", 5, "50.00", "Books"),
                            item("item-2-2", "Pen Set", 2, "50.00", "Stationery"))),
                    new TestOrder("order-3", "Pending", new BigDecimal("2500.00"), NOW.minusDays(5), List.of(
                            item("item-3-1", "Monitor", 2, "800.00", "Electronics"),
                            item("item-3-2", "Keyboard", 1, "250.00", "Electronics"),
                            item("item-3-3", "Webcam", 1, "150.00", "Electronics"),
                            item("item-3-4", "Desk Lamp", 2, "250.00", "Home"))),
                    new TestOrder("order-4", "Cancelled", new BigDecimal("100.00"), NOW.minusDays(60), List.of(
                            item("item-4-1", "Notebook", 10, "10.00", "Stationery"))))),
            new TestCustomer("customer-jane", "Jane Smith", "jane@example.com", 28, false, NOW.minusYears(1), List.of(
                    new TestOrder("order-5", "Completed", new BigDecimal("800.00"), NOW.minusDays(10), List.of(
                            item("item-5-1", "Headphones", 1, "300.00", "Electronics"),
                            item("item-5-2", "Phone Case", 3, "50.00", "Accessories"),
                            item("item-5-3", "Charger", 2, "200.00", "Electronics"))))),
            // No orders, for the edge cases
            new TestCustomer("customer-bob", "Bob Wilson", "bob@example.com", 45, true, NOW.minusYears(5), List.of()),
            new TestCustomer("customer-alice", "Alice Brown", "alice@example.com", 32, false, NOW.minusMonths(6), List.of(
                    new TestOrder("order-6", "Completed", new BigDecimal("5000.00"), NOW.minusDays(2), List.of(
                            item("item-6-1", "MacBook Pro", 1, "2500.00", "Electronics"),
                            item("item-6-2", "iPad", 1, "1500.00", "Electronics"),
                            item("item-6-3", "AirPods", 2, "500.00", "Electronics"))),
                    new TestOrder("order-7", "Pending", new BigDecimal("150.00"), NOW.minusDays(1), List.of(
                            item("item-7-1", "USB Cable", 5, "20.00", "Accessories"),
                            item("item-7-2", "Screen Protector", 2, "25.00", "Accessories"))))));

    private static TestOrderItem item(String id, String name, int quantity, String price, String category) {
        return new TestOrderItem(id, name, quantity, new BigDecimal(price), category);
    }

    private static Object customer(String id, String expression) {
        var customer = CUSTOMERS.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
        return EVALUATOR.evaluate(expression, customer);
    }

    private static Object province(String id, String expression) {
        var province = COUNTRIES.stream().flatMap(c -> c.provinces().stream())
                .filter(p -> p.id().equals(id)).findFirst().orElseThrow();
        return EVALUATOR.evaluate(expression, province);
    }

    private static Object country(String id, String expression) {
        var country = COUNTRIES.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
        return EVALUATOR.evaluate(expression, country);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Object value) {
        return (Map<String, Object>) value;
    }

    private static BigDecimal decimal(Object value) {
        return (BigDecimal) value;
    }

    // region Ordering and paging

    @Test
    void orderByNameReturnsOrderedCollection() {
        var node = ExpressionParser.parse("Cities:orderBy(Name)");
        assertThat(((FunctionNode) node).functionName()).isEqualTo(FunctionType.ORDER_BY);

        var cities = rows(province("province-ca", "Cities:orderBy(Name).{Id, Name}"));
        assertThat(cities).hasSize(3);

        // Ordered by Name ascending
        assertThat(cities.get(0).get("Name")).isEqualTo("Los Angeles");
        assertThat(cities.get(1).get("Name")).isEqualTo("Sacramento");
        assertThat(cities.get(2).get("Name")).isEqualTo("San Francisco");
    }

    @Test
    void orderByPopulationDescReturnsOrderedCollection() {
        var cities = rows(province("province-ca", "Cities:orderBy(Population desc).{Name, Population}"));
        assertThat(cities).hasSize(3);

        assertThat(cities.get(0).get("Name")).isEqualTo("Los Angeles");
        assertThat(cities.get(0).get("Population")).isEqualTo(3900000);
        assertThat(cities.get(1).get("Name")).isEqualTo("San Francisco");
        assertThat(cities.get(2).get("Name")).isEqualTo("Sacramento");
    }

    @Test
    void firstReturnsFirstOrderedItem() {
        var firstCity = row(province("province-tx", "Cities:orderBy(Name):first.{Id, Name}"));
        assertThat(firstCity).isNotNull();
        assertThat(firstCity.get("Name")).isEqualTo("Austin"); // First alphabetically
    }

    @Test
    void lastReturnsLastOrderedItem() {
        var lastCity = row(province("province-tx", "Cities:orderBy(Population desc):last.{Name, Population}"));
        assertThat(lastCity).isNotNull();
        // desc Population order: Houston(2.3M), San Antonio(1.5M), Dallas(1.3M), Austin(950K)
        assertThat(lastCity.get("Name")).isEqualTo("Austin");
    }

    @Test
    void limitReturnsPagedCollection() {
        var cities = rows(province("province-tx", "Cities:orderBy(Name):limit(2).{Name}"));
        assertThat(cities).hasSize(2);
        assertThat(cities.get(0).get("Name")).isEqualTo("Austin");
        assertThat(cities.get(1).get("Name")).isEqualTo("Dallas");
    }

    @Test
    void offsetAndLimitReturnPaginatedResults() {
        var cities = rows(province("province-tx", "Cities:orderBy(Name):offset(1):limit(2).{Name}"));
        assertThat(cities).hasSize(2);
        // Order: Austin, Dallas, Houston, San Antonio; skip Austin, take Dallas and Houston
        assertThat(cities.get(0).get("Name")).isEqualTo("Dallas");
        assertThat(cities.get(1).get("Name")).isEqualTo("Houston");
    }

    // endregion

    // region Filters

    @Test
    void filterSimpleEqualsReturnsMatchingItems() {
        assertThat(customer("customer-john", "Orders:where(Status = 'Completed'):count")).isEqualTo(2);
    }

    @Test
    void filterGreaterThanReturnsMatchingItems() {
        // order-1 (1500), order-3 (2500)
        assertThat(customer("customer-john", "Orders:where(Total > 1000):count")).isEqualTo(2);
    }

    @Test
    void filterLessThanOrEqualReturnsMatchingItems() {
        // order-2 (350), order-4 (100)
        assertThat(customer("customer-john", "Orders:where(Total <= 500):count")).isEqualTo(2);
    }

    @Test
    void filterNotEqualsReturnsNonMatchingItems() {
        assertThat(customer("customer-john", "Orders:where(Status != 'Cancelled'):count")).isEqualTo(3);
    }

    @Test
    void filterContainsReturnsMatchingItems() {
        // "Pending" contains "end"
        assertThat(customer("customer-john", "Orders:where(Status contains 'end'):count")).isEqualTo(1);
    }

    @Test
    void filterStartsWithReturnsMatchingItems() {
        // "Completed" (2) + "Cancelled" (1)
        assertThat(customer("customer-john", "Orders:where(Status startswith 'C'):count")).isEqualTo(3);
    }

    @Test
    void filterEndsWithReturnsMatchingItems() {
        // "Completed" (2) + "Cancelled" (1)
        assertThat(customer("customer-john", "Orders:where(Status endswith 'ed'):count")).isEqualTo(3);
    }

    @Test
    void filterAndConditionReturnsMatchingItems() {
        // Only order-1 (Status=Completed AND Total=1500)
        assertThat(customer("customer-john", "Orders:where(Status = 'Completed', Total > 500):count")).isEqualTo(1);
    }

    @Test
    void filterOrConditionReturnsMatchingItems() {
        // order-3 (Pending), order-4 (Cancelled)
        assertThat(customer("customer-john", "Orders:where(Status = 'Pending' || Status = 'Cancelled'):count"))
                .isEqualTo(2);
    }

    // endregion

    // region Aggregate functions

    @Test
    void countReturnsCollectionCount() {
        assertThat(customer("customer-john", "Orders:count")).isEqualTo(4);
    }

    @Test
    void sumReturnsSum() {
        // 1500 + 350 + 2500 + 100
        assertThat(decimal(customer("customer-john", "Orders:sum(Total)"))).isEqualByComparingTo("4450.00");
    }

    @Test
    void avgReturnsAverage() {
        // 4450 / 4
        assertThat(decimal(customer("customer-john", "Orders:avg(Total)"))).isEqualByComparingTo("1112.50");
    }

    @Test
    void minReturnsMinimum() {
        assertThat(decimal(customer("customer-john", "Orders:min(Total)"))).isEqualByComparingTo("100.00");
    }

    @Test
    void maxReturnsMaximum() {
        assertThat(decimal(customer("customer-john", "Orders:max(Total)"))).isEqualByComparingTo("2500.00");
    }

    @Test
    void anyReturnsTrueWhenMatchExists() {
        assertThat(customer("customer-john", "Orders:any(Status = 'Pending')")).isEqualTo(true);
    }

    @Test
    void anyReturnsFalseWhenNoMatch() {
        assertThat(customer("customer-john", "Orders:any(Status = 'Shipped')")).isEqualTo(false);
    }

    @Test
    void allReturnsTrueWhenAllMatch() {
        assertThat(customer("customer-john", "Orders:all(Total > 0)")).isEqualTo(true);
    }

    @Test
    void allReturnsFalseWhenNotAllMatch() {
        // order-4 has Total = 100
        assertThat(customer("customer-john", "Orders:all(Total > 200)")).isEqualTo(false);
    }

    // endregion

    // region String functions

    @Test
    void upperReturnsUppercase() {
        assertThat(customer("customer-john", "Name:upper")).isEqualTo("JOHN DOE");
    }

    @Test
    void lowerReturnsLowercase() {
        assertThat(customer("customer-john", "Email:lower")).isEqualTo("john@example.com");
    }

    @Test
    void countOfAStringReturnsLength() {
        assertThat(customer("customer-john", "Name:count")).isEqualTo(8); // "John Doe".length()
    }

    // endregion

    // region Chains

    @Test
    void filterThenOrderThenProjection() {
        var firstCompletedOrder = row(customer("customer-john",
                "Orders:where(Status = 'Completed'):orderBy(OrderDate):first.{Id, Total}"));
        assertThat(firstCompletedOrder).isNotNull();
        // Completed orders, oldest first: order-1 (30 days ago), order-2 (20 days ago)
        assertThat(firstCompletedOrder.get("Id")).isEqualTo("order-1");
        assertThat(decimal(firstCompletedOrder.get("Total"))).isEqualByComparingTo("1500.00");
    }

    @Test
    void filterThenAggregate() {
        // 1500 + 350
        assertThat(decimal(customer("customer-john", "Orders:where(Status = 'Completed'):sum(Total)")))
                .isEqualByComparingTo("1850.00");
    }

    @Test
    void filteredCollectionMax() {
        // Completed orders: order-1 (1500), order-2 (350)
        assertThat(decimal(customer("customer-john", "Orders:where(Status = 'Completed'):max(Total)")))
                .isEqualByComparingTo("1500.00");
    }

    @Test
    void filteredCollectionMin() {
        assertThat(decimal(customer("customer-john", "Orders:where(Status = 'Completed'):min(Total)")))
                .isEqualByComparingTo("350.00");
    }

    @Test
    void filteredProvincesCount() {
        // Texas (695662), California (423967) > 200000
        assertThat(country("country-usa", "Provinces:where(Area > 200000):count")).isEqualTo(2);
    }

    @Test
    void orderThenTakeWithProjection() {
        var provinces = rows(country("country-usa", "Provinces:orderBy(Area desc):limit(2).{Name, Area}"));
        assertThat(provinces).hasSize(2);
        // Desc by Area: Texas (695662), California (423967), New York (141297)
        assertThat(provinces.get(0).get("Name")).isEqualTo("Texas");
        assertThat(provinces.get(1).get("Name")).isEqualTo("California");
    }

    @Test
    void multipleExpressionsMixedTypes() {
        assertThat(customer("customer-john", "Name")).isEqualTo("John Doe");
        assertThat(customer("customer-john", "Age")).isEqualTo(35);
        assertThat(customer("customer-john", "IsVip")).isEqualTo(true);
        assertThat(customer("customer-john", "Orders:count")).isEqualTo(4);
        assertThat(decimal(customer("customer-john", "Orders:where(Status = 'Completed'):sum(Total)")))
                .isEqualByComparingTo("1850.00");
        // Average of all orders: (1500+350+2500+100)/4
        assertThat(decimal(customer("customer-john", "Orders:avg(Total)"))).isEqualByComparingTo("1112.50");
    }

    // endregion

    // region Edge cases

    @Test
    void emptyCollectionCountReturnsZero() {
        assertThat(customer("customer-bob", "Orders:count")).isEqualTo(0); // Bob has no orders
    }

    @Test
    void emptyCollectionSumReturnsZero() {
        // The zero of the type of Total
        assertThat(customer("customer-bob", "Orders:sum(Total)")).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void filterReturnsEmptyCountReturnsZero() {
        assertThat(customer("customer-john", "Orders:where(Status = 'NonExistent'):count")).isEqualTo(0);
    }

    @Test
    void firstOfAnEmptyCollectionProjectsToNullValues() {
        // The projection of the first item of an empty collection has its properties null
        var dict = row(customer("customer-bob", "Orders:orderBy(OrderDate):first.{Id}"));
        assertThat(dict).isNotNull();
        assertThat(dict).containsEntry("Id", null);
    }

    @Test
    void emptyProvincesCollectionCountReturnsZero() {
        assertThat(country("country-empty", "Provinces:count")).isEqualTo(0);
    }

    @Test
    void projectionOnEmptyCollectionReturnsEmptyList() {
        assertThat(rows(customer("customer-bob", "Orders.{Id, Status}"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Orders:max(Total)", "Orders:min(Total)", "Orders:avg(Total)"})
    void minMaxAverageOnEmptyCollectionReturnNull(String expression) {
        assertThat(customer("customer-bob", expression)).isNull();
    }

    @Test
    void emptyCollectionDoesNotBreakAggregatesOfOtherObjects() {
        var withOrders = CUSTOMERS.stream()
                .filter(c -> EVALUATOR.evaluate("Orders:max(Total)", c) != null)
                .toList();
        assertThat(withOrders).isNotEmpty();
        assertThat(customer("customer-bob", "Orders:max(Total)")).isNull();
        assertThat(customer("customer-bob", "Orders:sum(Total)")).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void anyOnEmptyCollectionReturnsFalse() {
        assertThat(customer("customer-bob", "Orders:any(Status = 'Completed')")).isEqualTo(false);
    }

    @Test
    void allOnEmptyCollectionReturnsTrue() {
        // Vacuous truth
        assertThat(customer("customer-bob", "Orders:all(Status = 'Completed')")).isEqualTo(true);
    }

    // endregion

    // region Root projection

    @Test
    void rootProjectionSimpleFields() {
        var rootProj = row(customer("customer-jane", "{Id, Name, Email, Age}"));
        assertThat(rootProj).isNotNull();
        assertThat(rootProj.get("Id")).isEqualTo("customer-jane");
        assertThat(rootProj.get("Name")).isEqualTo("Jane Smith");
        assertThat(rootProj.get("Email")).isEqualTo("jane@example.com");
        assertThat(rootProj.get("Age")).isEqualTo(28);
    }

    @Test
    void rootProjectionWithAlias() {
        var rootProj = row(customer("customer-jane", "{Id, Name as CustomerName, Age as CustomerAge}"));
        assertThat(rootProj).isNotNull();
        assertThat(rootProj.get("Id")).isEqualTo("customer-jane");
        assertThat(rootProj.get("CustomerName")).isEqualTo("Jane Smith");
        assertThat(rootProj.get("CustomerAge")).isEqualTo(28);
    }

    // endregion

    // region Coalesce and ternary

    @Test
    void coalesceReturnsFirstNonNull() {
        assertThat(customer("customer-john", "Email ?? Name")).isEqualTo("john@example.com");
    }

    @Test
    void ternaryReturnsCorrectBranchWhenTrue() {
        // John is VIP
        assertThat(customer("customer-john", "IsVip = true ? 'VIP Customer' : 'Regular Customer'"))
                .isEqualTo("VIP Customer");
    }

    @Test
    void ternaryReturnsCorrectBranchWhenFalse() {
        // Jane is not VIP
        assertThat(customer("customer-jane", "IsVip = true ? 'VIP Customer' : 'Regular Customer'"))
                .isEqualTo("Regular Customer");
    }

    // endregion

    // region Collection projection

    @Test
    void collectionProjectionReturnsProjectedProperties() {
        var projectedOrders = rows(customer("customer-john", "Orders.{Id, Status}"));
        assertThat(projectedOrders).hasSize(4); // John has 4 orders
        assertThat(projectedOrders).anyMatch(o -> o.get("Id").equals("order-1") && o.get("Status").equals("Completed"));
        assertThat(projectedOrders).anyMatch(o -> o.get("Id").equals("order-2") && o.get("Status").equals("Completed"));
        assertThat(projectedOrders).anyMatch(o -> o.get("Id").equals("order-3") && o.get("Status").equals("Pending"));
        assertThat(projectedOrders).anyMatch(o -> o.get("Id").equals("order-4") && o.get("Status").equals("Cancelled"));
    }

    @Test
    void collectionProjectionWithFilterReturnsFilteredProjectedProperties() {
        var projectedOrders = rows(customer("customer-john", "Orders:where(Status = 'Completed').{Id, Total}"));
        assertThat(projectedOrders).hasSize(2); // 2 completed orders
        assertThat(projectedOrders).anyMatch(o -> o.get("Id").equals("order-1")
                && decimal(o.get("Total")).compareTo(new BigDecimal("1500.00")) == 0);
        assertThat(projectedOrders).anyMatch(o -> o.get("Id").equals("order-2")
                && decimal(o.get("Total")).compareTo(new BigDecimal("350.00")) == 0);
    }

    @Test
    void collectionProjectionMultipleFieldsReturnsAllFields() {
        var projectedOrders = rows(customer("customer-jane", "Orders.{Id, Status, Total}"));
        assertThat(projectedOrders).hasSize(1); // Jane has 1 order
        assertThat(projectedOrders.get(0).get("Id")).isEqualTo("order-5");
        assertThat(projectedOrders.get(0).get("Status")).isEqualTo("Completed");
        assertThat(decimal(projectedOrders.get(0).get("Total"))).isEqualByComparingTo("800.00");
    }

    @Test
    void collectionProjectionEmptyCollectionReturnsEmptyList() {
        assertThat(rows(customer("customer-bob", "Orders.{Id, Status}"))).isEmpty();
    }

    // endregion

    // region Parser edge cases

    @Test
    void parserOrderByParsesCorrectly() {
        var function = (FunctionNode) ExpressionParser.parse("Items:orderBy(Name)");
        assertThat(function.functionName()).isEqualTo(FunctionType.ORDER_BY);
        assertThat(function.getArguments()).containsExactly(new SortKeyNode("Name", OrderDirection.ASC));
    }

    @Test
    void parserFirstAfterOrderByParsesCorrectly() {
        var first = (FunctionNode) ExpressionParser.parse("Items:orderBy(Price desc):first");
        assertThat(first.functionName()).isEqualTo(FunctionType.FIRST);
        assertThat(((FunctionNode) first.source()).getArguments())
                .containsExactly(new SortKeyNode("Price", OrderDirection.DESC));
    }

    @Test
    void parserLastAfterOrderByParsesCorrectly() {
        var node = (FunctionNode) ExpressionParser.parse("Items:orderBy(CreatedAt):last");
        assertThat(node.functionName()).isEqualTo(FunctionType.LAST);
    }

    @Test
    void parserOffsetAndLimitAfterOrderByParsesCorrectly() {
        var limit = (FunctionNode) ExpressionParser.parse("Items:orderBy(Total desc):offset(5):limit(10)");
        assertThat(limit.functionName()).isEqualTo(FunctionType.TAKE);
        var offset = (FunctionNode) limit.source();
        assertThat(offset.functionName()).isEqualTo(FunctionType.SKIP);
        assertThat(((FunctionNode) offset.source()).functionName()).isEqualTo(FunctionType.ORDER_BY);
    }

    @Test
    void parserComplexChainParsesCorrectly() {
        var node = ExpressionParser.parse(
                "Orders:where(Status = 'Done'):orderBy(OrderDate):limit(2).Items:where(Quantity > 1):sum(Price)");

        // NavigationNode [limit(orderBy(where(Orders))), sum(where(Items))]
        assertThat(node).isInstanceOf(NavigationNode.class);
        var navigation = (NavigationNode) node;
        assertThat(navigation.segments()).hasSize(2);
        assertThat(((FunctionNode) navigation.segments().get(0)).functionName()).isEqualTo(FunctionType.TAKE);
        var sum = (AggregationNode) navigation.segments().get(1);
        assertThat(sum.aggregationType()).isEqualTo(AggregationType.SUM);
        assertThat(sum.source()).isInstanceOf(FilterNode.class);
    }

    // endregion
}

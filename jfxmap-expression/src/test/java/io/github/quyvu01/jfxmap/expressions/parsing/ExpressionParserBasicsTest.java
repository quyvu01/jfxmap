package io.github.quyvu01.jfxmap.expressions.parsing;

import io.github.quyvu01.jfxmap.expressions.nodes.AggregationNode;
import io.github.quyvu01.jfxmap.expressions.nodes.AggregationType;
import io.github.quyvu01.jfxmap.expressions.nodes.BinaryConditionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.BooleanFunctionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.BooleanFunctionType;
import io.github.quyvu01.jfxmap.expressions.nodes.CoalesceNode;
import io.github.quyvu01.jfxmap.expressions.nodes.ComparisonOperator;
import io.github.quyvu01.jfxmap.expressions.nodes.FilterNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FunctionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FunctionType;
import io.github.quyvu01.jfxmap.expressions.nodes.LiteralNode;
import io.github.quyvu01.jfxmap.expressions.nodes.LiteralType;
import io.github.quyvu01.jfxmap.expressions.nodes.LogicalConditionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.LogicalOperator;
import io.github.quyvu01.jfxmap.expressions.nodes.NavigationNode;
import io.github.quyvu01.jfxmap.expressions.nodes.OrderDirection;
import io.github.quyvu01.jfxmap.expressions.nodes.ProjectionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.ProjectionProperty;
import io.github.quyvu01.jfxmap.expressions.nodes.PropertyNode;
import io.github.quyvu01.jfxmap.expressions.nodes.RootProjectionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.SortKeyNode;
import io.github.quyvu01.jfxmap.expressions.nodes.TernaryNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpressionParserBasicsTest {

    // region Simple Property Tests

    @Test
    void parseSimplePropertyReturnsPropertyNode() {
        // Act
        var result = ExpressionParser.parse("Name");

        // Assert
        assertThat(result).isInstanceOf(PropertyNode.class);
        var propertyNode = (PropertyNode) result;
        assertThat(propertyNode.name()).isEqualTo("Name");
        assertThat(propertyNode.isNullSafe()).isFalse();
    }

    @Test
    void parseNullSafePropertyReturnsPropertyNodeWithNullSafe() {
        // Act
        var result = ExpressionParser.parse("Name?");

        // Assert
        assertThat(result).isInstanceOf(PropertyNode.class);
        var propertyNode = (PropertyNode) result;
        assertThat(propertyNode.name()).isEqualTo("Name");
        assertThat(propertyNode.isNullSafe()).isTrue();
    }

    // endregion

    // region Navigation Tests

    @Test
    void parseNavigationReturnsNavigationNode() {
        // Act
        var result = ExpressionParser.parse("Country.Name");

        // Assert
        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(2);
    }

    @Test
    void parseDeepNavigationReturnsNavigationNode() {
        // Act
        var result = ExpressionParser.parse("Country.Province.City.Name");

        // Assert
        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(4);
    }

    @Test
    void parseNullSafeNavigationReturnsNavigationNodeWithNullSafe() {
        // Act
        var result = ExpressionParser.parse("Country?.Name");

        // Assert
        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(2);

        assertThat(navNode.segments().get(0)).isInstanceOf(PropertyNode.class);
        var firstSegment = (PropertyNode) navNode.segments().get(0);
        assertThat(firstSegment.name()).isEqualTo("Country");
        assertThat(firstSegment.isNullSafe()).isTrue();
    }

    // endregion

    // region Filter Tests

    @Test
    void parseFilterWithEqualsReturnsFilterNode() {
        // Act
        var result = ExpressionParser.parse("Orders:where(Status = 'Done')");

        // Assert
        assertThat(result).isInstanceOf(FilterNode.class);
        var filterNode = (FilterNode) result;
        assertThat(filterNode.source()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) filterNode.source()).name()).isEqualTo("Orders");

        assertThat(filterNode.condition()).isInstanceOf(BinaryConditionNode.class);
        var condition = (BinaryConditionNode) filterNode.condition();
        assertThat(condition.operator()).isEqualTo(ComparisonOperator.EQUAL);
    }

    @Test
    void parseFilterWithGreaterThanReturnsFilterNode() {
        // Act
        var result = ExpressionParser.parse("Orders:where(Total > 100)");

        // Assert
        assertThat(result).isInstanceOf(FilterNode.class);
        var filterNode = (FilterNode) result;
        var condition = (BinaryConditionNode) filterNode.condition();
        assertThat(condition.operator()).isEqualTo(ComparisonOperator.GREATER_THAN);
    }

    @Test
    void parseFilterWithLogicalAndReturnsFilterNode() {
        // Act
        var result = ExpressionParser.parse("Orders:where(Status = 'Done' && Total > 100)");

        // Assert
        assertThat(result).isInstanceOf(FilterNode.class);
        var filterNode = (FilterNode) result;
        assertThat(filterNode.condition()).isInstanceOf(LogicalConditionNode.class);
        var logicalCondition = (LogicalConditionNode) filterNode.condition();
        assertThat(logicalCondition.operator()).isEqualTo(LogicalOperator.AND);
    }

    @Test
    void parseFilterWithLogicalOrReturnsFilterNode() {
        // Act
        var result = ExpressionParser.parse("Orders:where(Status = 'Done' || Status = 'Pending')");

        // Assert
        assertThat(result).isInstanceOf(FilterNode.class);
        var filterNode = (FilterNode) result;
        assertThat(filterNode.condition()).isInstanceOf(LogicalConditionNode.class);
        var logicalCondition = (LogicalConditionNode) filterNode.condition();
        assertThat(logicalCondition.operator()).isEqualTo(LogicalOperator.OR);
    }

    @Test
    void parseFilterWithAndKeywordReturnsFilterNode() {
        // Act
        var result = ExpressionParser.parse("Orders:where(Status = 'Done' and Total > 100)");

        // Assert
        assertThat(result).isInstanceOf(FilterNode.class);
        var filterNode = (FilterNode) result;
        assertThat(filterNode.condition()).isInstanceOf(LogicalConditionNode.class);
        var logicalCondition = (LogicalConditionNode) filterNode.condition();
        assertThat(logicalCondition.operator()).isEqualTo(LogicalOperator.AND);
    }

    @Test
    void parseFilterWithOrKeywordReturnsFilterNode() {
        // Act
        var result = ExpressionParser.parse("Orders:where(Status = 'Done' or Status = 'Pending')");

        // Assert
        assertThat(result).isInstanceOf(FilterNode.class);
        var filterNode = (FilterNode) result;
        assertThat(filterNode.condition()).isInstanceOf(LogicalConditionNode.class);
        var logicalCondition = (LogicalConditionNode) filterNode.condition();
        assertThat(logicalCondition.operator()).isEqualTo(LogicalOperator.OR);
    }

    // endregion

    // region Collection Function Tests (orderBy, offset, limit, first, last)

    private static FunctionNode function(String expression, FunctionType type) {
        var result = ExpressionParser.parse(expression);
        assertThat(result).isInstanceOf(FunctionNode.class);
        var node = (FunctionNode) result;
        assertThat(node.functionName()).isEqualTo(type);
        return node;
    }

    @Test
    void parseOrderByDefaultsToAscending() {
        var node = function("Orders:orderBy(OrderDate)", FunctionType.ORDER_BY);

        assertThat(node.source()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) node.source()).name()).isEqualTo("Orders");
        assertThat(node.getArguments()).containsExactly(new SortKeyNode("OrderDate", OrderDirection.ASC));
    }

    @ParameterizedTest
    @CsvSource({
            "Orders:orderBy(OrderDate asc), ASC",
            "Orders:orderBy(OrderDate desc), DESC"
    })
    void parseOrderByWithDirection(String expression, OrderDirection direction) {
        assertThat(function(expression, FunctionType.ORDER_BY).getArguments())
                .containsExactly(new SortKeyNode("OrderDate", direction));
    }

    @Test
    void parseOrderByWithSeveralKeysKeepsTheirOrderAndDirections() {
        var node = function("Orders:orderBy(CreatedAt, CreatedBy desc, Id asc)", FunctionType.ORDER_BY);

        assertThat(node.getArguments()).containsExactly(
                new SortKeyNode("CreatedAt", OrderDirection.ASC),
                new SortKeyNode("CreatedBy", OrderDirection.DESC),
                new SortKeyNode("Id", OrderDirection.ASC));
    }

    @Test
    void parseOffsetThenLimitChainsOnTheOrderedCollection() {
        var limit = function("Orders:orderBy(Total desc):offset(5):limit(10)", FunctionType.TAKE);
        assertThat(limit.getArguments()).hasSize(1);
        assertThat((BigDecimal) ((LiteralNode) limit.getArguments().get(0)).value())
                .isEqualByComparingTo(new BigDecimal("10"));

        assertThat(limit.source()).isInstanceOf(FunctionNode.class);
        var offset = (FunctionNode) limit.source();
        assertThat(offset.functionName()).isEqualTo(FunctionType.SKIP);
        assertThat(offset.getArguments()).hasSize(1);
        assertThat((BigDecimal) ((LiteralNode) offset.getArguments().get(0)).value())
                .isEqualByComparingTo(new BigDecimal("5"));

        assertThat(offset.source()).isInstanceOf(FunctionNode.class);
        var orderBy = (FunctionNode) offset.source();
        assertThat(orderBy.functionName()).isEqualTo(FunctionType.ORDER_BY);
        assertThat(orderBy.getArguments()).containsExactly(new SortKeyNode("Total", OrderDirection.DESC));
    }

    @ParameterizedTest
    @CsvSource({
            "Orders:orderBy(OrderDate):first, FIRST",
            "Orders:orderBy(OrderDate desc):last, LAST"
    })
    void parseFirstAndLastTakeNoArguments(String expression, FunctionType type) {
        var node = function(expression, type);

        assertThat(node.getArguments()).isEmpty();
        assertThat(node.source()).isInstanceOf(FunctionNode.class);
        assertThat(((FunctionNode) node.source()).functionName()).isEqualTo(FunctionType.ORDER_BY);
    }

    @Test
    void parseOrderByThenProjectionProjectsTheOrderedCollection() {
        var result = ExpressionParser.parse("Orders:orderBy(OrderDate).{Id, Status}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;
        assertThat(projNode.source()).isInstanceOf(FunctionNode.class);
        assertThat(((FunctionNode) projNode.source()).functionName()).isEqualTo(FunctionType.ORDER_BY);
    }

    @Test
    void parseFilterThenOrderByOrdersTheFilteredCollection() {
        var node = function("Orders:where(Status = 'Active'):orderBy(CreatedAt desc)", FunctionType.ORDER_BY);

        assertThat(node.getArguments()).containsExactly(new SortKeyNode("CreatedAt", OrderDirection.DESC));
        assertThat(node.source()).isInstanceOf(FilterNode.class);
    }

    @Test
    void parseFilterThenFunctionsThenProjectionKeepsTheProjection() {
        // The projection after a function that follows a filter used to be dropped
        var result = ExpressionParser.parse("Orders:where(Status = 'Done'):orderBy(Total desc):first.{Id, Total}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;
        assertThat(projNode.properties()).hasSize(2);
        assertThat(projNode.source()).isInstanceOf(FunctionNode.class);
        assertThat(((FunctionNode) projNode.source()).functionName()).isEqualTo(FunctionType.FIRST);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Orders[0 asc OrderDate]",
            "Orders[-1 desc OrderDate]",
            "Orders[0 10 asc OrderDate]",
            "Orders[asc OrderDate]",
            "Orders:where(Status = 'Done')[0 asc OrderDate]",
            "Orders[asc OrderDate].{Id}",
            "Orders:orderBy(OrderDate)[0 asc OrderDate]"
    })
    void parseTheRemovedIndexerIsRejectedWithTheFunctionsToUseInstead(String expression) {
        assertThatThrownBy(() -> ExpressionParser.parse(expression))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("no longer supported")
                .hasMessageContaining(":orderBy(");
    }

    @Test
    void parseWhereFiltersTheCollection() {
        var result = ExpressionParser.parse("Orders:where(Status = 'Done' && Total > 100)");

        assertThat(result).isInstanceOf(FilterNode.class);
        var filter = (FilterNode) result;
        assertThat(filter.source()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) filter.source()).name()).isEqualTo("Orders");
        assertThat(filter.condition()).isInstanceOf(LogicalConditionNode.class);
    }

    @Test
    void parseWhereChainsWithTheOtherFunctionsInAnyOrder() {
        // where after orderBy
        var first = function("Orders:orderBy(Date):where(Total > 100):first", FunctionType.FIRST);
        assertThat(first.source()).isInstanceOf(FilterNode.class);
        var filter = (FilterNode) first.source();
        assertThat(filter.source()).isInstanceOf(FunctionNode.class);
        assertThat(((FunctionNode) filter.source()).functionName()).isEqualTo(FunctionType.ORDER_BY);

        // where, orderBy, then a projection
        var projection = ExpressionParser.parse("Orders:where(Status = 'Done'):orderBy(Total desc):limit(3).{Id}");
        assertThat(projection).isInstanceOf(ProjectionNode.class);
        assertThat(((ProjectionNode) projection).source()).isInstanceOf(FunctionNode.class);
        var limit = (FunctionNode) ((ProjectionNode) projection).source();
        assertThat(limit.functionName()).isEqualTo(FunctionType.TAKE);

        // two filters
        var twoFilters = ExpressionParser.parse("Orders:where(Status = 'Done'):where(Total > 100)");
        assertThat(twoFilters).isInstanceOf(FilterNode.class);
        assertThat(((FilterNode) twoFilters).source()).isInstanceOf(FilterNode.class);
    }

    @Test
    void parseWhereWithAFunctionInTheConditionAndAnAggregateAfterIt() {
        var result = ExpressionParser.parse("Orders:where(Items:count > 5):sum(Total)");

        assertThat(result).isInstanceOf(AggregationNode.class);
        var sum = (AggregationNode) result;
        assertThat(sum.source()).isInstanceOf(FilterNode.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Orders(Status = 'Done')",
            "Orders(Status = 'Done'):count",
            "Orders?(Status = 'Done')",
            "Customer.Orders(Total > 1).Items",
            "Orders(Status = 'Done').{Id}"
    })
    void parseTheRemovedFilterIsRejectedWithTheFunctionToUseInstead(String expression) {
        assertThatThrownBy(() -> ExpressionParser.parse(expression))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("no longer supported")
                .hasMessageContaining(":where(");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Orders:where",
            "Orders:where()",
            "Orders:where(Status = 'Done'",
            "Orders:where(Status)",
            "Orders:where(Status = )"
    })
    void parseWrongWhereIsRejected(String expression) {
        assertThatThrownBy(() -> ExpressionParser.parse(expression))
                .isInstanceOf(ExpressionParseException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Orders:orderBy()",
            "Orders:orderBy(Date,)",
            "Orders:orderBy(Date up)",
            "Orders:orderBy(Date desc desc)",
            "Orders:first(Id)",
            "Orders:offset",
            "Orders:limit"
    })
    void parseWrongArgumentsOfTheCollectionFunctionsAreRejected(String expression) {
        assertThatThrownBy(() -> ExpressionParser.parse(expression))
                .isInstanceOf(ExpressionParseException.class);
    }

    // endregion

    // region Function Tests

    @Test
    void parseCountFunctionReturnsFunctionNode() {
        // Act
        var result = ExpressionParser.parse("Orders:count");

        // Assert
        assertThat(result).isInstanceOf(FunctionNode.class);
        var funcNode = (FunctionNode) result;
        assertThat(funcNode.functionName()).isEqualTo(FunctionType.COUNT);
        assertThat(funcNode.source()).isInstanceOf(PropertyNode.class);
    }

    @Test
    void parseSumFunctionReturnsAggregationNode() {
        // Act
        var result = ExpressionParser.parse("Orders:sum(Total)");

        // Assert - sum with argument returns AggregationNode
        assertThat(result).isInstanceOf(AggregationNode.class);
        var aggNode = (AggregationNode) result;
        assertThat(aggNode.aggregationType()).isEqualTo(AggregationType.SUM);
        assertThat(aggNode.propertyName()).isEqualTo("Total");
    }

    @Test
    void parseAvgFunctionReturnsAggregationNode() {
        // Act
        var result = ExpressionParser.parse("Orders:avg(Total)");

        // Assert - avg with argument returns AggregationNode
        assertThat(result).isInstanceOf(AggregationNode.class);
        var aggNode = (AggregationNode) result;
        assertThat(aggNode.aggregationType()).isEqualTo(AggregationType.AVERAGE);
        assertThat(aggNode.propertyName()).isEqualTo("Total");
    }

    @Test
    void parseMinFunctionReturnsAggregationNode() {
        // Act
        var result = ExpressionParser.parse("Orders:min(Total)");

        // Assert - min with argument returns AggregationNode
        assertThat(result).isInstanceOf(AggregationNode.class);
        var aggNode = (AggregationNode) result;
        assertThat(aggNode.aggregationType()).isEqualTo(AggregationType.MIN);
        assertThat(aggNode.propertyName()).isEqualTo("Total");
    }

    @Test
    void parseMaxFunctionReturnsAggregationNode() {
        // Act
        var result = ExpressionParser.parse("Orders:max(Total)");

        // Assert - max with argument returns AggregationNode
        assertThat(result).isInstanceOf(AggregationNode.class);
        var aggNode = (AggregationNode) result;
        assertThat(aggNode.aggregationType()).isEqualTo(AggregationType.MAX);
        assertThat(aggNode.propertyName()).isEqualTo("Total");
    }

    @Test
    void parseStringCountFunctionReturnsFunctionNode() {
        // Act
        var result = ExpressionParser.parse("Name:count");

        // Assert
        assertThat(result).isInstanceOf(FunctionNode.class);
        var funcNode = (FunctionNode) result;
        assertThat(funcNode.functionName()).isEqualTo(FunctionType.COUNT);
        assertThat(funcNode.source()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) funcNode.source()).name()).isEqualTo("Name");
    }

    // endregion

    // region Projection Tests

    @Test
    void parseProjectionReturnsProjectionNode() {
        // Act
        var result = ExpressionParser.parse("Orders.{Id, Status}");

        // Assert
        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;
        assertThat(projNode.properties()).hasSize(2);
        assertThat(projNode.properties().get(0).path()).isEqualTo("Id");
        assertThat(projNode.properties().get(1).path()).isEqualTo("Status");
    }

    @Test
    void parseProjectionMultipleFieldsReturnsProjectionNode() {
        // Act
        var result = ExpressionParser.parse("Orders.{Id, Status, Total, OrderDate}");

        // Assert
        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;
        assertThat(projNode.properties()).hasSize(4);
    }

    @Test
    void parseRootProjectionReturnsRootProjectionNode() {
        // Act
        var result = ExpressionParser.parse("{Id, Name}");

        // Assert
        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var rootProjNode = (RootProjectionNode) result;
        assertThat(rootProjNode.properties()).hasSize(2);
        assertThat(rootProjNode.properties()).anyMatch(p -> "Id".equals(p.path()));
        assertThat(rootProjNode.properties()).anyMatch(p -> "Name".equals(p.path()));
    }

    @Test
    void parseRootProjectionMultipleFieldsReturnsRootProjectionNode() {
        // Act
        var result = ExpressionParser.parse("{Id, UserEmail, ProvinceId, Age}");

        // Assert
        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var rootProjNode = (RootProjectionNode) result;
        assertThat(rootProjNode.properties()).hasSize(4);
        assertThat(rootProjNode.properties()).anyMatch(p -> "Id".equals(p.path()));
        assertThat(rootProjNode.properties()).anyMatch(p -> "UserEmail".equals(p.path()));
        assertThat(rootProjNode.properties()).anyMatch(p -> "ProvinceId".equals(p.path()));
        assertThat(rootProjNode.properties()).anyMatch(p -> "Age".equals(p.path()));
    }

    @Test
    void parseRootProjectionSingleFieldReturnsRootProjectionNode() {
        // Act
        var result = ExpressionParser.parse("{Name}");

        // Assert
        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var rootProjNode = (RootProjectionNode) result;
        assertThat(rootProjNode.properties()).hasSize(1);
        assertThat(rootProjNode.properties()).anyMatch(p -> "Name".equals(p.path()));
    }

    @Test
    void parseRootProjectionWithNavigationReturnsProjectionPropertyWithPath() {
        // Act
        var result = ExpressionParser.parse("{Id, Country.Name}");

        // Assert
        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var rootProjNode = (RootProjectionNode) result;
        assertThat(rootProjNode.properties()).hasSize(2);

        var idProp = firstWithPath(rootProjNode, "Id");
        assertThat(idProp.outputKey()).isEqualTo("Id");
        assertThat(idProp.hasNavigation()).isFalse();

        var countryNameProp = firstWithPath(rootProjNode, "Country.Name");
        assertThat(countryNameProp.outputKey()).isEqualTo("Name"); // Last segment without alias
        assertThat(countryNameProp.hasNavigation()).isTrue();
        assertThat(countryNameProp.pathSegments()).containsExactly("Country", "Name");
    }

    @Test
    void parseRootProjectionWithAliasReturnsProjectionPropertyWithAlias() {
        // Act
        var result = ExpressionParser.parse("{Id, Country.Name as CountryName}");

        // Assert
        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var rootProjNode = (RootProjectionNode) result;
        assertThat(rootProjNode.properties()).hasSize(2);

        var countryNameProp = firstWithPath(rootProjNode, "Country.Name");
        assertThat(countryNameProp.alias()).isEqualTo("CountryName");
        assertThat(countryNameProp.outputKey()).isEqualTo("CountryName"); // Uses alias as output key
    }

    @Test
    void parseRootProjectionWithMultipleAliasesReturnsCorrectProperties() {
        // Act
        var result = ExpressionParser.parse("{Id, Name, Country.Name as CountryName, Province.City.Name as CityName}");

        // Assert
        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var rootProjNode = (RootProjectionNode) result;
        var properties = rootProjNode.properties();
        assertThat(properties).hasSize(4);

        assertThat(properties.get(0).path()).isEqualTo("Id");
        assertThat(properties.get(0).alias()).isNull();
        assertThat(properties.get(0).outputKey()).isEqualTo("Id");

        assertThat(properties.get(1).path()).isEqualTo("Name");
        assertThat(properties.get(1).alias()).isNull();
        assertThat(properties.get(1).outputKey()).isEqualTo("Name");

        assertThat(properties.get(2).path()).isEqualTo("Country.Name");
        assertThat(properties.get(2).alias()).isEqualTo("CountryName");
        assertThat(properties.get(2).outputKey()).isEqualTo("CountryName");

        assertThat(properties.get(3).path()).isEqualTo("Province.City.Name");
        assertThat(properties.get(3).alias()).isEqualTo("CityName");
        assertThat(properties.get(3).outputKey()).isEqualTo("CityName");
        assertThat(properties.get(3).pathSegments()).containsExactly("Province", "City", "Name");
    }

    private static ProjectionProperty firstWithPath(RootProjectionNode node, String path) {
        return node.properties().stream()
                .filter(p -> path.equals(p.path()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No projection property with path " + path));
    }

    // endregion

    // region Complex Expression Tests

    @Test
    void parseFilterThenFirstReturnsCorrectStructure() {
        // Act
        var result = ExpressionParser.parse("Orders:where(Status = 'Done'):orderBy(OrderDate):first");

        // Assert
        assertThat(result).isInstanceOf(FunctionNode.class);
        var first = (FunctionNode) result;
        assertThat(first.functionName()).isEqualTo(FunctionType.FIRST);
        assertThat(first.source()).isInstanceOf(FunctionNode.class);
        var orderBy = (FunctionNode) first.source();
        assertThat(orderBy.functionName()).isEqualTo(FunctionType.ORDER_BY);
        assertThat(orderBy.source()).isInstanceOf(FilterNode.class);
    }

    @Test
    void parseFilterThenProjectionReturnsCorrectStructure() {
        // Act
        var result = ExpressionParser.parse("Orders:where(Status = 'Done').{Id, Total}");

        // Assert
        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;
        assertThat(projNode.source()).isInstanceOf(FilterNode.class);
    }

    @Test
    void parseFilterThenCountReturnsCorrectStructure() {
        // Act
        var result = ExpressionParser.parse("Orders:where(Status = 'Done'):count");

        // Assert
        assertThat(result).isInstanceOf(FunctionNode.class);
        var funcNode = (FunctionNode) result;
        assertThat(funcNode.functionName()).isEqualTo(FunctionType.COUNT);
        assertThat(funcNode.source()).isInstanceOf(FilterNode.class);
    }

    @Test
    void parseComplexExpressionReturnsCorrectStructure() {
        // Provinces:where(Name:count > 3):orderBy(Name):limit(10).{Id, Name}
        // Note: The parser builds segments sequentially, so the structure is:
        // ProjectionNode(source: limit(orderBy(filter(Provinces))))
        var result = ExpressionParser.parse("Provinces:where(Name:count > 3):orderBy(Name):limit(10).{Id, Name}");

        // Assert - should be ProjectionNode at top
        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        // Source is the limit, over the orderBy, over the filter
        assertThat(projNode.source()).isInstanceOf(FunctionNode.class);
        var limitNode = (FunctionNode) projNode.source();
        assertThat(limitNode.functionName()).isEqualTo(FunctionType.TAKE);
        assertThat(limitNode.source()).isInstanceOf(FunctionNode.class);
        var orderByNode = (FunctionNode) limitNode.source();
        assertThat(orderByNode.functionName()).isEqualTo(FunctionType.ORDER_BY);

        // The orderBy source should be FilterNode
        assertThat(orderByNode.source()).isInstanceOf(FilterNode.class);
        var filterNode = (FilterNode) orderByNode.source();

        // Filter source should be PropertyNode (Provinces)
        assertThat(filterNode.source()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) filterNode.source()).name()).isEqualTo("Provinces");
    }

    @Test
    void parseNavigationWithFilterIndexerProjectionReturnsCorrectStructure() {
        // Country.Provinces - navigation first, then continue
        var result = ExpressionParser.parse("Country.Provinces.{Id, Name}");

        // Assert - should be ProjectionNode at top
        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        // Source should be NavigationNode (Country.Provinces)
        assertThat(projNode.source()).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) projNode.source();
        assertThat(navNode.segments()).hasSize(2);
    }

    // endregion

    // region Literal Tests

    @Test
    void parseStringLiteralReturnsCorrectValue() {
        // Act
        var result = ExpressionParser.parse("Orders:where(Status = 'Completed')");

        // Assert
        var filterNode = (FilterNode) result;
        var condition = (BinaryConditionNode) filterNode.condition();
        assertThat(condition.right()).isInstanceOf(LiteralNode.class);
        var literal = (LiteralNode) condition.right();
        assertThat(literal.literalType()).isEqualTo(LiteralType.STRING);
        assertThat(literal.value()).isEqualTo("Completed");
    }

    @Test
    void parseNumberLiteralReturnsCorrectValue() {
        // Act
        var result = ExpressionParser.parse("Orders:where(Total > 100)");

        // Assert
        var filterNode = (FilterNode) result;
        var condition = (BinaryConditionNode) filterNode.condition();
        assertThat(condition.right()).isInstanceOf(LiteralNode.class);
        var literal = (LiteralNode) condition.right();
        assertThat(literal.literalType()).isEqualTo(LiteralType.NUMBER);
    }

    @Test
    void parseBooleanLiteralReturnsCorrectValue() {
        // Act
        var result = ExpressionParser.parse("Users:where(IsActive = true)");

        // Assert
        var filterNode = (FilterNode) result;
        var condition = (BinaryConditionNode) filterNode.condition();
        assertThat(condition.right()).isInstanceOf(LiteralNode.class);
        var literal = (LiteralNode) condition.right();
        assertThat(literal.literalType()).isEqualTo(LiteralType.BOOLEAN);
        assertThat(literal.value()).isEqualTo(true);
    }

    // endregion

    // region Comparison Operator Tests

    @ParameterizedTest
    @CsvSource({
            "Orders:where(Total = 100), EQUAL",
            "Orders:where(Total != 100), NOT_EQUAL",
            "Orders:where(Total > 100), GREATER_THAN",
            "Orders:where(Total < 100), LESS_THAN",
            "Orders:where(Total >= 100), GREATER_THAN_OR_EQUAL",
            "Orders:where(Total <= 100), LESS_THAN_OR_EQUAL"
    })
    void parseComparisonOperatorsReturnsCorrectOperator(String expression, ComparisonOperator expected) {
        // Act
        var result = ExpressionParser.parse(expression);

        // Assert
        var filterNode = (FilterNode) result;
        var condition = (BinaryConditionNode) filterNode.condition();
        assertThat(condition.operator()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(quoteCharacter = '"', value = {
            "Orders:where(Name contains 'test'), CONTAINS",
            "Orders:where(Name startswith 'test'), STARTS_WITH",
            "Orders:where(Name endswith 'test'), ENDS_WITH"
    })
    void parseStringComparisonOperatorsReturnsCorrectOperator(String expression, ComparisonOperator expected) {
        // Act
        var result = ExpressionParser.parse(expression);

        // Assert
        var filterNode = (FilterNode) result;
        var condition = (BinaryConditionNode) filterNode.condition();
        assertThat(condition.operator()).isEqualTo(expected);
    }

    // endregion

    // region Boolean Function Tests (:any, :all)

    @Test
    void parseAnyWithoutConditionReturnsBooleanFunctionNode() {
        // Act
        var result = ExpressionParser.parse("Orders:any");

        // Assert
        assertThat(result).isInstanceOf(BooleanFunctionNode.class);
        var boolFunc = (BooleanFunctionNode) result;
        assertThat(boolFunc.functionName()).isEqualTo(BooleanFunctionType.ANY);
        assertThat(boolFunc.hasCondition()).isFalse();
        assertThat(boolFunc.condition()).isNull();
        assertThat(boolFunc.source()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) boolFunc.source()).name()).isEqualTo("Orders");
    }

    @Test
    void parseAnyWithConditionReturnsBooleanFunctionNodeWithCondition() {
        // Act
        var result = ExpressionParser.parse("Orders:any(Status = 'Pending')");

        // Assert
        assertThat(result).isInstanceOf(BooleanFunctionNode.class);
        var boolFunc = (BooleanFunctionNode) result;
        assertThat(boolFunc.functionName()).isEqualTo(BooleanFunctionType.ANY);
        assertThat(boolFunc.hasCondition()).isTrue();
        assertThat(boolFunc.condition()).isInstanceOf(BinaryConditionNode.class);

        var condition = (BinaryConditionNode) boolFunc.condition();
        assertThat(condition.left()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) condition.left()).name()).isEqualTo("Status");
        assertThat(condition.operator()).isEqualTo(ComparisonOperator.EQUAL);
        assertThat(condition.right()).isInstanceOf(LiteralNode.class);
        assertThat(((LiteralNode) condition.right()).value()).isEqualTo("Pending");
    }

    @Test
    void parseAllWithoutConditionReturnsBooleanFunctionNode() {
        // Act
        var result = ExpressionParser.parse("Documents:all");

        // Assert
        assertThat(result).isInstanceOf(BooleanFunctionNode.class);
        var boolFunc = (BooleanFunctionNode) result;
        assertThat(boolFunc.functionName()).isEqualTo(BooleanFunctionType.ALL);
        assertThat(boolFunc.hasCondition()).isFalse();
        assertThat(boolFunc.condition()).isNull();
    }

    @Test
    void parseAllWithConditionReturnsBooleanFunctionNodeWithCondition() {
        // Act
        var result = ExpressionParser.parse("Documents:all(IsApproved = true)");

        // Assert
        assertThat(result).isInstanceOf(BooleanFunctionNode.class);
        var boolFunc = (BooleanFunctionNode) result;
        assertThat(boolFunc.functionName()).isEqualTo(BooleanFunctionType.ALL);
        assertThat(boolFunc.hasCondition()).isTrue();
        assertThat(boolFunc.condition()).isInstanceOf(BinaryConditionNode.class);

        var condition = (BinaryConditionNode) boolFunc.condition();
        assertThat(((PropertyNode) condition.left()).name()).isEqualTo("IsApproved");
        assertThat(condition.operator()).isEqualTo(ComparisonOperator.EQUAL);
        assertThat(((LiteralNode) condition.right()).value()).isEqualTo(true);
    }

    @Test
    void parseAnyWithComplexConditionReturnsBooleanFunctionNodeWithLogicalCondition() {
        // Act
        var result = ExpressionParser.parse("Orders:any(Status = 'Done' || Status = 'Shipped')");

        // Assert
        assertThat(result).isInstanceOf(BooleanFunctionNode.class);
        var boolFunc = (BooleanFunctionNode) result;
        assertThat(boolFunc.functionName()).isEqualTo(BooleanFunctionType.ANY);
        assertThat(boolFunc.condition()).isInstanceOf(LogicalConditionNode.class);

        var logicalCond = (LogicalConditionNode) boolFunc.condition();
        assertThat(logicalCond.operator()).isEqualTo(LogicalOperator.OR);
    }

    @Test
    void parseAllWithAndConditionReturnsBooleanFunctionNodeWithLogicalCondition() {
        // Act - Items:all(Price > 0 && Quantity > 0)
        var result = ExpressionParser.parse("Items:all(Price > 0 && Quantity > 0)");

        // Assert
        assertThat(result).isInstanceOf(BooleanFunctionNode.class);
        var boolFunc = (BooleanFunctionNode) result;
        assertThat(boolFunc.functionName()).isEqualTo(BooleanFunctionType.ALL);
        assertThat(boolFunc.condition()).isInstanceOf(LogicalConditionNode.class);

        var logicalCond = (LogicalConditionNode) boolFunc.condition();
        assertThat(logicalCond.operator()).isEqualTo(LogicalOperator.AND);
    }

    @Test
    void parseNavigationThenAnyReturnsCorrectStructure() {
        // Act - User.Orders:any(Status = 'Done')
        // The :any is applied to Orders segment, then wrapped in NavigationNode with User
        var result = ExpressionParser.parse("User.Orders:any(Status = 'Done')");

        // Assert - Result is NavigationNode containing [User, BooleanFunctionNode(Orders)]
        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(2);

        assertThat(navNode.segments().get(0)).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) navNode.segments().get(0)).name()).isEqualTo("User");

        assertThat(navNode.segments().get(1)).isInstanceOf(BooleanFunctionNode.class);
        var boolFunc = (BooleanFunctionNode) navNode.segments().get(1);
        assertThat(boolFunc.functionName()).isEqualTo(BooleanFunctionType.ANY);
        assertThat(boolFunc.source()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) boolFunc.source()).name()).isEqualTo("Orders");
    }

    @Test
    void parseFilterThenAnyReturnsCorrectStructure() {
        // First filter, then check any on filtered result
        var result = ExpressionParser.parse("Orders:where(Year = 2024):any(Status = 'Done')");

        // Assert
        assertThat(result).isInstanceOf(BooleanFunctionNode.class);
        var boolFunc = (BooleanFunctionNode) result;
        assertThat(boolFunc.source()).isInstanceOf(FilterNode.class);
    }

    // endregion

    // region Coalesce Tests (??)

    @Test
    void parseSimpleCoalesceReturnsCoalesceNode() {
        // Arrange & Act
        var result = ExpressionParser.parse("Nickname ?? Name");

        // Assert
        assertThat(result).isInstanceOf(CoalesceNode.class);
        var coalesce = (CoalesceNode) result;

        assertThat(coalesce.left()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) coalesce.left()).name()).isEqualTo("Nickname");

        assertThat(coalesce.right()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) coalesce.right()).name()).isEqualTo("Name");
    }

    @Test
    void parseCoalesceWithLiteralReturnsCoalesceNode() {
        // Arrange & Act - Address?.City ?? 'Unknown'
        var result = ExpressionParser.parse("City ?? 'Unknown'");

        // Assert
        assertThat(result).isInstanceOf(CoalesceNode.class);
        var coalesce = (CoalesceNode) result;

        assertThat(coalesce.left()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) coalesce.left()).name()).isEqualTo("City");

        assertThat(coalesce.right()).isInstanceOf(LiteralNode.class);
        var literal = (LiteralNode) coalesce.right();
        assertThat(literal.value()).isEqualTo("Unknown");
    }

    @Test
    void parseChainedCoalesceReturnsRightToLeftAssociativity() {
        // Arrange & Act - A ?? B ?? C should be A ?? (B ?? C)
        var result = ExpressionParser.parse("PreferredName ?? Nickname ?? Name");

        // Assert
        assertThat(result).isInstanceOf(CoalesceNode.class);
        var coalesce = (CoalesceNode) result;

        assertThat(coalesce.left()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) coalesce.left()).name()).isEqualTo("PreferredName");

        // Right should be another CoalesceNode: Nickname ?? Name
        assertThat(coalesce.right()).isInstanceOf(CoalesceNode.class);
        var innerCoalesce = (CoalesceNode) coalesce.right();
        assertThat(((PropertyNode) innerCoalesce.left()).name()).isEqualTo("Nickname");
        assertThat(((PropertyNode) innerCoalesce.right()).name()).isEqualTo("Name");
    }

    @Test
    void parseCoalesceWithNumberReturnsCoalesceNode() {
        // Arrange & Act
        var result = ExpressionParser.parse("Score ?? 0");

        // Assert
        assertThat(result).isInstanceOf(CoalesceNode.class);
        var coalesce = (CoalesceNode) result;

        assertThat(coalesce.left()).isInstanceOf(PropertyNode.class);
        assertThat(coalesce.right()).isInstanceOf(LiteralNode.class);
        assertThat((BigDecimal) ((LiteralNode) coalesce.right()).value()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void parseCoalesceWithBooleanReturnsCoalesceNode() {
        // Arrange & Act
        var result = ExpressionParser.parse("IsActive ?? false");

        // Assert
        assertThat(result).isInstanceOf(CoalesceNode.class);
        var coalesce = (CoalesceNode) result;

        assertThat(coalesce.left()).isInstanceOf(PropertyNode.class);
        assertThat(coalesce.right()).isInstanceOf(LiteralNode.class);
        assertThat(((LiteralNode) coalesce.right()).value()).isEqualTo(false);
    }

    // endregion

    // region Ternary Tests (?:)

    @Test
    void parseSimpleTernaryReturnsTernaryNode() {
        // Arrange & Act - Status = 'Active' ? 'Yes' : 'No'
        var result = ExpressionParser.parse("Status = 'Active' ? 'Yes' : 'No'");

        // Assert
        assertThat(result).isInstanceOf(TernaryNode.class);
        var ternary = (TernaryNode) result;

        assertThat(ternary.condition()).isInstanceOf(BinaryConditionNode.class);
        var condition = (BinaryConditionNode) ternary.condition();
        assertThat(condition.left()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) condition.left()).name()).isEqualTo("Status");
        assertThat(condition.operator()).isEqualTo(ComparisonOperator.EQUAL);

        assertThat(ternary.whenTrue()).isInstanceOf(LiteralNode.class);
        assertThat(((LiteralNode) ternary.whenTrue()).value()).isEqualTo("Yes");

        assertThat(ternary.whenFalse()).isInstanceOf(LiteralNode.class);
        assertThat(((LiteralNode) ternary.whenFalse()).value()).isEqualTo("No");
    }

    @Test
    void parseTernaryWithGreaterThanReturnsTernaryNode() {
        // Arrange & Act - Count > 0 ? 'HasItems' : 'Empty'
        var result = ExpressionParser.parse("Count > 0 ? 'HasItems' : 'Empty'");

        // Assert
        assertThat(result).isInstanceOf(TernaryNode.class);
        var ternary = (TernaryNode) result;

        assertThat(ternary.condition()).isInstanceOf(BinaryConditionNode.class);
        var condition = (BinaryConditionNode) ternary.condition();
        assertThat(condition.operator()).isEqualTo(ComparisonOperator.GREATER_THAN);
    }

    @Test
    void parseTernaryWithExpressionResultsReturnsTernaryNode() {
        // Arrange & Act - IsVIP = true ? Discount1 : Discount2
        var result = ExpressionParser.parse("IsVIP = true ? Discount1 : Discount2");

        // Assert
        assertThat(result).isInstanceOf(TernaryNode.class);
        var ternary = (TernaryNode) result;

        assertThat(ternary.whenTrue()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) ternary.whenTrue()).name()).isEqualTo("Discount1");

        assertThat(ternary.whenFalse()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) ternary.whenFalse()).name()).isEqualTo("Discount2");
    }

    @Test
    void parseNestedTernaryReturnsRightToLeftAssociativity() {
        // Arrange & Act - A = 1 ? 'One' : B = 2 ? 'Two' : 'Other'
        // Should be: A = 1 ? 'One' : (B = 2 ? 'Two' : 'Other')
        var result = ExpressionParser.parse("Score >= 90 ? 'A' : Score >= 80 ? 'B' : 'C'");

        // Assert
        assertThat(result).isInstanceOf(TernaryNode.class);
        var ternary = (TernaryNode) result;

        assertThat(ternary.whenTrue()).isInstanceOf(LiteralNode.class);
        assertThat(((LiteralNode) ternary.whenTrue()).value()).isEqualTo("A");

        // WhenFalse should be another TernaryNode
        assertThat(ternary.whenFalse()).isInstanceOf(TernaryNode.class);
        var innerTernary = (TernaryNode) ternary.whenFalse();
        assertThat(((LiteralNode) innerTernary.whenTrue()).value()).isEqualTo("B");
        assertThat(((LiteralNode) innerTernary.whenFalse()).value()).isEqualTo("C");
    }

    @Test
    void parseTernaryWithBooleanFunctionReturnsTernaryNode() {
        // Arrange & Act - Orders:any ? 'Has Orders' : 'No Orders'
        var result = ExpressionParser.parse("Orders:any ? 'Has Orders' : 'No Orders'");

        // Assert
        assertThat(result).isInstanceOf(TernaryNode.class);
        var ternary = (TernaryNode) result;

        // Condition should wrap BooleanFunctionNode as comparison
        assertThat(ternary.condition()).isInstanceOf(BinaryConditionNode.class);
        var condition = (BinaryConditionNode) ternary.condition();
        assertThat(condition.left()).isInstanceOf(BooleanFunctionNode.class);
    }

    // endregion
}

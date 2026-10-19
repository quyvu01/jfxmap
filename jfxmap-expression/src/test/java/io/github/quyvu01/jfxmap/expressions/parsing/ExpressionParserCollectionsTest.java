package io.github.quyvu01.jfxmap.expressions.parsing;

import io.github.quyvu01.jfxmap.expressions.nodes.AggregationNode;
import io.github.quyvu01.jfxmap.expressions.nodes.AggregationType;
import io.github.quyvu01.jfxmap.expressions.nodes.BinaryConditionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.BooleanFunctionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.BooleanFunctionType;
import io.github.quyvu01.jfxmap.expressions.nodes.CoalesceNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FilterNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FunctionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FunctionType;
import io.github.quyvu01.jfxmap.expressions.nodes.GroupByNode;
import io.github.quyvu01.jfxmap.expressions.nodes.GroupElementsNode;
import io.github.quyvu01.jfxmap.expressions.nodes.LogicalConditionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.LogicalOperator;
import io.github.quyvu01.jfxmap.expressions.nodes.NavigationNode;
import io.github.quyvu01.jfxmap.expressions.nodes.OrderDirection;
import io.github.quyvu01.jfxmap.expressions.nodes.ProjectionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.PropertyNode;
import io.github.quyvu01.jfxmap.expressions.nodes.RootProjectionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.SortKeyNode;
import io.github.quyvu01.jfxmap.expressions.nodes.TernaryNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Port of the collection-function and groupBy tests of FxMap's ExpressionParserTests.
 */
class ExpressionParserCollectionsTest {

    // region Collection Functions - Distinct

    @Test
    void parseDistinctWithPropertyReturnsFunctionNode() {
        // Items:distinct(Name) -> select distinct names from items
        var result = ExpressionParser.parse("Items:distinct(Name)");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var funcNode = (FunctionNode) result;
        assertThat(funcNode.functionName()).isEqualTo(FunctionType.DISTINCT);
        assertThat(funcNode.source()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) funcNode.source()).name()).isEqualTo("Items");

        var args = funcNode.getArguments();
        assertThat(args).hasSize(1);
        assertThat(args.get(0)).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) args.get(0)).name()).isEqualTo("Name");
    }

    @Test
    void parseDistinctWithNavigationSourceReturnsCorrectStructure() {
        // Order.Items:distinct(ProductName)
        // Structure: NavigationNode([Order, FunctionNode(Items:distinct(ProductName))])
        var result = ExpressionParser.parse("Order.Items:distinct(ProductName)");

        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(2);

        // First segment: Order
        assertThat(navNode.segments().get(0)).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) navNode.segments().get(0)).name()).isEqualTo("Order");

        // Second segment: Items:distinct(ProductName)
        assertThat(navNode.segments().get(1)).isInstanceOf(FunctionNode.class);
        var funcNode = (FunctionNode) navNode.segments().get(1);
        assertThat(funcNode.functionName()).isEqualTo(FunctionType.DISTINCT);
        assertThat(((PropertyNode) funcNode.source()).name()).isEqualTo("Items");

        var args = funcNode.getArguments();
        assertThat(args).hasSize(1);
        assertThat(((PropertyNode) args.get(0)).name()).isEqualTo("ProductName");
    }

    @Test
    void parseDistinctAfterFilterReturnsCorrectStructure() {
        // Orders:where(Status = 'Done').Items:distinct(CustomerId)
        // Structure: NavigationNode([FilterNode(Orders), FunctionNode(Items:distinct)])
        var result = ExpressionParser.parse("Orders:where(Status = 'Done').Items:distinct(CustomerId)");

        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(2);

        // First segment: FilterNode for Orders:where(Status = 'Done')
        assertThat(navNode.segments().get(0)).isInstanceOf(FilterNode.class);
        var filterNode = (FilterNode) navNode.segments().get(0);
        assertThat(((PropertyNode) filterNode.source()).name()).isEqualTo("Orders");

        // Second segment: FunctionNode for Items:distinct(CustomerId)
        assertThat(navNode.segments().get(1)).isInstanceOf(FunctionNode.class);
        var funcNode = (FunctionNode) navNode.segments().get(1);
        assertThat(funcNode.functionName()).isEqualTo(FunctionType.DISTINCT);

        var args = funcNode.getArguments();
        assertThat(((PropertyNode) args.get(0)).name()).isEqualTo("CustomerId");
    }

    @Test
    void parseDistinctWithCountChainReturnsCorrectStructure() {
        // Items:distinct(Category):count -> count of distinct categories
        var result = ExpressionParser.parse("Items:distinct(Category):count");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var countFunc = (FunctionNode) result;
        assertThat(countFunc.functionName()).isEqualTo(FunctionType.COUNT);

        assertThat(countFunc.source()).isInstanceOf(FunctionNode.class);
        var distinctFunc = (FunctionNode) countFunc.source();
        assertThat(distinctFunc.functionName()).isEqualTo(FunctionType.DISTINCT);
        assertThat(((PropertyNode) distinctFunc.getArguments().get(0)).name()).isEqualTo("Category");
    }

    @Test
    void parseDistinctInProjectionReturnsCorrectStructure() {
        // {Id, Name, Items:distinct(Category) as Categories}
        var result = ExpressionParser.parse("{Id, Name, Items:distinct(Category) as Categories}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(3);

        // Third property should be distinct function
        var distinctProp = projection.properties().get(2);
        assertThat(distinctProp.outputKey()).isEqualTo("Categories");
        assertThat(distinctProp.isComputed()).isTrue();
        assertThat(distinctProp.expression()).isInstanceOf(FunctionNode.class);
        var distinctFunc = (FunctionNode) distinctProp.expression();
        assertThat(distinctFunc.functionName()).isEqualTo(FunctionType.DISTINCT);
    }

    @Test
    void parseDistinctWithFilterAndCountComplexChain() {
        // Orders:where(Status = 'Completed').Items:distinct(ProductId):count
        // Structure: NavigationNode([FilterNode(Orders), FunctionNode(Items:distinct(ProductId):count)])
        var result = ExpressionParser.parse("Orders:where(Status = 'Completed').Items:distinct(ProductId):count");

        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(2);

        // First segment should be FilterNode
        assertThat(navNode.segments().get(0)).isInstanceOf(FilterNode.class);
        var filterNode = (FilterNode) navNode.segments().get(0);
        assertThat(((PropertyNode) filterNode.source()).name()).isEqualTo("Orders");

        // Second segment should be Items:distinct(ProductId):count
        assertThat(navNode.segments().get(1)).isInstanceOf(FunctionNode.class);
        var countFunc = (FunctionNode) navNode.segments().get(1);
        assertThat(countFunc.functionName()).isEqualTo(FunctionType.COUNT);

        assertThat(countFunc.source()).isInstanceOf(FunctionNode.class);
        var distinctFunc = (FunctionNode) countFunc.source();
        assertThat(distinctFunc.functionName()).isEqualTo(FunctionType.DISTINCT);
        assertThat(((PropertyNode) distinctFunc.source()).name()).isEqualTo("Items");
    }

    @Test
    void parseDistinctWithoutArgumentThrowsException() {
        // Items:distinct should throw because property argument is required
        assertThatThrownBy(() -> ExpressionParser.parse("Items:distinct"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("distinct")
                .hasMessageContaining("requires");
    }

    @Test
    void parseDistinctWithEmptyParensThrowsException() {
        // Items:distinct() should throw because property argument is required
        assertThatThrownBy(() -> ExpressionParser.parse("Items:distinct()"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("argument");
    }

    @Test
    void parseMultipleDistinctsInProjectionReturnsCorrectStructure() {
        // {Items:distinct(Category) as Categories, Items:distinct(Vendor) as Vendors}
        var result = ExpressionParser.parse("{Items:distinct(Category) as Categories, Items:distinct(Vendor) as Vendors}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(2);

        // First distinct
        var catProp = projection.properties().get(0);
        assertThat(catProp.outputKey()).isEqualTo("Categories");
        var catFunc = (FunctionNode) catProp.expression();
        assertThat(catFunc.functionName()).isEqualTo(FunctionType.DISTINCT);
        assertThat(((PropertyNode) catFunc.getArguments().get(0)).name()).isEqualTo("Category");

        // Second distinct
        var vendorProp = projection.properties().get(1);
        assertThat(vendorProp.outputKey()).isEqualTo("Vendors");
        var vendorFunc = (FunctionNode) vendorProp.expression();
        assertThat(vendorFunc.functionName()).isEqualTo(FunctionType.DISTINCT);
        assertThat(((PropertyNode) vendorFunc.getArguments().get(0)).name()).isEqualTo("Vendor");
    }

    @Test
    void parseDistinctWithDeepNavigationReturnsCorrectStructure() {
        // Customer.Orders.Items:distinct(ProductName)
        // Structure: NavigationNode([Customer, Orders, FunctionNode(Items:distinct(ProductName))])
        var result = ExpressionParser.parse("Customer.Orders.Items:distinct(ProductName)");

        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(3);

        assertThat(((PropertyNode) navNode.segments().get(0)).name()).isEqualTo("Customer");
        assertThat(((PropertyNode) navNode.segments().get(1)).name()).isEqualTo("Orders");

        // Last segment is FunctionNode
        assertThat(navNode.segments().get(2)).isInstanceOf(FunctionNode.class);
        var funcNode = (FunctionNode) navNode.segments().get(2);
        assertThat(funcNode.functionName()).isEqualTo(FunctionType.DISTINCT);
        assertThat(((PropertyNode) funcNode.source()).name()).isEqualTo("Items");
    }

    @Test
    void parseDistinctWithFilterOnNestedCollectionComplexQuery() {
        // Company.Departments:where(IsActive = true).Employees.Items:distinct(Role)
        // Structure: NavigationNode([Company, FilterNode(Departments), Employees, FunctionNode(Items:distinct(Role))])
        var result = ExpressionParser.parse("Company.Departments:where(IsActive = true).Employees.Items:distinct(Role)");

        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(4);

        // First segment: Company
        assertThat(navNode.segments().get(0)).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) navNode.segments().get(0)).name()).isEqualTo("Company");

        // Second segment: Departments with filter
        assertThat(navNode.segments().get(1)).isInstanceOf(FilterNode.class);
        var filterNode = (FilterNode) navNode.segments().get(1);
        assertThat(((PropertyNode) filterNode.source()).name()).isEqualTo("Departments");

        // Third segment: Employees
        assertThat(navNode.segments().get(2)).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) navNode.segments().get(2)).name()).isEqualTo("Employees");

        // Fourth segment: Items:distinct(Role)
        assertThat(navNode.segments().get(3)).isInstanceOf(FunctionNode.class);
        var distinctFunc = (FunctionNode) navNode.segments().get(3);
        assertThat(distinctFunc.functionName()).isEqualTo(FunctionType.DISTINCT);
        assertThat(((PropertyNode) distinctFunc.source()).name()).isEqualTo("Items");
        assertThat(((PropertyNode) distinctFunc.getArguments().get(0)).name()).isEqualTo("Role");
    }

    @Test
    void parseDistinctAfterOrderByAndLimitComplexChain() {
        // Customers:orderBy(CreatedAt):limit(10).Orders.Items:distinct(Status)
        // Structure: NavigationNode([limit(orderBy(Customers)), Orders, FunctionNode(Items:distinct(Status))])
        var result = ExpressionParser.parse("Customers:orderBy(CreatedAt):limit(10).Orders.Items:distinct(Status)");

        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(3);

        // First segment: Customers:orderBy(CreatedAt):limit(10)
        assertThat(navNode.segments().get(0)).isInstanceOf(FunctionNode.class);
        var limitNode = (FunctionNode) navNode.segments().get(0);
        assertThat(limitNode.functionName()).isEqualTo(FunctionType.TAKE);
        assertThat(limitNode.source()).isInstanceOf(FunctionNode.class);
        var orderByNode = (FunctionNode) limitNode.source();
        assertThat(orderByNode.functionName()).isEqualTo(FunctionType.ORDER_BY);
        assertThat(((PropertyNode) orderByNode.source()).name()).isEqualTo("Customers");
        assertThat(orderByNode.getArguments()).isEqualTo(List.of(new SortKeyNode("CreatedAt", OrderDirection.ASC)));

        // Second segment: Orders
        assertThat(navNode.segments().get(1)).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) navNode.segments().get(1)).name()).isEqualTo("Orders");

        // Third segment: Items:distinct(Status)
        assertThat(navNode.segments().get(2)).isInstanceOf(FunctionNode.class);
        var distinctFunc = (FunctionNode) navNode.segments().get(2);
        assertThat(distinctFunc.functionName()).isEqualTo(FunctionType.DISTINCT);
        assertThat(((PropertyNode) distinctFunc.source()).name()).isEqualTo("Items");
        assertThat(((PropertyNode) distinctFunc.getArguments().get(0)).name()).isEqualTo("Status");
    }

    @Test
    void parseDistinctCombinedWithTernaryInProjection() {
        // {(Items:distinct(Category):count > 0 ? 'Has Categories' : 'No Categories') as CategoryStatus}
        var result = ExpressionParser.parse(
                "{(Items:distinct(Category):count > 0 ? 'Has Categories' : 'No Categories') as CategoryStatus}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(1);

        var prop = projection.properties().get(0);
        assertThat(prop.outputKey()).isEqualTo("CategoryStatus");
        assertThat(prop.expression()).isInstanceOf(TernaryNode.class);

        var ternary = (TernaryNode) prop.expression();
        assertThat(ternary.condition()).isInstanceOf(BinaryConditionNode.class);

        var condition = (BinaryConditionNode) ternary.condition();
        // The left side should be Items:distinct(Category):count
        assertThat(condition.left()).isInstanceOf(FunctionNode.class);
        var countFunc = (FunctionNode) condition.left();
        assertThat(countFunc.functionName()).isEqualTo(FunctionType.COUNT);

        assertThat(countFunc.source()).isInstanceOf(FunctionNode.class);
        var distinctFunc = (FunctionNode) countFunc.source();
        assertThat(distinctFunc.functionName()).isEqualTo(FunctionType.DISTINCT);
    }

    @Test
    void parseDistinctWithCoalesceInProjectionComplexExpression() {
        // {(Items:distinct(Category) ?? Items:distinct(SubCategory)) as Categories}
        var result = ExpressionParser.parse("{(Items:distinct(Category) ?? Items:distinct(SubCategory)) as Categories}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(1);

        var prop = projection.properties().get(0);
        assertThat(prop.outputKey()).isEqualTo("Categories");
        assertThat(prop.expression()).isInstanceOf(CoalesceNode.class);

        var coalesce = (CoalesceNode) prop.expression();

        // Left side: Items:distinct(Category)
        assertThat(coalesce.left()).isInstanceOf(FunctionNode.class);
        var leftDistinct = (FunctionNode) coalesce.left();
        assertThat(leftDistinct.functionName()).isEqualTo(FunctionType.DISTINCT);
        assertThat(((PropertyNode) leftDistinct.getArguments().get(0)).name()).isEqualTo("Category");

        // Right side: Items:distinct(SubCategory)
        assertThat(coalesce.right()).isInstanceOf(FunctionNode.class);
        var rightDistinct = (FunctionNode) coalesce.right();
        assertThat(rightDistinct.functionName()).isEqualTo(FunctionType.DISTINCT);
        assertThat(((PropertyNode) rightDistinct.getArguments().get(0)).name()).isEqualTo("SubCategory");
    }

    @Test
    void parseDistinctWithAndFilterComplexQuery() {
        // Orders:where(Year = 2024, Status = 'Active').Items:distinct(CustomerId)
        // Use AND condition instead of multiple filter blocks
        var result = ExpressionParser.parse("Orders:where(Year = 2024, Status = 'Active').Items:distinct(CustomerId)");

        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(2);

        // First segment: FilterNode for Orders with AND condition
        assertThat(navNode.segments().get(0)).isInstanceOf(FilterNode.class);
        var filterNode = (FilterNode) navNode.segments().get(0);
        assertThat(((PropertyNode) filterNode.source()).name()).isEqualTo("Orders");

        // Condition should be LogicalConditionNode (AND)
        assertThat(filterNode.condition()).isInstanceOf(LogicalConditionNode.class);
        var logicalCond = (LogicalConditionNode) filterNode.condition();
        assertThat(logicalCond.operator()).isEqualTo(LogicalOperator.AND);

        // Second segment: Items:distinct(CustomerId)
        assertThat(navNode.segments().get(1)).isInstanceOf(FunctionNode.class);
        var distinctFunc = (FunctionNode) navNode.segments().get(1);
        assertThat(distinctFunc.functionName()).isEqualTo(FunctionType.DISTINCT);
        assertThat(((PropertyNode) distinctFunc.getArguments().get(0)).name()).isEqualTo("CustomerId");
    }

    @Test
    void parseDistinctCaseInsensitiveWorks() {
        // Test that DISTINCT, Distinct, distinct all work
        var result1 = ExpressionParser.parse("Items:distinct(Name)");
        var result2 = ExpressionParser.parse("Items:DISTINCT(Name)");
        var result3 = ExpressionParser.parse("Items:Distinct(Name)");

        assertThat(result1).isInstanceOf(FunctionNode.class);
        assertThat(result2).isInstanceOf(FunctionNode.class);
        assertThat(result3).isInstanceOf(FunctionNode.class);

        assertThat(((FunctionNode) result1).functionName()).isEqualTo(FunctionType.DISTINCT);
        assertThat(((FunctionNode) result2).functionName()).isEqualTo(FunctionType.DISTINCT);
        assertThat(((FunctionNode) result3).functionName()).isEqualTo(FunctionType.DISTINCT);
    }

    // endregion

    // region GroupBy Tests - Basic Parsing

    @Test
    void parseGroupBySingleKeyReturnsGroupByNode() {
        // Orders:groupBy(Status) -> group orders by Status
        var result = ExpressionParser.parse("Orders:groupBy(Status)");

        assertThat(result).isInstanceOf(GroupByNode.class);
        var groupByNode = (GroupByNode) result;
        assertThat(groupByNode.source()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) groupByNode.source()).name()).isEqualTo("Orders");
        assertThat(groupByNode.keyProperties()).hasSize(1);
        assertThat(groupByNode.keyProperties().get(0)).isEqualTo("Status");
        assertThat(groupByNode.isSingleKey()).isTrue();
        assertThat(groupByNode.isMultiKey()).isFalse();
    }

    @Test
    void parseGroupByMultipleKeysReturnsGroupByNode() {
        // Orders:groupBy(Year, Month) -> group orders by Year and Month
        var result = ExpressionParser.parse("Orders:groupBy(Year, Month)");

        assertThat(result).isInstanceOf(GroupByNode.class);
        var groupByNode = (GroupByNode) result;
        assertThat(groupByNode.source()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) groupByNode.source()).name()).isEqualTo("Orders");
        assertThat(groupByNode.keyProperties()).hasSize(2);
        assertThat(groupByNode.keyProperties().get(0)).isEqualTo("Year");
        assertThat(groupByNode.keyProperties().get(1)).isEqualTo("Month");
        assertThat(groupByNode.isSingleKey()).isFalse();
        assertThat(groupByNode.isMultiKey()).isTrue();
    }

    @Test
    void parseGroupByThreeKeysReturnsGroupByNode() {
        // Sales:groupBy(Year, Quarter, Region)
        var result = ExpressionParser.parse("Sales:groupBy(Year, Quarter, Region)");

        assertThat(result).isInstanceOf(GroupByNode.class);
        var groupByNode = (GroupByNode) result;
        assertThat(groupByNode.keyProperties()).hasSize(3);
        assertThat(groupByNode.keyProperties().get(0)).isEqualTo("Year");
        assertThat(groupByNode.keyProperties().get(1)).isEqualTo("Quarter");
        assertThat(groupByNode.keyProperties().get(2)).isEqualTo("Region");
        assertThat(groupByNode.isMultiKey()).isTrue();
    }

    @Test
    void parseGroupByAfterFilterReturnsCorrectStructure() {
        // Orders:where(Status = 'Active'):groupBy(CustomerId)
        var result = ExpressionParser.parse("Orders:where(Status = 'Active'):groupBy(CustomerId)");

        assertThat(result).isInstanceOf(GroupByNode.class);
        var groupByNode = (GroupByNode) result;

        // Source should be FilterNode
        assertThat(groupByNode.source()).isInstanceOf(FilterNode.class);
        var filterNode = (FilterNode) groupByNode.source();
        assertThat(((PropertyNode) filterNode.source()).name()).isEqualTo("Orders");

        assertThat(groupByNode.keyProperties()).hasSize(1);
        assertThat(groupByNode.keyProperties().get(0)).isEqualTo("CustomerId");
    }

    @Test
    void parseGroupByWithNavigationReturnsCorrectStructure() {
        // Customer.Orders:groupBy(Status)
        var result = ExpressionParser.parse("Customer.Orders:groupBy(Status)");

        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(2);

        // First segment: Customer
        assertThat(navNode.segments().get(0)).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) navNode.segments().get(0)).name()).isEqualTo("Customer");

        // Second segment: Orders:groupBy(Status)
        assertThat(navNode.segments().get(1)).isInstanceOf(GroupByNode.class);
        var groupByNode = (GroupByNode) navNode.segments().get(1);
        assertThat(((PropertyNode) groupByNode.source()).name()).isEqualTo("Orders");
        assertThat(groupByNode.keyProperties().get(0)).isEqualTo("Status");
    }

    @Test
    void parseGroupByCaseInsensitiveWorks() {
        // Test that GROUPBY, GroupBy, groupby all work
        var result1 = ExpressionParser.parse("Items:groupby(Category)");
        var result2 = ExpressionParser.parse("Items:GROUPBY(Category)");
        var result3 = ExpressionParser.parse("Items:GroupBy(Category)");

        assertThat(result1).isInstanceOf(GroupByNode.class);
        assertThat(result2).isInstanceOf(GroupByNode.class);
        assertThat(result3).isInstanceOf(GroupByNode.class);

        assertThat(((GroupByNode) result1).keyProperties().get(0)).isEqualTo("Category");
        assertThat(((GroupByNode) result2).keyProperties().get(0)).isEqualTo("Category");
        assertThat(((GroupByNode) result3).keyProperties().get(0)).isEqualTo("Category");
    }

    // endregion

    // region GroupBy Tests - With Projection

    @Test
    void parseGroupByWithSimpleProjectionReturnsProjectionNode() {
        // Orders:groupBy(Status).{Status, :count}
        var result = ExpressionParser.parse("Orders:groupBy(Status).{Status, :count}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        // Source should be GroupByNode
        assertThat(projNode.source()).isInstanceOf(GroupByNode.class);
        var groupByNode = (GroupByNode) projNode.source();
        assertThat(groupByNode.keyProperties().get(0)).isEqualTo("Status");

        // Properties
        assertThat(projNode.properties()).hasSize(2);

        // First property: Status (key)
        assertThat(projNode.properties().get(0).path()).isEqualTo("Status");

        // Second property: :count (direct aggregation on group elements)
        assertThat(projNode.properties().get(1).isComputed()).isTrue();
        assertThat(projNode.properties().get(1).expression()).isInstanceOf(FunctionNode.class);
        var countFunc = (FunctionNode) projNode.properties().get(1).expression();
        assertThat(countFunc.functionName()).isEqualTo(FunctionType.COUNT);
        assertThat(countFunc.source()).isInstanceOf(GroupElementsNode.class);
    }

    @Test
    void parseGroupByWithAliasedProjectionReturnsCorrectAliases() {
        // Orders:groupBy(Status).{Status as OrderStatus, :count as TotalCount}
        var result = ExpressionParser.parse("Orders:groupBy(Status).{Status as OrderStatus, :count as TotalCount}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        assertThat(projNode.properties()).hasSize(2);

        // First property: Status as OrderStatus
        assertThat(projNode.properties().get(0).outputKey()).isEqualTo("OrderStatus");

        // Second property: :count as TotalCount
        assertThat(projNode.properties().get(1).outputKey()).isEqualTo("TotalCount");
    }

    @Test
    void parseGroupByMultiKeyWithProjectionReturnsCorrectStructure() {
        // Orders:groupBy(Year, Month).{Year, Month, :count as OrderCount}
        var result = ExpressionParser.parse("Orders:groupBy(Year, Month).{Year, Month, :count as OrderCount}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        // Source should be GroupByNode with 2 keys
        var groupByNode = (GroupByNode) projNode.source();
        assertThat(groupByNode.keyProperties()).hasSize(2);
        assertThat(groupByNode.keyProperties().get(0)).isEqualTo("Year");
        assertThat(groupByNode.keyProperties().get(1)).isEqualTo("Month");

        // Properties: Year, Month, OrderCount
        assertThat(projNode.properties()).hasSize(3);
        assertThat(projNode.properties().get(0).path()).isEqualTo("Year");
        assertThat(projNode.properties().get(1).path()).isEqualTo("Month");
        assertThat(projNode.properties().get(2).outputKey()).isEqualTo("OrderCount");
    }

    @Test
    void parseGroupByWithSumFunctionReturnsCorrectStructure() {
        // Orders:groupBy(CustomerId).{CustomerId, :sum(Total) as TotalAmount}
        var result = ExpressionParser.parse("Orders:groupBy(CustomerId).{CustomerId, :sum(Total) as TotalAmount}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        assertThat(projNode.properties()).hasSize(2);

        // Second property: :sum(Total) as TotalAmount
        var totalProp = projNode.properties().get(1);
        assertThat(totalProp.outputKey()).isEqualTo("TotalAmount");
        assertThat(totalProp.expression()).isInstanceOf(AggregationNode.class);

        var sumAgg = (AggregationNode) totalProp.expression();
        assertThat(sumAgg.aggregationType()).isEqualTo(AggregationType.SUM);
        assertThat(sumAgg.propertyName()).isEqualTo("Total");
    }

    @Test
    void parseGroupByWithAvgFunctionReturnsCorrectStructure() {
        // Products:groupBy(Category).{Category, :avg(Price) as AvgPrice}
        var result = ExpressionParser.parse("Products:groupBy(Category).{Category, :avg(Price) as AvgPrice}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        var avgProp = projNode.properties().get(1);
        assertThat(avgProp.outputKey()).isEqualTo("AvgPrice");
        assertThat(avgProp.expression()).isInstanceOf(AggregationNode.class);
        var avgAgg = (AggregationNode) avgProp.expression();
        assertThat(avgAgg.aggregationType()).isEqualTo(AggregationType.AVERAGE);
        assertThat(avgAgg.propertyName()).isEqualTo("Price");
    }

    @Test
    void parseGroupByWithMinMaxFunctionsReturnsCorrectStructure() {
        // Orders:groupBy(Status).{Status, :min(CreatedAt) as FirstOrder, :max(CreatedAt) as LastOrder}
        var result = ExpressionParser.parse(
                "Orders:groupBy(Status).{Status, :min(CreatedAt) as FirstOrder, :max(CreatedAt) as LastOrder}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        assertThat(projNode.properties()).hasSize(3);

        // Min property
        var minProp = projNode.properties().get(1);
        assertThat(minProp.outputKey()).isEqualTo("FirstOrder");
        assertThat(minProp.expression()).isInstanceOf(AggregationNode.class);
        var minAgg = (AggregationNode) minProp.expression();
        assertThat(minAgg.aggregationType()).isEqualTo(AggregationType.MIN);

        // Max property
        var maxProp = projNode.properties().get(2);
        assertThat(maxProp.outputKey()).isEqualTo("LastOrder");
        assertThat(maxProp.expression()).isInstanceOf(AggregationNode.class);
        var maxAgg = (AggregationNode) maxProp.expression();
        assertThat(maxAgg.aggregationType()).isEqualTo(AggregationType.MAX);
    }

    @Test
    void parseGroupByWithMultipleAggregationsReturnsCorrectStructure() {
        // Orders:groupBy(CustomerId).{CustomerId, :count as OrderCount, :sum(Total) as TotalSpent, :avg(Total) as AvgOrderValue}
        var result = ExpressionParser.parse(
                "Orders:groupBy(CustomerId).{CustomerId, :count as OrderCount, :sum(Total) as TotalSpent, :avg(Total) as AvgOrderValue}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        assertThat(projNode.properties()).hasSize(4);

        // CustomerId
        assertThat(projNode.properties().get(0).path()).isEqualTo("CustomerId");

        // OrderCount - count returns FunctionNode
        assertThat(projNode.properties().get(1).outputKey()).isEqualTo("OrderCount");
        assertThat(((FunctionNode) projNode.properties().get(1).expression()).functionName())
                .isEqualTo(FunctionType.COUNT);

        // TotalSpent - sum returns AggregationNode
        assertThat(projNode.properties().get(2).outputKey()).isEqualTo("TotalSpent");
        assertThat(((AggregationNode) projNode.properties().get(2).expression()).aggregationType())
                .isEqualTo(AggregationType.SUM);

        // AvgOrderValue - avg returns AggregationNode
        assertThat(projNode.properties().get(3).outputKey()).isEqualTo("AvgOrderValue");
        assertThat(((AggregationNode) projNode.properties().get(3).expression()).aggregationType())
                .isEqualTo(AggregationType.AVERAGE);
    }

    // endregion

    // NOTE: Items(condition) syntax in GroupBy projection is not supported in the current design.
    // The new syntax uses direct aggregations like :count, :sum(Total) on group elements.
    // Filtered Items support may be added in a future version.

    // region GroupBy Tests - Error Cases

    @Test
    void parseGroupByWithoutArgumentsThrowsException() {
        // Orders:groupBy should throw
        assertThatThrownBy(() -> ExpressionParser.parse("Orders:groupBy"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("groupBy")
                .hasMessageContaining("requires");
    }

    @Test
    void parseGroupByWithEmptyParensThrowsException() {
        // Orders:groupBy() should throw
        assertThatThrownBy(() -> ExpressionParser.parse("Orders:groupBy()"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("property");
    }

    @Test
    void parseGroupByWithInvalidProjectionThrowsException() {
        // Orders:groupBy(Status).InvalidToken should throw
        assertThatThrownBy(() -> ExpressionParser.parse("Orders:groupBy(Status).InvalidToken"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("{");
    }

    @Test
    void parseGroupByWithTrailingCommaThrowsException() {
        // Orders:groupBy(Status,) should throw
        assertThatThrownBy(() -> ExpressionParser.parse("Orders:groupBy(Status,)"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("property");
    }

    // endregion

    // region GroupBy Tests - Complex Scenarios

    @Test
    void parseGroupByAfterFilterWithProjectionComplexChain() {
        // Orders:where(Year = 2024):groupBy(Status).{Status, :count as Count, :sum(Total) as Revenue}
        var result = ExpressionParser.parse(
                "Orders:where(Year = 2024):groupBy(Status).{Status, :count as Count, :sum(Total) as Revenue}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        // Source is GroupByNode
        var groupByNode = (GroupByNode) projNode.source();
        assertThat(groupByNode.keyProperties().get(0)).isEqualTo("Status");

        // GroupByNode's source is FilterNode
        assertThat(groupByNode.source()).isInstanceOf(FilterNode.class);
        var filterNode = (FilterNode) groupByNode.source();
        assertThat(((PropertyNode) filterNode.source()).name()).isEqualTo("Orders");

        assertThat(projNode.properties()).hasSize(3);
    }

    @Test
    void parseGroupByWithDeepNavigationReturnsCorrectStructure() {
        // Company.Departments.Employees:groupBy(Role).{Role, :count as HeadCount}
        var result = ExpressionParser.parse("Company.Departments.Employees:groupBy(Role).{Role, :count as HeadCount}");

        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(3);

        // First two segments: Company, Departments
        assertThat(((PropertyNode) navNode.segments().get(0)).name()).isEqualTo("Company");
        assertThat(((PropertyNode) navNode.segments().get(1)).name()).isEqualTo("Departments");

        // Third segment: ProjectionNode (wrapping GroupByNode)
        assertThat(navNode.segments().get(2)).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) navNode.segments().get(2);
        assertThat(projNode.source()).isInstanceOf(GroupByNode.class);
    }

    @Test
    void parseGroupByAfterOrderByAndLimitComplexChain() {
        // Customers:orderBy(CreatedAt desc):limit(10).Orders:groupBy(Status).{Status, :count}
        var result = ExpressionParser.parse(
                "Customers:orderBy(CreatedAt desc):limit(10).Orders:groupBy(Status).{Status, :count}");

        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(2);

        // First segment: Customers:orderBy(CreatedAt desc):limit(10)
        assertThat(navNode.segments().get(0)).isInstanceOf(FunctionNode.class);
        var limitNode = (FunctionNode) navNode.segments().get(0);
        assertThat(limitNode.functionName()).isEqualTo(FunctionType.TAKE);
        assertThat(limitNode.source()).isInstanceOf(FunctionNode.class);
        var orderByNode = (FunctionNode) limitNode.source();
        assertThat(((PropertyNode) orderByNode.source()).name()).isEqualTo("Customers");
        assertThat(orderByNode.getArguments()).isEqualTo(List.of(new SortKeyNode("CreatedAt", OrderDirection.DESC)));

        // Second segment: ProjectionNode (Orders:groupBy with projection)
        assertThat(navNode.segments().get(1)).isInstanceOf(ProjectionNode.class);
    }

    @Test
    void parseGroupByWithComputedExpressionInProjection() {
        // Orders:groupBy(Status).{Status, (:count:multiply(10)) as WeightedCount}
        // Use multiply function instead of * operator (which isn't supported in tokenizer)
        var result = ExpressionParser.parse("Orders:groupBy(Status).{Status, (:count:multiply(10)) as WeightedCount}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        assertThat(projNode.properties()).hasSize(2);

        // Second property: computed expression
        var weightedProp = projNode.properties().get(1);
        assertThat(weightedProp.outputKey()).isEqualTo("WeightedCount");
        assertThat(weightedProp.isComputed()).isTrue();
    }

    @Test
    void parseGroupByWithAnyFunctionReturnsCorrectStructure() {
        // Orders:groupBy(CustomerId).{CustomerId, :any(Status = 'Urgent') as HasUrgent}
        var result = ExpressionParser.parse("Orders:groupBy(CustomerId).{CustomerId, :any(Status = 'Urgent') as HasUrgent}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        var hasUrgentProp = projNode.properties().get(1);
        assertThat(hasUrgentProp.outputKey()).isEqualTo("HasUrgent");

        // Should be BooleanFunctionNode (any with condition)
        assertThat(hasUrgentProp.expression()).isInstanceOf(BooleanFunctionNode.class);
        var anyFunc = (BooleanFunctionNode) hasUrgentProp.expression();
        assertThat(anyFunc.functionName()).isEqualTo(BooleanFunctionType.ANY);
    }

    @Test
    void parseGroupByWithAllFunctionReturnsCorrectStructure() {
        // Tasks:groupBy(ProjectId).{ProjectId, :all(IsCompleted = true) as AllCompleted}
        var result = ExpressionParser.parse("Tasks:groupBy(ProjectId).{ProjectId, :all(IsCompleted = true) as AllCompleted}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        var allCompletedProp = projNode.properties().get(1);
        assertThat(allCompletedProp.outputKey()).isEqualTo("AllCompleted");

        assertThat(allCompletedProp.expression()).isInstanceOf(BooleanFunctionNode.class);
        var allFunc = (BooleanFunctionNode) allCompletedProp.expression();
        assertThat(allFunc.functionName()).isEqualTo(BooleanFunctionType.ALL);
    }

    // NOTE: Items:distinct syntax in GroupBy projection is not supported in current design.
    // Use direct aggregations like :count, :sum on group elements instead.

    @Test
    void parseGroupByWithDefaultAliasWhenNoAliasProvided() {
        // Orders:groupBy(Status).{Status, :count}
        // :count should get default alias "Count"
        var result = ExpressionParser.parse("Orders:groupBy(Status).{Status, :count}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        // The count property should have "Count" as default alias
        var countProp = projNode.properties().get(1);
        assertThat(countProp.outputKey()).isEqualTo("Count"); // Default alias from function name
    }

    @Test
    void parseGroupByWithMultipleKeysAndComplexAggregations() {
        // Sales:groupBy(Year, Quarter, Region).{Year, Quarter, Region, :count as SalesCount, :sum(Amount) as TotalSales, :avg(Amount) as AvgSale}
        var result = ExpressionParser.parse(
                "Sales:groupBy(Year, Quarter, Region).{Year, Quarter, Region, :count as SalesCount, :sum(Amount) as TotalSales, :avg(Amount) as AvgSale}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        var groupByNode = (GroupByNode) projNode.source();
        assertThat(groupByNode.keyProperties()).hasSize(3);

        assertThat(projNode.properties()).hasSize(6);
        assertThat(projNode.properties().get(0).path()).isEqualTo("Year");
        assertThat(projNode.properties().get(1).path()).isEqualTo("Quarter");
        assertThat(projNode.properties().get(2).path()).isEqualTo("Region");
        assertThat(projNode.properties().get(3).outputKey()).isEqualTo("SalesCount");
        assertThat(projNode.properties().get(4).outputKey()).isEqualTo("TotalSales");
        assertThat(projNode.properties().get(5).outputKey()).isEqualTo("AvgSale");
    }

    @Test
    void parseGroupByWithTernaryInProjectionComplexScenario() {
        // Orders:groupBy(Status).{Status, (:count > 10 ? 'High' : 'Low') as Volume}
        var result = ExpressionParser.parse("Orders:groupBy(Status).{Status, (:count > 10 ? 'High' : 'Low') as Volume}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        var volumeProp = projNode.properties().get(1);
        assertThat(volumeProp.outputKey()).isEqualTo("Volume");
        assertThat(volumeProp.expression()).isInstanceOf(TernaryNode.class);

        var ternary = (TernaryNode) volumeProp.expression();
        assertThat(ternary.condition()).isInstanceOf(BinaryConditionNode.class);
    }

    @Test
    void parseGroupByWithCoalesceInProjectionComplexScenario() {
        // Orders:groupBy(Status).{Status, (:sum(Discount) ?? 0) as TotalDiscount}
        var result = ExpressionParser.parse("Orders:groupBy(Status).{Status, (:sum(Discount) ?? 0) as TotalDiscount}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        var discountProp = projNode.properties().get(1);
        assertThat(discountProp.outputKey()).isEqualTo("TotalDiscount");
        assertThat(discountProp.expression()).isInstanceOf(CoalesceNode.class);
    }

    @Test
    void parseGroupByOnNestedCollectionAfterNavigation() {
        // Customer.Orders:where(Status != 'Cancelled'):groupBy(ProductCategory).{ProductCategory, :count as CategoryCount}
        // The functions belong to the segment Orders, as for any other function: NavigationNode [Customer, Projection(GroupBy(Where(Orders)))]
        var result = ExpressionParser.parse(
                "Customer.Orders:where(Status != 'Cancelled'):groupBy(ProductCategory).{ProductCategory, :count as CategoryCount}");

        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(2);

        // First segment: Customer
        assertThat(((PropertyNode) navNode.segments().get(0)).name()).isEqualTo("Customer");

        // Second segment: the projection of the groups
        assertThat(navNode.segments().get(1)).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) navNode.segments().get(1);
        assertThat(projNode.source()).isInstanceOf(GroupByNode.class);
        var groupByNode = (GroupByNode) projNode.source();
        assertThat(groupByNode.keyProperties().get(0)).isEqualTo("ProductCategory");

        // The groups are made of the filtered Orders
        assertThat(groupByNode.source()).isInstanceOf(FilterNode.class);
        var filterNode = (FilterNode) groupByNode.source();
        assertThat(((PropertyNode) filterNode.source()).name()).isEqualTo("Orders");
    }

    @Test
    void parseGroupByWithOnlyItemsInProjection() {
        // Orders:groupBy(Status).{:count}
        // Just Items aggregation without key in projection (unusual but valid)
        var result = ExpressionParser.parse("Orders:groupBy(Status).{:count}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        assertThat(projNode.properties()).hasSize(1);
        assertThat(((FunctionNode) projNode.properties().get(0).expression()).functionName())
                .isEqualTo(FunctionType.COUNT);
    }

    @Test
    void parseGroupByPreservesKeyPropertyCase() {
        // Orders:groupBy(CustomerID).{CustomerID, :count}
        // Key property case should be preserved
        var result = ExpressionParser.parse("Orders:groupBy(CustomerID).{CustomerID, :count}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        var groupByNode = (GroupByNode) projNode.source();
        assertThat(groupByNode.keyProperties().get(0)).isEqualTo("CustomerID");

        assertThat(projNode.properties().get(0).path()).isEqualTo("CustomerID");
    }

    // endregion

    // region GroupBy Tests - Inner Projection

    @Test
    void parseGroupByWithInnerProjectionSimpleCase() {
        // Orders:groupBy(Status).{Status, {Id, Total} as Items}
        // Inner projection creates a Select on group elements
        var result = ExpressionParser.parse("Orders:groupBy(Status).{Status, {Id, Total} as Items}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        // Source is GroupByNode
        assertThat(projNode.source()).isInstanceOf(GroupByNode.class);
        var groupByNode = (GroupByNode) projNode.source();
        assertThat(groupByNode.keyProperties().get(0)).isEqualTo("Status");

        // Should have 2 properties: Status and Items
        assertThat(projNode.properties()).hasSize(2);

        // First property: Status (key)
        assertThat(projNode.properties().get(0).path()).isEqualTo("Status");

        // Second property: inner projection with alias Items
        var itemsProp = projNode.properties().get(1);
        assertThat(itemsProp.outputKey()).isEqualTo("Items");
        assertThat(itemsProp.isComputed()).isTrue();

        // Expression is a ProjectionNode with GroupElementsNode as source
        assertThat(itemsProp.expression()).isInstanceOf(ProjectionNode.class);
        var innerProjNode = (ProjectionNode) itemsProp.expression();
        assertThat(innerProjNode.source()).isInstanceOf(GroupElementsNode.class);

        // Inner projection has 2 properties: Id and Total
        assertThat(innerProjNode.properties()).hasSize(2);
        assertThat(innerProjNode.properties().get(0).path()).isEqualTo("Id");
        assertThat(innerProjNode.properties().get(1).path()).isEqualTo("Total");
    }

    @Test
    void parseGroupByWithInnerProjectionMultipleProperties() {
        // Orders:groupBy(CustomerId).{CustomerId, {Id, ProductName, Quantity, UnitPrice} as OrderDetails}
        var result = ExpressionParser.parse(
                "Orders:groupBy(CustomerId).{CustomerId, {Id, ProductName, Quantity, UnitPrice} as OrderDetails}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        var detailsProp = projNode.properties().get(1);
        assertThat(detailsProp.outputKey()).isEqualTo("OrderDetails");

        var innerProjNode = (ProjectionNode) detailsProp.expression();
        assertThat(innerProjNode.source()).isInstanceOf(GroupElementsNode.class);
        assertThat(innerProjNode.properties()).hasSize(4);
        assertThat(innerProjNode.properties().get(0).path()).isEqualTo("Id");
        assertThat(innerProjNode.properties().get(1).path()).isEqualTo("ProductName");
        assertThat(innerProjNode.properties().get(2).path()).isEqualTo("Quantity");
        assertThat(innerProjNode.properties().get(3).path()).isEqualTo("UnitPrice");
    }

    @Test
    void parseGroupByWithInnerProjectionWithAggregation() {
        // Orders:groupBy(Status).{Status, :count as Count, {Id, CustomerName} as Items}
        // Mix of aggregation and inner projection
        var result = ExpressionParser.parse("Orders:groupBy(Status).{Status, :count as Count, {Id, CustomerName} as Items}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        assertThat(projNode.properties()).hasSize(3);

        // First: Status (key)
        assertThat(projNode.properties().get(0).path()).isEqualTo("Status");

        // Second: :count as Count (aggregation)
        assertThat(projNode.properties().get(1).outputKey()).isEqualTo("Count");
        assertThat(projNode.properties().get(1).expression()).isInstanceOf(FunctionNode.class);

        // Third: {Id, CustomerName} as Items (inner projection)
        var itemsProp = projNode.properties().get(2);
        assertThat(itemsProp.outputKey()).isEqualTo("Items");
        var innerProjNode = (ProjectionNode) itemsProp.expression();
        assertThat(innerProjNode.source()).isInstanceOf(GroupElementsNode.class);
        assertThat(innerProjNode.properties()).hasSize(2);
    }

    @Test
    void parseGroupByWithInnerProjectionMultipleKeys() {
        // Sales:groupBy(Year, Month).{Year, Month, {Id, Amount, Customer} as Transactions}
        var result = ExpressionParser.parse("Sales:groupBy(Year, Month).{Year, Month, {Id, Amount, Customer} as Transactions}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        var groupByNode = (GroupByNode) projNode.source();
        assertThat(groupByNode.keyProperties()).hasSize(2);
        assertThat(groupByNode.keyProperties().get(0)).isEqualTo("Year");
        assertThat(groupByNode.keyProperties().get(1)).isEqualTo("Month");

        assertThat(projNode.properties()).hasSize(3);
        assertThat(projNode.properties().get(0).path()).isEqualTo("Year");
        assertThat(projNode.properties().get(1).path()).isEqualTo("Month");

        var transactionsProp = projNode.properties().get(2);
        assertThat(transactionsProp.outputKey()).isEqualTo("Transactions");

        var innerProjNode = (ProjectionNode) transactionsProp.expression();
        assertThat(innerProjNode.source()).isInstanceOf(GroupElementsNode.class);
        assertThat(innerProjNode.properties()).hasSize(3);
    }

    @Test
    void parseGroupByWithInnerProjectionSingleProperty() {
        // Orders:groupBy(Status).{Status, {Id} as OrderIds}
        var result = ExpressionParser.parse("Orders:groupBy(Status).{Status, {Id} as OrderIds}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        var orderIdsProp = projNode.properties().get(1);
        assertThat(orderIdsProp.outputKey()).isEqualTo("OrderIds");

        var innerProjNode = (ProjectionNode) orderIdsProp.expression();
        assertThat(innerProjNode.source()).isInstanceOf(GroupElementsNode.class);
        assertThat(innerProjNode.properties()).hasSize(1);
        assertThat(innerProjNode.properties().get(0).path()).isEqualTo("Id");
    }

    @Test
    void parseGroupByWithInnerProjectionAliasedInnerProperties() {
        // Orders:groupBy(Status).{Status, {Id as OrderId, Total as OrderTotal} as Items}
        var result = ExpressionParser.parse("Orders:groupBy(Status).{Status, {Id as OrderId, Total as OrderTotal} as Items}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        var itemsProp = projNode.properties().get(1);
        assertThat(itemsProp.outputKey()).isEqualTo("Items");

        var innerProjNode = (ProjectionNode) itemsProp.expression();
        assertThat(innerProjNode.properties()).hasSize(2);
        assertThat(innerProjNode.properties().get(0).path()).isEqualTo("Id");
        assertThat(innerProjNode.properties().get(0).outputKey()).isEqualTo("OrderId");
        assertThat(innerProjNode.properties().get(1).path()).isEqualTo("Total");
        assertThat(innerProjNode.properties().get(1).outputKey()).isEqualTo("OrderTotal");
    }

    @Test
    void parseGroupByWithInnerProjectionMissingAliasThrowsException() {
        // {Id, Total} without 'as Alias' should throw
        assertThatThrownBy(() -> ExpressionParser.parse("Orders:groupBy(Status).{Status, {Id, Total}}"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("as");
    }

    @Test
    void parseGroupByWithInnerProjectionAfterFilter() {
        // Orders:where(Year = 2024):groupBy(Status).{Status, {Id, CustomerName} as Items}
        var result = ExpressionParser.parse("Orders:where(Year = 2024):groupBy(Status).{Status, {Id, CustomerName} as Items}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        var groupByNode = (GroupByNode) projNode.source();
        assertThat(groupByNode.source()).isInstanceOf(FilterNode.class);

        var itemsProp = projNode.properties().get(1);
        assertThat(itemsProp.outputKey()).isEqualTo("Items");

        var innerProjNode = (ProjectionNode) itemsProp.expression();
        assertThat(innerProjNode.source()).isInstanceOf(GroupElementsNode.class);
    }

    @Test
    void parseGroupByWithInnerProjectionComplexCombination() {
        // Orders:groupBy(Status).{Status, :count as Count, :sum(Total) as Revenue, {Id, CustomerName, Total} as Details}
        var result = ExpressionParser.parse(
                "Orders:groupBy(Status).{Status, :count as Count, :sum(Total) as Revenue, {Id, CustomerName, Total} as Details}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projNode = (ProjectionNode) result;

        assertThat(projNode.properties()).hasSize(4);

        // Status (key)
        assertThat(projNode.properties().get(0).path()).isEqualTo("Status");

        // :count as Count
        assertThat(projNode.properties().get(1).outputKey()).isEqualTo("Count");
        assertThat(projNode.properties().get(1).expression()).isInstanceOf(FunctionNode.class);

        // :sum(Total) as Revenue
        assertThat(projNode.properties().get(2).outputKey()).isEqualTo("Revenue");
        assertThat(projNode.properties().get(2).expression()).isInstanceOf(AggregationNode.class);

        // {Id, CustomerName, Total} as Details
        var detailsProp = projNode.properties().get(3);
        assertThat(detailsProp.outputKey()).isEqualTo("Details");
        var innerProjNode = (ProjectionNode) detailsProp.expression();
        assertThat(innerProjNode.source()).isInstanceOf(GroupElementsNode.class);
        assertThat(innerProjNode.properties()).hasSize(3);
    }

    // endregion
}

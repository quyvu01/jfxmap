package io.github.quyvu01.jfxmap.expressions.parsing;

import io.github.quyvu01.jfxmap.expressions.nodes.AggregationNode;
import io.github.quyvu01.jfxmap.expressions.nodes.AggregationType;
import io.github.quyvu01.jfxmap.expressions.nodes.BinaryConditionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.CoalesceNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FilterNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FunctionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FunctionType;
import io.github.quyvu01.jfxmap.expressions.nodes.LiteralNode;
import io.github.quyvu01.jfxmap.expressions.nodes.NavigationNode;
import io.github.quyvu01.jfxmap.expressions.nodes.ProjectionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.PropertyNode;
import io.github.quyvu01.jfxmap.expressions.nodes.RootProjectionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.TernaryNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpressionParserFunctionsTest {

    // region Computed Projection Tests

    @Test
    void parseRootProjectionWithCoalesceExpressionReturnsCorrectNode() {
        // Arrange & Act - {Id, (Nickname ?? Name) as DisplayName}
        var result = ExpressionParser.parse("{Id, (Nickname ?? Name) as DisplayName}");

        // Assert
        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(2);

        // First property: Id
        assertThat(projection.properties().get(0).path()).isEqualTo("Id");
        assertThat(projection.properties().get(0).isComputed()).isFalse();

        // Second property: computed coalesce
        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("DisplayName");
        assertThat(projection.properties().get(1).expression()).isInstanceOf(CoalesceNode.class);

        var coalesce = (CoalesceNode) projection.properties().get(1).expression();
        assertThat(((PropertyNode) coalesce.left()).name()).isEqualTo("Nickname");
        assertThat(((PropertyNode) coalesce.right()).name()).isEqualTo("Name");
    }

    @Test
    void parseRootProjectionWithTernaryExpressionReturnsCorrectNode() {
        // Arrange & Act - {Id, (Status = 'Active' ? 'Yes' : 'No') as StatusText}
        var result = ExpressionParser.parse("{Id, (Status = 'Active' ? 'Yes' : 'No') as StatusText}");

        // Assert
        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(2);

        // Second property: computed ternary
        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("StatusText");
        assertThat(projection.properties().get(1).expression()).isInstanceOf(TernaryNode.class);

        var ternary = (TernaryNode) projection.properties().get(1).expression();
        assertThat(ternary.condition()).isInstanceOf(BinaryConditionNode.class);
        assertThat(((LiteralNode) ternary.whenTrue()).value()).isEqualTo("Yes");
        assertThat(((LiteralNode) ternary.whenFalse()).value()).isEqualTo("No");
    }

    @Test
    void parseRootProjectionWithMixedPropertiesReturnsCorrectNode() {
        // Arrange & Act - {Id, Name, Country.Name as CountryName, (Nickname ?? Name) as Display}
        var result = ExpressionParser.parse("{Id, Name, Country.Name as CountryName, (Nickname ?? Name) as Display}");

        // Assert
        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(4);

        // Check each property type
        assertThat(projection.properties().get(0).isComputed()).isFalse();
        assertThat(projection.properties().get(0).outputKey()).isEqualTo("Id");

        assertThat(projection.properties().get(1).isComputed()).isFalse();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("Name");

        assertThat(projection.properties().get(2).isComputed()).isFalse();
        assertThat(projection.properties().get(2).outputKey()).isEqualTo("CountryName");
        assertThat(projection.properties().get(2).hasNavigation()).isTrue();

        assertThat(projection.properties().get(3).isComputed()).isTrue();
        assertThat(projection.properties().get(3).outputKey()).isEqualTo("Display");
    }

    @Test
    void parseComputedProjectionWithoutAliasThrowsException() {
        // Arrange & Act - (Nickname ?? Name) without alias should fail
        var expression = "{Id, (Nickname ?? Name)}";

        // Assert
        assertThatThrownBy(() -> ExpressionParser.parse(expression))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("as");
    }

    @Test
    void parseFilterWithSimpleProjectionReturnsCorrectStructure() {
        // Non-root projection (Collection.{...}) only supports simple property names
        // For computed expressions, use RootProjection syntax
        var expression = "Provinces:where(Name endswith '0').{Id, Name}";

        // Act
        var result = ExpressionParser.parse(expression);

        // Assert
        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projection = (ProjectionNode) result;

        // The source should be a FilterNode
        assertThat(projection.source()).isInstanceOf(FilterNode.class);
        var filter = (FilterNode) projection.source();
        assertThat(((PropertyNode) filter.source()).name()).isEqualTo("Provinces");

        // Properties should be simple names
        assertThat(projection.properties()).hasSize(2);
        assertThat(projection.properties().get(0).path()).isEqualTo("Id");
        assertThat(projection.properties().get(1).path()).isEqualTo("Name");
    }

    @Test
    void parseNonRootProjectionWithComputedExpressionReturnsCorrectStructure() {
        // Non-root projection (Collection.{...}) now supports computed expressions
        var expression = "Provinces:where(Name endswith '0').{Id, (Name endswith '0' ? Name : 'N/A') as DisplayName}";

        // Act
        var result = ExpressionParser.parse(expression);

        // Assert
        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projection = (ProjectionNode) result;

        // The source should be a FilterNode
        assertThat(projection.source()).isInstanceOf(FilterNode.class);

        // Properties
        assertThat(projection.properties()).hasSize(2);

        // First property: simple path
        assertThat(projection.properties().get(0).path()).isEqualTo("Id");
        assertThat(projection.properties().get(0).isComputed()).isFalse();

        // Second property: computed ternary expression
        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("DisplayName");
        assertThat(projection.properties().get(1).expression()).isInstanceOf(TernaryNode.class);
    }

    // endregion

    // region String Functions

    @Test
    void parseUpperFunctionReturnsFunctionNode() {
        // Act
        var result = ExpressionParser.parse("Name:upper");

        // Assert
        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.UPPER);
        assertThat(func.source()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) func.source()).name()).isEqualTo("Name");
    }

    @Test
    void parseLowerFunctionReturnsFunctionNode() {
        // Act
        var result = ExpressionParser.parse("Name:lower");

        // Assert
        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.LOWER);
    }

    @Test
    void parseTrimFunctionReturnsFunctionNode() {
        // Act
        var result = ExpressionParser.parse("Description:trim");

        // Assert
        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.TRIM);
    }

    @Test
    void parseSubstringWithOneArgReturnsFunctionNode() {
        // Act
        var result = ExpressionParser.parse("Name:substring(0)");

        // Assert
        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.SUBSTRING);
        assertThat(func.arguments()).isNotNull();
        assertThat(func.arguments()).hasSize(1);
        assertThat(func.arguments().get(0)).isInstanceOf(LiteralNode.class);
        assertThat((BigDecimal) ((LiteralNode) func.arguments().get(0)).value()).isEqualByComparingTo("0");
    }

    @Test
    void parseSubstringWithTwoArgsReturnsFunctionNode() {
        // Act
        var result = ExpressionParser.parse("Name:substring(0, 3)");

        // Assert
        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.SUBSTRING);
        assertThat(func.arguments()).isNotNull();
        assertThat(func.arguments()).hasSize(2);
        assertThat((BigDecimal) ((LiteralNode) func.arguments().get(0)).value()).isEqualByComparingTo("0");
        assertThat((BigDecimal) ((LiteralNode) func.arguments().get(1)).value()).isEqualByComparingTo("3");
    }

    @Test
    void parseReplaceFunctionReturnsFunctionNode() {
        // Act
        var result = ExpressionParser.parse("Name:replace('a', 'b')");

        // Assert
        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.REPLACE);
        assertThat(func.arguments()).isNotNull();
        assertThat(func.arguments()).hasSize(2);
        assertThat(((LiteralNode) func.arguments().get(0)).value()).isEqualTo("a");
        assertThat(((LiteralNode) func.arguments().get(1)).value()).isEqualTo("b");
    }

    @Test
    void parseConcatWithLiteralsReturnsFunctionNode() {
        // Act
        var result = ExpressionParser.parse("FirstName:concat(' ', LastName)");

        // Assert
        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.CONCAT);
        assertThat(func.arguments()).isNotNull();
        assertThat(func.arguments()).hasSize(2);
        assertThat(((LiteralNode) func.arguments().get(0)).value()).isEqualTo(" ");
        assertThat(func.arguments().get(1)).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) func.arguments().get(1)).name()).isEqualTo("LastName");
    }

    @Test
    void parseSplitFunctionReturnsFunctionNode() {
        // Act
        var result = ExpressionParser.parse("Tags:split(',')");

        // Assert
        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.SPLIT);
        assertThat(func.arguments()).isNotNull();
        assertThat(func.arguments()).hasSize(1);
        assertThat(((LiteralNode) func.arguments().get(0)).value()).isEqualTo(",");
    }

    @Test
    void parseChainedStringFunctionsReturnsFunctionNode() {
        // Name:trim:upper - trim first, then uppercase
        // This parses as (Name:trim):upper
        var result = ExpressionParser.parse("Name:trim:upper");

        // Assert
        assertThat(result).isInstanceOf(FunctionNode.class);
        var upperFunc = (FunctionNode) result;
        assertThat(upperFunc.functionName()).isEqualTo(FunctionType.UPPER);

        assertThat(upperFunc.source()).isInstanceOf(FunctionNode.class);
        var trimFunc = (FunctionNode) upperFunc.source();
        assertThat(trimFunc.functionName()).isEqualTo(FunctionType.TRIM);
        assertThat(trimFunc.source()).isInstanceOf(PropertyNode.class);
    }

    @Test
    void parseSubstringWithoutArgsThrowsException() {
        // substring requires at least 1 argument
        assertThatThrownBy(() -> ExpressionParser.parse("Name:substring"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("requires arguments");
    }

    @Test
    void parseReplaceWithOneArgThrowsException() {
        // replace requires exactly 2 arguments
        assertThatThrownBy(() -> ExpressionParser.parse("Name:replace('a')"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("requires exactly 2 arguments");
    }

    @Test
    void parseSplitWithoutArgsThrowsException() {
        // split requires exactly 1 argument
        assertThatThrownBy(() -> ExpressionParser.parse("Tags:split"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("requires arguments");
    }

    @Test
    void parseStringFunctionInProjectionReturnsCorrectStructure() {
        // {Id, Name:upper as UpperName}
        var result = ExpressionParser.parse("{Id, (Name:upper) as UpperName}");

        // Assert
        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(2);

        assertThat(projection.properties().get(0).path()).isEqualTo("Id");
        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("UpperName");
        assertThat(projection.properties().get(1).expression()).isInstanceOf(FunctionNode.class);

        var func = (FunctionNode) projection.properties().get(1).expression();
        assertThat(func.functionName()).isEqualTo(FunctionType.UPPER);
    }

    // endregion

    // region Inline Functions in Projection Tests (Optional Parentheses)

    @Test
    void parseInlineFunctionWithoutAliasUsesPropertyNameAsOutputKey() {
        // {Id, Name:upper} → Name:upper outputs as "Name"
        var result = ExpressionParser.parse("{Id, Name:upper}");

        // Assert
        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(2);

        assertThat(projection.properties().get(0).path()).isEqualTo("Id");
        assertThat(projection.properties().get(0).isComputed()).isFalse();

        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("Name"); // Uses property name as output key
        assertThat(projection.properties().get(1).expression()).isInstanceOf(FunctionNode.class);

        var func = (FunctionNode) projection.properties().get(1).expression();
        assertThat(func.functionName()).isEqualTo(FunctionType.UPPER);
        assertThat(func.source()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) func.source()).name()).isEqualTo("Name");
    }

    @Test
    void parseInlineFunctionWithAliasUsesAliasAsOutputKey() {
        // {Id, Name:upper as UpperName}
        var result = ExpressionParser.parse("{Id, Name:upper as UpperName}");

        // Assert
        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(2);

        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("UpperName");
        assertThat(projection.properties().get(1).expression()).isInstanceOf(FunctionNode.class);
    }

    @Test
    void parseInlineLowerFunctionReturnsCorrectStructure() {
        var result = ExpressionParser.parse("{Id, Name:lower as LowerName}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;

        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("LowerName");

        var func = (FunctionNode) projection.properties().get(1).expression();
        assertThat(func.functionName()).isEqualTo(FunctionType.LOWER);
    }

    @Test
    void parseInlineTrimFunctionReturnsCorrectStructure() {
        var result = ExpressionParser.parse("{Id, Description:trim}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;

        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("Description");

        var func = (FunctionNode) projection.properties().get(1).expression();
        assertThat(func.functionName()).isEqualTo(FunctionType.TRIM);
    }

    @Test
    void parseInlineSubstringFunctionReturnsCorrectStructure() {
        var result = ExpressionParser.parse("{Id, Name:substring(0, 3) as Short}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;

        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("Short");

        var func = (FunctionNode) projection.properties().get(1).expression();
        assertThat(func.functionName()).isEqualTo(FunctionType.SUBSTRING);
        assertThat(func.arguments()).hasSize(2);
    }

    @Test
    void parseInlineReplaceFunctionReturnsCorrectStructure() {
        var result = ExpressionParser.parse("{Id, Name:replace('a', 'b') as ReplacedName}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;

        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("ReplacedName");

        var func = (FunctionNode) projection.properties().get(1).expression();
        assertThat(func.functionName()).isEqualTo(FunctionType.REPLACE);
        assertThat(func.arguments()).hasSize(2);
    }

    @Test
    void parseInlineConcatFunctionReturnsCorrectStructure() {
        var result = ExpressionParser.parse("{Id, FirstName:concat(' ', LastName) as FullName}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;

        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("FullName");

        var func = (FunctionNode) projection.properties().get(1).expression();
        assertThat(func.functionName()).isEqualTo(FunctionType.CONCAT);
        assertThat(func.arguments()).hasSize(2);
    }

    @Test
    void parseInlineChainedFunctionsReturnsCorrectStructure() {
        // Name:trim:upper - trim first, then uppercase
        var result = ExpressionParser.parse("{Id, Name:trim:upper as CleanName}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;

        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("CleanName");

        // Should be (Name:trim):upper
        var upperFunc = (FunctionNode) projection.properties().get(1).expression();
        assertThat(upperFunc.functionName()).isEqualTo(FunctionType.UPPER);

        var trimFunc = (FunctionNode) upperFunc.source();
        assertThat(trimFunc.functionName()).isEqualTo(FunctionType.TRIM);
        assertThat(trimFunc.source()).isInstanceOf(PropertyNode.class);
    }

    @Test
    void parseInlineFunctionWithNavigationPathReturnsCorrectStructure() {
        // Country.Name:upper - navigation then function
        var result = ExpressionParser.parse("{Id, Country.Name:upper as CountryName}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;

        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("CountryName");

        var func = (FunctionNode) projection.properties().get(1).expression();
        assertThat(func.functionName()).isEqualTo(FunctionType.UPPER);

        // Source should be NavigationNode(Country, Name)
        assertThat(func.source()).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) func.source();
        assertThat(navNode.segments()).hasSize(2);
    }

    @Test
    void parseInlineCountFunctionReturnsCorrectStructure() {
        // Orders:count
        var result = ExpressionParser.parse("{Id, Orders:count as OrderCount}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;

        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("OrderCount");

        var func = (FunctionNode) projection.properties().get(1).expression();
        assertThat(func.functionName()).isEqualTo(FunctionType.COUNT);
    }

    @Test
    void parseMultipleInlineFunctionsReturnsCorrectStructure() {
        // Multiple inline functions in one projection
        var result = ExpressionParser.parse(
                "{Id, Name:upper, Description:trim as TrimmedDesc, Country.Name:lower as CountryName}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(4);

        // Id - simple property
        assertThat(projection.properties().get(0).path()).isEqualTo("Id");
        assertThat(projection.properties().get(0).isComputed()).isFalse();

        // Name:upper - no alias, uses "Name"
        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("Name");

        // Description:trim as TrimmedDesc
        assertThat(projection.properties().get(2).isComputed()).isTrue();
        assertThat(projection.properties().get(2).outputKey()).isEqualTo("TrimmedDesc");

        // Country.Name:lower as CountryName
        assertThat(projection.properties().get(3).isComputed()).isTrue();
        assertThat(projection.properties().get(3).outputKey()).isEqualTo("CountryName");
    }

    @Test
    void parseInlineFunctionWithSplitFunctionReturnsCorrectStructure() {
        var result = ExpressionParser.parse("{Id, Tags:split(',') as TagList}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;

        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("TagList");

        var func = (FunctionNode) projection.properties().get(1).expression();
        assertThat(func.functionName()).isEqualTo(FunctionType.SPLIT);
    }

    @Test
    void parseMixedInlineAndComputedExpressionsReturnsCorrectStructure() {
        // Mix of inline functions and computed expressions (with parens)
        var result = ExpressionParser.parse("{Id, Name:upper, (Nickname ?? Name) as DisplayName}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(3);

        // Name:upper - inline function
        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("Name");
        assertThat(projection.properties().get(1).expression()).isInstanceOf(FunctionNode.class);

        // (Nickname ?? Name) as DisplayName - computed expression
        assertThat(projection.properties().get(2).isComputed()).isTrue();
        assertThat(projection.properties().get(2).outputKey()).isEqualTo("DisplayName");
        assertThat(projection.properties().get(2).expression()).isInstanceOf(CoalesceNode.class);
    }

    @Test
    void parseInlineFunctionInNonRootProjectionReturnsCorrectStructure() {
        // Collection projection with inline function
        var result = ExpressionParser.parse("Orders.{Id, ProductName:upper as Product}");

        assertThat(result).isInstanceOf(ProjectionNode.class);
        var projection = (ProjectionNode) result;

        assertThat(projection.properties()).hasSize(2);
        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("Product");
        assertThat(projection.properties().get(1).expression()).isInstanceOf(FunctionNode.class);
    }

    @Test
    void parseComplexExpressionRequiresParenthesesCoalesceNeedsParens() {
        // Coalesce without parens should still work at top level
        // But in projection, complex expressions need parens
        var result = ExpressionParser.parse("{Id, (Nickname ?? Name) as Display}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;

        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).expression()).isInstanceOf(CoalesceNode.class);
    }

    @Test
    void parseComplexExpressionRequiresParenthesesTernaryNeedsParens() {
        // Ternary expressions must use parens in projection
        var result = ExpressionParser.parse("{Id, (Status = 'Active' ? 'Yes' : 'No') as StatusText}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;

        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).expression()).isInstanceOf(TernaryNode.class);
    }

    // endregion

    // region Date Functions

    @Test
    void parseYearFunctionReturnsFunctionNode() {
        var result = ExpressionParser.parse("CreatedAt:year");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.YEAR);
        assertThat(func.source()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) func.source()).name()).isEqualTo("CreatedAt");
    }

    @Test
    void parseMonthFunctionReturnsFunctionNode() {
        var result = ExpressionParser.parse("CreatedAt:month");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.MONTH);
    }

    @Test
    void parseDayFunctionReturnsFunctionNode() {
        var result = ExpressionParser.parse("CreatedAt:day");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.DAY);
    }

    @Test
    void parseHourFunctionReturnsFunctionNode() {
        var result = ExpressionParser.parse("CreatedAt:hour");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.HOUR);
    }

    @Test
    void parseMinuteFunctionReturnsFunctionNode() {
        var result = ExpressionParser.parse("CreatedAt:minute");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.MINUTE);
    }

    @Test
    void parseSecondFunctionReturnsFunctionNode() {
        var result = ExpressionParser.parse("CreatedAt:second");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.SECOND);
    }

    @Test
    void parseDayOfWeekFunctionReturnsFunctionNode() {
        var result = ExpressionParser.parse("CreatedAt:dayOfWeek");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.DAY_OF_WEEK);
    }

    @Test
    void parseDaysAgoFunctionReturnsFunctionNode() {
        var result = ExpressionParser.parse("CreatedAt:daysAgo");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.DAYS_AGO);
    }

    @Test
    void parseFormatFunctionReturnsFunctionNodeWithArgument() {
        var result = ExpressionParser.parse("CreatedAt:format('yyyy-MM-dd')");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.FORMAT);
        assertThat(func.arguments()).isNotNull();
        assertThat(func.arguments()).hasSize(1);
        assertThat(func.arguments().get(0)).isInstanceOf(LiteralNode.class);
        assertThat(((LiteralNode) func.arguments().get(0)).value()).isEqualTo("yyyy-MM-dd");
    }

    @Test
    void parseFormatFunctionWithTimeFormatReturnsFunctionNode() {
        var result = ExpressionParser.parse("CreatedAt:format('yyyy-MM-dd HH:mm:ss')");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.FORMAT);
        assertThat(((LiteralNode) func.arguments().get(0)).value()).isEqualTo("yyyy-MM-dd HH:mm:ss");
    }

    @Test
    void parseFormatFunctionWithoutArgsThrowsException() {
        assertThatThrownBy(() -> ExpressionParser.parse("CreatedAt:format"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("requires");
    }

    @Test
    void parseDateFunctionInProjectionReturnsCorrectStructure() {
        var result = ExpressionParser.parse("{Id, CreatedAt:year as Year}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(2);

        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("Year");
        assertThat(projection.properties().get(1).expression()).isInstanceOf(FunctionNode.class);

        var func = (FunctionNode) projection.properties().get(1).expression();
        assertThat(func.functionName()).isEqualTo(FunctionType.YEAR);
    }

    @Test
    void parseDateFunctionWithNavigationReturnsCorrectStructure() {
        var result = ExpressionParser.parse("Order.CreatedAt:year");

        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(2);

        assertThat(navNode.segments().get(1)).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) navNode.segments().get(1);
        assertThat(func.functionName()).isEqualTo(FunctionType.YEAR);
    }

    @Test
    void parseMultipleDateFunctionsInProjectionReturnsCorrectStructure() {
        var result = ExpressionParser.parse(
                "{Id, CreatedAt:year as Year, CreatedAt:month as Month, CreatedAt:day as Day}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(4);

        assertThat(((FunctionNode) projection.properties().get(1).expression()).functionName())
                .isEqualTo(FunctionType.YEAR);
        assertThat(((FunctionNode) projection.properties().get(2).expression()).functionName())
                .isEqualTo(FunctionType.MONTH);
        assertThat(((FunctionNode) projection.properties().get(3).expression()).functionName())
                .isEqualTo(FunctionType.DAY);
    }

    @Test
    void parseDateFormatInProjectionReturnsCorrectStructure() {
        var result = ExpressionParser.parse("{Id, CreatedAt:format('yyyy-MM-dd') as DateStr}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;

        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("DateStr");

        var func = (FunctionNode) projection.properties().get(1).expression();
        assertThat(func.functionName()).isEqualTo(FunctionType.FORMAT);
    }

    @Test
    void parseDateFunctionCaseInsensitiveReturnsFunctionNode() {
        // Test case insensitivity
        var result1 = ExpressionParser.parse("CreatedAt:YEAR");
        var result2 = ExpressionParser.parse("CreatedAt:Year");
        var result3 = ExpressionParser.parse("CreatedAt:year");

        assertThat(((FunctionNode) result1).functionName()).isEqualTo(FunctionType.YEAR);
        assertThat(((FunctionNode) result2).functionName()).isEqualTo(FunctionType.YEAR);
        assertThat(((FunctionNode) result3).functionName()).isEqualTo(FunctionType.YEAR);
    }

    // endregion

    // region Math Functions

    @Test
    void parseFloorFunctionReturnsFunctionNode() {
        var result = ExpressionParser.parse("Price:floor");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.FLOOR);
        assertThat(func.source()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) func.source()).name()).isEqualTo("Price");
    }

    @Test
    void parseCeilFunctionReturnsFunctionNode() {
        var result = ExpressionParser.parse("Price:ceil");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.CEIL);
    }

    @Test
    void parseAbsFunctionReturnsFunctionNode() {
        var result = ExpressionParser.parse("Balance:abs");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.ABS);
    }

    @Test
    void parseRoundFunctionWithoutArgsReturnsFunctionNode() {
        var result = ExpressionParser.parse("Price:round");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.ROUND);
        // FxMap leaves Arguments null; FunctionNode.arguments() is never null, so no arguments is an empty list
        assertThat(func.arguments()).isEmpty();
    }

    @Test
    void parseRoundFunctionWithDecimalsReturnsFunctionNodeWithArgument() {
        var result = ExpressionParser.parse("Price:round(2)");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.ROUND);
        assertThat(func.arguments()).isNotNull();
        assertThat(func.arguments()).hasSize(1);
        assertThat(func.arguments().get(0)).isInstanceOf(LiteralNode.class);
        assertThat((BigDecimal) ((LiteralNode) func.arguments().get(0)).value()).isEqualByComparingTo("2");
    }

    @Test
    void parseAddFunctionWithNumberReturnsFunctionNode() {
        var result = ExpressionParser.parse("Price:add(10)");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.ADD);
        assertThat(func.arguments()).isNotNull();
        assertThat(func.arguments()).hasSize(1);
        assertThat(func.arguments().get(0)).isInstanceOf(LiteralNode.class);
        assertThat((BigDecimal) ((LiteralNode) func.arguments().get(0)).value()).isEqualByComparingTo("10");
    }

    @Test
    void parseAddFunctionWithPropertyReturnsFunctionNode() {
        var result = ExpressionParser.parse("Price:add(Tax)");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.ADD);
        assertThat(func.arguments()).isNotNull();
        assertThat(func.arguments()).hasSize(1);
        assertThat(func.arguments().get(0)).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) func.arguments().get(0)).name()).isEqualTo("Tax");
    }

    @Test
    void parseSubtractFunctionReturnsFunctionNode() {
        var result = ExpressionParser.parse("Price:subtract(Discount)");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.SUBTRACT);
        assertThat(func.arguments()).isNotNull();
        assertThat(func.arguments()).hasSize(1);
        assertThat(func.arguments().get(0)).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) func.arguments().get(0)).name()).isEqualTo("Discount");
    }

    @Test
    void parseMultiplyFunctionReturnsFunctionNode() {
        var result = ExpressionParser.parse("Price:multiply(Quantity)");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.MULTIPLY);
        assertThat(func.arguments()).isNotNull();
        assertThat(func.arguments()).hasSize(1);
        assertThat(func.arguments().get(0)).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) func.arguments().get(0)).name()).isEqualTo("Quantity");
    }

    @Test
    void parseDivideFunctionReturnsFunctionNode() {
        var result = ExpressionParser.parse("Total:divide(Count)");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.DIVIDE);
        assertThat(func.arguments()).isNotNull();
        assertThat(func.arguments()).hasSize(1);
    }

    @Test
    void parseModFunctionReturnsFunctionNode() {
        var result = ExpressionParser.parse("Value:mod(3)");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.MOD);
        assertThat(func.arguments()).isNotNull();
        assertThat(func.arguments()).hasSize(1);
        assertThat((BigDecimal) ((LiteralNode) func.arguments().get(0)).value()).isEqualByComparingTo("3");
    }

    @Test
    void parsePowFunctionReturnsFunctionNode() {
        var result = ExpressionParser.parse("Value:pow(2)");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.POW);
        assertThat(func.arguments()).isNotNull();
        assertThat(func.arguments()).hasSize(1);
        assertThat((BigDecimal) ((LiteralNode) func.arguments().get(0)).value()).isEqualByComparingTo("2");
    }

    @Test
    void parseAddWithoutArgsThrowsException() {
        assertThatThrownBy(() -> ExpressionParser.parse("Price:add"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("requires an argument");
    }

    @Test
    void parseSubtractWithoutArgsThrowsException() {
        assertThatThrownBy(() -> ExpressionParser.parse("Price:subtract"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("requires an argument");
    }

    @Test
    void parseMultiplyWithoutArgsThrowsException() {
        assertThatThrownBy(() -> ExpressionParser.parse("Price:multiply"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("requires an argument");
    }

    @Test
    void parseDivideWithoutArgsThrowsException() {
        assertThatThrownBy(() -> ExpressionParser.parse("Price:divide"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("requires an argument");
    }

    @Test
    void parseModWithoutArgsThrowsException() {
        assertThatThrownBy(() -> ExpressionParser.parse("Value:mod"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("requires an argument");
    }

    @Test
    void parsePowWithoutArgsThrowsException() {
        assertThatThrownBy(() -> ExpressionParser.parse("Value:pow"))
                .isInstanceOf(ExpressionParseException.class)
                .hasMessageContaining("requires an argument");
    }

    @Test
    void parseMathFunctionInProjectionReturnsCorrectStructure() {
        var result = ExpressionParser.parse("{Id, Price:round(2) as RoundedPrice}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(2);

        assertThat(projection.properties().get(1).isComputed()).isTrue();
        assertThat(projection.properties().get(1).outputKey()).isEqualTo("RoundedPrice");
        assertThat(projection.properties().get(1).expression()).isInstanceOf(FunctionNode.class);

        var func = (FunctionNode) projection.properties().get(1).expression();
        assertThat(func.functionName()).isEqualTo(FunctionType.ROUND);
    }

    @Test
    void parseChainedMathFunctionsReturnsCorrectStructure() {
        // Price:add(Tax):round(2) - add tax first, then round
        var result = ExpressionParser.parse("Price:add(Tax):round(2)");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var roundFunc = (FunctionNode) result;
        assertThat(roundFunc.functionName()).isEqualTo(FunctionType.ROUND);

        assertThat(roundFunc.source()).isInstanceOf(FunctionNode.class);
        var addFunc = (FunctionNode) roundFunc.source();
        assertThat(addFunc.functionName()).isEqualTo(FunctionType.ADD);
        assertThat(addFunc.source()).isInstanceOf(PropertyNode.class);
        assertThat(((PropertyNode) addFunc.source()).name()).isEqualTo("Price");
    }

    @Test
    void parseMathFunctionWithNavigationReturnsCorrectStructure() {
        var result = ExpressionParser.parse("Order.Total:floor");

        assertThat(result).isInstanceOf(NavigationNode.class);
        var navNode = (NavigationNode) result;
        assertThat(navNode.segments()).hasSize(2);

        assertThat(navNode.segments().get(1)).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) navNode.segments().get(1);
        assertThat(func.functionName()).isEqualTo(FunctionType.FLOOR);
    }

    @Test
    void parseMultipleMathFunctionsInProjectionReturnsCorrectStructure() {
        var result = ExpressionParser.parse(
                "{Id, Price:floor as FloorPrice, Price:ceil as CeilPrice, Balance:abs as AbsBalance}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(4);

        assertThat(((FunctionNode) projection.properties().get(1).expression()).functionName())
                .isEqualTo(FunctionType.FLOOR);
        assertThat(((FunctionNode) projection.properties().get(2).expression()).functionName())
                .isEqualTo(FunctionType.CEIL);
        assertThat(((FunctionNode) projection.properties().get(3).expression()).functionName())
                .isEqualTo(FunctionType.ABS);
    }

    @Test
    void parseMathFunctionCaseInsensitiveReturnsFunctionNode() {
        // Test case insensitivity
        var result1 = ExpressionParser.parse("Price:FLOOR");
        var result2 = ExpressionParser.parse("Price:Floor");
        var result3 = ExpressionParser.parse("Price:floor");

        assertThat(((FunctionNode) result1).functionName()).isEqualTo(FunctionType.FLOOR);
        assertThat(((FunctionNode) result2).functionName()).isEqualTo(FunctionType.FLOOR);
        assertThat(((FunctionNode) result3).functionName()).isEqualTo(FunctionType.FLOOR);
    }

    @Test
    void parseComplexMathExpressionReturnsCorrectStructure() {
        // Simulate: (Price * Quantity - Discount):round(2)
        // Using chained functions: Price:multiply(Quantity):subtract(Discount):round(2)
        var result = ExpressionParser.parse("Price:multiply(Quantity):subtract(Discount):round(2)");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var roundFunc = (FunctionNode) result;
        assertThat(roundFunc.functionName()).isEqualTo(FunctionType.ROUND);

        var subtractFunc = (FunctionNode) roundFunc.source();
        assertThat(subtractFunc.functionName()).isEqualTo(FunctionType.SUBTRACT);

        var multiplyFunc = (FunctionNode) subtractFunc.source();
        assertThat(multiplyFunc.functionName()).isEqualTo(FunctionType.MULTIPLY);

        var priceNode = (PropertyNode) multiplyFunc.source();
        assertThat(priceNode.name()).isEqualTo("Price");
    }

    @Test
    void parseMathFunctionInFilterReturnsCorrectStructure() {
        // Filter products where rounded price > 100
        var result = ExpressionParser.parse("Products:where(Price:floor > 100)");

        assertThat(result).isInstanceOf(FilterNode.class);
        var filterNode = (FilterNode) result;
        var condition = (BinaryConditionNode) filterNode.condition();

        assertThat(condition.left()).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) condition.left();
        assertThat(func.functionName()).isEqualTo(FunctionType.FLOOR);
    }

    @Test
    void parseMathFunctionWithNegativeNumberReturnsCorrectStructure() {
        var result = ExpressionParser.parse("Value:add(-10)");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.ADD);
        assertThat(func.arguments()).hasSize(1);
        assertThat(func.arguments().get(0)).isInstanceOf(LiteralNode.class);
        assertThat((BigDecimal) ((LiteralNode) func.arguments().get(0)).value()).isEqualByComparingTo("-10");
    }

    @Test
    void parseMathFunctionWithDecimalNumberReturnsCorrectStructure() {
        var result = ExpressionParser.parse("Price:multiply(1.15)");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var func = (FunctionNode) result;
        assertThat(func.functionName()).isEqualTo(FunctionType.MULTIPLY);
        assertThat(func.arguments()).hasSize(1);
        assertThat(func.arguments().get(0)).isInstanceOf(LiteralNode.class);
        assertThat((BigDecimal) ((LiteralNode) func.arguments().get(0)).value()).isEqualByComparingTo("1.15");
    }

    @Test
    void parseComplexProjectionWithMathAndStringReturnsCorrectStructure() {
        // Mix of math and string functions in projection
        var result = ExpressionParser.parse(
                "{Id, Name:upper, Price:round(2) as RoundedPrice, (Balance:abs) as AbsBalance}");

        assertThat(result).isInstanceOf(RootProjectionNode.class);
        var projection = (RootProjectionNode) result;
        assertThat(projection.properties()).hasSize(4);

        // Name:upper - string function
        assertThat(((FunctionNode) projection.properties().get(1).expression()).functionName())
                .isEqualTo(FunctionType.UPPER);

        // Price:round(2) - math function
        assertThat(((FunctionNode) projection.properties().get(2).expression()).functionName())
                .isEqualTo(FunctionType.ROUND);

        // (Balance:abs) - math function with parens
        assertThat(((FunctionNode) projection.properties().get(3).expression()).functionName())
                .isEqualTo(FunctionType.ABS);
    }

    @Test
    void parseMathFunctionAfterAggregationReturnsAggregationNode() {
        // Note: When chaining :sum(Total):round(2), the parser currently returns
        // an AggregationNode for :sum(Total) and doesn't chain the :round(2) after it.
        // This is because aggregation functions (with property selector) are handled
        // differently than regular functions.
        // To apply math operations on aggregation results, use projections:
        // {Total: (Orders:where(Status = 'Done'):sum(Total)):round(2) as RoundedTotal}
        var result = ExpressionParser.parse("Orders:where(Status = 'Done'):sum(Total)");

        assertThat(result).isInstanceOf(AggregationNode.class);
        var sumAgg = (AggregationNode) result;
        assertThat(sumAgg.aggregationType()).isEqualTo(AggregationType.SUM);
        assertThat(sumAgg.propertyName()).isEqualTo("Total");

        assertThat(sumAgg.source()).isInstanceOf(FilterNode.class);
    }

    @Test
    void parseMathFunctionAfterCountFunctionReturnsCorrectStructure() {
        // Chaining works for functions that return FunctionNode like :count
        // Orders:where(Status = 'Done'):count:add(10)
        var result = ExpressionParser.parse("Orders:where(Status = 'Done'):count:add(10)");

        assertThat(result).isInstanceOf(FunctionNode.class);
        var addFunc = (FunctionNode) result;
        assertThat(addFunc.functionName()).isEqualTo(FunctionType.ADD);

        assertThat(addFunc.source()).isInstanceOf(FunctionNode.class);
        var countFunc = (FunctionNode) addFunc.source();
        assertThat(countFunc.functionName()).isEqualTo(FunctionType.COUNT);

        assertThat(countFunc.source()).isInstanceOf(FilterNode.class);
    }

    // endregion
}

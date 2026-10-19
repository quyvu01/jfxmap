package io.github.quyvu01.jfxmap.expressions.parsing;

import io.github.quyvu01.jfxmap.expressions.nodes.AggregationNode;
import io.github.quyvu01.jfxmap.expressions.nodes.AggregationType;
import io.github.quyvu01.jfxmap.expressions.nodes.BinaryConditionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.BooleanFunctionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.BooleanFunctionType;
import io.github.quyvu01.jfxmap.expressions.nodes.CoalesceNode;
import io.github.quyvu01.jfxmap.expressions.nodes.ComparisonOperator;
import io.github.quyvu01.jfxmap.expressions.nodes.ConditionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.ExpressionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FilterNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FunctionNode;
import io.github.quyvu01.jfxmap.expressions.nodes.FunctionType;
import io.github.quyvu01.jfxmap.expressions.nodes.GroupByNode;
import io.github.quyvu01.jfxmap.expressions.nodes.GroupElementsNode;
import io.github.quyvu01.jfxmap.expressions.nodes.LiteralNode;
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
import io.github.quyvu01.jfxmap.expressions.tokens.Token;
import io.github.quyvu01.jfxmap.expressions.tokens.TokenType;
import io.github.quyvu01.jfxmap.expressions.tokens.Tokenizer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Parses FxMap expressions into an abstract syntax tree.
 *
 * <p>Grammar (with precedence from low to high):
 * <pre>
 * Expression         := TernaryExpr
 * TernaryExpr        := CoalesceExpr (Condition '?' Expression ':' Expression)?
 * CoalesceExpr       := PrimaryExpr ('??' CoalesceExpr)?
 * PrimaryExpr        := RootProjection | Literal | NavigationExpr
 * NavigationExpr     := Segment ('.' Segment)* ('.' Projection)?
 * RootProjection     := '{' ProjectionProperty (',' ProjectionProperty)* '}'
 * ProjectionProperty := PropertyPath (':' Function)* ('as' Identifier)? | '(' Expression ')' 'as' Identifier
 * PropertyPath       := Identifier ('.' Identifier)*
 * Segment            := Identifier ('?')? (':' Function)*
 * Condition          := OrCondition
 * OrCondition        := AndCondition (('||' | 'or') AndCondition)*
 * AndCondition       := Comparison (('&amp;&amp;' | 'and' | ',') Comparison)*
 * Comparison         := FieldPath Operator Value
 * FieldPath          := Identifier (':' FunctionName)? ('.' Identifier (':' FunctionName)?)*
 * Operator           := '=' | '!=' | '&gt;' | '&lt;' | '&gt;=' | '&lt;=' | 'contains' | 'startswith' | 'endswith'
 * Value              := String | Number | Boolean | Null
 * Projection         := '{' ProjectionProperty (',' ProjectionProperty)* '}'
 * Function           := FunctionName ('(' Arguments ')')?
 * FunctionName       := 'where' | 'count' | 'sum' | 'avg' | 'min' | 'max' | 'any' | 'all' | 'orderBy' | 'offset'
 *                     | 'limit' | 'first' | 'last' | 'distinct' | 'groupBy' | ...
 * SortKey            := Identifier ('asc' | 'desc')?        (orderBy(CreatedAt, CreatedBy desc))
 * </pre>
 */
public final class ExpressionParser {
    private static final Map<String, FunctionType> FUNCTION_NAMES = new HashMap<>();
    private static final Map<String, AggregationType> AGGREGATION_NAMES = new HashMap<>();
    private static final Map<String, BooleanFunctionType> BOOLEAN_FUNCTION_NAMES = new HashMap<>();

    private static final Set<FunctionType> STRING_FUNCTIONS = EnumSet.of(
            FunctionType.UPPER, FunctionType.LOWER, FunctionType.TRIM, FunctionType.SUBSTRING,
            FunctionType.REPLACE, FunctionType.CONCAT, FunctionType.SPLIT);

    private static final Set<FunctionType> DATE_FUNCTIONS = EnumSet.of(
            FunctionType.YEAR, FunctionType.MONTH, FunctionType.DAY, FunctionType.HOUR, FunctionType.MINUTE,
            FunctionType.SECOND, FunctionType.DAY_OF_WEEK, FunctionType.DAYS_AGO, FunctionType.FORMAT);

    private static final Set<FunctionType> MATH_FUNCTIONS = EnumSet.of(
            FunctionType.ROUND, FunctionType.FLOOR, FunctionType.CEIL, FunctionType.ABS, FunctionType.ADD,
            FunctionType.SUBTRACT, FunctionType.MULTIPLY, FunctionType.DIVIDE, FunctionType.MOD, FunctionType.POW);

    private static final Set<FunctionType> COLLECTION_FUNCTIONS = EnumSet.of(
            FunctionType.ORDER_BY, FunctionType.SKIP, FunctionType.TAKE, FunctionType.FIRST, FunctionType.LAST,
            FunctionType.DISTINCT);

    static {
        // Aggregate functions
        FUNCTION_NAMES.put("count", FunctionType.COUNT);
        FUNCTION_NAMES.put("sum", FunctionType.SUM);
        FUNCTION_NAMES.put("avg", FunctionType.AVG);
        FUNCTION_NAMES.put("min", FunctionType.MIN);
        FUNCTION_NAMES.put("max", FunctionType.MAX);
        // String functions
        FUNCTION_NAMES.put("upper", FunctionType.UPPER);
        FUNCTION_NAMES.put("lower", FunctionType.LOWER);
        FUNCTION_NAMES.put("trim", FunctionType.TRIM);
        FUNCTION_NAMES.put("substring", FunctionType.SUBSTRING);
        FUNCTION_NAMES.put("replace", FunctionType.REPLACE);
        FUNCTION_NAMES.put("concat", FunctionType.CONCAT);
        FUNCTION_NAMES.put("split", FunctionType.SPLIT);
        // Date/Time functions
        FUNCTION_NAMES.put("year", FunctionType.YEAR);
        FUNCTION_NAMES.put("month", FunctionType.MONTH);
        FUNCTION_NAMES.put("day", FunctionType.DAY);
        FUNCTION_NAMES.put("hour", FunctionType.HOUR);
        FUNCTION_NAMES.put("minute", FunctionType.MINUTE);
        FUNCTION_NAMES.put("second", FunctionType.SECOND);
        FUNCTION_NAMES.put("dayofweek", FunctionType.DAY_OF_WEEK);
        FUNCTION_NAMES.put("daysago", FunctionType.DAYS_AGO);
        FUNCTION_NAMES.put("format", FunctionType.FORMAT);
        // Math functions
        FUNCTION_NAMES.put("round", FunctionType.ROUND);
        FUNCTION_NAMES.put("floor", FunctionType.FLOOR);
        FUNCTION_NAMES.put("ceil", FunctionType.CEIL);
        FUNCTION_NAMES.put("abs", FunctionType.ABS);
        FUNCTION_NAMES.put("add", FunctionType.ADD);
        FUNCTION_NAMES.put("subtract", FunctionType.SUBTRACT);
        FUNCTION_NAMES.put("multiply", FunctionType.MULTIPLY);
        FUNCTION_NAMES.put("divide", FunctionType.DIVIDE);
        FUNCTION_NAMES.put("mod", FunctionType.MOD);
        FUNCTION_NAMES.put("pow", FunctionType.POW);
        // Collection functions
        // Names of the expression language: offset -> Skip, limit -> Take
        FUNCTION_NAMES.put("orderby", FunctionType.ORDER_BY);
        FUNCTION_NAMES.put("offset", FunctionType.SKIP);
        FUNCTION_NAMES.put("limit", FunctionType.TAKE);
        FUNCTION_NAMES.put("first", FunctionType.FIRST);
        FUNCTION_NAMES.put("last", FunctionType.LAST);
        FUNCTION_NAMES.put("distinct", FunctionType.DISTINCT);

        AGGREGATION_NAMES.put("count", AggregationType.COUNT);
        AGGREGATION_NAMES.put("sum", AggregationType.SUM);
        AGGREGATION_NAMES.put("avg", AggregationType.AVERAGE);
        AGGREGATION_NAMES.put("min", AggregationType.MIN);
        AGGREGATION_NAMES.put("max", AggregationType.MAX);

        BOOLEAN_FUNCTION_NAMES.put("any", BooleanFunctionType.ANY);
        BOOLEAN_FUNCTION_NAMES.put("all", BooleanFunctionType.ALL);
    }

    private final List<Token> tokens;
    private int position;

    public ExpressionParser(List<Token> tokens) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    /**
     * Parses an expression into its syntax tree.
     *
     * @throws ExpressionParseException when the expression is not valid
     * @throws io.github.quyvu01.jfxmap.expressions.tokens.ExpressionTokenizeException when it has a character
     *                                  that is not part of the language
     */
    public static ExpressionNode parse(String expression) {
        var tokens = new Tokenizer(expression).tokenize();
        var parser = new ExpressionParser(tokens);
        var result = parser.parseExpression();

        // The whole text must be part of the expression: a rest that is left would be silently ignored
        if (!parser.isAtEnd()) {
            var rest = parser.current();
            throw new ExpressionParseException("Unexpected '" + rest.value() + "' at position " + rest.position());
        }

        return result;
    }

    /**
     * Parses an expression, starting with the ternary (the lowest precedence).
     */
    public ExpressionNode parseExpression() {
        return parseTernaryExpression();
    }

    /**
     * Parses a ternary expression: Condition ? WhenTrue : WhenFalse (right-associative).
     * Example: Status = 'Active' ? 'Yes' : 'No'
     */
    private ExpressionNode parseTernaryExpression() {
        var expr = parseCoalesceExpression();

        // A ternary condition is: expr op value ? whenTrue : whenFalse
        if (isComparisonOperator()) {
            var op = parseComparisonOperator();
            var rightValue = parseValue();
            ConditionNode condition = new BinaryConditionNode(expr, op, rightValue);

            // More comparisons joined by && and ||: Name = 'John' && Score > 80 ? 'Hit' : 'Miss'
            condition = parseLogicalRest(condition);

            if (match(TokenType.QUESTION)) {
                var whenTrue = parseTernaryBranch();
                consume(TokenType.COLON, "Expected ':' in ternary expression");
                var whenFalse = parseExpression();
                return new TernaryNode(condition, whenTrue, whenFalse);
            }

            // Without a ternary, this is just a condition (unusual at the top level, but valid)
            return condition;
        }

        // An expression that is already a boolean (Orders:any) followed by '?'
        if (check(TokenType.QUESTION) && canBeCondition(expr)) {
            match(TokenType.QUESTION);
            var whenTrue = parseTernaryBranch();
            consume(TokenType.COLON, "Expected ':' in ternary expression");
            var whenFalse = parseExpression();
            return new TernaryNode(convertToCondition(expr), whenTrue, whenFalse);
        }

        return expr;
    }

    /**
     * Parses the whenTrue branch of a ternary: it stops at the ':' that separates the branches.
     */
    private ExpressionNode parseTernaryBranch() {
        return parseCoalesceBranchExpression();
    }

    private ExpressionNode parseCoalesceBranchExpression() {
        var left = parsePrimaryBranchExpression();

        if (match(TokenType.QUESTION_QUESTION)) {
            var right = parseCoalesceBranchExpression();
            return new CoalesceNode(left, right);
        }

        return left;
    }

    private ExpressionNode parsePrimaryBranchExpression() {
        if (check(TokenType.OPEN_BRACE))
            return parseRootProjection();

        if (isLiteral())
            return parseLiteralExpression();

        // A navigation whose ':' may be the separator of the ternary
        return parseNavigationBranchExpression();
    }

    private ExpressionNode parseNavigationBranchExpression() {
        var segments = new ArrayList<ExpressionNode>();
        segments.add(parseSegmentWithoutTrailingFunction());

        while (match(TokenType.DOT)) {
            if (check(TokenType.OPEN_BRACE)) {
                var source = segments.size() == 1 ? segments.get(0) : new NavigationNode(segments);
                return parseProjection(source);
            }

            segments.add(parseSegmentWithoutTrailingFunction());
        }

        return segments.size() == 1 ? segments.get(0) : new NavigationNode(segments);
    }

    /**
     * Parses a segment, reading a ':' as a function only when a known function name follows it; otherwise the ':'
     * is the separator of the ternary.
     */
    private ExpressionNode parseSegmentWithoutTrailingFunction() {
        var propertyName = consume(TokenType.IDENTIFIER, "Expected property name").value();
        var isNullSafe = match(TokenType.QUESTION);

        ExpressionNode result = new PropertyNode(propertyName, isNullSafe);

        if (check(TokenType.COLON)) {
            var savedPosition = position;
            advance(); // consume ':'

            if (check(TokenType.IDENTIFIER) && isKnownFunctionName(current().value().toLowerCase(Locale.ROOT))) {
                result = parseFunctionOrAggregation(result);
            } else {
                // Not a function: this ':' belongs to the ternary
                position = savedPosition;
            }
        }

        checkSegmentEnd();
        return result;
    }

    private static boolean isKnownFunctionName(String name) {
        return FUNCTION_NAMES.containsKey(name) || BOOLEAN_FUNCTION_NAMES.containsKey(name)
                || AGGREGATION_NAMES.containsKey(name) || name.equals("where");
    }

    private static boolean canBeCondition(ExpressionNode expr) {
        return expr instanceof BooleanFunctionNode || expr instanceof ConditionNode;
    }

    /**
     * Converts an expression to a condition: a condition as it is, a boolean function (Orders:any) as
     * {@code Orders:any = true}.
     */
    private static ConditionNode convertToCondition(ExpressionNode expr) {
        if (expr instanceof ConditionNode condition) return condition;

        if (expr instanceof BooleanFunctionNode boolFunc)
            return new BinaryConditionNode(boolFunc, ComparisonOperator.EQUAL, LiteralNode.bool(true));

        throw new ExpressionParseException(
                "Expected a condition expression for ternary operator. Got " + expr.getClass().getSimpleName());
    }

    /**
     * Parses a coalesce expression: A ?? B ?? C is A ?? (B ?? C).
     */
    private ExpressionNode parseCoalesceExpression() {
        var left = parsePrimaryExpression();

        if (match(TokenType.QUESTION_QUESTION)) {
            var right = parseCoalesceExpression();
            return new CoalesceNode(left, right);
        }

        return left;
    }

    /**
     * Parses a primary expression: RootProjection | Literal | NavigationExpr
     */
    private ExpressionNode parsePrimaryExpression() {
        if (check(TokenType.OPEN_BRACE))
            return parseRootProjection();

        // Literals (the right side of a ternary or a coalesce)
        if (isLiteral())
            return parseLiteralExpression();

        return parseNavigationExpression();
    }

    private boolean isLiteral() {
        return check(TokenType.STRING) || check(TokenType.NUMBER) || check(TokenType.BOOLEAN)
                || check(TokenType.NULL);
    }

    private LiteralNode parseLiteralExpression() {
        if (isLiteral()) return parseValue();
        throw new ExpressionParseException("Expected literal value at position " + current().position());
    }

    /**
     * Parses a navigation: Segment ('.' Segment)*, ending with an optional projection .{...}
     */
    private ExpressionNode parseNavigationExpression() {
        var segments = new ArrayList<ExpressionNode>();
        segments.add(parseSegment());

        while (match(TokenType.DOT)) {
            if (check(TokenType.OPEN_BRACE)) {
                var source = segments.size() == 1 ? segments.get(0) : new NavigationNode(segments);
                return parseProjection(source);
            }

            segments.add(parseSegment());
        }

        var result = segments.size() == 1 ? segments.get(0) : new NavigationNode(segments);

        if (check(TokenType.OPEN_BRACE))
            throw new ExpressionParseException(
                    "Projection requires '.' before '{' at position " + current().position());

        return result;
    }

    /**
     * Parses a segment: Property, Property?, Property:function...
     */
    private ExpressionNode parseSegment() {
        var propertyName = consume(TokenType.IDENTIFIER, "Expected property name").value();
        var isNullSafe = match(TokenType.QUESTION);

        ExpressionNode result = new PropertyNode(propertyName, isNullSafe);

        if (match(TokenType.COLON))
            result = parseFunctionOrAggregation(result);

        checkSegmentEnd();
        return result;
    }

    private void checkSegmentEnd() {
        // The filter Name(condition) was removed in 5.0: it is the function :where(condition)
        if (check(TokenType.OPEN_PAREN))
            throw filterRemoved();

        // The indexer syntax [skip take asc|desc Name] was removed in 5.0
        if (check(TokenType.OPEN_BRACKET))
            throw indexerRemoved();

        if (check(TokenType.IDENTIFIER))
            throw new ExpressionParseException(
                    "Property navigation requires '.' before identifier at position " + current().position());
    }

    /**
     * Parses a function (the ':' is consumed): :count, :sum(Total), :any, :any(Status = 'Done'), :where(...),
     * :upper, :substring(0, 3), :year, :format('yyyy-MM-dd'), :round(2), :add(Tax), :orderBy(Name desc),
     * :offset(2), :limit(10), :first, :last, :distinct(Name), :groupBy(Status), and the functions chained after it.
     */
    private ExpressionNode parseFunctionOrAggregation(ExpressionNode source) {
        var funcToken = consume(TokenType.IDENTIFIER, "Expected function name after ':'");
        var funcName = funcToken.value().toLowerCase(Locale.ROOT);

        // groupBy is not a FunctionType
        if (funcName.equals("groupby"))
            return parseGroupByFunction(source, funcToken);

        // :where(condition) filters a collection (a FilterNode, not a FunctionType)
        if (funcName.equals("where"))
            return parseWhereFunction(source, funcToken);

        var boolFuncType = BOOLEAN_FUNCTION_NAMES.get(funcName);
        if (boolFuncType != null) {
            ExpressionNode boolResult = parseBooleanFunction(source, boolFuncType);
            while (match(TokenType.COLON))
                boolResult = parseFunctionOrAggregation(boolResult);

            return boolResult;
        }

        var functionType = FUNCTION_NAMES.get(funcName);
        if (functionType == null)
            throw new ExpressionParseException(
                    "Unknown function '" + funcName + "' at position " + funcToken.position());

        ExpressionNode result;

        if (STRING_FUNCTIONS.contains(functionType)) {
            result = parseStringFunction(source, functionType, funcToken);
        } else if (DATE_FUNCTIONS.contains(functionType)) {
            result = parseDateFunction(source, functionType, funcToken);
        } else if (MATH_FUNCTIONS.contains(functionType)) {
            result = parseMathFunction(source, functionType, funcToken);
        } else if (COLLECTION_FUNCTIONS.contains(functionType)) {
            result = parseCollectionFunction(source, functionType);
        } else {
            String argument = null;

            // :sum(Total)
            if (match(TokenType.OPEN_PAREN)) {
                argument = consume(TokenType.IDENTIFIER, "Expected property name in function argument").value();
                consume(TokenType.CLOSE_PAREN, "Expected ')' after function argument");
            }

            // count without an argument is a FunctionNode (it is also the length of a string); the rest aggregate
            if (argument != null || functionType != FunctionType.COUNT)
                result = new AggregationNode(source, AGGREGATION_NAMES.get(funcName), argument);
            else
                result = new FunctionNode(source, functionType);
        }

        // Chained functions: :add(10):round(2), :trim:upper
        while (match(TokenType.COLON))
            result = parseFunctionOrAggregation(result);

        return result;
    }

    /**
     * Parses a date function: :year, :month, :day, :hour, :minute, :second, :dayOfWeek, :daysAgo take no
     * argument; :format(pattern) requires one.
     */
    private FunctionNode parseDateFunction(ExpressionNode source, FunctionType functionType, Token funcToken) {
        if (functionType != FunctionType.FORMAT)
            return new FunctionNode(source, functionType);

        if (!match(TokenType.OPEN_PAREN))
            throw new ExpressionParseException(
                    "Function 'format' requires a format pattern argument at position " + funcToken.position());

        var formatArg = parseFunctionArgument();
        consume(TokenType.CLOSE_PAREN, "Expected ')' after format argument");

        return new FunctionNode(source, functionType, List.of(formatArg));
    }

    /**
     * Parses a math function: :floor, :ceil, :abs take no argument; :round(decimals) takes an optional one;
     * :add, :subtract, :multiply, :divide, :mod, :pow require one (a number or a property reference).
     */
    private FunctionNode parseMathFunction(ExpressionNode source, FunctionType functionType, Token funcToken) {
        switch (functionType) {
            case FLOOR:
            case CEIL:
            case ABS:
                return new FunctionNode(source, functionType);
            case ROUND:
                if (match(TokenType.OPEN_PAREN)) {
                    var decimalsArg = parseFunctionArgument();
                    consume(TokenType.CLOSE_PAREN, "Expected ')' after round argument");
                    return new FunctionNode(source, functionType, List.of(decimalsArg));
                }

                // No argument: round to 0 decimal places
                return new FunctionNode(source, functionType);
            default:
                if (!match(TokenType.OPEN_PAREN))
                    throw new ExpressionParseException("Function '" + funcToken.value()
                            + "' requires an argument at position " + funcToken.position());

                var operandArg = parseFunctionArgument();
                consume(TokenType.CLOSE_PAREN, "Expected ')' after " + funcToken.value() + " argument");
                return new FunctionNode(source, functionType, List.of(operandArg));
        }
    }

    /**
     * Parses a collection function:
     * :orderBy(Key [asc|desc], ...) orders by the first key, then by the others;
     * :offset(n) skips the first n items; :limit(n) takes the first n items;
     * :first, :last take no argument;
     * :distinct(Property) selects the distinct values of a property.
     */
    private FunctionNode parseCollectionFunction(ExpressionNode source, FunctionType functionType) {
        var funcToken = previous();
        switch (functionType) {
            case FIRST:
            case LAST:
                return new FunctionNode(source, functionType);
            case ORDER_BY: {
                if (!match(TokenType.OPEN_PAREN))
                    throw new ExpressionParseException(
                            "Function 'orderBy' requires a property argument at position " + funcToken.position());

                // Each key is a property with an optional direction; the keys are separated by ','
                var keys = new ArrayList<ExpressionNode>();
                do {
                    var name = consume(TokenType.IDENTIFIER, "Expected property name in orderBy").value();
                    var direction = OrderDirection.ASC;
                    // 'asc' is the default, so it may be left out or written
                    if (match(TokenType.DESC)) direction = OrderDirection.DESC;
                    else match(TokenType.ASC);
                    keys.add(new SortKeyNode(name, direction));
                } while (match(TokenType.COMMA));

                consume(TokenType.CLOSE_PAREN, "Expected ')' after orderBy argument");
                return new FunctionNode(source, functionType, keys);
            }
            default: {
                // offset(n), limit(n): the number is validated when the expression is evaluated; distinct(Property)
                var name = functionType == FunctionType.SKIP ? "offset"
                        : functionType == FunctionType.TAKE ? "limit" : "distinct";
                var kind = functionType == FunctionType.DISTINCT ? "a property" : "a number";
                if (!match(TokenType.OPEN_PAREN))
                    throw new ExpressionParseException("Function '" + name + "' requires " + kind
                            + " argument at position " + funcToken.position());

                var argument = parseFunctionArgument();
                consume(TokenType.CLOSE_PAREN, "Expected ')' after " + name + " argument");

                return new FunctionNode(source, functionType, List.of(argument));
            }
        }
    }

    /**
     * Parses :groupBy(Property1, Property2, ...), and the projection or the functions that follow it:
     * Orders:groupBy(Status).{Status, :count as Count}
     */
    private ExpressionNode parseGroupByFunction(ExpressionNode source, Token funcToken) {
        if (!match(TokenType.OPEN_PAREN))
            throw new ExpressionParseException("Function 'groupBy' requires at least one property argument at position "
                    + funcToken.position());

        var keyProperties = new ArrayList<String>();
        keyProperties.add(consume(TokenType.IDENTIFIER, "Expected property name for groupBy key").value());

        while (match(TokenType.COMMA))
            keyProperties.add(consume(TokenType.IDENTIFIER, "Expected property name after ','").value());

        consume(TokenType.CLOSE_PAREN, "Expected ')' after groupBy arguments");

        var groupByNode = new GroupByNode(source, keyProperties);

        // A projection after groupBy: :groupBy(Status).{Status, :count}
        if (match(TokenType.DOT)) {
            if (check(TokenType.OPEN_BRACE))
                return parseGroupByProjection(groupByNode);

            throw new ExpressionParseException(
                    "Expected '{' for projection after groupBy at position " + current().position());
        }

        // A function after groupBy (without a projection)
        if (match(TokenType.COLON))
            return parseFunctionOrAggregation(groupByNode);

        return groupByNode;
    }

    /**
     * Parses the projection after groupBy: .{Status, :count as Count}. Key property names are the keys of the group,
     * and the functions with no source apply to the items of the group.
     */
    private ExpressionNode parseGroupByProjection(GroupByNode groupByNode) {
        consume(TokenType.OPEN_BRACE, "Expected '{' for projection");

        var properties = new ArrayList<ProjectionProperty>();
        do {
            properties.add(parseGroupByProjectionProperty());
        } while (match(TokenType.COMMA));

        consume(TokenType.CLOSE_BRACE, "Expected '}' after projection");

        return new ProjectionNode(groupByNode, properties);
    }

    /**
     * Parses a property of a projection after groupBy:
     * <ul>
     *   <li>a key property: Status, Year (with an optional alias)</li>
     *   <li>an aggregation of the group: :count, :sum(Total), :avg(Price)</li>
     *   <li>a computed expression: (:count &gt; 10 ? 'High' : 'Low') as Volume</li>
     *   <li>an inner projection of the items: {Id, Total} as Items</li>
     * </ul>
     */
    private ProjectionProperty parseGroupByProjectionProperty() {
        // (expression) as Alias
        if (match(TokenType.OPEN_PAREN)) {
            var expression = parseGroupByExpression();
            consume(TokenType.CLOSE_PAREN, "Expected ')' after computed expression");
            consume(TokenType.AS, "Computed expression requires 'as' alias");
            var alias = consume(TokenType.IDENTIFIER, "Expected alias name").value();
            return ProjectionProperty.fromExpression(expression, alias);
        }

        // {Id, Name} as Items: a projection of each item of the group
        if (check(TokenType.OPEN_BRACE)) {
            var innerProjection = parseProjection(new GroupElementsNode());
            consume(TokenType.AS, "Inner projection requires 'as' alias");
            var alias = consume(TokenType.IDENTIFIER, "Expected alias name").value();
            return ProjectionProperty.fromExpression(innerProjection, alias);
        }

        // :count, :sum(Total): an aggregation of the items of the group
        if (match(TokenType.COLON)) {
            var functionResult = parseFunctionOrAggregation(new GroupElementsNode());

            String alias = null;
            if (match(TokenType.AS))
                alias = consume(TokenType.IDENTIFIER, "Expected alias name").value();

            return ProjectionProperty.fromExpression(functionResult,
                    alias != null ? alias : defaultAliasForExpression(functionResult));
        }

        // A key property
        var name = consume(TokenType.IDENTIFIER, "Expected property name").value();

        String propertyAlias = null;
        if (match(TokenType.AS))
            propertyAlias = consume(TokenType.IDENTIFIER, "Expected alias name").value();

        return ProjectionProperty.fromPath(name, propertyAlias);
    }

    /**
     * Parses an expression of a projection after groupBy, where :function applies to the items of the group.
     */
    private ExpressionNode parseGroupByExpression() {
        var expr = parseGroupByCoalesceExpression();

        if (isComparisonOperator()) {
            var op = parseComparisonOperator();
            var rightValue = parseValue();
            var condition = new BinaryConditionNode(expr, op, rightValue);

            if (match(TokenType.QUESTION)) {
                var whenTrue = parseGroupByCoalesceExpression();
                consume(TokenType.COLON, "Expected ':' in ternary expression");
                var whenFalse = parseGroupByExpression();
                return new TernaryNode(condition, whenTrue, whenFalse);
            }

            return condition;
        }

        return expr;
    }

    private ExpressionNode parseGroupByCoalesceExpression() {
        var left = parseGroupByPrimaryExpression();

        while (match(TokenType.QUESTION_QUESTION)) {
            var right = parseGroupByPrimaryExpression();
            left = new CoalesceNode(left, right);
        }

        return left;
    }

    private ExpressionNode parseGroupByPrimaryExpression() {
        // :function applies to the items of the group
        if (match(TokenType.COLON))
            return parseFunctionOrAggregation(new GroupElementsNode());

        return parsePrimaryExpression();
    }

    /**
     * The output name of an aggregation of a group that has no alias.
     */
    private static String defaultAliasForExpression(ExpressionNode expression) {
        if (expression instanceof FunctionNode fn) return fn.functionName().pascalName();
        if (expression instanceof AggregationNode an) return an.aggregationType().pascalName();
        if (expression instanceof FilterNode) return "FilteredItems";
        return "Value";
    }

    /**
     * Parses a string function: :upper, :lower, :trim take no argument; :substring(start[, length]),
     * :replace(old, new), :concat(values...), :split(separator) require theirs.
     */
    private FunctionNode parseStringFunction(ExpressionNode source, FunctionType functionType, Token funcToken) {
        if (functionType == FunctionType.UPPER || functionType == FunctionType.LOWER
                || functionType == FunctionType.TRIM)
            return new FunctionNode(source, functionType);

        if (!match(TokenType.OPEN_PAREN))
            throw new ExpressionParseException("Function '" + funcToken.value() + "' requires arguments at position "
                    + funcToken.position());

        var arguments = new ArrayList<ExpressionNode>();
        arguments.add(parseFunctionArgument());

        while (match(TokenType.COMMA))
            arguments.add(parseFunctionArgument());

        consume(TokenType.CLOSE_PAREN, "Expected ')' after " + funcToken.value() + " arguments");

        validateStringFunctionArguments(functionType, arguments, funcToken);

        return new FunctionNode(source, functionType, arguments);
    }

    /**
     * Parses an argument of a function: a string, a number or a property reference.
     */
    private ExpressionNode parseFunctionArgument() {
        if (match(TokenType.STRING))
            return LiteralNode.string(previous().value());

        if (match(TokenType.NUMBER))
            return LiteralNode.number(parseNumber(previous()));

        if (check(TokenType.IDENTIFIER))
            return new PropertyNode(advance().value());

        throw new ExpressionParseException(
                "Expected argument (string, number, or property) at position " + current().position());
    }

    private static void validateStringFunctionArguments(FunctionType functionType, List<ExpressionNode> arguments,
                                                        Token funcToken) {
        var count = arguments.size();
        switch (functionType) {
            case SUBSTRING:
                if (count < 1 || count > 2)
                    throw new ExpressionParseException(
                            "substring requires 1 or 2 arguments at position " + funcToken.position());
                break;
            case REPLACE:
                if (count != 2)
                    throw new ExpressionParseException(
                            "replace requires exactly 2 arguments at position " + funcToken.position());
                break;
            case CONCAT:
                if (count < 1)
                    throw new ExpressionParseException(
                            "concat requires at least 1 argument at position " + funcToken.position());
                break;
            case SPLIT:
                if (count != 1)
                    throw new ExpressionParseException(
                            "split requires exactly 1 argument at position " + funcToken.position());
                break;
            default:
                break;
        }
    }

    /**
     * Parses the filter :where(condition), e.g. Orders:where(Status = 'Done' &amp;&amp; Total &gt; 100), and the
     * functions that follow it.
     */
    private ExpressionNode parseWhereFunction(ExpressionNode source, Token funcToken) {
        if (!match(TokenType.OPEN_PAREN))
            throw new ExpressionParseException(
                    "Function 'where' requires a condition at position " + funcToken.position());

        var condition = parseCondition();
        consume(TokenType.CLOSE_PAREN, "Expected ')' after where condition");

        ExpressionNode result = new FilterNode(source, condition);

        // Chained functions: :where(...):orderBy(...):first
        while (match(TokenType.COLON))
            result = parseFunctionOrAggregation(result);

        return result;
    }

    /**
     * The filter of the versions before 5.0, {@code Orders(Status = 'Done')}, was replaced by the function :where.
     */
    private ExpressionParseException filterRemoved() {
        return new ExpressionParseException("The filter '(...)' at position " + current().position()
                + " is no longer supported (removed in 5.0). "
                + "Use the function :where(condition): Orders(Status = 'Done') is Orders:where(Status = 'Done')");
    }

    /**
     * Parses :any, :any(condition), :all, :all(condition) (the name is consumed).
     */
    private BooleanFunctionNode parseBooleanFunction(ExpressionNode source, BooleanFunctionType functionType) {
        ConditionNode condition = null;

        if (match(TokenType.OPEN_PAREN)) {
            condition = parseCondition();
            consume(TokenType.CLOSE_PAREN, "Expected ')' after boolean function condition");
        }

        return new BooleanFunctionNode(source, functionType, condition);
    }

    private ConditionNode parseCondition() {
        return parseOrCondition();
    }

    /**
     * The comparisons that follow the first one of a condition outside of a filter: &amp;&amp; binds tighter than ||.
     * A comma does not join them here (it separates the items of a projection).
     */
    private ConditionNode parseLogicalRest(ConditionNode first) {
        var left = first;
        while (match(TokenType.AND))
            left = new LogicalConditionNode(left, LogicalOperator.AND, parseComparison());

        while (match(TokenType.OR)) {
            var right = parseComparison();
            while (match(TokenType.AND))
                right = new LogicalConditionNode(right, LogicalOperator.AND, parseComparison());
            left = new LogicalConditionNode(left, LogicalOperator.OR, right);
        }

        return left;
    }

    /**
     * Parses OR conditions: A || B || C
     */
    private ConditionNode parseOrCondition() {
        var left = parseAndCondition();

        while (match(TokenType.OR))
            left = new LogicalConditionNode(left, LogicalOperator.OR, parseAndCondition());

        return left;
    }

    /**
     * Parses AND conditions: A &amp;&amp; B &amp;&amp; C (a comma is AND too)
     */
    private ConditionNode parseAndCondition() {
        var left = parseComparison();

        while (match(TokenType.AND) || match(TokenType.COMMA))
            left = new LogicalConditionNode(left, LogicalOperator.AND, parseComparison());

        return left;
    }

    /**
     * Parses a comparison: Name = 'value', Count &gt; 3, Name:count &gt;= 5
     */
    private ConditionNode parseComparison() {
        var left = parseFieldPath();
        var op = parseComparisonOperator();
        var right = parseValue();

        return new BinaryConditionNode(left, op, right);
    }

    /**
     * Parses a field path: Name, Name:count, User.Name
     */
    private ExpressionNode parseFieldPath() {
        var segments = new ArrayList<ExpressionNode>();

        do {
            var name = consume(TokenType.IDENTIFIER, "Expected property name").value();
            ExpressionNode node = new PropertyNode(name);

            if (match(TokenType.COLON)) {
                var funcToken = consume(TokenType.IDENTIFIER, "Expected function name after ':'");
                var funcType = FUNCTION_NAMES.get(funcToken.value().toLowerCase(Locale.ROOT));
                if (funcType == null)
                    throw new ExpressionParseException(
                            "Unknown function '" + funcToken.value() + "' at position " + funcToken.position());

                node = new FunctionNode(node, funcType);
            }

            segments.add(node);
        } while (match(TokenType.DOT) && check(TokenType.IDENTIFIER) && !isComparisonOperator());

        return segments.size() == 1 ? segments.get(0) : new NavigationNode(segments);
    }

    private ComparisonOperator parseComparisonOperator() {
        if (match(TokenType.EQUAL)) return ComparisonOperator.EQUAL;
        if (match(TokenType.NOT_EQUAL)) return ComparisonOperator.NOT_EQUAL;
        if (match(TokenType.GREATER_THAN)) return ComparisonOperator.GREATER_THAN;
        if (match(TokenType.LESS_THAN)) return ComparisonOperator.LESS_THAN;
        if (match(TokenType.GREATER_THAN_OR_EQUAL)) return ComparisonOperator.GREATER_THAN_OR_EQUAL;
        if (match(TokenType.LESS_THAN_OR_EQUAL)) return ComparisonOperator.LESS_THAN_OR_EQUAL;
        if (match(TokenType.CONTAINS)) return ComparisonOperator.CONTAINS;
        if (match(TokenType.STARTS_WITH)) return ComparisonOperator.STARTS_WITH;
        if (match(TokenType.ENDS_WITH)) return ComparisonOperator.ENDS_WITH;

        throw new ExpressionParseException("Expected comparison operator at position " + current().position());
    }

    /**
     * Parses a literal value.
     */
    private LiteralNode parseValue() {
        if (match(TokenType.STRING))
            return LiteralNode.string(previous().value());

        if (match(TokenType.NUMBER))
            return LiteralNode.number(parseNumber(previous()));

        if (match(TokenType.BOOLEAN))
            return LiteralNode.bool(Boolean.parseBoolean(previous().value()));

        if (match(TokenType.NULL))
            return LiteralNode.nullValue();

        throw new ExpressionParseException("Expected value at position " + current().position());
    }

    private static BigDecimal parseNumber(Token token) {
        try {
            return new BigDecimal(token.value());
        } catch (NumberFormatException e) {
            throw new ExpressionParseException(
                    "Invalid number '" + token.value() + "' at position " + token.position());
        }
    }

    /**
     * The indexer of the versions before 5.0 (skip, take, asc|desc and a property between brackets) was replaced by
     * the functions.
     */
    private ExpressionParseException indexerRemoved() {
        return new ExpressionParseException("The indexer '[...]' at position " + current().position()
                + " is no longer supported (removed in 5.0). Use the functions: "
                + "'[asc Date]' is ':orderBy(Date)', '[0 asc Date]' is ':orderBy(Date):first', "
                + "'[-1 asc Date]' is ':orderBy(Date):last', '[5 10 desc Date]' is ':orderBy(Date desc):offset(5):limit(10)'");
    }

    /**
     * Parses a projection of a source: .{Id, Name, Description} (the '.' is consumed).
     */
    private ProjectionNode parseProjection(ExpressionNode source) {
        consume(TokenType.OPEN_BRACE, "Expected '{' for projection");

        var properties = new ArrayList<ProjectionProperty>();
        properties.add(parseProjectionProperty());

        while (match(TokenType.COMMA))
            properties.add(parseProjectionProperty());

        consume(TokenType.CLOSE_BRACE, "Expected '}' after projection");

        return new ProjectionNode(source, properties);
    }

    /**
     * Parses a projection of the root object: {Id, Name, Country.Name as CountryName}
     */
    private RootProjectionNode parseRootProjection() {
        consume(TokenType.OPEN_BRACE, "Expected '{' for root projection");

        var properties = new ArrayList<ProjectionProperty>();
        properties.add(parseProjectionProperty());

        while (match(TokenType.COMMA))
            properties.add(parseProjectionProperty());

        consume(TokenType.CLOSE_BRACE, "Expected '}' after projection");

        return new RootProjectionNode(properties);
    }

    /**
     * Parses a property of a projection:
     * <ul>
     *   <li>a property or a path: Name, Country.Name</li>
     *   <li>functions (no parentheses needed): Name:upper, Name:substring(0, 3), Name:trim:upper</li>
     *   <li>an alias: Country.Name as CountryName, Name:upper as UpperName</li>
     *   <li>a computed expression (parentheses and an alias required): (Nickname ?? Name) as DisplayName</li>
     * </ul>
     */
    private ProjectionProperty parseProjectionProperty() {
        if (check(TokenType.OPEN_PAREN))
            return parseComputedProjectionProperty();

        var pathParts = new ArrayList<String>();
        pathParts.add(consume(TokenType.IDENTIFIER, "Expected property name in projection").value());

        while (match(TokenType.DOT)) {
            // Stop at a nested projection
            if (check(TokenType.OPEN_BRACE)) break;
            pathParts.add(consume(TokenType.IDENTIFIER, "Expected property name after '.'").value());
        }

        var path = String.join(".", pathParts);

        if (check(TokenType.COLON))
            return parseInlineFunctionProperty(pathParts);

        String alias = null;
        if (match(TokenType.AS))
            alias = consume(TokenType.IDENTIFIER, "Expected alias name after 'as'").value();

        return new ProjectionProperty(path, alias);
    }

    /**
     * Parses a property with functions: Name:upper, Name:substring(0, 3) as Short, Name:trim:upper
     */
    private ProjectionProperty parseInlineFunctionProperty(List<String> pathParts) {
        ExpressionNode source;
        if (pathParts.size() == 1) {
            source = new PropertyNode(pathParts.get(0));
        } else {
            var segments = new ArrayList<ExpressionNode>();
            for (var part : pathParts) segments.add(new PropertyNode(part));
            source = new NavigationNode(segments);
        }

        while (match(TokenType.COLON))
            source = parseFunctionOrAggregation(source);

        String alias = null;
        if (match(TokenType.AS))
            alias = consume(TokenType.IDENTIFIER, "Expected alias name after 'as'").value();

        // Without an alias, the output key is the property name: {Name:upper} is "Name"
        if (alias == null)
            alias = pathParts.get(pathParts.size() - 1);

        return ProjectionProperty.fromExpression(source, alias);
    }

    /**
     * Parses a computed property: (expression) as Alias. The alias is required.
     */
    private ProjectionProperty parseComputedProjectionProperty() {
        consume(TokenType.OPEN_PAREN, "Expected '(' for computed expression");

        var expression = parseExpression();

        consume(TokenType.CLOSE_PAREN, "Expected ')' after computed expression");

        consume(TokenType.AS, "Expected 'as' keyword after computed expression - alias is required");
        var alias = consume(TokenType.IDENTIFIER, "Expected alias name after 'as'").value();

        return ProjectionProperty.fromExpression(expression, alias);
    }

    // Helpers

    private boolean isComparisonOperator() {
        switch (current().type()) {
            case EQUAL:
            case NOT_EQUAL:
            case GREATER_THAN:
            case LESS_THAN:
            case GREATER_THAN_OR_EQUAL:
            case LESS_THAN_OR_EQUAL:
            case CONTAINS:
            case STARTS_WITH:
            case ENDS_WITH:
                return true;
            default:
                return false;
        }
    }

    private Token current() {
        return position < tokens.size() ? tokens.get(position) : tokens.get(tokens.size() - 1);
    }

    private Token previous() {
        return tokens.get(position - 1);
    }

    private boolean isAtEnd() {
        return current().type() == TokenType.END_OF_EXPRESSION;
    }

    private boolean check(TokenType type) {
        return !isAtEnd() && current().type() == type;
    }

    private boolean match(TokenType type) {
        if (!check(type)) return false;
        position++;
        return true;
    }

    private Token advance() {
        if (!isAtEnd()) position++;
        return previous();
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) return advance();
        throw new ExpressionParseException(
                message + ". Got '" + current().type() + "' at position " + current().position());
    }
}

package io.github.quyvu01.jfxmap.expressions.nodes;

/**
 * A property of a projection: a path, an alias, or a computed expression.
 *
 * <ul>
 *   <li>{@code Name}: path "Name", no alias, the output key is "Name"</li>
 *   <li>{@code Country.Name}: path "Country.Name", no alias, the output key is "Name"</li>
 *   <li>{@code Country.Name as CountryName}: path "Country.Name", alias "CountryName"</li>
 *   <li>{@code (Nickname ?? Name) as DisplayName}: a {@link CoalesceNode} expression, alias "DisplayName"</li>
 * </ul>
 *
 * @param path       the property path ("Name" or "Country.Name"), null for a computed expression
 * @param alias      the output name; required for a computed expression, optional for a path
 * @param expression the computed expression, null for a path
 */
public record ProjectionProperty(String path, String alias, ExpressionNode expression) {
    public ProjectionProperty(String path, String alias) {
        this(path, alias, null);
    }

    /**
     * The output key: the alias if there is one, otherwise the last segment of the path.
     */
    public String outputKey() {
        if (alias != null) return alias;
        if (path != null) {
            var segments = pathSegments();
            return segments[segments.length - 1];
        }
        throw new IllegalStateException("Computed expression requires an alias");
    }

    /**
     * The segments of the path; empty for a computed expression.
     */
    public String[] pathSegments() {
        return path == null ? new String[0] : path.split("\\.");
    }

    /**
     * Whether the path navigates (contains '.').
     */
    public boolean hasNavigation() {
        return path != null && path.contains(".");
    }

    /**
     * Whether this is a computed expression.
     */
    public boolean isComputed() {
        return expression != null;
    }

    public static ProjectionProperty fromPath(String path, String alias) {
        return new ProjectionProperty(path, alias, null);
    }

    public static ProjectionProperty fromExpression(ExpressionNode expression, String alias) {
        return new ProjectionProperty(null, alias, expression);
    }
}

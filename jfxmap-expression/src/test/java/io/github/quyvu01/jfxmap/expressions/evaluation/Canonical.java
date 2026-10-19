package io.github.quyvu01.jfxmap.expressions.evaluation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A JSON text of a value, to compare a result with the expected JSON of FxMap's tests: numbers are compared by value
 * (100 and 100.0 are the same), the components of a record are written with the first letter in upper case, as the
 * properties of the C# models.
 */
final class Canonical {
    private Canonical() {
    }

    static String of(Object value) {
        var sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    static String ofJson(String json) {
        return of(new Parser(json).value());
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        } else if (value instanceof Number n) {
            var d = n instanceof BigDecimal b ? b : new BigDecimal(n.toString());
            sb.append(d.compareTo(BigDecimal.ZERO) == 0 ? "0" : d.stripTrailingZeros().toPlainString());
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            var first = true;
            for (var entry : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                write(sb, entry.getKey().toString());
                sb.append(':');
                write(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> items) {
            sb.append('[');
            var first = true;
            for (var item : items) {
                if (!first) sb.append(',');
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Record record) {
            var map = new LinkedHashMap<String, Object>();
            for (var component : record.getClass().getRecordComponents()) {
                try {
                    var accessor = component.getAccessor();
                    accessor.trySetAccessible();
                    var name = component.getName();
                    map.put(name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1), accessor.invoke(record));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            write(sb, map);
        } else {
            write(sb, value.toString());
        }
    }

    // A small JSON reader for the expected values
    private static final class Parser {
        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        Object value() {
            skipSpaces();
            var c = text.charAt(position);
            if (c == '{') {
                position++;
                var map = new LinkedHashMap<String, Object>();
                skipSpaces();
                if (text.charAt(position) == '}') {
                    position++;
                    return map;
                }
                do {
                    skipSpaces();
                    var key = (String) value();
                    skipSpaces();
                    expect(':');
                    map.put(key, value());
                    skipSpaces();
                } while (text.charAt(position++) == ',');
                return map;
            }
            if (c == '[') {
                position++;
                var list = new ArrayList<>();
                skipSpaces();
                if (text.charAt(position) == ']') {
                    position++;
                    return list;
                }
                do {
                    list.add(value());
                    skipSpaces();
                } while (text.charAt(position++) == ',');
                return list;
            }
            if (c == '"') {
                var sb = new StringBuilder();
                position++;
                while (text.charAt(position) != '"') {
                    if (text.charAt(position) == '\\') position++;
                    sb.append(text.charAt(position++));
                }
                position++;
                return sb.toString();
            }
            if (text.startsWith("true", position)) {
                position += 4;
                return true;
            }
            if (text.startsWith("false", position)) {
                position += 5;
                return false;
            }
            if (text.startsWith("null", position)) {
                position += 4;
                return null;
            }
            var start = position;
            while (position < text.length() && "-+.eE0123456789".indexOf(text.charAt(position)) >= 0) position++;
            return new BigDecimal(text.substring(start, position));
        }

        private void expect(char c) {
            if (text.charAt(position++) != c) throw new IllegalArgumentException("Expected " + c + " at " + position);
        }

        private void skipSpaces() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
        }
    }

    static List<Object> list(Object value) {
        @SuppressWarnings("unchecked")
        var list = (List<Object>) value;
        return list;
    }
}

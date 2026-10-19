package io.github.quyvu01.jfxmap.expressions.evaluation;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The patterns of {@code :format(...)}. They are the .NET custom date and time patterns of FxMap ('yyyy-MM-dd',
 * 'dd/MM/yyyy HH:mm:ss'), translated to {@link DateTimeFormatter} patterns: most letters are the same; the .NET
 * day names ddd/dddd, fractions f/F, AM/PM tt and offsets zzz/K differ, and the letters .NET does not know are
 * literal text.
 */
final class DateFormats {
    private static final Map<String, DateTimeFormatter> CACHE = new ConcurrentHashMap<>();

    private DateFormats() {
    }

    static DateTimeFormatter of(String pattern) {
        return CACHE.computeIfAbsent(pattern,
                p -> DateTimeFormatter.ofPattern(translate(p), Locale.ROOT));
    }

    static String translate(String pattern) {
        var out = new StringBuilder();
        var literal = new StringBuilder();
        var i = 0;
        while (i < pattern.length()) {
            var c = pattern.charAt(i);

            // Quoted text and escaped characters are literal
            if (c == '\'' || c == '"') {
                var end = pattern.indexOf(c, i + 1);
                if (end < 0) end = pattern.length();
                literal.append(pattern, i + 1, end);
                i = end + 1;
                continue;
            }
            if (c == '\\' && i + 1 < pattern.length()) {
                literal.append(pattern.charAt(i + 1));
                i += 2;
                continue;
            }

            var run = 1;
            while (i + run < pattern.length() && pattern.charAt(i + run) == c) run++;

            var translated = translateRun(c, run);
            if (translated == null) {
                literal.append(String.valueOf(c).repeat(run));
            } else {
                flushLiteral(out, literal);
                out.append(translated);
            }
            i += run;
        }
        flushLiteral(out, literal);
        return out.toString();
    }

    private static String translateRun(char c, int run) {
        switch (c) {
            case 'y':
                return run == 2 ? "yy" : run == 1 ? "y" : "y".repeat(Math.max(run, 4));
            case 'M':
            case 'H':
            case 'h':
            case 'm':
            case 's':
                return String.valueOf(c).repeat(Math.min(run, c == 'M' ? 4 : 2));
            case 'd':
                if (run <= 2) return "d".repeat(run);
                return run == 3 ? "EEE" : "EEEE";
            case 'f':
            case 'F':
                return "S".repeat(Math.min(run, 9));
            case 't':
                return "a";
            case 'z':
                return run >= 3 ? "xxx" : "x";
            case 'K':
                return "XXX";
            default:
                return null;
        }
    }

    private static void flushLiteral(StringBuilder out, StringBuilder literal) {
        if (literal.length() == 0) return;
        var text = literal.toString();
        // Java pattern letters are quoted; the rest (separators, spaces) is written as it is
        var plain = text.chars().noneMatch(Character::isLetter) && text.indexOf('\'') < 0
                && text.indexOf('[') < 0 && text.indexOf(']') < 0 && text.indexOf('#') < 0
                && text.indexOf('{') < 0 && text.indexOf('}') < 0;
        if (plain) out.append(text);
        else out.append('\'').append(text.replace("'", "''")).append('\'');
        literal.setLength(0);
    }
}

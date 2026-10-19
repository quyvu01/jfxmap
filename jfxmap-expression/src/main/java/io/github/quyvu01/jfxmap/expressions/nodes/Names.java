package io.github.quyvu01.jfxmap.expressions.nodes;

import java.util.Locale;

final class Names {
    private Names() {
    }

    /**
     * The name of a constant as FxMap writes it: ORDER_BY is OrderBy, DAY_OF_WEEK is DayOfWeek.
     */
    static String pascal(Enum<?> constant) {
        var sb = new StringBuilder();
        for (var part : constant.name().split("_")) {
            if (part.isEmpty()) continue;
            sb.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}

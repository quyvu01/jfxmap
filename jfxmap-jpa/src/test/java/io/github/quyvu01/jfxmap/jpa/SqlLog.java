package io.github.quyvu01.jfxmap.jpa;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Records the SQL statements Hibernate runs, so that the tests can tell what the database computed. */
public final class SqlLog implements StatementInspector {
    private static final List<String> STATEMENTS = Collections.synchronizedList(new ArrayList<>());

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;
    }

    static void clear() {
        STATEMENTS.clear();
    }

    static List<String> statements() {
        synchronized (STATEMENTS) {
            return List.copyOf(STATEMENTS);
        }
    }
}

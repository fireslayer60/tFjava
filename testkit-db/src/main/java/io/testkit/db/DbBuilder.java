package io.testkit.db;

import io.testkit.core.TestKitContext;
import io.testkit.core.TestKitException;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fluent DSL for asserting database state.
 *
 * <pre>{@code
 * .db(d -> d
 *     .dataSource(ds)
 *     .query("SELECT * FROM users WHERE email = ?", "test@example.com")
 *     .assertRowCount(1)
 *     .assertColumn("active", true)
 *     .assertColumnNotNull("created_at"))
 * }</pre>
 */
public final class DbBuilder {

    private DataSource dataSource;
    private final List<DbOperation> operations = new ArrayList<>();

    public DbBuilder dataSource(DataSource ds) {
        this.dataSource = ds;
        return this;
    }

    // ── Query + assertions ────────────────────────────────────────────────────

    public QueryBuilder query(String sql, Object... params) {
        return new QueryBuilder(sql, params);
    }

    public DbBuilder assertExists(String table, String where, Object... params) {
        operations.add(ctx -> {
            if (!client().exists(table, where, params)) {
                throw new TestKitException("Expected row in '%s' WHERE %s — not found".formatted(table, where));
            }
        });
        return this;
    }

    public DbBuilder assertNotExists(String table, String where, Object... params) {
        operations.add(ctx -> {
            if (client().exists(table, where, params)) {
                throw new TestKitException("Expected no row in '%s' WHERE %s — found one".formatted(table, where));
            }
        });
        return this;
    }

    public DbBuilder assertCount(String table, long expected) {
        return assertCount(table, null, expected);
    }

    public DbBuilder assertCount(String table, String where, long expected, Object... params) {
        operations.add(ctx -> {
            long actual = client().count(table, where, params);
            if (actual != expected) {
                throw new TestKitException(
                        "Row count mismatch in '%s': expected %d, got %d".formatted(table, expected, actual));
            }
        });
        return this;
    }

    /** Execute raw SQL without assertions (e.g. cleanup). */
    public DbBuilder execute(String sql, Object... params) {
        operations.add(ctx -> client().execute(sql, params));
        return this;
    }

    /** Extract a single scalar value into context. */
    public DbBuilder extract(String contextKey, String sql, Object... params) {
        operations.add(ctx -> {
            RowSet rs = client().query(sql, params);
            Object val = rs.first().values().iterator().next();
            ctx.put(contextKey, val);
        });
        return this;
    }

    // ── Internal execution ────────────────────────────────────────────────────

    void executeDb(TestKitContext ctx) {
        for (DbOperation op : operations) op.execute(ctx);
    }

    private DbClient client() {
        if (dataSource == null) throw new TestKitException("DbBuilder requires a DataSource — call .dataSource(ds)");
        return new DbClient(dataSource);
    }

    @FunctionalInterface
    private interface DbOperation {
        void execute(TestKitContext ctx);
    }

    // ── Inner query builder ───────────────────────────────────────────────────

    public final class QueryBuilder {
        private final String sql;
        private final Object[] params;
        private final List<Consumer<RowSet>> assertions = new ArrayList<>();

        QueryBuilder(String sql, Object[] params) {
            this.sql    = sql;
            this.params = params;
        }

        public QueryBuilder assertRowCount(int expected) {
            assertions.add(rs -> rs.assertRowCount(expected));
            return this;
        }

        public QueryBuilder assertNotEmpty() {
            assertions.add(RowSet::assertNotEmpty);
            return this;
        }

        public QueryBuilder assertEmpty() {
            assertions.add(RowSet::assertEmpty);
            return this;
        }

        public QueryBuilder assertColumn(String col, Object expected) {
            assertions.add(rs -> rs.assertColumn(col, expected));
            return this;
        }

        public QueryBuilder assertColumnNull(String col) {
            assertions.add(rs -> rs.assertColumnNull(col));
            return this;
        }

        public QueryBuilder assertColumnNotNull(String col) {
            assertions.add(rs -> rs.assertColumnNotNull(col));
            return this;
        }

        /** Finish this query block and return to the outer DbBuilder. */
        public DbBuilder end() {
            operations.add(ctx -> {
                RowSet rs = client().query(sql, params);
                assertions.forEach(a -> a.accept(rs));
            });
            return DbBuilder.this;
        }
    }
}

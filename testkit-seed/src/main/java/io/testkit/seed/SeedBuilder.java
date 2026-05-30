package io.testkit.seed;

import io.testkit.core.TestKitContext;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Fluent DSL for declaring seed operations.
 *
 * <pre>{@code
 * .seed(s -> s
 *     .dataSource(ds)
 *     .table("users").fromFactory(new UserFactory()).count(5)
 *     .table("roles").fromFixture("fixtures/roles.json")
 *     .sql("INSERT INTO settings VALUES (?, ?)", "theme", "dark"))
 * }</pre>
 */
public final class SeedBuilder {

    private DataSource dataSource;
    private final List<SeedOperation> operations = new ArrayList<>();

    // ── DataSource ────────────────────────────────────────────────────────────

    public SeedBuilder dataSource(DataSource ds) {
        this.dataSource = ds;
        return this;
    }

    // ── Factory-based seeding ─────────────────────────────────────────────────

    public FactoryStep table(String tableName) {
        return new FactoryStep(tableName);
    }

    // ── Raw SQL ───────────────────────────────────────────────────────────────

    public SeedBuilder sql(String sql, Object... params) {
        operations.add(ctx -> {
            JdbcSeeder seeder = seeder();
            seeder.execute(sql, params);
        });
        return this;
    }

    // ── Truncate ──────────────────────────────────────────────────────────────

    public SeedBuilder truncate(String... tables) {
        operations.add(ctx -> {
            JdbcSeeder seeder = seeder();
            for (String table : tables) seeder.truncate(table);
        });
        return this;
    }

    // ── Build and execute ─────────────────────────────────────────────────────

    void executeSeed(TestKitContext ctx) {
        for (SeedOperation op : operations) op.execute(ctx);
    }

    private JdbcSeeder seeder() {
        if (dataSource == null) {
            throw new io.testkit.core.TestKitException(
                    "SeedBuilder requires a DataSource — call .dataSource(ds)");
        }
        return new JdbcSeeder(dataSource);
    }

    // ── Inner DSL classes ─────────────────────────────────────────────────────

    @FunctionalInterface
    private interface SeedOperation {
        void execute(TestKitContext ctx);
    }

    public final class FactoryStep {

        private final String tableName;
        private DataFactory<?> factory;
        private List<Map<String, Object>> rows;

        private FactoryStep(String tableName) {
            this.tableName = tableName;
        }

        public SeedBuilder fromFactory(DataFactory<?> f, int count) {
            this.factory = f;
            operations.add(ctx -> {
                JdbcSeeder seeder = seeder();
                List<Map<String, Object>> generated = f.buildMaps(count);
                List<Map<String, Object>> keys = seeder.insertAll(tableName, generated);
                ctx.put("seed." + tableName + ".keys", keys);
                ctx.put("seed." + tableName + ".rows", generated);
            });
            return SeedBuilder.this;
        }

        public SeedBuilder fromRows(List<Map<String, Object>> rows) {
            operations.add(ctx -> {
                JdbcSeeder seeder = seeder();
                seeder.insertAll(tableName, rows);
            });
            return SeedBuilder.this;
        }

        public SeedBuilder fromFixture(String classpathResource) {
            operations.add(ctx -> {
                JdbcSeeder seeder = seeder();
                List<Map<String, Object>> loaded = FixtureLoader.from(classpathResource);
                seeder.insertAll(tableName, loaded);
            });
            return SeedBuilder.this;
        }
    }
}

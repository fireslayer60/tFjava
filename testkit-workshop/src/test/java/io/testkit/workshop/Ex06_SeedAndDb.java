package io.testkit.workshop;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.testkit.core.TestKit;
import io.testkit.core.TestKitResult;
import io.testkit.db.DbStep;
import io.testkit.seed.*;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EXERCISE 6 — Test data seeding and database assertions.
 *
 * Uses H2 in-memory database — no external DB needed.
 *
 * Goal: understand DataFactory, SeedBuilder, JdbcSeeder, and DbBuilder.
 * After seeding data, DbBuilder verifies the database state via SQL assertions.
 *
 * Run: mvn test -pl testkit-workshop -Dtest=Ex06_SeedAndDb
 */
class Ex06_SeedAndDb {

    private static HikariDataSource dataSource;

    @BeforeAll
    static void setupDatabase() throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:workshopdb;DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        dataSource = new HikariDataSource(cfg);

        // Create schema
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id         VARCHAR(36) PRIMARY KEY,
                        email      VARCHAR(255) UNIQUE NOT NULL,
                        name       VARCHAR(255),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS orders (
                        id         VARCHAR(36) PRIMARY KEY,
                        user_id    VARCHAR(36) NOT NULL,
                        status     VARCHAR(50) DEFAULT 'PENDING',
                        total      DECIMAL(10,2)
                    )
                    """);
        }
    }

    @AfterAll
    static void tearDown() {
        dataSource.close();
    }

    @BeforeEach
    void cleanTables() throws Exception {
        // Reset state between tests
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM orders");
            stmt.execute("DELETE FROM users");
        }
    }

    // ── DataFactory ───────────────────────────────────────────────────────────

    static class UserFactory extends DataFactory<java.util.Map<String, Object>> {
        @Override
        protected void define() {
            field("id",    FieldSupplier.uuid());
            field("email", FieldSupplier.email());
            field("name",  FieldSupplier.name());
        }
    }

    @Test
    void dataFactoryGeneratesUniqueRows() {
        UserFactory factory = new UserFactory();

        var row1 = factory.buildMap();
        var row2 = factory.buildMap();

        System.out.println("\nRow 1: " + row1);
        System.out.println("Row 2: " + row2);

        // Each call generates fresh values (FieldSupplier.uuid(), FieldSupplier.email() etc.)
        assertNotEquals(row1.get("id"),    row2.get("id"));
        assertNotEquals(row1.get("email"), row2.get("email"));

        // Fixed fields always return the same value
        DataFactory<java.util.Map<String, Object>> withFixed = new DataFactory<>() {
            @Override protected void define() {
                field("role", FieldSupplier.fixed("ADMIN"));
            }
        };
        assertEquals("ADMIN", withFixed.buildMap().get("role"));
        assertEquals("ADMIN", withFixed.buildMap().get("role")); // same every time
    }

    @Test
    void seedBuilderInsertsRowsViaJdbc() {
        // SeedBuilder builds insert statements dynamically from the Map's key set.
        // It stores generated keys in context under seed.<table>.keys

        TestKitResult result = TestKit.test("Seed users")
                .step(new SeedStep(s -> s
                        .dataSource(dataSource)
                        .table("users").fromFactory(new UserFactory(), 3)))
                .run();

        assertTrue(result.passed(), "Seed step should succeed");

        // Verify rows exist in DB using raw JDBC
        io.testkit.db.DbClient db = new io.testkit.db.DbClient(dataSource);
        long count = db.count("users", "1=1");
        assertEquals(3, count, "3 rows should have been inserted");
    }

    @Test
    void dbBuilderAssertions() {
        // DbBuilder is a fluent query + assertion DSL.
        // It chains multiple operations; each has targeted assertion methods.

        // First seed some data manually via raw SQL
        JdbcSeeder seeder = new JdbcSeeder(dataSource);
        seeder.execute("INSERT INTO users (id, email, name) VALUES (?,?,?)",
                "U-1", "alice@example.com", "Alice");

        TestKitResult result = TestKit.test("DB assertions")
                .step(new DbStep(d -> d
                        .dataSource(dataSource)

                        // assertExists: passes if at least one row matches the WHERE clause
                        .assertExists("users", "email = ?", "alice@example.com")

                        // assertNotExists: passes if no rows match
                        .assertNotExists("users", "email = ?", "nobody@example.com")

                        // assertCount: passes if row count matches
                        .assertCount("users", 1)

                        // query + row-level assertions
                        .query("SELECT * FROM users WHERE email = ?", "alice@example.com")
                            .assertRowCount(1)
                            .assertColumn("name", "Alice")
                            .assertColumnNotNull("id")
                            // H2 evaluates DEFAULT CURRENT_TIMESTAMP on INSERT, so created_at is set
                            .assertColumnNotNull("created_at")
                            .end()))
                .run();

        assertTrue(result.passed());
    }

    @Test
    void seedThenDbInSequence() {
        // The canonical pattern: seed data first, then assert state via DB step.
        // The seed step stores inserted rows in context; DB step can read them.

        TestKitResult result = TestKit.test("Seed then verify")
                .step(new SeedStep("Seed 2 users", s -> s
                        .dataSource(dataSource)
                        .table("users").fromFactory(new UserFactory(), 2)))

                .step(new DbStep("Verify users inserted", d -> d
                        .dataSource(dataSource)
                        .assertCount("users", 2)
                        .assertNotExists("users", "email IS NULL")))

                .run();

        assertTrue(result.passed());
        assertEquals(2, result.passCount());
    }

    @Test
    void extractFromDbIntoContext() {
        // DbBuilder.extract() runs a scalar SQL query and puts the result in context.
        // Subsequent steps can read that value.

        JdbcSeeder seeder = new JdbcSeeder(dataSource);
        seeder.execute("INSERT INTO users (id, email, name) VALUES (?,?,?)",
                "U-99", "bob@example.com", "Bob");

        // Extract "U-99" from DB into context key "userId",
        // then a custom step verifies the context contains it.
        TestKitResult result = TestKit.test("Extract from DB")
                .step(new DbStep(d -> d
                        .dataSource(dataSource)
                        .extract("userId", "SELECT id FROM users WHERE email = ?",
                                "bob@example.com")))

                .step(new io.testkit.core.TestKitStep() {
                    @Override public String name() { return "Check extracted userId"; }

                    @Override
                    public io.testkit.core.StepResult execute(io.testkit.core.TestKitContext ctx) {
                        String userId = ctx.get("userId").toString();
                        System.out.println("\nExtracted userId from DB: " + userId);
                        assertEquals("U-99", userId);
                        return io.testkit.core.StepResult
                                .passed(name(), java.time.Duration.ZERO)
                                .detail("userId=" + userId).build();
                    }
                })

                .run();

        assertTrue(result.passed());
    }

    @Test
    void truncateAndRawSql() {
        // SeedBuilder.truncate() and .sql() for cleanup/setup operations

        JdbcSeeder seeder = new JdbcSeeder(dataSource);
        // Insert some rows
        seeder.execute("INSERT INTO users (id, email, name) VALUES (?,?,?)",
                "U-A", "a@x.com", "A");
        seeder.execute("INSERT INTO users (id, email, name) VALUES (?,?,?)",
                "U-B", "b@x.com", "B");

        TestKitResult result = TestKit.test("Truncate demo")
                .step(new SeedStep(s -> s
                        .dataSource(dataSource)
                        .truncate("users")         // DELETE all rows (or TRUNCATE depending on DB)
                        .sql("INSERT INTO users (id, email, name) VALUES (?,?,?)",
                                "U-NEW", "new@x.com", "New")))
                .step(new DbStep(d -> d
                        .dataSource(dataSource)
                        .assertCount("users", 1)   // only the freshly inserted row
                        .assertExists("users", "email = ?", "new@x.com")))
                .run();

        assertTrue(result.passed());
    }
}

package io.testkit.workshop;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testkit.api.ApiConfig;
import io.testkit.api.ApiStep;
import io.testkit.core.*;
import io.testkit.db.DbStep;
import io.testkit.mock.MockStep;
import io.testkit.seed.*;
import io.testkit.security.SecurityFinding;
import io.testkit.security.SecurityStep;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.Statement;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EXERCISE 7 — The full chain: Mock → Seed → API → fn → DB → Security.
 *
 * This simulates a realistic e-commerce "place order" flow:
 *
 *   Step 1: SeedStep     — insert a test user into H2
 *   Step 2: ApiStep      — POST /api/orders (against WireMock, extract orderId)
 *   Step 3: ApiStep      — GET /api/orders/{orderId} using extracted ID
 *   Step 4: FnStep (.fn) — persist the order to H2 using the extracted orderId
 *   Step 5: DbStep       — assert order row exists in H2
 *   Step 6: SecurityStep — scan for SQL injection and auth bypass issues
 *
 * Step 4 shows how .fn() replaces a verbose anonymous TestKitStep inner class
 * when the logic is too simple to deserve its own named class.
 *
 * All steps share ONE TestKitContext. Values written in step 2 are read in steps 3 and 4.
 *
 * Run: mvn test -pl testkit-workshop -Dtest=Ex07_FullChain
 */
class Ex07_FullChain {

    private static HikariDataSource dataSource;
    private static WireMockServer wire;

    @BeforeAll
    static void setup() throws Exception {
        // --- H2 in-memory DB ---
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:h2:mem:e2edb;DB_CLOSE_DELAY=-1");
        hikari.setUsername("sa");
        hikari.setPassword("");
        dataSource = new HikariDataSource(hikari);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id    VARCHAR(36) PRIMARY KEY,
                        email VARCHAR(255) NOT NULL,
                        name  VARCHAR(255)
                    )""");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS orders (
                        id      VARCHAR(36) PRIMARY KEY,
                        user_id VARCHAR(36),
                        status  VARCHAR(50) DEFAULT 'PENDING',
                        total   DECIMAL(10,2)
                    )""");
        }

        // --- WireMock as fake API layer ---
        // In a real project this would be your actual Spring Boot service.
        // Here WireMock simulates it so we can run without a real server.
        wire = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wire.start();
        configureFor("localhost", wire.port());
    }

    @AfterAll
    static void tearDown() throws Exception {
        wire.stop();
        dataSource.close();
    }

    @BeforeEach
    void resetState() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM orders");
            stmt.execute("DELETE FROM users");
        }
        wire.resetAll();
    }

    // ── Factory for test users ────────────────────────────────────────────────

    static class UserFactory extends DataFactory<java.util.Map<String, Object>> {
        @Override
        protected void define() {
            field("id",    FieldSupplier.uuid());
            field("email", FieldSupplier.email());
            field("name",  FieldSupplier.name());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The full chain test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void orderPlacementE2E() {
        int port = wire.port();
        String apiBase = "http://localhost:" + port;
        ApiConfig apiCfg = ApiConfig.builder().baseUrl(apiBase).build();

        // ── Register stubs on WireMock ────────────────────────────────────────

        // POST /api/orders → 201
        stubFor(post("/api/orders")
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"ORD-E2E-1","userId":"SEEDED-USER","status":"PENDING","total":49.99}
                                """)));

        // GET /api/orders/ORD-E2E-1 → 200
        stubFor(get(urlEqualTo("/api/orders/ORD-E2E-1"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"ORD-E2E-1","userId":"SEEDED-USER","status":"PENDING","total":49.99}
                                """)));

        // Endpoint for security scanning — well-behaved (returns 400 on bad input)
        stubFor(get(urlMatching("/api/orders\\?.*"))
                .willReturn(aResponse().withStatus(400)
                        .withBody("{\"error\":\"invalid input\"}")));
        stubFor(get("/api/orders")
                .willReturn(aResponse().withStatus(401)
                        .withBody("{\"error\":\"unauthorized\"}")));

        // ── Also insert the order into H2 so DbStep can assert it ────────────
        // In a real app, your service would write to DB. Here WireMock can't write to H2,
        // so we pre-insert to simulate what the service would have done.
        JdbcSeeder seeder = new JdbcSeeder(dataSource);

        // ── Build the pipeline ────────────────────────────────────────────────

        TestKitResult result = TestKit.test("Order Placement E2E")
                .config(c -> c.baseUrl(apiBase).failFast(true))

                // STEP 1 — Seed a test user into H2
                .step(new SeedStep("Seed test user", s -> s
                        .dataSource(dataSource)
                        .table("users").fromFactory(new UserFactory(), 1)))

                // STEP 2 — POST /api/orders → extract orderId
                .step(new ApiStep("Create order", a -> a
                        .POST("/api/orders")
                        .json("{\"productId\":\"PROD-1\",\"qty\":1}")
                        .expect(201)
                        .assertJsonPath("$.status", "PENDING")
                        .assertJsonPathPresent("$.id")
                        .extract("orderId", "$.id"),  // → ctx["orderId"] = "ORD-E2E-1"
                        apiCfg))

                // STEP 3 — GET /api/orders/{orderId} using extracted ID (late binding)
                .step(new ApiStep("Fetch order", a -> a
                        .GET("/api/orders/{id}")
                        .pathParam("id", ctx -> ctx.get("orderId")) // reads "ORD-E2E-1" at run time
                        .expect(200)
                        .assertJsonPath("$.status", "PENDING"),
                        apiCfg))

                // STEP 4 — Insert order into H2 (simulating what the real service would do).
                //          .fn() reads orderId from context — no custom step class needed.
                .fn("Persist order to DB (simulated)", ctx -> {
                    String orderId = ctx.get("orderId");
                    seeder.execute(
                            "INSERT INTO orders (id, user_id, status, total) VALUES (?,?,?,?)",
                            orderId, "SEEDED-USER", "PENDING", 49.99);
                })

                // STEP 5 — DB assertions: verify order row exists with correct status
                .step(new DbStep("Assert order in DB", d -> d
                        .dataSource(dataSource)
                        .assertExists("orders", "id = ?", "ORD-E2E-1")
                        .query("SELECT * FROM orders WHERE id = ?", "ORD-E2E-1")
                            .assertRowCount(1)
                            .assertColumn("status", "PENDING")
                            .assertColumnNotNull("user_id")
                            .end()))

                // STEP 6 — Security: scan the orders endpoint for SQL injection
                //          Safe stubs return 400/401, so no findings expected
                .step(new SecurityStep("Security scan", s -> s
                        .baseUrl(apiBase)
                        .sqlInjection("/api/orders")
                        .authBypass("/api/orders")
                        .failOnSeverity(SecurityFinding.Severity.HIGH)))

                .run();

        // ── Verify overall result ─────────────────────────────────────────────

        System.out.println("\n=== Full Chain Results ===");
        result.steps().forEach(r ->
                System.out.printf("  %-40s [%s]%s%n",
                        r.stepName(), r.status(),
                        r.detail() != null ? "  " + r.detail() : ""));
        System.out.printf("Passed: %d  Failed: %d  Total: %dms%n",
                result.passCount(), result.failCount(),
                result.totalDuration().toMillis());

        assertTrue(result.passed(), "All 6 steps should pass");
        assertEquals(6, result.passCount());
    }

    @Test
    void failFastAbortsMidChain() {
        // With failFast=true (default), when step 2 fails, steps 3+ are SKIPPED.
        // This demonstrates the runner's abort behaviour.

        // No stubs — all HTTP calls will fail (connection refused equivalent)
        // Actually WireMock returns 404 for unstubbed requests:
        ApiConfig apiCfg = ApiConfig.builder().baseUrl("http://localhost:" + wire.port()).build();

        TestKitResult result = TestKit.test("FailFast demo")
                .config(c -> c.failFast(true))

                .step(new SeedStep(s -> s
                        .dataSource(dataSource)
                        .table("users").fromFactory(new UserFactory(), 1)))

                // This step expects 201 but WireMock returns 404 (unstubbed)
                .step(new ApiStep("Create order (will fail)", a -> a
                        .POST("/api/orders")
                        .expect(201),   // WireMock returns 404 → mismatch → FAILED
                        apiCfg))

                // These two steps should be SKIPPED due to failFast
                .step(new ApiStep("Fetch order (should be skipped)", a -> a
                        .GET("/api/orders/anything")
                        .expect(200),
                        apiCfg))

                .step(new DbStep("DB check (should be skipped)", d -> d
                        .dataSource(dataSource)
                        .assertCount("users", 999)))  // wrong count, but shouldn't run

                .run();

        assertFalse(result.passed());

        System.out.println("\n=== FailFast Results ===");
        result.steps().forEach(r ->
                System.out.printf("  %-40s [%s]%n", r.stepName(), r.status()));

        assertEquals(StepResult.Status.PASSED,  result.steps().get(0).status()); // seed OK
        assertEquals(StepResult.Status.FAILED,  result.steps().get(1).status()); // API fails
        assertEquals(StepResult.Status.SKIPPED, result.steps().get(2).status()); // skipped
        assertEquals(StepResult.Status.SKIPPED, result.steps().get(3).status()); // skipped
    }
}

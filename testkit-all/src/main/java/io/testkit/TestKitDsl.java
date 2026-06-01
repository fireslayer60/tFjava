package io.testkit;

import io.testkit.core.TestKit;
import io.testkit.core.TestKitConfig;
import io.testkit.core.TestKitResult;
import io.testkit.core.TestKitStep;
import io.testkit.core.report.TestKitReporter;

import java.util.function.Consumer;

/**
 * <h2>TestKit — Unified Backend Testing DSL</h2>
 *
 * One-stop façade that exposes every testing layer through a single fluent builder.
 * Import this class and discover the full API via IDE auto-complete.
 *
 * <pre>{@code
 * import io.testkit.TestKitDsl;
 *
 * TestKitDsl.test("Order placement E2E")
 *     .config(c -> c.baseUrl("http://localhost:8080").failFast(true))
 *
 *     // 1. Seed test data
 *     .seed(s -> s
 *         .dataSource(dataSource)
 *         .table("users").fromFactory(new UserFactory(), 1)
 *         .table("products").fromFixture("fixtures/products.json"))
 *
 *     // 2. Start a mock dependency
 *     .mock(m -> m
 *         .port(9090)
 *         .stub(s -> s.GET("/payments/health").willReturn(200, "{\"status\":\"UP\"}"))
 *         .contextKey("mockBaseUrl"))
 *
 *     // 3. API functional tests
 *     .api(a -> a
 *         .POST("/api/orders")
 *         .json("""{"productId":"abc","qty":2}""")
 *         .expect(201)
 *         .extract("orderId", "$.id"))
 *
 *     .api("Fetch order", a -> a
 *         .GET("/api/orders/{id}")
 *         .pathParam("id", ctx -> ctx.get("orderId"))
 *         .expect(200)
 *         .assertJsonPath("$.status", "PENDING"))
 *
 *     // 4. DB state assertions
 *     .db(d -> d
 *         .dataSource(dataSource)
 *         .query("SELECT * FROM orders WHERE id = ?", ctx -> ctx.get("orderId"))
 *         .assertRowCount(1)
 *         .assertColumn("status", "PENDING").end())
 *
 *     // 5. Queue assertions
 *     .queue(q -> q
 *         .kafka("localhost:9092")
 *         .assertMessageOnTopic("order-events", "\"status\":\"PENDING\"", Duration.ofSeconds(5)))
 *
 *     // 6. Security probes
 *     .security(s -> s
 *         .sqlInjection("/api/orders")
 *         .rateLimit("/api/orders")
 *         .failOnSeverity(SecurityFinding.Severity.HIGH))
 *
 *     // 7. Contract verification
 *     .contract(c -> c
 *         .consumer("order-service").provider("payment-service")
 *         .describe("Create order requires payment API")
 *         .request(r -> r.GET("/payments/health"))
 *         .expectStatus(200))
 *
 *     // 8. Load test
 *     .load(l -> l
 *         .scenario("Order creation under load")
 *         .POST("/api/orders")
 *         .json("""{"productId":"abc","qty":1}""")
 *         .virtualUsers(50)
 *         .duration(30)
 *         .rps(200)
 *         .p99Under(500)
 *         .errorRateUnder(1.0))
 *
 *     .run()
 *     .assertPassed();
 * }</pre>
 */
public final class TestKitDsl {

    private final TestKit kit;

    private TestKitDsl(String suiteName) {
        this.kit = TestKit.test(suiteName);
    }

    public static TestKitDsl test(String suiteName) {
        return new TestKitDsl(suiteName);
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    public TestKitDsl config(Consumer<TestKitConfig.Builder> cfg) {
        kit.config(cfg);
        return this;
    }

    public TestKitDsl config(TestKitConfig config) {
        kit.config(config);
        return this;
    }

    public TestKitDsl reporter(TestKitReporter reporter) {
        kit.reporter(reporter);
        return this;
    }

    // ── Low-level step ────────────────────────────────────────────────────────

    public TestKitDsl step(TestKitStep step) {
        kit.step(step);
        return this;
    }

    // ── Inline function steps ─────────────────────────────────────────────────

    /** Run any context-aware function as a named step. */
    public TestKitDsl fn(String name, io.testkit.core.FnStep.Action action) {
        kit.fn(name, action);
        return this;
    }

    /** Run a plain {@link Runnable} as a named step (no context access needed). */
    public TestKitDsl fn(String name, Runnable runnable) {
        kit.fn(name, runnable);
        return this;
    }

    // ── Seed ─────────────────────────────────────────────────────────────────

    public TestKitDsl seed(Consumer<io.testkit.seed.SeedBuilder> config) {
        kit.step(new io.testkit.seed.SeedStep(config));
        return this;
    }

    public TestKitDsl seed(String stepName, Consumer<io.testkit.seed.SeedBuilder> config) {
        kit.step(new io.testkit.seed.SeedStep(stepName, config));
        return this;
    }

    // ── API ───────────────────────────────────────────────────────────────────

    public TestKitDsl api(Consumer<io.testkit.api.ApiBuilder> config) {
        io.testkit.api.TestKitApiExtensions.api(kit, config);
        return this;
    }

    public TestKitDsl api(String stepName, Consumer<io.testkit.api.ApiBuilder> config) {
        io.testkit.api.TestKitApiExtensions.api(kit, stepName, config);
        return this;
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    public TestKitDsl load(Consumer<io.testkit.load.LoadBuilder> config) {
        kit.step(new io.testkit.load.LoadStep(config));
        return this;
    }

    public TestKitDsl load(String stepName, Consumer<io.testkit.load.LoadBuilder> config) {
        kit.step(new io.testkit.load.LoadStep(stepName, config));
        return this;
    }

    // ── DB ────────────────────────────────────────────────────────────────────

    public TestKitDsl db(Consumer<io.testkit.db.DbBuilder> config) {
        kit.step(new io.testkit.db.DbStep(config));
        return this;
    }

    public TestKitDsl db(String stepName, Consumer<io.testkit.db.DbBuilder> config) {
        kit.step(new io.testkit.db.DbStep(stepName, config));
        return this;
    }

    // ── Contract ──────────────────────────────────────────────────────────────

    public TestKitDsl contract(Consumer<io.testkit.contract.ContractBuilder> config) {
        kit.step(new io.testkit.contract.ContractStep(config));
        return this;
    }

    public TestKitDsl contract(String stepName, Consumer<io.testkit.contract.ContractBuilder> config) {
        kit.step(new io.testkit.contract.ContractStep(stepName, config));
        return this;
    }

    // ── Security ──────────────────────────────────────────────────────────────

    public TestKitDsl security(Consumer<io.testkit.security.SecurityBuilder> config) {
        kit.step(new io.testkit.security.SecurityStep(config));
        return this;
    }

    public TestKitDsl security(String stepName, Consumer<io.testkit.security.SecurityBuilder> config) {
        kit.step(new io.testkit.security.SecurityStep(stepName, config));
        return this;
    }

    // ── Mock ──────────────────────────────────────────────────────────────────

    public TestKitDsl mock(Consumer<io.testkit.mock.MockBuilder> config) {
        kit.step(new io.testkit.mock.MockStep(config));
        return this;
    }

    public TestKitDsl mock(String stepName, Consumer<io.testkit.mock.MockBuilder> config) {
        kit.step(new io.testkit.mock.MockStep(stepName, config));
        return this;
    }

    // ── Queue ─────────────────────────────────────────────────────────────────

    public TestKitDsl queue(Consumer<io.testkit.queue.QueueBuilder> config) {
        kit.step(new io.testkit.queue.QueueStep(config));
        return this;
    }

    public TestKitDsl queue(String stepName, Consumer<io.testkit.queue.QueueBuilder> config) {
        kit.step(new io.testkit.queue.QueueStep(stepName, config));
        return this;
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    public TestKitResult run() {
        return kit.run();
    }

    public void runAndAssert() {
        kit.runAndAssert();
    }
}

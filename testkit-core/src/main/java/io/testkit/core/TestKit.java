package io.testkit.core;

import io.testkit.core.report.TestKitReporter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Entry point for the TestKit DSL.
 *
 * <pre>{@code
 * TestKit.test("User Registration Flow")
 *     .config(cfg -> cfg.baseUrl("http://localhost:8080").failFast(true))
 *     .seed(s -> ...)
 *     .api(a -> ...)
 *     .load(l -> ...)
 *     .db(d -> ...)
 *     .run()
 *     .assertPassed();
 * }</pre>
 */
public final class TestKit {

    private final String suiteName;
    private TestKitConfig config = TestKitConfig.defaults();
    private final List<TestKitStep> steps = new ArrayList<>();
    private final List<TestKitReporter> reporters = new ArrayList<>();

    private TestKit(String suiteName) {
        this.suiteName = suiteName;
    }

    /** Start building a named test suite. */
    public static TestKit test(String suiteName) {
        return new TestKit(suiteName);
    }

    /** Shorthand for a single-step test. */
    public static TestKit of(String suiteName) {
        return test(suiteName);
    }

    // ── Configuration ────────────────────────────────────────────────────────

    public TestKit config(Consumer<TestKitConfig.Builder> cfg) {
        TestKitConfig.Builder b = TestKitConfig.builder();
        cfg.accept(b);
        this.config = b.build();
        return this;
    }

    public TestKit config(TestKitConfig config) {
        this.config = config;
        return this;
    }

    // ── Step registration (low-level) ─────────────────────────────────────────

    public TestKit step(TestKitStep step) {
        steps.add(step);
        return this;
    }

    // ── Inline function steps ─────────────────────────────────────────────────

    /** Run any context-aware function as a named step. */
    public TestKit fn(String name, FnStep.Action action) {
        return step(new FnStep(name, action));
    }

    /** Run a plain {@link Runnable} as a named step (no context access needed). */
    public TestKit fn(String name, Runnable runnable) {
        return step(new FnStep(name, runnable));
    }

    // ── Reporting ────────────────────────────────────────────────────────────

    public TestKit reporter(TestKitReporter reporter) {
        reporters.add(reporter);
        return this;
    }

    // ── Execution ────────────────────────────────────────────────────────────

    /**
     * Execute all steps sequentially and return the aggregated result.
     * Does NOT throw on failure — call {@link TestKitResult#assertPassed()} for that.
     */
    public TestKitResult run() {
        return new TestKitRunner(suiteName, steps, config, reporters).run();
    }

    /**
     * Execute and immediately assert all steps passed (throws {@link TestKitException} on failure).
     * Convenience wrapper for JUnit 5 usage.
     */
    public void runAndAssert() {
        run().assertPassed();
    }

    // ── Package-accessible getters for module builders ────────────────────────

    public TestKitConfig getConfig() { return config; }
}

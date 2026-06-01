package io.testkit.workshop;

import io.testkit.core.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EXERCISE 1 — Core engine: TestKitContext, custom TestKitStep, FnStep, StepResult, failFast.
 *
 * Goal: understand how the runner loops through steps, passes a shared context,
 * and collects results. No HTTP, no DB, no external infra needed.
 *
 * Run: mvn test -pl testkit-workshop -Dtest=Ex01_CoreContextAndCustomStep
 */
class Ex01_CoreContextAndCustomStep {

    // ── 1a. A step that writes into context ───────────────────────────────────

    static class GenerateOrderIdStep implements TestKitStep {

        @Override
        public String name() { return "Generate order ID"; }

        @Override
        public StepResult execute(TestKitContext ctx) {
            long start = System.currentTimeMillis();

            // Put something in context for downstream steps to read
            ctx.put("orderId", "ORD-" + System.currentTimeMillis());
            ctx.put("userId",  42L);

            return StepResult
                    .passed(name(), Duration.ofMillis(System.currentTimeMillis() - start))
                    .detail("orderId and userId stored in context")
                    .build();
        }
    }

    // ── 1b. A step that reads from context ────────────────────────────────────

    static class ValidateOrderIdStep implements TestKitStep {

        @Override
        public String name() { return "Validate order ID"; }

        @Override
        public StepResult execute(TestKitContext ctx) {
            long start = System.currentTimeMillis();
            Duration elapsed = Duration.ofMillis(System.currentTimeMillis() - start);

            // ctx.get() throws if key is missing — safe to call after GenerateOrderIdStep
            String orderId = ctx.get("orderId");
            Long   userId  = ctx.get("userId");

            if (!orderId.startsWith("ORD-")) {
                // Return a FAILED result — runner records it but does NOT throw
                return StepResult.failed(name(), elapsed)
                        .detail("Expected orderId to start with ORD-, got: " + orderId)
                        .build();
            }

            return StepResult.passed(name(), elapsed)
                    .detail("orderId=" + orderId + " userId=" + userId)
                    .build();
        }
    }

    // ── 1c. A step that intentionally fails ───────────────────────────────────

    static class AlwaysFailStep implements TestKitStep {
        @Override public String name() { return "Always fail"; }

        @Override
        public StepResult execute(TestKitContext ctx) {
            return StepResult.failed(name(), Duration.ZERO)
                    .detail("This step always fails — intentional for demo purposes")
                    .build();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void contextFlowsBetweenSteps() {
        // The two steps share ONE TestKitContext instance.
        // Whatever Step A puts in, Step B can read.
        TestKitResult result = TestKit.test("Context propagation")
                .config(c -> c.failFast(false))
                .step(new GenerateOrderIdStep())
                .step(new ValidateOrderIdStep())
                .run();

        System.out.println("\n--- Step results ---");
        result.steps().forEach(r ->
                System.out.printf("  %-30s [%s] %s%n",
                        r.stepName(), r.status(), r.detail() != null ? r.detail() : ""));

        assertTrue(result.passed(), "Both steps should pass");
        assertEquals(2, result.passCount());
    }

    @Test
    void failFastSkipsRemainingSteps() {
        // failFast=true (default): once a step fails, subsequent steps are SKIPPED (not run)
        TestKitResult result = TestKit.test("Fail-fast demo")
                .config(c -> c.failFast(true))
                .step(new AlwaysFailStep())
                .step(new ValidateOrderIdStep())  // this should be SKIPPED
                .run();

        assertEquals(1, result.failCount(), "One step should have FAILED");
        // The second step should be SKIPPED, not FAILED
        StepResult second = result.steps().get(1);
        assertEquals(StepResult.Status.SKIPPED, second.status(),
                "Second step should be SKIPPED when failFast=true");
    }

    @Test
    void failFastFalseRunsAllSteps() {
        // failFast=false: ALL steps run even if one fails
        TestKitResult result = TestKit.test("Continue on failure")
                .config(c -> c.failFast(false))
                .step(new AlwaysFailStep())
                .step(new GenerateOrderIdStep())  // still runs despite prior failure
                .run();

        // Overall result is failed (one step failed), but both steps ran
        assertFalse(result.passed());
        assertEquals(2, result.steps().size());
        assertEquals(StepResult.Status.FAILED,  result.steps().get(0).status());
        assertEquals(StepResult.Status.PASSED,  result.steps().get(1).status());
    }

    @Test
    void contextFindVsGet() {
        // ctx.find() returns Optional — safe when a key might not exist.
        // ctx.get() throws TestKitException if key is missing.
        TestKitContext ctx = new TestKitContext();
        ctx.put("name", "Krish");

        Optional<String> found   = ctx.find("name");
        Optional<String> missing = ctx.find("email");  // doesn't throw

        assertTrue(found.isPresent());
        assertTrue(missing.isEmpty());

        // ctx.get on a missing key throws with a helpful message listing available keys
        assertThrows(TestKitException.class, () -> ctx.get("email"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FnStep — inline functions without a dedicated step class
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void fnStepRunnable() {
        // .fn(name, Runnable) — no context needed; ideal for setup/teardown or
        // utility calls that don't interact with shared state.
        AtomicInteger counter = new AtomicInteger(0);

        TestKitResult result = TestKit.test("Inline Runnable")
                .fn("increment counter", counter::incrementAndGet)
                .fn("increment counter again", counter::incrementAndGet)
                .run();

        assertTrue(result.passed());
        assertEquals(2, counter.get(), "Both fn steps should have run");
    }

    @Test
    void fnStepWithContext() {
        // .fn(name, Action) — receives the shared TestKitContext so you can
        // read extracted values from earlier steps or write new ones.
        TestKitResult result = TestKit.test("Inline context-aware fn")
                .fn("populate context", ctx -> {
                    ctx.put("tenantId", "T-42");
                    ctx.put("role", "ADMIN");
                })
                .fn("assert context values", ctx -> {
                    String tenant = ctx.get("tenantId");
                    String role   = ctx.get("role");
                    if (!"T-42".equals(tenant) || !"ADMIN".equals(role)) {
                        throw new AssertionError("Unexpected context: tenant=" + tenant + " role=" + role);
                    }
                })
                .run();

        assertTrue(result.passed(), "Both fn steps should pass");
        assertEquals(2, result.passCount());
    }

    @Test
    void fnStepCheckedExceptionBecomesFailure() {
        // FnStep.Action may throw any checked exception.
        // The step catches it and converts it to a FAILED StepResult —
        // the suite does NOT throw; you inspect the result normally.
        TestKitResult result = TestKit.test("Fn checked exception")
                .config(c -> c.failFast(false))
                .fn("throw checked exception", ctx -> {
                    throw new java.io.IOException("simulated IO failure");
                })
                .fn("this still runs (failFast=false)", ctx -> ctx.put("ran", true))
                .run();

        assertFalse(result.passed());
        assertEquals(StepResult.Status.FAILED, result.steps().get(0).status());
        assertEquals(StepResult.Status.PASSED, result.steps().get(1).status());
        assertTrue(result.steps().get(0).detail().contains("IOException"));
    }

    @Test
    void fnStepVsCustomStep() {
        // Comparison: the custom-step class above (GenerateOrderIdStep) does exactly
        // the same work as this single .fn() call. Use custom steps only when the
        // logic is complex enough to deserve its own named class; otherwise .fn() is
        // cleaner and keeps the intent right where you read the pipeline.
        TestKitResult result = TestKit.test("fn replaces inline anonymous step")
                .fn("Generate order ID", ctx -> {
                    ctx.put("orderId", "ORD-" + System.currentTimeMillis());
                    ctx.put("userId", 42L);
                })
                .fn("Validate order ID", ctx -> {
                    String orderId = ctx.get("orderId");
                    if (!orderId.startsWith("ORD-")) {
                        throw new AssertionError("Expected orderId to start with ORD-, got: " + orderId);
                    }
                })
                .run();

        assertTrue(result.passed());
        assertEquals(2, result.passCount());
    }

    @Test
    void lateBindingResolve() {
        // ctx.resolve() is the core of late-binding: pass a lambda, it runs AT EXECUTION TIME
        // This is how .pathParam("id", ctx -> ctx.get("orderId")) works internally.
        TestKitContext ctx = new TestKitContext();
        ctx.put("userId", "U-999");

        // Value known at build time: resolve returns it as-is
        String literal = ctx.resolve("hello");
        assertEquals("hello", literal);

        // Value via lambda: resolve calls the function with the live context
        String dynamic = ctx.resolve((java.util.function.Function<TestKitContext, String>)
                c -> "user/" + c.get("userId"));
        assertEquals("user/U-999", dynamic);
    }
}

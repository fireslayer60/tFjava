package io.testkit;

import io.testkit.core.TestKit;
import io.testkit.core.TestKitResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Illustrates the TestKit DSL without any real infrastructure.
 * All assertions are on a standalone HTTP bin (httpbin.org would work when network available).
 */
class ExampleTest {

    @Test
    void coreContextAndStepRunnerWork() {
        // Wire a single custom step to verify the runner works
        TestKitResult result = TestKit.test("Core Engine Smoke Test")
                .config(c -> c.baseUrl("http://localhost:9999").failFast(false))
                .step(new io.testkit.core.TestKitStep() {
                    @Override
                    public String name() { return "Context round-trip"; }

                    @Override
                    public io.testkit.core.StepResult execute(io.testkit.core.TestKitContext ctx) {
                        ctx.put("answer", 42);
                        int answer = ctx.get("answer");
                        if (answer != 42) {
                            return io.testkit.core.StepResult
                                    .failed("Context round-trip", java.time.Duration.ZERO)
                                    .detail("Expected 42 but got " + answer).build();
                        }
                        return io.testkit.core.StepResult
                                .passed("Context round-trip", java.time.Duration.ZERO)
                                .detail("Context stored and retrieved correctly").build();
                    }
                })
                .run();

        assertTrue(result.passed(), "Core engine smoke test should pass");
    }
}

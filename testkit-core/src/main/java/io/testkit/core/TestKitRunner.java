package io.testkit.core;

import io.testkit.core.report.ConsoleReporter;
import io.testkit.core.report.TestKitReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Executes a list of {@link TestKitStep}s sequentially, feeding results to reporters. */
final class TestKitRunner {

    private static final Logger log = LoggerFactory.getLogger(TestKitRunner.class);

    private final String suiteName;
    private final List<TestKitStep> steps;
    private final TestKitConfig config;
    private final List<TestKitReporter> reporters;

    TestKitRunner(String suiteName, List<TestKitStep> steps,
                  TestKitConfig config, List<TestKitReporter> reporters) {
        this.suiteName = suiteName;
        this.steps     = List.copyOf(steps);
        this.config    = config;
        this.reporters = reporters.isEmpty()
                ? List.of(new ConsoleReporter())
                : List.copyOf(reporters);
    }

    TestKitResult run() {
        TestKitContext ctx = new TestKitContext();
        List<StepResult> results = new ArrayList<>();
        Instant suiteStart = Instant.now();

        reporters.forEach(r -> r.onSuiteStart(suiteName));

        boolean aborted = false;
        for (TestKitStep step : steps) {
            if (aborted) {
                StepResult skipped = StepResult.skipped(step.name());
                results.add(skipped);
                reporters.forEach(r -> r.onStepEnd(skipped));
                continue;
            }

            reporters.forEach(r -> r.onStepStart(step.name()));
            StepResult result = executeStep(step, ctx);
            results.add(result);
            reporters.forEach(r -> r.onStepEnd(result));

            if (result.failed() && config.failFast()) {
                log.warn("Step '{}' failed — aborting suite (failFast=true)", step.name());
                aborted = true;
            }
        }

        Duration total = Duration.between(suiteStart, Instant.now());
        TestKitResult suiteResult = new TestKitResult(suiteName, results, total);
        reporters.forEach(r -> r.onSuiteEnd(suiteResult));
        return suiteResult;
    }

    private StepResult executeStep(TestKitStep step, TestKitContext ctx) {
        Instant start = Instant.now();
        try {
            return step.execute(ctx);
        } catch (TestKitException e) {
            Duration d = Duration.between(start, Instant.now());
            return StepResult.failed(step.name(), d).detail(e.getMessage()).cause(e).build();
        } catch (Exception e) {
            Duration d = Duration.between(start, Instant.now());
            log.error("Unexpected error in step '{}'", step.name(), e);
            return StepResult.failed(step.name(), d)
                    .detail("Unexpected error: " + e.getClass().getSimpleName() + ": " + e.getMessage())
                    .cause(e).build();
        }
    }
}

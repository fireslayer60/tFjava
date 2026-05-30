package io.testkit.core;

import java.time.Duration;
import java.util.List;

/** Aggregated result for an entire TestKit run (all steps). */
public final class TestKitResult {

    private final String suiteName;
    private final List<StepResult> stepResults;
    private final Duration totalDuration;
    private final boolean passed;

    public TestKitResult(String suiteName, List<StepResult> stepResults, Duration totalDuration) {
        this.suiteName     = suiteName;
        this.stepResults   = List.copyOf(stepResults);
        this.totalDuration = totalDuration;
        this.passed        = stepResults.stream().noneMatch(StepResult::failed);
    }

    public String suiteName()            { return suiteName; }
    public List<StepResult> steps()      { return stepResults; }
    public Duration totalDuration()      { return totalDuration; }
    public boolean passed()              { return passed; }
    public boolean failed()              { return !passed; }

    public long passCount() { return stepResults.stream().filter(StepResult::passed).count(); }
    public long failCount() { return stepResults.stream().filter(StepResult::failed).count(); }

    public void assertPassed() {
        if (failed()) {
            long fc = failCount();
            throw new TestKitException(
                    "TestKit suite '%s' failed: %d step(s) failed out of %d"
                    .formatted(suiteName, fc, stepResults.size()));
        }
    }
}

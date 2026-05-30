package io.testkit.core.report;

import io.testkit.core.StepResult;
import io.testkit.core.TestKitResult;

/** SPI for pluggable reporters. Console, JSON, and HTML implementations are provided. */
public interface TestKitReporter {
    void onSuiteStart(String suiteName);
    void onStepStart(String stepName);
    void onStepEnd(StepResult result);
    void onSuiteEnd(TestKitResult result);
}

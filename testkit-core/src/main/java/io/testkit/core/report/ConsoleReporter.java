package io.testkit.core.report;

import io.testkit.core.StepResult;
import io.testkit.core.TestKitResult;

import java.io.PrintStream;

/** Prints a coloured summary table to stdout. */
public final class ConsoleReporter implements TestKitReporter {

    private static final String RESET  = "\033[0m";
    private static final String GREEN  = "\033[32m";
    private static final String RED    = "\033[31m";
    private static final String YELLOW = "\033[33m";
    private static final String BOLD   = "\033[1m";
    private static final String CYAN   = "\033[36m";

    private final PrintStream out;

    public ConsoleReporter() { this(System.out); }
    public ConsoleReporter(PrintStream out) { this.out = out; }

    @Override
    public void onSuiteStart(String suiteName) {
        out.printf("%n%s%s▶ TestKit — %s%s%n", BOLD, CYAN, suiteName, RESET);
        out.println("─".repeat(60));
    }

    @Override
    public void onStepStart(String stepName) {
        out.printf("  %-45s ", stepName);
        out.flush();
    }

    @Override
    public void onStepEnd(StepResult r) {
        String badge = switch (r.status()) {
            case PASSED  -> GREEN  + "✓ PASSED"  + RESET;
            case FAILED  -> RED    + "✗ FAILED"  + RESET;
            case SKIPPED -> YELLOW + "- SKIPPED" + RESET;
        };
        out.printf("%s  %dms%n", badge, r.duration().toMillis());
        if (r.failed() && r.detail() != null) {
            out.printf("    %s→ %s%s%n", RED, r.detail(), RESET);
        }
    }

    @Override
    public void onSuiteEnd(TestKitResult r) {
        out.println("─".repeat(60));
        String status = r.passed()
                ? GREEN + "PASSED" + RESET
                : RED   + "FAILED" + RESET;
        out.printf("%s%s Suite %s%s  |  %d passed, %d failed  |  total %dms%n%n",
                BOLD, r.suiteName(), status, RESET,
                r.passCount(), r.failCount(),
                r.totalDuration().toMillis());
    }
}

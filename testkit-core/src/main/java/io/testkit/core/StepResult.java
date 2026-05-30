package io.testkit.core;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/** Immutable result produced by a single {@link TestKitStep}. */
public final class StepResult {

    public enum Status { PASSED, FAILED, SKIPPED }

    private final String stepName;
    private final Status status;
    private final Duration duration;
    private final String detail;
    private final List<String> assertions;
    private final Throwable cause;

    private StepResult(Builder b) {
        this.stepName   = b.stepName;
        this.status     = b.status;
        this.duration   = b.duration;
        this.detail     = b.detail;
        this.assertions = Collections.unmodifiableList(b.assertions);
        this.cause      = b.cause;
    }

    public static Builder passed(String stepName, Duration duration) {
        return new Builder(stepName, Status.PASSED, duration);
    }

    public static Builder failed(String stepName, Duration duration) {
        return new Builder(stepName, Status.FAILED, duration);
    }

    public static StepResult skipped(String stepName) {
        return new Builder(stepName, Status.SKIPPED, Duration.ZERO).build();
    }

    public String stepName()        { return stepName; }
    public Status status()          { return status; }
    public Duration duration()      { return duration; }
    public String detail()          { return detail; }
    public List<String> assertions(){ return assertions; }
    public Throwable cause()        { return cause; }
    public boolean passed()         { return status == Status.PASSED; }
    public boolean failed()         { return status == Status.FAILED; }

    @Override
    public String toString() {
        return "[%s] %s (%dms)%s".formatted(
                status, stepName, duration.toMillis(),
                detail != null ? " — " + detail : "");
    }

    public static final class Builder {
        private final String stepName;
        private final Status status;
        private final Duration duration;
        private String detail;
        private List<String> assertions = List.of();
        private Throwable cause;

        private Builder(String stepName, Status status, Duration duration) {
            this.stepName = stepName;
            this.status   = status;
            this.duration = duration;
        }

        public Builder detail(String detail)              { this.detail = detail; return this; }
        public Builder assertions(List<String> a)         { this.assertions = a; return this; }
        public Builder cause(Throwable t)                 { this.cause = t; return this; }
        public StepResult build()                         { return new StepResult(this); }
    }
}

package io.testkit.security;

import io.testkit.core.StepResult;
import io.testkit.core.TestKitContext;
import io.testkit.core.TestKitStep;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/** {@link TestKitStep} that runs security probes against configured endpoints. */
public final class SecurityStep implements TestKitStep {

    private final String name;
    private final Consumer<SecurityBuilder> config;

    public SecurityStep(String name, Consumer<SecurityBuilder> config) {
        this.name   = name;
        this.config = config;
    }

    public SecurityStep(Consumer<SecurityBuilder> config) {
        this("Security Scan", config);
    }

    @Override
    public String name() { return name; }

    @Override
    public StepResult execute(TestKitContext ctx) {
        Instant start = Instant.now();
        SecurityBuilder builder = new SecurityBuilder();
        config.accept(builder);
        SecurityReport report = builder.executeSecurity(ctx);
        Duration elapsed = Duration.between(start, Instant.now());

        String detail = report.hasFindings()
                ? report.findings().size() + " finding(s)"
                : "No issues found";
        return StepResult.passed(name, elapsed).detail(detail).build();
    }
}

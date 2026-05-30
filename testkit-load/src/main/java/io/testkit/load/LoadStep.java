package io.testkit.load;

import io.testkit.core.StepResult;
import io.testkit.core.TestKitContext;
import io.testkit.core.TestKitStep;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/** {@link TestKitStep} that runs a full load test and validates SLO thresholds. */
public final class LoadStep implements TestKitStep {

    private final String name;
    private final Consumer<LoadBuilder> config;

    public LoadStep(String name, Consumer<LoadBuilder> config) {
        this.name   = name;
        this.config = config;
    }

    public LoadStep(Consumer<LoadBuilder> config) {
        this("Load Test", config);
    }

    @Override
    public String name() { return name; }

    @Override
    public StepResult execute(TestKitContext ctx) {
        Instant start = Instant.now();
        LoadBuilder builder = new LoadBuilder();
        config.accept(builder);
        LoadBuilder.LoadResult result = builder.executeLoad(ctx);
        Duration elapsed = Duration.between(start, Instant.now());

        LoadMetrics m = result.metrics();
        String detail = "RPS=%.1f  P99=%dms  Errors=%.2f%%"
                .formatted(m.actualRps(), m.p99(), m.errorRatePercent());
        return StepResult.passed(name, elapsed).detail(detail).build();
    }
}

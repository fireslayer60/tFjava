package io.testkit.seed;

import io.testkit.core.StepResult;
import io.testkit.core.TestKitContext;
import io.testkit.core.TestKitStep;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/** {@link TestKitStep} that runs one or more seed operations before API/load steps. */
public final class SeedStep implements TestKitStep {

    private final String name;
    private final Consumer<SeedBuilder> config;

    public SeedStep(String name, Consumer<SeedBuilder> config) {
        this.name = name;
        this.config = config;
    }

    public SeedStep(Consumer<SeedBuilder> config) {
        this("Seed", config);
    }

    @Override
    public String name() { return name; }

    @Override
    public StepResult execute(TestKitContext ctx) {
        Instant start = Instant.now();
        SeedBuilder builder = new SeedBuilder();
        config.accept(builder);
        builder.executeSeed(ctx);
        Duration elapsed = Duration.between(start, Instant.now());
        return StepResult.passed(name, elapsed).detail("Seed operations completed").build();
    }
}

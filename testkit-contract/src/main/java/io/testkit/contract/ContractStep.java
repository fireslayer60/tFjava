package io.testkit.contract;

import io.testkit.core.StepResult;
import io.testkit.core.TestKitContext;
import io.testkit.core.TestKitStep;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/** {@link TestKitStep} for consumer-driven contract testing. */
public final class ContractStep implements TestKitStep {

    private final String name;
    private final Consumer<ContractBuilder> config;

    public ContractStep(String name, Consumer<ContractBuilder> config) {
        this.name   = name;
        this.config = config;
    }

    public ContractStep(Consumer<ContractBuilder> config) {
        this("Contract Test", config);
    }

    @Override
    public String name() { return name; }

    @Override
    public StepResult execute(TestKitContext ctx) {
        Instant start = Instant.now();
        ContractBuilder builder = new ContractBuilder();
        config.accept(builder);
        builder.executeContract(ctx);
        Duration elapsed = Duration.between(start, Instant.now());
        return StepResult.passed(name, elapsed).detail("Contract verified").build();
    }
}

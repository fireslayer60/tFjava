package io.testkit.queue;

import io.testkit.core.StepResult;
import io.testkit.core.TestKitContext;
import io.testkit.core.TestKitStep;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/** {@link TestKitStep} that produces/consumes messages and asserts queue state. */
public final class QueueStep implements TestKitStep {

    private final String name;
    private final Consumer<QueueBuilder> config;

    public QueueStep(String name, Consumer<QueueBuilder> config) {
        this.name   = name;
        this.config = config;
    }

    public QueueStep(Consumer<QueueBuilder> config) {
        this("Queue Assertions", config);
    }

    @Override
    public String name() { return name; }

    @Override
    public StepResult execute(TestKitContext ctx) {
        Instant start = Instant.now();
        QueueBuilder builder = new QueueBuilder();
        config.accept(builder);
        builder.executeQueue(ctx);
        Duration elapsed = Duration.between(start, Instant.now());
        return StepResult.passed(name, elapsed).detail("Queue operations completed").build();
    }
}

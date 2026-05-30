package io.testkit.db;

import io.testkit.core.StepResult;
import io.testkit.core.TestKitContext;
import io.testkit.core.TestKitStep;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/** {@link TestKitStep} that asserts database state after API operations. */
public final class DbStep implements TestKitStep {

    private final String name;
    private final Consumer<DbBuilder> config;

    public DbStep(String name, Consumer<DbBuilder> config) {
        this.name   = name;
        this.config = config;
    }

    public DbStep(Consumer<DbBuilder> config) {
        this("DB Assertions", config);
    }

    @Override
    public String name() { return name; }

    @Override
    public StepResult execute(TestKitContext ctx) {
        Instant start = Instant.now();
        DbBuilder builder = new DbBuilder();
        config.accept(builder);
        builder.executeDb(ctx);
        Duration elapsed = Duration.between(start, Instant.now());
        return StepResult.passed(name, elapsed).detail("DB assertions passed").build();
    }
}

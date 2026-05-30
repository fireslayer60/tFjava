package io.testkit.api;

import io.testkit.core.StepResult;
import io.testkit.core.TestKitContext;
import io.testkit.core.TestKitStep;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/** {@link TestKitStep} that executes a single HTTP request and validates the response. */
public final class ApiStep implements TestKitStep {

    private final String name;
    private final Consumer<ApiBuilder> config;
    private final ApiConfig apiConfig;

    public ApiStep(String name, Consumer<ApiBuilder> config, ApiConfig apiConfig) {
        this.name      = name;
        this.config    = config;
        this.apiConfig = apiConfig;
    }

    public ApiStep(Consumer<ApiBuilder> config) {
        this("API Request", config, ApiConfig.defaults());
    }

    @Override
    public String name() { return name; }

    @Override
    public StepResult execute(TestKitContext ctx) {
        Instant start = Instant.now();
        ApiBuilder builder = new ApiBuilder();
        config.accept(builder);
        ApiResponse response = builder.executeWith(ctx, apiConfig);
        Duration elapsed = Duration.between(start, Instant.now());

        String detail = "HTTP %d  %dms".formatted(response.status(), response.durationMs());
        return StepResult.passed(name, elapsed).detail(detail).build();
    }
}

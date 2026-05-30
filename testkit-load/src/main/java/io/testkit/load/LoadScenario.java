package io.testkit.load;

import io.testkit.api.ApiBuilder;
import io.testkit.api.ApiConfig;
import io.testkit.api.ApiResponse;
import io.testkit.core.TestKitContext;
import io.testkit.core.TestKitException;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A single repeatable user journey executed by each virtual user.
 * Can be an API call, a custom runnable, or a series of steps.
 */
public final class LoadScenario {

    private final String name;
    private final Supplier<Runnable> scenarioFactory;

    private LoadScenario(String name, Supplier<Runnable> factory) {
        this.name = name;
        this.scenarioFactory = factory;
    }

    /** Scenario is a single API call. */
    public static LoadScenario http(String name, Consumer<ApiBuilder> spec, ApiConfig apiConfig) {
        return new LoadScenario(name, () -> () -> {
            ApiBuilder builder = new ApiBuilder();
            spec.accept(builder);
            // Use a throw-away context per invocation (no state sharing between VU iterations)
            builder.executeWith(new TestKitContext(), apiConfig);
        });
    }

    /** Scenario is an arbitrary runnable (full control). */
    public static LoadScenario custom(String name, Runnable task) {
        return new LoadScenario(name, () -> task);
    }

    /** Scenario is a runnable created fresh for each virtual user (e.g. carries per-VU state). */
    public static LoadScenario perVu(String name, Supplier<Runnable> factory) {
        return new LoadScenario(name, factory);
    }

    public String name() { return name; }

    /** Create a fresh runnable instance for one virtual user. */
    Runnable newIteration() {
        return scenarioFactory.get();
    }
}

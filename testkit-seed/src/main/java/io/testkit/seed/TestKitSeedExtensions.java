package io.testkit.seed;

import io.testkit.core.TestKit;

import java.util.function.Consumer;

/**
 * Extension methods for {@link TestKit} — adds {@code .seed()} to the builder.
 * Usage:
 * <pre>{@code
 * TestKitSeedExtensions.seed(kit, s -> s.dataSource(ds).table("users").fromFactory(uf, 5))
 * }</pre>
 *
 * Or use the {@link io.testkit.TestKitDsl} façade which already wires this in:
 * <pre>{@code
 * TestKitDsl.test("My suite")
 *     .seed(s -> s.dataSource(ds).table("users").fromFactory(uf, 5))
 * }</pre>
 */
public final class TestKitSeedExtensions {

    private TestKitSeedExtensions() {}

    public static TestKit seed(TestKit kit, Consumer<SeedBuilder> config) {
        return kit.step(new SeedStep(config));
    }

    public static TestKit seed(TestKit kit, String stepName, Consumer<SeedBuilder> config) {
        return kit.step(new SeedStep(stepName, config));
    }
}

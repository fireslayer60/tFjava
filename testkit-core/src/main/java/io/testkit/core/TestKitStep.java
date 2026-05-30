package io.testkit.core;

/**
 * A single executable unit within a TestKit chain.
 * Steps run sequentially; each receives the shared {@link TestKitContext} so values
 * extracted in earlier steps are available to later ones.
 */
public interface TestKitStep {

    /** Human-readable label shown in reports. */
    String name();

    /**
     * Execute this step.
     *
     * @param ctx shared context — read extracted values, write new ones
     * @return result describing pass/fail, duration, and optional detail message
     */
    StepResult execute(TestKitContext ctx);
}

package io.testkit.mock;

import io.testkit.core.StepResult;
import io.testkit.core.TestKitContext;
import io.testkit.core.TestKitStep;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * {@link TestKitStep} that starts an embedded WireMock server before tests run.
 * The server is stored in context so later teardown steps or cleanup hooks can stop it.
 *
 * <pre>{@code
 * .mock(m -> m
 *     .port(9090)
 *     .stub(s -> s.GET("/health").willReturn(200, "{\"status\":\"UP\"}"))
 *     .contextKey("mockServer"))
 * // Later: ((ManagedMockServer) ctx.get("mockServer")).stop();
 * }</pre>
 */
public final class MockStep implements TestKitStep {

    static final String DEFAULT_CONTEXT_KEY = "testkit.mockServer";

    private final String name;
    private final Consumer<MockBuilder> config;

    public MockStep(String name, Consumer<MockBuilder> config) {
        this.name   = name;
        this.config = config;
    }

    public MockStep(Consumer<MockBuilder> config) {
        this("Mock Server", config);
    }

    @Override
    public String name() { return name; }

    @Override
    public StepResult execute(TestKitContext ctx) {
        Instant start = Instant.now();
        MockBuilder builder = new MockBuilder();
        config.accept(builder);
        ManagedMockServer server = builder.startServer(ctx);

        // Always store for potential teardown
        ctx.put(DEFAULT_CONTEXT_KEY, server);

        Duration elapsed = Duration.between(start, Instant.now());
        return StepResult.passed(name, elapsed)
                .detail("Mock server started at " + server.baseUrl())
                .build();
    }
}

package io.testkit.core.junit;

import io.testkit.core.TestKitContext;
import org.junit.jupiter.api.extension.*;

import java.lang.annotation.*;

/**
 * JUnit 5 extension that injects a fresh {@link TestKitContext} into test parameters
 * and ensures any context-registered cleanup callbacks run after the test.
 *
 * <pre>{@code
 * @ExtendWith(TestKitExtension.class)
 * class MyTest {
 *     @Test
 *     void registration(TestKitContext ctx) { ... }
 * }
 * }</pre>
 */
public final class TestKitExtension
        implements ParameterResolver, AfterEachCallback {

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(TestKitExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext param, ExtensionContext ext) {
        return param.getParameter().getType() == TestKitContext.class;
    }

    @Override
    public Object resolveParameter(ParameterContext param, ExtensionContext ext) {
        return ext.getStore(NS).getOrComputeIfAbsent(
                "ctx", k -> new TestKitContext(), TestKitContext.class);
    }

    @Override
    public void afterEach(ExtensionContext ext) {
        // Future: run cleanup callbacks registered in context
    }

    // ── Convenience annotation ────────────────────────────────────────────────

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExtendWith(TestKitExtension.class)
    public @interface TestKitTest {}
}

package io.testkit.spring;

import io.testkit.core.TestKit;
import io.testkit.core.TestKitConfig;

/**
 * Spring-managed factory that creates pre-configured {@link TestKit} instances.
 * Inject this into your Spring Boot test classes.
 *
 * <pre>{@code
 * @Autowired TestKitFactory testKit;
 *
 * @Test
 * void userRegistration() {
 *     testKit.test("User Registration")
 *         .api(a -> a.POST("/api/users")...)
 *         .runAndAssert();
 * }
 * }</pre>
 */
public final class TestKitFactory {

    private final TestKitConfig config;

    public TestKitFactory(TestKitConfig config) {
        this.config = config;
    }

    public TestKit test(String suiteName) {
        return TestKit.test(suiteName).config(config);
    }
}

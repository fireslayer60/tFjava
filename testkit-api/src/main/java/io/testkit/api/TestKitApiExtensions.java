package io.testkit.api;

import io.testkit.core.TestKit;

import java.util.function.Consumer;

/** Adds {@code .api()} to {@link TestKit}. Used via the unified {@code TestKitDsl} façade. */
public final class TestKitApiExtensions {

    private TestKitApiExtensions() {}

    public static TestKit api(TestKit kit, Consumer<ApiBuilder> config) {
        ApiConfig apiCfg = ApiConfig.builder()
                .baseUrl(kit.getConfig().baseUrl())
                .timeout(kit.getConfig().defaultTimeout())
                .build();
        return kit.step(new ApiStep("API Request", config, apiCfg));
    }

    public static TestKit api(TestKit kit, String stepName, Consumer<ApiBuilder> config) {
        ApiConfig apiCfg = ApiConfig.builder()
                .baseUrl(kit.getConfig().baseUrl())
                .timeout(kit.getConfig().defaultTimeout())
                .build();
        return kit.step(new ApiStep(stepName, config, apiCfg));
    }
}

package io.testkit.workshop;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testkit.api.ApiConfig;
import io.testkit.core.TestKit;
import io.testkit.core.TestKitResult;
import io.testkit.load.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EXERCISE 4 — Load testing with virtual threads and HDR histogram.
 *
 * Goal: understand how LoadRunner, LoadMetrics, and Threshold work.
 * We use WireMock as the target so tests are self-contained.
 *
 * Key concepts:
 *  - Java 21 virtual threads: each VU runs in its own VThread — cheap, no OS threads
 *  - HDR Histogram: O(1) record, O(log N) percentile query, fixed memory
 *  - Token bucket rate limiter: aggregate RPS across all VUs
 *  - Threshold: SLO assertions on metrics (p99, errorRate, etc.)
 *
 * Run: mvn test -pl testkit-workshop -Dtest=Ex04_LoadTesting
 */
class Ex04_LoadTesting {

    private WireMockServer wire;
    private String baseUrl;

    @BeforeEach
    void startWireMock() {
        wire = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wire.start();
        baseUrl = "http://localhost:" + wire.port();
        configureFor("localhost", wire.port());
    }

    @AfterEach
    void stopWireMock() {
        wire.stop();
    }

    @Test
    void basicLoadRunWithMetricsInspection() {
        // Stub: fast endpoint, always 200
        stubFor(get("/api/ping").willReturn(aResponse().withStatus(200).withBody("{}")));

        ApiConfig cfg = ApiConfig.builder().baseUrl(baseUrl).build();

        // Build the scenario: repeated GET /api/ping
        LoadScenario scenario = LoadScenario.http(
                "Ping under load",
                a -> a.GET("/api/ping").expect(200),
                cfg);

        // Configure: 5 VUs, 3 second run
        LoadConfig config = LoadConfig.builder()
                .virtualUsers(5)
                .duration(java.time.Duration.ofSeconds(3))
                .build();

        // Run the load test directly (no TestKit runner involved)
        LoadMetrics metrics = new LoadRunner(config, scenario).run();

        System.out.println("\n--- Load Metrics ---");
        System.out.printf("  Total requests : %d%n",   metrics.totalRequests());
        System.out.printf("  Success        : %d%n",   metrics.successCount());
        System.out.printf("  Errors         : %d%n",   metrics.errorCount());
        System.out.printf("  Actual RPS     : %.1f%n", metrics.actualRps());
        System.out.printf("  P50 latency    : %dms%n", metrics.p50());
        System.out.printf("  P95 latency    : %dms%n", metrics.p95());
        System.out.printf("  P99 latency    : %dms%n", metrics.p99());

        assertTrue(metrics.totalRequests() > 0, "Should have fired at least one request");
        assertEquals(0, metrics.errorCount(), "No errors expected against a healthy stub");
    }

    @Test
    void thresholdChecksEnforceSlO() {
        // Threshold.checkAll() runs ALL thresholds and throws ONE combined exception
        // listing every violation — it never short-circuits.

        // Create metrics manually to test threshold logic without running a load test
        LoadMetrics metrics = new LoadMetrics();
        metrics.recordStart();
        // Simulate: 100 requests, all take 200ms
        for (int i = 0; i < 100; i++) metrics.record(200, true);
        metrics.recordEnd();

        // This threshold should PASS: p99=200ms which is under 500ms
        assertDoesNotThrow(() ->
                Threshold.checkAll(java.util.List.of(Threshold.p99Under(500)), metrics));

        // This threshold should FAIL: p99=200ms is NOT under 100ms
        var ex = assertThrows(io.testkit.core.TestKitException.class, () ->
                Threshold.checkAll(java.util.List.of(Threshold.p99Under(100)), metrics));
        System.out.println("\nThreshold violation message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("P99"));
    }

    @Test
    void multipleThresholdsAllChecked() {
        // Even if the first threshold fails, ALL thresholds are evaluated.
        // You see every violation at once, not just the first one.

        LoadMetrics metrics = new LoadMetrics();
        metrics.recordStart();
        for (int i = 0; i < 10; i++) metrics.record(300, false); // 100% error rate, slow
        metrics.recordEnd();

        var ex = assertThrows(io.testkit.core.TestKitException.class, () ->
                Threshold.checkAll(java.util.List.of(
                        Threshold.p99Under(100),          // will fail (300ms > 100ms)
                        Threshold.errorRateUnder(1.0)     // will fail (100% errors)
                ), metrics));

        System.out.println("\nAll violations:\n" + ex.getMessage());
        // Both violations should be in the message
        assertTrue(ex.getMessage().contains("P99"));
        assertTrue(ex.getMessage().contains("error rate") || ex.getMessage().contains("Error"));
    }

    @Test
    void rateLimiterCapsThroughput() {
        // With targetRps=20 and 5 VUs over 2 seconds, we expect ~40 total requests (20/s × 2s).
        // The rate limiter is a token bucket shared across all VUs.

        stubFor(get("/api/ratelimited").willReturn(aResponse().withStatus(200).withBody("{}")));

        ApiConfig cfg = ApiConfig.builder().baseUrl(baseUrl).build();
        LoadScenario scenario = LoadScenario.http(
                "Rate-limited scenario",
                a -> a.GET("/api/ratelimited").expect(200),
                cfg);

        LoadConfig config = LoadConfig.builder()
                .virtualUsers(5)
                .rps(20)                                            // cap at 20 req/s across all VUs
                .duration(java.time.Duration.ofSeconds(2))
                .build();

        LoadMetrics metrics = new LoadRunner(config, scenario).run();

        System.out.printf("%nRate-limited run: %d requests in %dms (%.1f rps)%n",
                metrics.totalRequests(), metrics.durationMs(), metrics.actualRps());

        // Should be nowhere near the unlimit cap; loose bounds since timing isn't exact
        assertTrue(metrics.totalRequests() <= 60,
                "Rate limiter should cap requests, got: " + metrics.totalRequests());
    }

    @Test
    void loadStepInTestKitPipeline() {
        // This is how you use load testing inside the full TestKit DSL.
        // LoadStep wraps LoadBuilder which wraps LoadRunner.

        stubFor(post("/api/orders").willReturn(aResponse().withStatus(201).withBody("{}")));

        // LoadBuilder needs apiConfig explicitly - it does NOT inherit from TestKit.config
        ApiConfig loadCfg = ApiConfig.builder().baseUrl(baseUrl).build();

        TestKitResult result = TestKit.test("Order load test")
                .config(c -> c.baseUrl(baseUrl).failFast(true))
                .step(new LoadStep(l -> l
                        .scenario("Order creation load")
                        .apiConfig(loadCfg)      // must be set explicitly on the load builder
                        .POST("/api/orders")
                        .json("{\"productId\":\"P-1\",\"qty\":1}")
                        .expect(201)
                        .virtualUsers(3)
                        .duration(2)            // seconds shorthand
                        .p99Under(2000)         // generous SLO for a local WireMock
                        .errorRateUnder(5.0)))
                .run();

        assertTrue(result.passed(), "Load test should pass with generous SLOs against WireMock");
    }
}

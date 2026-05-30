package io.testkit.load;

import io.testkit.api.ApiBuilder;
import io.testkit.api.ApiConfig;
import io.testkit.core.TestKitContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fluent DSL for defining a load test.
 *
 * <pre>{@code
 * .load(l -> l
 *     .scenario("Checkout flow")
 *     .POST("/api/checkout")
 *     .json("""{"cartId":"abc"}""")
 *     .virtualUsers(50)
 *     .duration(30)
 *     .rps(200)
 *     .threshold(Threshold.p99Under(500))
 *     .threshold(Threshold.errorRateUnder(1.0)))
 * }</pre>
 */
public final class LoadBuilder {

    private String scenarioName = "Load Test";
    private int virtualUsers = 10;
    private int targetRps = 0;
    private Duration rampUp   = Duration.ZERO;
    private Duration duration  = Duration.ofSeconds(30);
    private Duration coolDown  = Duration.ofSeconds(5);
    private final List<Threshold> thresholds = new ArrayList<>();

    // HTTP scenario shorthand
    private Consumer<ApiBuilder> apiSpec;
    private ApiConfig apiConfig;

    // Custom scenario
    private LoadScenario customScenario;

    // ── Scenario identity ─────────────────────────────────────────────────────

    public LoadBuilder scenario(String name) { this.scenarioName = name; return this; }

    // ── HTTP shorthand (delegates to LoadScenario.http) ───────────────────────

    public LoadBuilder GET(String path)    { this.apiSpec = a -> a.GET(path); return this; }
    public LoadBuilder POST(String path)   { this.apiSpec = a -> a.POST(path); return this; }
    public LoadBuilder PUT(String path)    { this.apiSpec = a -> a.PUT(path); return this; }
    public LoadBuilder DELETE(String path) { this.apiSpec = a -> a.DELETE(path); return this; }
    public LoadBuilder PATCH(String path)  { this.apiSpec = a -> a.PATCH(path); return this; }

    public LoadBuilder json(String body) {
        Consumer<ApiBuilder> prev = this.apiSpec;
        this.apiSpec = a -> { prev.accept(a); a.json(body); };
        return this;
    }

    public LoadBuilder header(String name, String value) {
        Consumer<ApiBuilder> prev = this.apiSpec;
        this.apiSpec = a -> { prev.accept(a); a.header(name, value); };
        return this;
    }

    public LoadBuilder expect(int status) {
        Consumer<ApiBuilder> prev = this.apiSpec;
        this.apiSpec = a -> { prev.accept(a); a.expect(status); };
        return this;
    }

    public LoadBuilder apiConfig(ApiConfig cfg) { this.apiConfig = cfg; return this; }

    /** Provide a fully custom scenario instead of an HTTP shorthand. */
    public LoadBuilder scenario(LoadScenario scenario) {
        this.customScenario = scenario;
        return this;
    }

    // ── Load shape ────────────────────────────────────────────────────────────

    public LoadBuilder virtualUsers(int vu)      { this.virtualUsers = vu; return this; }
    public LoadBuilder rps(int rps)              { this.targetRps = rps; return this; }
    public LoadBuilder rampUp(Duration d)        { this.rampUp = d; return this; }
    public LoadBuilder duration(Duration d)      { this.duration = d; return this; }
    public LoadBuilder duration(long seconds)    { return duration(Duration.ofSeconds(seconds)); }
    public LoadBuilder coolDown(Duration d)      { this.coolDown = d; return this; }

    // ── SLO thresholds ────────────────────────────────────────────────────────

    public LoadBuilder threshold(Threshold t)    { this.thresholds.add(t); return this; }

    public LoadBuilder p99Under(long maxMs)      { return threshold(Threshold.p99Under(maxMs)); }
    public LoadBuilder p95Under(long maxMs)      { return threshold(Threshold.p95Under(maxMs)); }
    public LoadBuilder p50Under(long maxMs)      { return threshold(Threshold.p50Under(maxMs)); }
    public LoadBuilder maxUnder(long maxMs)      { return threshold(Threshold.maxUnder(maxMs)); }
    public LoadBuilder errorRateUnder(double pct){ return threshold(Threshold.errorRateUnder(pct)); }

    // ── Execution ─────────────────────────────────────────────────────────────

    LoadResult executeLoad(TestKitContext ctx) {
        LoadScenario scenario = buildScenario(ctx);
        LoadConfig config = LoadConfig.builder()
                .virtualUsers(virtualUsers)
                .rps(targetRps)
                .rampUp(rampUp)
                .duration(duration)
                .coolDown(coolDown)
                .build();

        LoadRunner runner = new LoadRunner(config, scenario);
        LoadMetrics metrics = runner.run();

        // Validate thresholds
        if (!thresholds.isEmpty()) {
            Threshold.checkAll(thresholds, metrics);
        }

        LoadReport report = new LoadReport(scenarioName, config, metrics);
        report.print();
        return new LoadResult(metrics, report);
    }

    private LoadScenario buildScenario(TestKitContext ctx) {
        if (customScenario != null) return customScenario;
        if (apiSpec != null) {
            ApiConfig effective = apiConfig != null ? apiConfig : ApiConfig.defaults();
            return LoadScenario.http(scenarioName, apiSpec, effective);
        }
        throw new io.testkit.core.TestKitException(
                "LoadBuilder requires either .GET/POST/... or .scenario(LoadScenario)");
    }

    /** Internal result holder. */
    record LoadResult(LoadMetrics metrics, LoadReport report) {}
}

package io.testkit.load;

/** Pretty-print and programmatic access to load test results. */
public final class LoadReport {

    private final String scenarioName;
    private final LoadConfig config;
    private final LoadMetrics metrics;

    public LoadReport(String scenarioName, LoadConfig config, LoadMetrics metrics) {
        this.scenarioName = scenarioName;
        this.config       = config;
        this.metrics      = metrics;
    }

    public LoadMetrics metrics() { return metrics; }

    public String summary() {
        return """
                ┌─ Load Test: %s ──────────────────────────────────────
                │  VUs: %-5d  Duration: %ds  Target RPS: %s
                ├─ Throughput ────────────────────────────────────────
                │  Total requests : %,d
                │  Actual RPS     : %.1f
                │  Success        : %,d  (%.1f%%)
                │  Errors         : %,d  (%.2f%%)
                ├─ Latency (ms) ──────────────────────────────────────
                │  Min   : %d
                │  Mean  : %d
                │  P50   : %d
                │  P95   : %d
                │  P99   : %d
                │  P99.9 : %d
                │  Max   : %d
                └─────────────────────────────────────────────────────
                """.formatted(
                scenarioName,
                config.virtualUsers(), config.duration().getSeconds(),
                config.targetRps() > 0 ? config.targetRps() : "∞",
                metrics.totalRequests(),
                metrics.actualRps(),
                metrics.successCount(),
                metrics.totalRequests() > 0 ? metrics.successCount() * 100.0 / metrics.totalRequests() : 0.0,
                metrics.errorCount(),
                metrics.errorRatePercent(),
                metrics.min(),
                metrics.mean(),
                metrics.p50(),
                metrics.p95(),
                metrics.p99(),
                metrics.p999(),
                metrics.max()
        );
    }

    public void print() {
        System.out.println(summary());
    }
}

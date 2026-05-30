package io.testkit.load;

import org.HdrHistogram.Histogram;

import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe metrics collector backed by an HDR histogram.
 * Provides P50/P95/P99/P999/max with minimal allocation overhead.
 */
public final class LoadMetrics {

    // HDR histogram: 1 µs to 60 s, 3 significant figures
    private final Histogram latencyHistogram = new Histogram(60_000_000L, 3);
    private final LongAdder totalRequests  = new LongAdder();
    private final LongAdder successCount   = new LongAdder();
    private final LongAdder errorCount     = new LongAdder();
    private volatile long startEpochMs;
    private volatile long endEpochMs;

    public void recordStart() {
        startEpochMs = System.currentTimeMillis();
    }

    public void recordEnd() {
        endEpochMs = System.currentTimeMillis();
    }

    public void record(long durationMs, boolean success) {
        synchronized (latencyHistogram) {
            latencyHistogram.recordValue(Math.max(durationMs, 1));
        }
        totalRequests.increment();
        if (success) successCount.increment();
        else         errorCount.increment();
    }

    // ── Percentile accessors ──────────────────────────────────────────────────

    public long p50()  { return percentile(50.0); }
    public long p95()  { return percentile(95.0); }
    public long p99()  { return percentile(99.0); }
    public long p999() { return percentile(99.9); }
    public long max()  { synchronized (latencyHistogram) { return latencyHistogram.getMaxValue(); } }
    public long mean() { synchronized (latencyHistogram) { return (long) latencyHistogram.getMean(); } }
    public long min()  { synchronized (latencyHistogram) { return latencyHistogram.getMinValue(); } }

    private long percentile(double p) {
        synchronized (latencyHistogram) {
            return latencyHistogram.getValueAtPercentile(p);
        }
    }

    // ── Throughput ────────────────────────────────────────────────────────────

    public long totalRequests() { return totalRequests.longValue(); }
    public long successCount()  { return successCount.longValue(); }
    public long errorCount()    { return errorCount.longValue(); }

    public double errorRatePercent() {
        long total = totalRequests.longValue();
        return total == 0 ? 0.0 : (errorCount.longValue() * 100.0) / total;
    }

    public double actualRps() {
        long durationMs = endEpochMs - startEpochMs;
        if (durationMs <= 0) return 0;
        return totalRequests.longValue() * 1000.0 / durationMs;
    }

    public long durationMs() { return endEpochMs - startEpochMs; }
}

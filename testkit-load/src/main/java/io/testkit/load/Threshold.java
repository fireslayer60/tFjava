package io.testkit.load;

import io.testkit.core.TestKitException;

import java.util.ArrayList;
import java.util.List;

/** A latency or error-rate SLO that must be satisfied for the load test to pass. */
public abstract class Threshold {

    private final String description;

    protected Threshold(String description) {
        this.description = description;
    }

    public abstract void check(LoadMetrics metrics);

    public String description() { return description; }

    // ── Static factory methods ────────────────────────────────────────────────

    public static Threshold p50Under(long maxMs) {
        return new LatencyThreshold("P50 < " + maxMs + "ms", maxMs) {
            @Override protected long value(LoadMetrics m) { return m.p50(); }
            @Override protected String label() { return "P50"; }
        };
    }

    public static Threshold p95Under(long maxMs) {
        return new LatencyThreshold("P95 < " + maxMs + "ms", maxMs) {
            @Override protected long value(LoadMetrics m) { return m.p95(); }
            @Override protected String label() { return "P95"; }
        };
    }

    public static Threshold p99Under(long maxMs) {
        return new LatencyThreshold("P99 < " + maxMs + "ms", maxMs) {
            @Override protected long value(LoadMetrics m) { return m.p99(); }
            @Override protected String label() { return "P99"; }
        };
    }

    public static Threshold maxUnder(long maxMs) {
        return new LatencyThreshold("Max < " + maxMs + "ms", maxMs) {
            @Override protected long value(LoadMetrics m) { return m.max(); }
            @Override protected String label() { return "Max"; }
        };
    }

    public static Threshold errorRateUnder(double maxPercent) {
        return new Threshold("Error rate < " + maxPercent + "%") {
            @Override
            public void check(LoadMetrics m) {
                double actual = m.errorRatePercent();
                if (actual > maxPercent) {
                    throw new TestKitException(
                            "Load threshold FAILED: error rate %.2f%% > %.2f%%".formatted(actual, maxPercent));
                }
            }
        };
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private abstract static class LatencyThreshold extends Threshold {
        private final long maxMs;

        LatencyThreshold(String desc, long maxMs) {
            super(desc);
            this.maxMs = maxMs;
        }

        protected abstract long value(LoadMetrics m);
        protected abstract String label();

        @Override
        public void check(LoadMetrics metrics) {
            long actual = value(metrics);
            if (actual > maxMs) {
                throw new TestKitException(
                        "Load threshold FAILED: %s = %dms exceeds limit of %dms"
                        .formatted(label(), actual, maxMs));
            }
        }
    }

    // ── Multi-threshold checker ───────────────────────────────────────────────

    public static void checkAll(List<Threshold> thresholds, LoadMetrics metrics) {
        List<String> failures = new ArrayList<>();
        for (Threshold t : thresholds) {
            try {
                t.check(metrics);
            } catch (TestKitException e) {
                failures.add(e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new TestKitException("Load test thresholds not met:\n  • " +
                    String.join("\n  • ", failures));
        }
    }
}

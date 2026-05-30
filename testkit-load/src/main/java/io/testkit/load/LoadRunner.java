package io.testkit.load;

import io.testkit.core.TestKitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates virtual users using Java 21 virtual threads.
 * Supports ramp-up, sustained load, optional RPS throttling via a token-bucket, and cool-down.
 */
public final class LoadRunner {

    private static final Logger log = LoggerFactory.getLogger(LoadRunner.class);

    private final LoadConfig config;
    private final LoadScenario scenario;

    public LoadRunner(LoadConfig config, LoadScenario scenario) {
        this.config   = config;
        this.scenario = scenario;
    }

    public LoadMetrics run() {
        LoadMetrics metrics = new LoadMetrics();
        AtomicBoolean running = new AtomicBoolean(true);

        // Token bucket for RPS throttling
        RateLimiter rateLimiter = config.targetRps() > 0
                ? new RateLimiter(config.targetRps())
                : null;

        // Use virtual threads for cheap concurrency
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            metrics.recordStart();

            // Ramp up
            if (!config.rampUp().isZero()) {
                log.info("Load test: ramping up over {}ms", config.rampUp().toMillis());
                Thread.sleep(config.rampUp().toMillis());
            }

            // Launch all VUs
            List<Future<?>> vuFutures = new ArrayList<>();
            for (int vu = 0; vu < config.virtualUsers(); vu++) {
                Runnable iteration = scenario.newIteration();
                vuFutures.add(executor.submit(() -> {
                    while (running.get()) {
                        if (rateLimiter != null) rateLimiter.acquire();
                        long start = System.currentTimeMillis();
                        boolean success = true;
                        try {
                            iteration.run();
                        } catch (TestKitException | AssertionError e) {
                            success = false;
                            log.debug("VU iteration failed: {}", e.getMessage());
                        } catch (Exception e) {
                            success = false;
                            log.debug("VU iteration error: {}", e.getMessage());
                        }
                        long duration = System.currentTimeMillis() - start;
                        metrics.record(duration, success);
                    }
                }));
            }

            log.info("Load test running: {} VUs, {}s duration", config.virtualUsers(),
                    config.duration().getSeconds());
            Thread.sleep(config.duration().toMillis());
            running.set(false);

            // Wait for VUs to finish their current iteration
            for (Future<?> f : vuFutures) {
                try { f.get(10, TimeUnit.SECONDS); }
                catch (TimeoutException | ExecutionException ignored) {}
            }

            // Cool-down
            if (!config.coolDown().isZero()) {
                log.info("Load test: cooling down {}ms", config.coolDown().toMillis());
                Thread.sleep(config.coolDown().toMillis());
            }

            metrics.recordEnd();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TestKitException("Load test interrupted", e);
        }

        return metrics;
    }

    // ── Token-bucket rate limiter ─────────────────────────────────────────────

    private static final class RateLimiter {
        private final long intervalNs;
        private long nextPermitNs;

        RateLimiter(int rps) {
            this.intervalNs  = 1_000_000_000L / rps;
            this.nextPermitNs = System.nanoTime();
        }

        synchronized void acquire() {
            long now = System.nanoTime();
            long wait = nextPermitNs - now;
            if (wait > 0) {
                try { Thread.sleep(wait / 1_000_000, (int)(wait % 1_000_000)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            nextPermitNs = Math.max(System.nanoTime(), nextPermitNs) + intervalNs;
        }
    }
}

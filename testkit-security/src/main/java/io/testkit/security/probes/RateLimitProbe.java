package io.testkit.security.probes;

import io.testkit.api.ApiConfig;
import io.testkit.security.SecurityFinding;
import io.testkit.security.SecurityProbe;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sends a burst of requests to check if rate limiting is enforced.
 * If all requests succeed with 2xx, it flags the endpoint as unprotected.
 */
public final class RateLimitProbe implements SecurityProbe {

    private static final Logger log = LoggerFactory.getLogger(RateLimitProbe.class);

    private final int burstCount;
    private final long delayBetweenMs;

    public RateLimitProbe() { this(30, 10); }

    public RateLimitProbe(int burstCount, long delayBetweenMs) {
        this.burstCount      = burstCount;
        this.delayBetweenMs  = delayBetweenMs;
    }

    @Override
    public String name() { return "Rate Limit"; }

    @Override
    public List<SecurityFinding> probe(String targetPath, ApiConfig apiConfig) {
        List<SecurityFinding> findings = new ArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(apiConfig.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(apiConfig.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .build();

        String base = apiConfig.baseUrl().endsWith("/")
                ? apiConfig.baseUrl().substring(0, apiConfig.baseUrl().length() - 1)
                : apiConfig.baseUrl();
        String url = base + targetPath;

        int successCount = 0;
        int rateLimitedCount = 0;

        for (int i = 0; i < burstCount; i++) {
            try {
                Request req = new Request.Builder().url(url).get().build();
                try (Response response = client.newCall(req).execute()) {
                    int status = response.code();
                    if (status == 429 || status == 503) rateLimitedCount++;
                    else if (status < 400) successCount++;
                }
                if (delayBetweenMs > 0) Thread.sleep(delayBetweenMs);
            } catch (IOException | InterruptedException e) {
                log.debug("Rate limit probe request failed: {}", e.getMessage());
            }
        }

        if (rateLimitedCount == 0 && successCount >= burstCount * 0.9) {
            findings.add(new SecurityFinding(
                    name(),
                    SecurityFinding.Severity.MEDIUM,
                    "No rate limiting detected: %d/%d requests succeeded without 429/503"
                            .formatted(successCount, burstCount),
                    targetPath, "burst of " + burstCount + " requests",
                    successCount + " successful, " + rateLimitedCount + " rate-limited"
            ));
            log.warn("Rate limit not enforced at {}", targetPath);
        }

        return findings;
    }
}

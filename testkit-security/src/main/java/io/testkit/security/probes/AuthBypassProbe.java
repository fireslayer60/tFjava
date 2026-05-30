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
 * Checks that protected endpoints properly reject unauthenticated / tampered requests.
 * A protected endpoint returning 200 without valid credentials is a critical finding.
 */
public final class AuthBypassProbe implements SecurityProbe {

    private static final Logger log = LoggerFactory.getLogger(AuthBypassProbe.class);

    @Override
    public String name() { return "Auth Bypass"; }

    @Override
    public List<SecurityFinding> probe(String targetPath, ApiConfig apiConfig) {
        List<SecurityFinding> findings = new ArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(apiConfig.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(apiConfig.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .build();

        String base = apiConfig.baseUrl().endsWith("/")
                ? apiConfig.baseUrl().substring(0, apiConfig.baseUrl().length() - 1)
                : apiConfig.baseUrl();
        String url = base + targetPath;

        // 1. No auth header
        findings.addAll(checkRequest(client, url, null, "No Authorization header"));

        // 2. Invalid bearer token
        findings.addAll(checkRequest(client, url, "Bearer invalid.token.here", "Invalid Bearer token"));

        // 3. Empty bearer
        findings.addAll(checkRequest(client, url, "Bearer ", "Empty Bearer token"));

        // 4. Algorithm-none JWT (alg:none attack)
        findings.addAll(checkRequest(client, url,
                "Bearer eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJzdWIiOiJhZG1pbiJ9.",
                "JWT alg:none attack"));

        return findings;
    }

    private List<SecurityFinding> checkRequest(OkHttpClient client, String url,
                                                String authHeader, String scenario) {
        List<SecurityFinding> findings = new ArrayList<>();
        Request.Builder rb = new Request.Builder().url(url).get();
        if (authHeader != null) rb.header("Authorization", authHeader);

        try (Response response = client.newCall(rb.build()).execute()) {
            int status = response.code();
            if (status == 200 || status == 201) {
                findings.add(new SecurityFinding(
                        name(),
                        SecurityFinding.Severity.CRITICAL,
                        "Auth bypass: endpoint accessible with scenario '" + scenario + "'",
                        url, authHeader != null ? authHeader : "(no token)",
                        "HTTP " + status + " returned — expected 401/403"
                ));
                log.error("AUTH BYPASS detected at {} — scenario: {}", url, scenario);
            }
        } catch (IOException e) {
            log.debug("Auth bypass probe failed for {}: {}", scenario, e.getMessage());
        }
        return findings;
    }
}

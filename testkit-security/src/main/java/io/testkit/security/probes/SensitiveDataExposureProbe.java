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
import java.util.regex.Pattern;

/**
 * Scans response bodies for common sensitive data patterns
 * (PAN numbers, SSN, raw passwords, private keys, AWS keys, etc.).
 */
public final class SensitiveDataExposureProbe implements SecurityProbe {

    private static final Logger log = LoggerFactory.getLogger(SensitiveDataExposureProbe.class);

    private record Detector(String name, Pattern pattern, SecurityFinding.Severity severity) {}

    private static final List<Detector> DETECTORS = List.of(
            new Detector("Credit card number",
                    Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13})\\b"),
                    SecurityFinding.Severity.CRITICAL),
            new Detector("SSN",
                    Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
                    SecurityFinding.Severity.HIGH),
            new Detector("AWS Access Key",
                    Pattern.compile("AKIA[0-9A-Z]{16}"),
                    SecurityFinding.Severity.CRITICAL),
            new Detector("Private key header",
                    Pattern.compile("-----BEGIN (RSA |EC )?PRIVATE KEY-----"),
                    SecurityFinding.Severity.CRITICAL),
            new Detector("Plaintext password field",
                    Pattern.compile("\"password\"\\s*:\\s*\"[^\"]{6,}\""),
                    SecurityFinding.Severity.HIGH),
            new Detector("Internal stack trace",
                    Pattern.compile("at \\w+\\.\\w+\\(\\w+\\.java:\\d+\\)"),
                    SecurityFinding.Severity.MEDIUM)
    );

    @Override
    public String name() { return "Sensitive Data Exposure"; }

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

        try {
            Request req = new Request.Builder().url(base + targetPath).get().build();
            try (Response response = client.newCall(req).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                for (Detector d : DETECTORS) {
                    if (d.pattern().matcher(body).find()) {
                        findings.add(new SecurityFinding(
                                name(), d.severity(),
                                d.name() + " pattern detected in response",
                                targetPath, "(GET request)", "Pattern: " + d.pattern()
                        ));
                        log.warn("{} detected at {}", d.name(), targetPath);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Sensitive data probe failed: {}", e.getMessage());
        }
        return findings;
    }
}

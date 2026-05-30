package io.testkit.security.probes;

import io.testkit.api.ApiConfig;
import io.testkit.security.SecurityFinding;
import io.testkit.security.SecurityProbe;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fires XSS payloads at endpoints and checks if unescaped payload is reflected in the response.
 * Identifies reflected XSS — stored XSS requires a read-back step.
 */
public final class XssProbe implements SecurityProbe {

    private static final Logger log = LoggerFactory.getLogger(XssProbe.class);

    private static final List<String> PAYLOADS = List.of(
            "<script>alert(1)</script>",
            "\"><script>alert(1)</script>",
            "javascript:alert(1)",
            "<img src=x onerror=alert(1)>",
            "<svg onload=alert(1)>",
            "';alert(1)//",
            "<body onload=alert(1)>",
            "<<SCRIPT>alert('XSS');//<</SCRIPT>"
    );

    private final String paramName;

    public XssProbe() { this("q"); }
    public XssProbe(String paramName) { this.paramName = paramName; }

    @Override
    public String name() { return "Reflected XSS"; }

    @Override
    public List<SecurityFinding> probe(String targetPath, ApiConfig apiConfig) {
        List<SecurityFinding> findings = new ArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(apiConfig.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(apiConfig.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .build();

        for (String payload : PAYLOADS) {
            try {
                String encoded = java.net.URLEncoder.encode(payload, StandardCharsets.UTF_8);
                String base    = apiConfig.baseUrl().endsWith("/")
                        ? apiConfig.baseUrl().substring(0, apiConfig.baseUrl().length() - 1)
                        : apiConfig.baseUrl();
                String url = base + targetPath + "?" + paramName + "=" + encoded;

                Request req = new Request.Builder().url(url).get().build();
                try (Response response = client.newCall(req).execute()) {
                    String body = response.body() != null ? response.body().string() : "";
                    String contentType = response.header("Content-Type", "");

                    // If HTML/JS response reflects the payload unencoded → reflected XSS
                    if ((contentType.contains("text/html") || contentType.contains("application/javascript"))
                            && body.contains(payload)) {
                        findings.add(new SecurityFinding(
                                name(),
                                SecurityFinding.Severity.HIGH,
                                "Reflected XSS: unescaped payload echoed back in " + contentType + " response",
                                targetPath, payload,
                                "Response body contained raw payload"
                        ));
                        log.warn("Reflected XSS at {} with payload: {}", targetPath, payload);
                    }
                }
            } catch (IOException e) {
                log.debug("XSS probe request failed: {}", e.getMessage());
            }
        }
        return findings;
    }
}

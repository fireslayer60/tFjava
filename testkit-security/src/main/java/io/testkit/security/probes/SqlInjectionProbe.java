package io.testkit.security.probes;

import io.testkit.api.ApiConfig;
import io.testkit.api.ApiResponse;
import io.testkit.api.HttpMethod;
import io.testkit.api.RequestSpec;
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
 * Fires common SQL injection payloads at an endpoint and looks for error signatures
 * (stack traces, SQL keywords in response bodies, 500 status codes with DB fingerprints).
 */
public final class SqlInjectionProbe implements SecurityProbe {

    private static final Logger log = LoggerFactory.getLogger(SqlInjectionProbe.class);

    private static final List<String> PAYLOADS = List.of(
            "'",
            "' OR '1'='1",
            "' OR '1'='1' --",
            "\" OR \"1\"=\"1",
            "1; DROP TABLE users; --",
            "1' AND SLEEP(0) --",
            "1 UNION SELECT NULL --",
            "' OR 1=1 LIMIT 1 --",
            "admin'--",
            "1' ORDER BY 1--+",
            "' AND 1=CONVERT(int,(SELECT TOP 1 table_name FROM information_schema.tables)) --"
    );

    private static final List<String> ERROR_SIGNATURES = List.of(
            "sql syntax", "mysql_fetch", "ora-", "pg_query", "sqlite_",
            "syntax error", "unclosed quotation", "sqlexception", "sqlstate",
            "jdbctemplate", "hibernate", "jpa", "column", "table", "database error"
    );

    private final HttpMethod method;
    private final String paramName;

    public SqlInjectionProbe() {
        this(HttpMethod.GET, "id");
    }

    public SqlInjectionProbe(HttpMethod method, String paramName) {
        this.method    = method;
        this.paramName = paramName;
    }

    @Override
    public String name() { return "SQL Injection"; }

    @Override
    public List<SecurityFinding> probe(String targetPath, ApiConfig apiConfig) {
        List<SecurityFinding> findings = new ArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(apiConfig.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(apiConfig.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .build();

        for (String payload : PAYLOADS) {
            try {
                String url = buildUrl(apiConfig.baseUrl(), targetPath, payload);
                Request request = new Request.Builder().url(url).get().build();

                try (Response response = client.newCall(request).execute()) {
                    String body = response.body() != null ? response.body().string().toLowerCase() : "";
                    int status  = response.code();

                    boolean suspicious = (status == 500 || status == 400) &&
                            ERROR_SIGNATURES.stream().anyMatch(body::contains);

                    if (suspicious) {
                        findings.add(new SecurityFinding(
                                name(),
                                SecurityFinding.Severity.HIGH,
                                "Possible SQL injection: server error with DB error signature detected",
                                targetPath, payload,
                                "HTTP " + status + " — body contains error signature"
                        ));
                        log.warn("SQL injection candidate found at {} with payload: {}", targetPath, payload);
                    }
                }
            } catch (IOException e) {
                log.debug("SQLi probe request failed: {}", e.getMessage());
            }
        }
        return findings;
    }

    private String buildUrl(String baseUrl, String path, String payload) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String full = base + path;
        return full + (full.contains("?") ? "&" : "?") + paramName + "=" +
                java.net.URLEncoder.encode(payload, java.nio.charset.StandardCharsets.UTF_8);
    }
}

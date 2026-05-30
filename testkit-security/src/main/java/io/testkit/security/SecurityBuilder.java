package io.testkit.security;

import io.testkit.api.ApiConfig;
import io.testkit.core.TestKitContext;
import io.testkit.core.TestKitException;
import io.testkit.security.probes.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent DSL for declarative security scanning.
 *
 * <pre>{@code
 * .security(s -> s
 *     .baseUrl("http://localhost:8080")
 *     .endpoint("/api/users/{id}")
 *     .sqlInjection()
 *     .xss()
 *     .authBypass("/api/admin/users")
 *     .rateLimit("/api/auth/login")
 *     .sensitiveDataExposure()
 *     .failOnSeverity(SecurityFinding.Severity.HIGH))
 * }</pre>
 */
public final class SecurityBuilder {

    private ApiConfig apiConfig;
    private final List<ScanTarget> scanTargets = new ArrayList<>();
    private SecurityFinding.Severity failOnSeverity = SecurityFinding.Severity.HIGH;
    private boolean failOnAnyFinding = false;

    public SecurityBuilder baseUrl(String url) {
        this.apiConfig = ApiConfig.builder().baseUrl(url).build();
        return this;
    }

    public SecurityBuilder apiConfig(ApiConfig cfg) {
        this.apiConfig = cfg;
        return this;
    }

    public SecurityBuilder failOnSeverity(SecurityFinding.Severity severity) {
        this.failOnSeverity = severity;
        return this;
    }

    public SecurityBuilder failOnAnyFinding() {
        this.failOnAnyFinding = true;
        return this;
    }

    // ── Endpoint + probe shortcuts ────────────────────────────────────────────

    public SecurityBuilder sqlInjection(String endpoint) {
        scanTargets.add(new ScanTarget(endpoint, new SqlInjectionProbe()));
        return this;
    }

    public SecurityBuilder xss(String endpoint) {
        scanTargets.add(new ScanTarget(endpoint, new XssProbe()));
        return this;
    }

    public SecurityBuilder authBypass(String protectedEndpoint) {
        scanTargets.add(new ScanTarget(protectedEndpoint, new AuthBypassProbe()));
        return this;
    }

    public SecurityBuilder rateLimit(String endpoint) {
        scanTargets.add(new ScanTarget(endpoint, new RateLimitProbe()));
        return this;
    }

    public SecurityBuilder sensitiveDataExposure(String endpoint) {
        scanTargets.add(new ScanTarget(endpoint, new SensitiveDataExposureProbe()));
        return this;
    }

    /** Run ALL probes against one endpoint (full scan). */
    public SecurityBuilder fullScan(String endpoint) {
        sqlInjection(endpoint);
        xss(endpoint);
        sensitiveDataExposure(endpoint);
        rateLimit(endpoint);
        return this;
    }

    /** Add a custom probe. */
    public SecurityBuilder probe(String endpoint, SecurityProbe probe) {
        scanTargets.add(new ScanTarget(endpoint, probe));
        return this;
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    SecurityReport executeSecurity(TestKitContext ctx) {
        if (apiConfig == null) apiConfig = ApiConfig.defaults();
        List<SecurityFinding> allFindings = new ArrayList<>();

        for (ScanTarget target : scanTargets) {
            List<SecurityFinding> findings = target.probe().probe(target.endpoint(), apiConfig);
            allFindings.addAll(findings);
        }

        SecurityReport report = new SecurityReport(allFindings);
        report.print();

        if (failOnAnyFinding && !allFindings.isEmpty()) {
            throw new TestKitException("Security scan found " + allFindings.size() + " issue(s):\n" +
                    allFindings.stream().map(SecurityFinding::toString)
                            .reduce("", (a, b) -> a + "\n  • " + b));
        }

        List<SecurityFinding> criticalFindings = allFindings.stream()
                .filter(f -> f.severity().ordinal() <= failOnSeverity.ordinal())
                .toList();

        if (!criticalFindings.isEmpty()) {
            throw new TestKitException(
                    "Security scan found %d issue(s) at severity >= %s:\n  • %s".formatted(
                            criticalFindings.size(), failOnSeverity,
                            criticalFindings.stream().map(SecurityFinding::toString)
                                    .reduce("", (a, b) -> a + "\n  • " + b).strip()));
        }

        return report;
    }

    private record ScanTarget(String endpoint, SecurityProbe probe) {}
}

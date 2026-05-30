package io.testkit.workshop;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testkit.api.ApiConfig;
import io.testkit.core.TestKit;
import io.testkit.core.TestKitResult;
import io.testkit.security.*;
import io.testkit.security.probes.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EXERCISE 3 - Security probes.
 *
 * Goal: understand what each built-in probe does by observing findings against
 * deliberately misconfigured WireMock stubs.
 *
 * Key insight: SecurityProbe.probe() returns List<SecurityFinding>.
 * You can call probes directly without the full TestKit runner for unit-test style inspection.
 *
 * Run: mvn test -pl testkit-workshop -Dtest=Ex03_SecurityProbes
 */
class Ex03_SecurityProbes {

    private WireMockServer wire;
    private ApiConfig apiConfig;

    @BeforeEach
    void startWireMock() {
        wire = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wire.start();
        configureFor("localhost", wire.port());
        apiConfig = ApiConfig.builder()
                .baseUrl("http://localhost:" + wire.port())
                .build();
    }

    @AfterEach
    void stopWireMock() {
        wire.stop();
    }

    // -- SqlInjectionProbe -------------------------------------------------------

    @Test
    void sqlInjectionProbeDetectsErrorLeak() {
        // Probe fires 11 SQL injection payloads as ?id=<payload>.
        // HIGH finding if server returns 500 AND body contains DB error keywords.
        stubFor(get(urlMatching("/api/search\\?.*"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Error: sql syntax error near \"'\" at position 1")));

        SqlInjectionProbe probe = new SqlInjectionProbe();
        List<SecurityFinding> findings = probe.probe("/api/search", apiConfig);

        System.out.println("\nSQL Injection findings:");
        findings.forEach(f -> System.out.printf("  [%s] %s -- payload: %s%n",
                f.severity(), f.description(), f.payload()));

        assertFalse(findings.isEmpty(), "Should find SQL injection vulnerability");
        assertEquals(SecurityFinding.Severity.HIGH, findings.get(0).severity());
    }

    @Test
    void sqlInjectionProbePassesOnSafeServer() {
        // Safe server returns 400 with no DB error message in body
        stubFor(get(urlMatching("/api/search\\?.*"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withBody("{\"error\":\"invalid input\"}")));

        List<SecurityFinding> findings = new SqlInjectionProbe().probe("/api/search", apiConfig);
        assertTrue(findings.isEmpty(), "No findings on safe server");
    }

    // -- XssProbe ----------------------------------------------------------------

    @Test
    void xssProbeDetectsReflection() {
        // XssProbe fires payloads like <script>alert('xss')</script> as query params.
        // Finding if raw payload appears verbatim in the response body.
        // XssProbe only flags when Content-Type is text/html or application/javascript
        stubFor(get(urlMatching("/api/feedback\\?.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html>You said: <script>alert(1)</script></html>")));

        List<SecurityFinding> findings = new XssProbe().probe("/api/feedback", apiConfig);

        System.out.println("\nXSS findings: " + findings.size());
        findings.forEach(f -> System.out.printf("  [%s] %s%n", f.severity(), f.description()));

        assertFalse(findings.isEmpty(), "Should detect reflected XSS");
    }

    // -- AuthBypassProbe ---------------------------------------------------------

    @Test
    void authBypassProbeDetectsMissingAuth() {
        // Probe sends 4 requests with no/invalid auth headers.
        // CRITICAL if ANY returns 200/201 (endpoint is unprotected).
        stubFor(get("/api/admin/users")
                .willReturn(aResponse()
                        .withStatus(200)   // BAD: should be 401/403
                        .withBody("{\"users\":[{\"id\":1,\"role\":\"admin\"}]}")));

        List<SecurityFinding> findings = new AuthBypassProbe().probe("/api/admin/users", apiConfig);

        assertFalse(findings.isEmpty());
        assertEquals(SecurityFinding.Severity.CRITICAL, findings.get(0).severity());
        System.out.println("\nAuth bypass: " + findings.get(0).description());
    }

    @Test
    void authBypassProbePassesOnProperlyProtectedEndpoint() {
        // Properly secured endpoint returns 401 for all unauthenticated requests
        stubFor(get("/api/admin/users")
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody("{\"error\":\"Unauthorized\"}")));

        List<SecurityFinding> findings = new AuthBypassProbe().probe("/api/admin/users", apiConfig);
        assertTrue(findings.isEmpty(), "No findings when endpoint properly returns 401");
    }

    // -- RateLimitProbe ----------------------------------------------------------

    @Test
    void rateLimitProbeDetectsMissingRateLimit() {
        // Probe fires 50 rapid requests. MEDIUM finding if no 429 is ever returned.
        stubFor(get("/api/auth/login")
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        List<SecurityFinding> findings = new RateLimitProbe().probe("/api/auth/login", apiConfig);

        assertFalse(findings.isEmpty(), "Should detect missing rate limit");
        assertEquals(SecurityFinding.Severity.MEDIUM, findings.get(0).severity());
        System.out.println("\nRate limit finding: " + findings.get(0).description());
    }

    // -- SensitiveDataExposureProbe ----------------------------------------------

    @Test
    void sensitiveDataExposureProbeDetectsSecrets() {
        // Probe GETs the endpoint and regex-scans for: credit cards, SSNs,
        // AWS keys, private keys, plaintext passwords in JSON, Java stack traces.
        stubFor(get("/api/debug/config")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"region\":\"us-east-1\",\"accessKey\":\"AKIAIOSFODNN7EXAMPLE\"}")));

        List<SecurityFinding> findings = new SensitiveDataExposureProbe()
                .probe("/api/debug/config", apiConfig);

        // AWS Access Key is classified as CRITICAL (not HIGH) — see SensitiveDataExposureProbe
        assertFalse(findings.isEmpty(), "Should detect AWS key in response");
        assertEquals(SecurityFinding.Severity.CRITICAL, findings.get(0).severity());
        System.out.println("\nSensitive data finding: " + findings.get(0).description());
    }

    // -- Custom probe via SecurityProbe SPI --------------------------------------

    @Test
    void customProbeCanBeRegistered() {
        // Any class implementing SecurityProbe can plug in - this is the SPI extension point.
        stubFor(get(urlMatching("/api/orders\\?.*"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        SecurityProbe corsProbe = new SecurityProbe() {
            @Override public String name() { return "CORS check"; }

            @Override
            public List<SecurityFinding> probe(String targetPath, ApiConfig cfg) {
                // Real impl: send OPTIONS and check Access-Control-Allow-Origin
                return List.of(new SecurityFinding(
                        name(), SecurityFinding.Severity.INFO,
                        "CORS policy not validated - add real check here",
                        targetPath, "OPTIONS", "no evidence"));
            }
        };

        List<SecurityFinding> findings = corsProbe.probe("/api/orders", apiConfig);
        assertEquals(1, findings.size());
        assertEquals(SecurityFinding.Severity.INFO, findings.get(0).severity());
    }

    // -- SecurityStep in full pipeline -------------------------------------------

    @Test
    void securityStepInPipeline() {
        // failOnSeverity controls what severity causes the STEP to fail.
        // A stack trace leak is MEDIUM; threshold is CRITICAL -> step PASSES.
        stubFor(get(urlMatching("/api/products\\?.*"))
                .willReturn(aResponse().withStatus(500)
                        .withBody("at com.example.ProductService.getAll(ProductService.java:42)")));

        TestKitResult result = TestKit.test("Security scan")
                .config(c -> c.baseUrl("http://localhost:" + wire.port()).failFast(false))
                .step(new io.testkit.security.SecurityStep(s -> s
                        .sensitiveDataExposure("/api/products")
                        .failOnSeverity(SecurityFinding.Severity.CRITICAL)))
                .run();

        // Stack trace = MEDIUM, threshold is CRITICAL -> step should PASS
        assertTrue(result.passed(), "MEDIUM finding should not fail when threshold is CRITICAL");
    }
}

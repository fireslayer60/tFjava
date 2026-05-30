package io.testkit.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bind TestKit settings from {@code application.yml} / {@code application.properties}.
 *
 * <pre>
 * testkit:
 *   base-url: http://localhost:${local.server.port}
 *   timeout: 10s
 *   fail-fast: true
 *   verbose: false
 * </pre>
 */
@ConfigurationProperties(prefix = "testkit")
public final class TestKitProperties {

    private String baseUrl = "http://localhost:8080";
    private Duration timeout = Duration.ofSeconds(10);
    private boolean failFast = true;
    private boolean verbose = false;

    public String getBaseUrl()              { return baseUrl; }
    public void setBaseUrl(String baseUrl)  { this.baseUrl = baseUrl; }

    public Duration getTimeout()            { return timeout; }
    public void setTimeout(Duration t)      { this.timeout = t; }

    public boolean isFailFast()             { return failFast; }
    public void setFailFast(boolean ff)     { this.failFast = ff; }

    public boolean isVerbose()              { return verbose; }
    public void setVerbose(boolean v)       { this.verbose = v; }
}

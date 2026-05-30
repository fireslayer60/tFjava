package io.testkit.core;

import java.time.Duration;

/** Global TestKit configuration — base URL, timeouts, fail-fast behaviour, etc. */
public final class TestKitConfig {

    private final String baseUrl;
    private final Duration defaultTimeout;
    private final boolean failFast;
    private final boolean verbose;

    private TestKitConfig(Builder b) {
        this.baseUrl        = b.baseUrl;
        this.defaultTimeout = b.defaultTimeout;
        this.failFast       = b.failFast;
        this.verbose        = b.verbose;
    }

    public static Builder builder() { return new Builder(); }

    public static TestKitConfig defaults() { return builder().build(); }

    public String baseUrl()            { return baseUrl; }
    public Duration defaultTimeout()   { return defaultTimeout; }
    public boolean failFast()          { return failFast; }
    public boolean verbose()           { return verbose; }

    public static final class Builder {
        private String baseUrl       = "http://localhost:8080";
        private Duration defaultTimeout = Duration.ofSeconds(10);
        private boolean failFast     = true;
        private boolean verbose      = false;

        public Builder baseUrl(String url)           { this.baseUrl = url; return this; }
        public Builder timeout(Duration t)           { this.defaultTimeout = t; return this; }
        public Builder failFast(boolean ff)          { this.failFast = ff; return this; }
        public Builder verbose(boolean v)            { this.verbose = v; return this; }

        public TestKitConfig build() { return new TestKitConfig(this); }
    }
}

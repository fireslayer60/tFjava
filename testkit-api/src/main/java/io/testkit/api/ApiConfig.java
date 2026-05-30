package io.testkit.api;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-step or global HTTP client configuration. */
public final class ApiConfig {

    private final String baseUrl;
    private final Duration timeout;
    private final Map<String, String> defaultHeaders;
    private final boolean followRedirects;

    private ApiConfig(Builder b) {
        this.baseUrl        = b.baseUrl;
        this.timeout        = b.timeout;
        this.defaultHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(b.defaultHeaders));
        this.followRedirects = b.followRedirects;
    }

    public static Builder builder() { return new Builder(); }

    public static ApiConfig defaults() { return builder().build(); }

    public String baseUrl()                    { return baseUrl; }
    public Duration timeout()                  { return timeout; }
    public Map<String, String> defaultHeaders(){ return defaultHeaders; }
    public boolean followRedirects()           { return followRedirects; }

    public static final class Builder {
        private String baseUrl = "http://localhost:8080";
        private Duration timeout = Duration.ofSeconds(10);
        private final Map<String, String> defaultHeaders = new LinkedHashMap<>();
        private boolean followRedirects = true;

        public Builder baseUrl(String url)               { this.baseUrl = url; return this; }
        public Builder timeout(Duration t)               { this.timeout = t; return this; }
        public Builder header(String name, String value) { defaultHeaders.put(name, value); return this; }
        public Builder bearerAuth(String token)          { return header("Authorization", "Bearer " + token); }
        public Builder basicAuth(String user, String pass) {
            String encoded = java.util.Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
            return header("Authorization", "Basic " + encoded);
        }
        public Builder followRedirects(boolean f)        { this.followRedirects = f; return this; }

        public ApiConfig build() { return new ApiConfig(this); }
    }
}

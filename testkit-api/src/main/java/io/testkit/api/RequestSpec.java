package io.testkit.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable snapshot of an HTTP request specification. */
public final class RequestSpec {

    private final HttpMethod method;
    private final String path;
    private final String baseUrl;
    private final Map<String, String> headers;
    private final Map<String, String> pathParams;
    private final Map<String, String> queryParams;
    private final String jsonBody;
    private final byte[] rawBody;
    private final Map<String, String> formParams;

    private RequestSpec(Builder b) {
        this.method      = b.method;
        this.path        = b.path;
        this.baseUrl     = b.baseUrl;
        this.headers     = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        this.pathParams  = Collections.unmodifiableMap(new LinkedHashMap<>(b.pathParams));
        this.queryParams = Collections.unmodifiableMap(new LinkedHashMap<>(b.queryParams));
        this.jsonBody    = b.jsonBody;
        this.rawBody     = b.rawBody;
        this.formParams  = b.formParams != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(b.formParams))
                : Map.of();
    }

    public HttpMethod method()              { return method; }
    public String path()                    { return path; }
    public String baseUrl()                 { return baseUrl; }
    public Map<String, String> headers()    { return headers; }
    public Map<String, String> pathParams() { return pathParams; }
    public Map<String, String> queryParams(){ return queryParams; }
    public String jsonBody()                { return jsonBody; }
    public byte[] rawBody()                 { return rawBody; }
    public Map<String, String> formParams() { return formParams; }

    public static Builder method(HttpMethod method, String path) {
        return new Builder(method, path);
    }

    public static final class Builder {
        private final HttpMethod method;
        private final String path;
        private String baseUrl;
        private final Map<String, String> headers     = new LinkedHashMap<>();
        private final Map<String, String> pathParams  = new LinkedHashMap<>();
        private final Map<String, String> queryParams = new LinkedHashMap<>();
        private String jsonBody;
        private byte[] rawBody;
        private Map<String, String> formParams;

        private Builder(HttpMethod method, String path) {
            this.method = method;
            this.path   = path;
        }

        public Builder baseUrl(String url)                   { this.baseUrl = url; return this; }
        public Builder header(String name, String value)     { headers.put(name, value); return this; }
        public Builder pathParam(String name, String value)  { pathParams.put(name, value); return this; }
        public Builder query(String name, String value)      { queryParams.put(name, value); return this; }
        public Builder json(String body)                     { this.jsonBody = body; return this; }
        public Builder raw(byte[] body)                      { this.rawBody = body; return this; }
        public Builder formParam(String name, String value)  {
            if (formParams == null) formParams = new LinkedHashMap<>();
            formParams.put(name, value); return this;
        }
        public Builder bearerToken(String token) {
            return header("Authorization", "Bearer " + token);
        }
        public RequestSpec build() { return new RequestSpec(this); }
    }
}

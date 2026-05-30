package io.testkit.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testkit.core.TestKitContext;
import io.testkit.core.TestKitException;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Fluent DSL for a single HTTP request-response cycle.
 *
 * <pre>{@code
 * .api(a -> a
 *     .POST("/api/users")
 *     .json("""{"email":"x@y.com","pass":"secret"}""")
 *     .expect(201)
 *     .extract("userId", "$.id")
 *     .assertJsonPath("$.email", "x@y.com"))
 * }</pre>
 */
public final class ApiBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpMethod method;
    private String path;
    private String baseUrl;
    private final Map<String, String> headers     = new LinkedHashMap<>();
    private final Map<String, Object> pathParams  = new LinkedHashMap<>(); // Object to support ctx-fn
    private final Map<String, String> queryParams = new LinkedHashMap<>();
    private Object jsonBody;       // String literal or context-fn
    private byte[] rawBody;
    private final Map<String, String> formParams  = new LinkedHashMap<>();
    private String bearerToken;

    // Expected assertions
    private int expectedStatus = -1;
    private final List<Consumer<ApiResponse>> assertions = new ArrayList<>();

    // Extractions: ctx-key → json-path
    private final Map<String, String> extractions = new LinkedHashMap<>();

    // Optional post-response callback
    private Consumer<ApiResponse> onResponse;

    private ApiConfig config;

    // ── HTTP verb shortcuts ───────────────────────────────────────────────────

    public ApiBuilder GET(String path)    { this.method = HttpMethod.GET;     this.path = path; return this; }
    public ApiBuilder POST(String path)   { this.method = HttpMethod.POST;    this.path = path; return this; }
    public ApiBuilder PUT(String path)    { this.method = HttpMethod.PUT;     this.path = path; return this; }
    public ApiBuilder PATCH(String path)  { this.method = HttpMethod.PATCH;   this.path = path; return this; }
    public ApiBuilder DELETE(String path) { this.method = HttpMethod.DELETE;  this.path = path; return this; }

    // ── Request config ────────────────────────────────────────────────────────

    public ApiBuilder baseUrl(String url)              { this.baseUrl = url; return this; }
    public ApiBuilder header(String name, String value){ headers.put(name, value); return this; }
    public ApiBuilder bearerToken(String token)        { this.bearerToken = token; return this; }

    /** Static path param. */
    public ApiBuilder pathParam(String name, String value) { pathParams.put(name, value); return this; }

    /** Dynamic path param resolved from context at execution time. */
    public ApiBuilder pathParam(String name, Function<TestKitContext, String> fn) {
        pathParams.put(name, fn); return this;
    }

    public ApiBuilder query(String name, String value) { queryParams.put(name, value); return this; }
    public ApiBuilder formParam(String name, String value){ formParams.put(name, value); return this; }

    /** JSON body as a raw string. */
    public ApiBuilder json(String body) { this.jsonBody = body; return this; }

    /** JSON body as a POJO — serialised by Jackson. */
    public ApiBuilder json(Object pojo) {
        try {
            this.jsonBody = MAPPER.writeValueAsString(pojo);
        } catch (Exception e) {
            throw new TestKitException("Failed to serialise body to JSON: " + e.getMessage(), e);
        }
        return this;
    }

    /** JSON body resolved from context at execution time. */
    public ApiBuilder json(Function<TestKitContext, String> fn) { this.jsonBody = fn; return this; }

    public ApiBuilder rawBody(byte[] body) { this.rawBody = body; return this; }

    public ApiBuilder config(ApiConfig cfg) { this.config = cfg; return this; }

    // ── Assertions ────────────────────────────────────────────────────────────

    public ApiBuilder expect(int status) { this.expectedStatus = status; return this; }

    public ApiBuilder assertBody(String substring) {
        assertions.add(r -> r.assertBodyContains(substring));
        return this;
    }

    public ApiBuilder assertJsonPath(String path, Object expected) {
        assertions.add(r -> r.assertJsonPath(path, expected));
        return this;
    }

    public ApiBuilder assertJsonPathPresent(String path) {
        assertions.add(r -> r.assertJsonPathPresent(path));
        return this;
    }

    public ApiBuilder assertHeader(String name, String expected) {
        assertions.add(r -> r.assertHeader(name, expected));
        return this;
    }

    public ApiBuilder assertContentType(String expected) {
        assertions.add(r -> r.assertContentType(expected));
        return this;
    }

    /** Custom assertion with full response access. */
    public ApiBuilder assertThat(Consumer<ApiResponse> assertion) {
        assertions.add(assertion);
        return this;
    }

    // ── Extraction ────────────────────────────────────────────────────────────

    /** Extract a JSONPath value from the response and store it in the context under {@code key}. */
    public ApiBuilder extract(String key, String jsonPath) {
        extractions.put(key, jsonPath);
        return this;
    }

    /** Custom response handler (does not affect assertions). */
    public ApiBuilder onResponse(Consumer<ApiResponse> handler) {
        this.onResponse = handler;
        return this;
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    public ApiResponse executeWith(TestKitContext ctx, ApiConfig defaultConfig) {
        ApiConfig effective = config != null ? config : defaultConfig;
        HttpTestClient client = new HttpTestClient(effective);

        RequestSpec.Builder specBuilder = RequestSpec.method(method, path).baseUrl(baseUrl);

        headers.forEach(specBuilder::header);
        queryParams.forEach(specBuilder::query);
        formParams.forEach(specBuilder::formParam);

        if (bearerToken != null) specBuilder.bearerToken(bearerToken);

        // Resolve path params
        pathParams.forEach((name, valueOrFn) -> {
            String resolved = ctx.resolve(valueOrFn).toString();
            specBuilder.pathParam(name, resolved);
        });

        // Resolve body
        if (jsonBody != null) {
            String body = ctx.resolve(jsonBody).toString();
            specBuilder.json(body);
        } else if (rawBody != null) {
            specBuilder.raw(rawBody);
        }

        ApiResponse response = client.execute(specBuilder.build());

        if (expectedStatus >= 0) response.assertStatus(expectedStatus);
        assertions.forEach(a -> a.accept(response));

        // Extract values into context
        extractions.forEach((key, jp) -> ctx.put(key, response.jsonPathString(jp)));

        if (onResponse != null) onResponse.accept(response);

        return response;
    }
}

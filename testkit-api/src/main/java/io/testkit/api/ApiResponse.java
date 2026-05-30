package io.testkit.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import io.testkit.core.TestKitException;

import java.util.List;
import java.util.Map;

/** Immutable snapshot of an HTTP response with rich extraction and assertion helpers. */
public final class ApiResponse {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Configuration JSONPATH_CONFIG = Configuration.defaultConfiguration()
            .addOptions(Option.SUPPRESS_EXCEPTIONS);

    private final int status;
    private final Map<String, List<String>> headers;
    private final String body;
    private final long durationMs;

    public ApiResponse(int status, Map<String, List<String>> headers, String body, long durationMs) {
        this.status     = status;
        this.headers    = Map.copyOf(headers);
        this.body       = body;
        this.durationMs = durationMs;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int status()        { return status; }
    public String body()       { return body; }
    public long durationMs()   { return durationMs; }

    public String header(String name) {
        List<String> vals = headers.getOrDefault(name.toLowerCase(), List.of());
        return vals.isEmpty() ? null : vals.get(0);
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    public JsonNode json() {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new TestKitException("Response body is not valid JSON: " + e.getMessage());
        }
    }

    /** Extract a value using a JSONPath expression (e.g. {@code "$.user.id"}). */
    @SuppressWarnings("unchecked")
    public <T> T jsonPath(String expression) {
        try {
            return JsonPath.using(JSONPATH_CONFIG).parse(body).read(expression);
        } catch (Exception e) {
            throw new TestKitException("JSONPath '" + expression + "' failed: " + e.getMessage());
        }
    }

    public String jsonPathString(String expression) {
        Object val = jsonPath(expression);
        return val == null ? null : val.toString();
    }

    // ── Assertions ────────────────────────────────────────────────────────────

    public ApiResponse assertStatus(int expected) {
        if (status != expected) {
            throw new TestKitException(
                    "Expected HTTP %d but got %d. Body: %s".formatted(expected, status, truncate(body, 500)));
        }
        return this;
    }

    public ApiResponse assertBodyContains(String substring) {
        if (!body.contains(substring)) {
            throw new TestKitException("Response body does not contain: " + substring);
        }
        return this;
    }

    public ApiResponse assertJsonPath(String expression, Object expected) {
        Object actual = jsonPath(expression);
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            throw new TestKitException(
                    "JSONPath '%s': expected '%s' but got '%s'".formatted(expression, expected, actual));
        }
        return this;
    }

    public ApiResponse assertJsonPathPresent(String expression) {
        Object val = jsonPath(expression);
        if (val == null) {
            throw new TestKitException("JSONPath '" + expression + "' was null/absent in response");
        }
        return this;
    }

    public ApiResponse assertHeader(String name, String expected) {
        String actual = header(name);
        if (!expected.equals(actual)) {
            throw new TestKitException(
                    "Header '%s': expected '%s' but got '%s'".formatted(name, expected, actual));
        }
        return this;
    }

    public ApiResponse assertContentType(String expected) {
        return assertHeader("content-type", expected);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }

    @Override
    public String toString() {
        return "ApiResponse[status=%d, durationMs=%d, body=%s]"
                .formatted(status, durationMs, truncate(body, 200));
    }
}

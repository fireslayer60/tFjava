package io.testkit.mock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testkit.core.TestKitContext;
import io.testkit.core.TestKitException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Fluent DSL for an embedded WireMock stub server.
 *
 * <pre>{@code
 * .mock(m -> m
 *     .port(9090)
 *     .stub(s -> s
 *         .GET("/api/products/123")
 *         .willReturn(200, """{"id":"123","name":"Widget","price":9.99}"""))
 *     .stub(s -> s
 *         .POST("/api/orders")
 *         .withBody("\"productId\":\"123\"")
 *         .willReturn(201, """{"orderId":"abc"}"""))
 *     .contextKey("mockBaseUrl"))  // store http://localhost:9090 in ctx
 * }</pre>
 */
public final class MockBuilder {

    private int port = 0;  // 0 = random
    private final List<Consumer<StubBuilder>> stubConfigs = new ArrayList<>();
    private String contextKey;

    public MockBuilder port(int port)          { this.port = port; return this; }
    public MockBuilder contextKey(String key)  { this.contextKey = key; return this; }

    public MockBuilder stub(Consumer<StubBuilder> stub) {
        stubConfigs.add(stub);
        return this;
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    ManagedMockServer startServer(TestKitContext ctx) {
        WireMockServer server = new WireMockServer(
                port > 0
                        ? WireMockConfiguration.wireMockConfig().port(port)
                        : WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();

        WireMock.configureFor("localhost", server.port());

        for (Consumer<StubBuilder> cfg : stubConfigs) {
            StubBuilder sb = new StubBuilder(server);
            cfg.accept(sb);
            sb.register();
        }

        String baseUrl = "http://localhost:" + server.port();
        if (contextKey != null) ctx.put(contextKey, baseUrl);

        return new ManagedMockServer(server, baseUrl);
    }

    // ── Stub builder ──────────────────────────────────────────────────────────

    public static final class StubBuilder {
        private final WireMockServer server;
        private String method;
        private String urlPattern;
        private String bodyContains;
        private final Map<String, String> responseHeaders = new LinkedHashMap<>(Map.of("Content-Type", "application/json"));
        private int statusCode = 200;
        private String responseBody = "{}";
        private long delayMs = 0;

        StubBuilder(WireMockServer server) { this.server = server; }

        public StubBuilder GET(String url)    { this.method = "GET";    this.urlPattern = url; return this; }
        public StubBuilder POST(String url)   { this.method = "POST";   this.urlPattern = url; return this; }
        public StubBuilder PUT(String url)    { this.method = "PUT";    this.urlPattern = url; return this; }
        public StubBuilder DELETE(String url) { this.method = "DELETE"; this.urlPattern = url; return this; }
        public StubBuilder PATCH(String url)  { this.method = "PATCH";  this.urlPattern = url; return this; }

        public StubBuilder withBody(String contains)          { this.bodyContains = contains; return this; }
        public StubBuilder header(String name, String value) { responseHeaders.put(name, value); return this; }
        public StubBuilder contentType(String ct)             { return header("Content-Type", ct); }
        public StubBuilder delay(long ms)                     { this.delayMs = ms; return this; }

        public StubBuilder willReturn(int status, Map<String, String> headers, String body) {
            this.statusCode = status;
            this.responseBody = body;
            this.responseHeaders.clear();
            if (headers != null) {
                this.responseHeaders.putAll(headers);
            }
            this.responseHeaders.putIfAbsent("Content-Type", "application/json");
            return this;
        }

        public StubBuilder willReturn(int status) {
            this.statusCode = status;
            return this;
        }

        void register() {
            if (method == null || urlPattern == null) {
                throw new TestKitException("MockBuilder stub requires method and URL");
            }

            var responseDefBuilder = aResponse()
                    .withStatus(statusCode)
                    .withBody(responseBody);
            responseHeaders.forEach(responseDefBuilder::withHeader);

            if (delayMs > 0) responseDefBuilder.withFixedDelay((int) delayMs);

            var mappingBuilder = switch (method) {
                case "GET"    -> get(urlEqualTo(urlPattern));
                case "POST"   -> post(urlEqualTo(urlPattern));
                case "PUT"    -> put(urlEqualTo(urlPattern));
                case "DELETE" -> delete(urlEqualTo(urlPattern));
                case "PATCH"  -> patch(urlEqualTo(urlPattern));
                default -> throw new TestKitException("Unsupported HTTP method: " + method);
            };

            if (bodyContains != null) {
                mappingBuilder.withRequestBody(containing(bodyContains));
            }

            server.stubFor(mappingBuilder.willReturn(responseDefBuilder));
        }
    }
}

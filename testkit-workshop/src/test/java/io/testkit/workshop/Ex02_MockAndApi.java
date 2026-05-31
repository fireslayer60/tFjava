package io.testkit.workshop;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testkit.api.ApiConfig;
import io.testkit.api.ApiStep;
import io.testkit.core.TestKit;
import io.testkit.core.TestKitContext;
import io.testkit.core.TestKitResult;
import io.testkit.mock.MockStep;
import io.testkit.TestKitDsl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EXERCISE 2 - Mock server + API testing.
 *
 * Goal: start an embedded WireMock server as a fake API, hit it with ApiBuilder,
 * chain two steps where the first extracts a value and the second uses it via context.
 *
 * Key concepts:
 *  - MockStep starts WireMock and stores its URL in context under contextKey
 *  - ApiBuilder.extract() puts a JSONPath value into TestKitContext
 *  - pathParam("id", ctx -> ctx.get("...")) is late-bound - resolved at run time
 *
 * Run: mvn test -pl testkit-workshop -Dtest=Ex02_MockAndApi
 */
class Ex02_MockAndApi {

    private WireMockServer wire;
    private String baseUrl;

    @BeforeEach
    void startWireMock() {
        wire = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wire.start();
        baseUrl = "http://localhost:" + wire.port();
        configureFor("localhost", wire.port());
    }

    @AfterEach
    void stopWireMock() {
        if (wire.isRunning()) wire.stop();
    }

    @Test
    void apiBuilderBasics() {
        // Stub: POST /api/orders -> 201
        String curPort = "5002";

        TestKitResult result = TestKitDsl.test("Order creation")
                .config(c->c.baseUrl("http://localhost:" +curPort))
                .mock(m->m.contextKey("orderUrl").port(Integer.parseInt(curPort))
                        .stub(s->s.POST("/api/orders").willReturn(201, Map.of("Content-Type", "application/json"),"{\"id\":\"ORD-123\",\"status\":\"PENDING\"}"))
                        .stub(s->s.GET("/api/orders/ORD-123").willReturn(200, Map.of("Content-Type", "application/json"),"{\"id\":\"ORD-123\",\"status\":\"PENDING\",\"items\":2}")))
                .api(
                        a -> a.POST("/api/orders")
                              .json("{\"productId\":\"P-1\",\"qty\":2}")
                              .expect(201)
                              .assertJsonPath("$.status", "PENDING")
                              .assertJsonPathPresent("$.id").extract("orderId","$.id"))
                .api(a -> a
                        .GET("/api/orders/{id}")
                        .pathParam("id", ctx -> ctx.get("orderId"))  // resolved after previous step ran
                        .expect(200))
                .run();

        assertTrue(result.passed(), "POST should 201 and assertions should pass");
    }

    @Test
    void extractAndChainSteps() {
        // CONCEPT: extract() reads a JSONPath value from the response and stores it in context.
        // A later step reads it from context using a lambda - this is late binding.

        stubFor(post("/api/orders")
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"ORD-123\",\"status\":\"PENDING\"}")));

        stubFor(get(urlEqualTo("/api/orders/ORD-123"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"ORD-123\",\"status\":\"PENDING\",\"items\":1}")));

        ApiConfig cfg = ApiConfig.builder().baseUrl(baseUrl).build();

        TestKitResult result = TestKit.test("Create then fetch order")

                // Step 1: POST -> extracts "ORD-123" into context key "orderId"
                .step(new ApiStep("Create order",
                        a -> a.POST("/api/orders")
                              .json("{\"productId\":\"P-1\"}")
                              .expect(201)
                              .extract("orderId", "$.id"),   // ctx.put("orderId", "ORD-123")
                        cfg))

                // Step 2: GET using orderId from context - the lambda runs AT STEP EXECUTION TIME
                .step(new ApiStep("Fetch order",
                        a -> a.GET("/api/orders/{id}")
                              .pathParam("id", ctx -> ctx.get("orderId")) // late binding
                              .expect(200)
                              .assertJsonPath("$.status", "PENDING"),
                        cfg))

                .run();

        assertTrue(result.passed(), "Step 2 uses orderId from Step 1 via context");
        assertEquals(2, result.passCount());
    }

    @Test
    void mockStepManagesWireMockLifecycle() {
        // CONCEPT: MockStep handles WireMock start/stop automatically.
        // You don't instantiate WireMockServer yourself.
        // It stores the server's base URL in context under contextKey.

        // Stop our manually started server first - MockStep will start its own
        wire.stop();

        TestKitResult result = TestKit.test("MockStep lifecycle")
                .config(c -> c.failFast(true))

                // MockStep: starts WireMock, stubs routes, stores URL in ctx["mockUrl"]
                .step(new MockStep(m -> m
                        .contextKey("mockUrl")
                        .stub(s -> s.GET("/health")
                                   .willReturn(200, Map.of("Content-Type", "application/json"), "{\"status\":\"UP\"}"))))

                // ApiStep: reads base URL from context at execution time
                .step(new io.testkit.core.TestKitStep() {
                    @Override public String name() { return "Health check via mock"; }

                    @Override
                    public io.testkit.core.StepResult execute(io.testkit.core.TestKitContext ctx) {
                        String mockUrl = ctx.get("mockUrl");
                        ApiConfig dynamicCfg = ApiConfig.builder().baseUrl(mockUrl).build();
                        io.testkit.api.ApiBuilder builder = new io.testkit.api.ApiBuilder();
                        builder.GET("/health").expect(200).assertJsonPath("$.status", "UP");
                        io.testkit.api.ApiResponse resp = builder.executeWith(ctx, dynamicCfg);
                        return io.testkit.core.StepResult
                                .passed(name(), java.time.Duration.ZERO)
                                .detail("HTTP " + resp.status())
                                .build();
                    }
                })

                .run();

        assertTrue(result.passed());
    }

    @Test
    void assertionsOnResponseHeaders() {
        // CONCEPT: assertHeader() checks a response header value.
        // assertContentType() is a shorthand for Content-Type header.
        stubFor(get("/api/users/me")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Request-Id", "req-abc")
                        .withBody("{\"id\":1,\"name\":\"Krish\"}")));

        ApiConfig cfg = ApiConfig.builder().baseUrl(baseUrl).build();

        TestKitResult result = TestKit.test("Header assertions")
                .step(new ApiStep("GET /api/users/me",
                        a -> a.GET("/api/users/me")
                              .expect(200)
                              .assertHeader("X-Request-Id", "req-abc")
                              .assertContentType("application/json")
                              .assertJsonPath("$.name", "Krish"),
                        cfg))
                .run();

        assertTrue(result.passed());
    }
}

package io.testkit.workshop;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testkit.api.ApiBuilder;
import io.testkit.api.ApiConfig;
import io.testkit.contract.ContractStep;
import io.testkit.core.TestKit;
import io.testkit.core.TestKitResult;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EXERCISE 5 - Consumer-driven contract testing.
 *
 * Contract testing philosophy (Pact-style):
 *  1. Consumer defines what it EXPECTS from the provider
 *  2. That expectation is verified against a running stub (consumer side)
 *  3. Provider proves it can satisfy the same request/response shape (provider side)
 *
 * Key concept: ContractBuilder.request() takes an ApiBuilder instance (not a lambda).
 * The step fires the request and asserts the response shape matches the contract.
 *
 * Run: mvn test -pl testkit-workshop -Dtest=Ex05_ContractTesting
 */
class Ex05_ContractTesting {

    private static WireMockServer wire;
    private static ApiConfig apiConfig;

    @BeforeAll
    static void startWireMock() {
        wire = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wire.start();
        configureFor("localhost", wire.port());
        apiConfig = ApiConfig.builder()
                .baseUrl("http://localhost:" + wire.port())
                .build();
    }

    @AfterAll
    static void stopWireMock() {
        wire.stop();
    }

    @BeforeEach
    void resetStubs() {
        wire.resetAll();
    }

    // -- Consumer side: verify your expectations against a running stub ----------

    @Test
    void consumerVerifiesItsExpectations() {
        // The consumer service stubs the provider's expected response and fires a request.
        // If the stub matches expectations, the contract is "proven" from the consumer's side.

        stubFor(get("/api/inventory/SKU-1")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sku\":\"SKU-1\",\"stock\":42,\"available\":true}")));

        // ContractBuilder.request() takes an ApiBuilder (not a lambda!)
        ApiBuilder request = new ApiBuilder().GET("/api/inventory/SKU-1");

        TestKitResult result = TestKit.test("Consumer: inventory contract")
                .step(new ContractStep(c -> c
                        .consumer("order-service")
                        .provider("inventory-service")
                        .describe("Get product stock")
                        .apiConfig(apiConfig)
                        .request(request)
                        .expectStatus(200)
                        .expectBodyField("$.sku", "SKU-1")   // must equal "SKU-1"
                        .expectBodyField("$.stock")))         // must be present
                .run();

        assertTrue(result.passed(),
                "Consumer contract step passes when stub returns expected response");
    }

    // -- Provider side: verify you satisfy the expected shape -------------------

    @Test
    void providerSatisfiesContractShape() {
        // The provider receives the agreed-upon request and returns the agreed-upon shape.
        // ContractStep can be used for provider verification inline (without a file).

        stubFor(get("/api/inventory/SKU-2")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sku\":\"SKU-2\",\"stock\":0,\"available\":false}")));

        ApiBuilder request = new ApiBuilder().GET("/api/inventory/SKU-2");

        TestKitResult result = TestKit.test("Provider: satisfies inventory contract shape")
                .step(new ContractStep(c -> c
                        .apiConfig(apiConfig)
                        .request(request)
                        .expectStatus(200)
                        .expectBodyField("$.sku", "SKU-2")
                        .expectBodyField("$.stock")))
                .run();

        assertTrue(result.passed(),
                "Provider step passes when it returns the expected response shape");
    }

    // -- Contract violation: provider returns wrong shape -----------------------

    @Test
    void contractStepFailsOnStatusMismatch() {
        // If the provider returns a different status than expected, the step fails.
        // This is the core value: catch API shape mismatches before deployment.

        stubFor(get("/api/inventory/DELETED-SKU")
                .willReturn(aResponse().withStatus(404)
                        .withBody("{\"error\":\"not found\"}")));

        ApiBuilder request = new ApiBuilder().GET("/api/inventory/DELETED-SKU");

        TestKitResult result = TestKit.test("Contract mismatch - wrong status")
                .config(c -> c.failFast(false))
                .step(new ContractStep(c -> c
                        .apiConfig(apiConfig)
                        .request(request)
                        .expectStatus(200)))   // expects 200, will get 404
                .run();

        assertFalse(result.passed(),
                "Step fails when provider returns 404 but contract expects 200");
        System.out.println("\nViolation detail: " + result.steps().get(0).detail());
    }

    @Test
    void contractStepFailsOnFieldMismatch() {
        // A field that must equal a specific value fails if the actual value differs.

        stubFor(get("/api/inventory/SKU-3")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sku\":\"WRONG-SKU\",\"stock\":5}")));

        ApiBuilder request = new ApiBuilder().GET("/api/inventory/SKU-3");

        TestKitResult result = TestKit.test("Contract mismatch - wrong field value")
                .config(c -> c.failFast(false))
                .step(new ContractStep(c -> c
                        .apiConfig(apiConfig)
                        .request(request)
                        .expectStatus(200)
                        .expectBodyField("$.sku", "SKU-3")))  // "WRONG-SKU" != "SKU-3"
                .run();

        assertFalse(result.passed(),
                "Step fails when a required field has the wrong value");
        System.out.println("\nField mismatch detail: " + result.steps().get(0).detail());
    }
}

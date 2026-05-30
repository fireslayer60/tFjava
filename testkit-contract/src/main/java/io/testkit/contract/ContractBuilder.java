package io.testkit.contract;

import io.testkit.api.ApiBuilder;
import io.testkit.api.ApiConfig;
import io.testkit.api.ApiResponse;
import io.testkit.core.TestKitContext;
import io.testkit.core.TestKitException;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fluent builder for consumer-driven contract testing.
 *
 * <pre>{@code
 * .contract(c -> c
 *     .consumer("order-service")
 *     .provider("inventory-service")
 *     .describe("Get product by ID")
 *     .request(r -> r.GET("/api/products/{id}").pathParam("id", "123"))
 *     .expectStatus(200)
 *     .expectBodyField("$.name")    // field must be present
 *     .expectBodyField("$.price")
 *     .saveContractTo(Path.of("contracts/order-inventory.json")))
 * }</pre>
 */
public final class ContractBuilder {

    private String consumer;
    private String provider;
    private String description;
    private ApiBuilder requestBuilder;
    private int expectedStatus = 200;
    private final Map<String, Object> bodyMatchers = new LinkedHashMap<>();
    private Path contractSavePath;
    private Path contractLoadPath;   // For provider verification
    private ApiConfig apiConfig;

    // ── Consumer side ─────────────────────────────────────────────────────────

    public ContractBuilder consumer(String name) { this.consumer = name; return this; }
    public ContractBuilder provider(String name) { this.provider = name; return this; }
    public ContractBuilder describe(String desc) { this.description = desc; return this; }

    public ContractBuilder request(io.testkit.api.ApiBuilder builder) {
        this.requestBuilder = builder;
        return this;
    }

    public ContractBuilder expectStatus(int status) { this.expectedStatus = status; return this; }

    /** Assert that this JSONPath expression exists in the response body. */
    public ContractBuilder expectBodyField(String jsonPath) {
        bodyMatchers.put(jsonPath, "present");
        return this;
    }

    /** Assert that this JSONPath expression equals the given value. */
    public ContractBuilder expectBodyField(String jsonPath, Object value) {
        bodyMatchers.put(jsonPath, value);
        return this;
    }

    public ContractBuilder apiConfig(ApiConfig cfg) { this.apiConfig = cfg; return this; }

    /** Persist the contract as a JSON file after verification. */
    public ContractBuilder saveContractTo(Path path) { this.contractSavePath = path; return this; }

    // ── Provider side ─────────────────────────────────────────────────────────

    /** Verify a previously saved contract against a live provider. */
    public ContractBuilder verifyContract(Path contractPath) {
        this.contractLoadPath = contractPath;
        return this;
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    void executeContract(TestKitContext ctx) {
        if (contractLoadPath != null) {
            verifyProviderContract(ctx);
        } else {
            verifyConsumerContract(ctx);
        }
    }

    private void verifyConsumerContract(TestKitContext ctx) {
        if (requestBuilder == null) throw new TestKitException("ContractBuilder: no request defined");

        ApiConfig effective = apiConfig != null ? apiConfig : ApiConfig.defaults();
        requestBuilder.expect(expectedStatus);

        bodyMatchers.forEach((path, expected) -> {
            if ("present".equals(expected)) {
                requestBuilder.assertJsonPathPresent(path);
            } else {
                requestBuilder.assertJsonPath(path, expected);
            }
        });

        ApiResponse response = requestBuilder.executeWith(ctx, effective);

        // Optionally save the contract
        if (contractSavePath != null) {
            Contract contract = new Contract(consumer, provider, description,
                    null,   // request is captured separately
                    new Contract.ContractResponse(expectedStatus, Map.of(), null, bodyMatchers));
            contract.writeTo(contractSavePath);
        }
    }

    private void verifyProviderContract(TestKitContext ctx) {
        Contract contract = Contract.readFrom(contractLoadPath);
        if (contract.response() == null) throw new TestKitException("Contract has no expected response");

        if (requestBuilder == null)
            throw new TestKitException("ContractBuilder (provider verify): provide a request builder");

        requestBuilder.expect(contract.response().status());
        if (contract.response().bodyMatchers() != null) {
            contract.response().bodyMatchers().forEach((path, expected) -> {
                if ("present".equals(expected)) requestBuilder.assertJsonPathPresent(path);
                else requestBuilder.assertJsonPath(path, expected);
            });
        }

        ApiConfig effective = apiConfig != null ? apiConfig : ApiConfig.defaults();
        requestBuilder.executeWith(ctx, effective);
    }
}

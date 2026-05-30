package io.testkit.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A portable, file-serialisable consumer-driven contract.
 * The consumer defines the contract; the provider verifies it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Contract {

    private String consumer;
    private String provider;
    private String description;
    private ContractRequest request;
    private ContractResponse response;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Default constructor for Jackson
    public Contract() {}

    public Contract(String consumer, String provider, String description,
                    ContractRequest request, ContractResponse response) {
        this.consumer    = consumer;
        this.provider    = provider;
        this.description = description;
        this.request     = request;
        this.response    = response;
    }

    public String consumer()           { return consumer; }
    public String provider()           { return provider; }
    public String description()        { return description; }
    public ContractRequest request()   { return request; }
    public ContractResponse response() { return response; }

    public void setConsumer(String c)           { this.consumer = c; }
    public void setProvider(String p)           { this.provider = p; }
    public void setDescription(String d)        { this.description = d; }
    public void setRequest(ContractRequest r)   { this.request = r; }
    public void setResponse(ContractResponse r) { this.response = r; }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void writeTo(Path path) {
        try {
            Files.createDirectories(path.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), this);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Contract readFrom(Path path) {
        try {
            return MAPPER.readValue(path.toFile(), Contract.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── Nested types ──────────────────────────────────────────────────────────

    public record ContractRequest(
            String method,
            String path,
            Map<String, String> headers,
            String body) {}

    public record ContractResponse(
            int status,
            Map<String, String> headers,
            String bodySchema,   // JSON Schema as string
            Map<String, Object> bodyMatchers) {}
}

package io.testkit.core.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.testkit.core.StepResult;
import io.testkit.core.TestKitResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Writes a machine-readable JSON report to a file. */
public final class JsonReporter implements TestKitReporter {

    private final Path outputPath;
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode root;

    public JsonReporter(Path outputPath) {
        this.outputPath = outputPath;
    }

    @Override
    public void onSuiteStart(String suiteName) {
        root = mapper.createObjectNode();
        root.put("suite", suiteName);
        root.put("startedAt", Instant.now().toString());
        root.putArray("steps");
    }

    @Override
    public void onStepStart(String stepName) {}

    @Override
    public void onStepEnd(StepResult r) {
        ArrayNode steps = (ArrayNode) root.get("steps");
        ObjectNode step = steps.addObject();
        step.put("name", r.stepName());
        step.put("status", r.status().name());
        step.put("durationMs", r.duration().toMillis());
        if (r.detail() != null) step.put("detail", r.detail());
        if (!r.assertions().isEmpty()) {
            ArrayNode arr = step.putArray("assertions");
            r.assertions().forEach(arr::add);
        }
    }

    @Override
    public void onSuiteEnd(TestKitResult result) {
        root.put("passed", result.passed());
        root.put("totalMs", result.totalDuration().toMillis());
        root.put("finishedAt", Instant.now().toString());
        try {
            Files.createDirectories(outputPath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), root);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write JSON report to " + outputPath, e);
        }
    }
}

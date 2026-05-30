package io.testkit.seed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testkit.core.TestKitException;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Loads test fixtures from JSON or YAML files on the classpath.
 *
 * <pre>{@code
 * List<Map<String, Object>> users = FixtureLoader.fromJson("fixtures/users.json");
 * }</pre>
 */
public final class FixtureLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> LIST_TYPE =
            new TypeReference<>() {};

    private FixtureLoader() {}

    public static List<Map<String, Object>> fromJson(String classpathResource) {
        try (InputStream is = stream(classpathResource)) {
            return MAPPER.readValue(is, LIST_TYPE);
        } catch (IOException e) {
            throw new TestKitException("Failed to load JSON fixture: " + classpathResource, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> fromYaml(String classpathResource) {
        try (InputStream is = stream(classpathResource)) {
            Yaml yaml = new Yaml();
            return yaml.load(is);
        } catch (IOException e) {
            throw new TestKitException("Failed to load YAML fixture: " + classpathResource, e);
        }
    }

    /** Auto-detect format from file extension. */
    public static List<Map<String, Object>> from(String classpathResource) {
        if (classpathResource.endsWith(".yaml") || classpathResource.endsWith(".yml")) {
            return fromYaml(classpathResource);
        }
        return fromJson(classpathResource);
    }

    private static InputStream stream(String resource) {
        InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource);
        if (is == null) {
            throw new TestKitException("Fixture not found on classpath: " + resource);
        }
        return is;
    }
}

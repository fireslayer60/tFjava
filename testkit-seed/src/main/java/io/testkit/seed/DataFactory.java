package io.testkit.seed;

import java.util.*;

/**
 * Base class for test-data factories.
 *
 * <pre>{@code
 * public class UserFactory extends DataFactory<Map<String, Object>> {
 *     @Override
 *     protected void define() {
 *         field("id",       FieldSupplier.uuid());
 *         field("email",    FieldSupplier.email());
 *         field("name",     FieldSupplier.name());
 *         field("password", FieldSupplier.fixed("hashed_secret"));
 *     }
 * }
 * }</pre>
 */
public abstract class DataFactory<T> {

    private final Map<String, FieldSupplier<?>> fields = new LinkedHashMap<>();
    private boolean defined = false;

    protected abstract void define();

    protected final void field(String name, FieldSupplier<?> supplier) {
        fields.put(name, supplier);
    }

    /** Build a single record as a {@code Map<String, Object>}. */
    public final Map<String, Object> buildMap() {
        ensureDefined();
        Map<String, Object> row = new LinkedHashMap<>();
        fields.forEach((k, v) -> row.put(k, v.get()));
        return row;
    }

    /** Build a list of {@code count} records. */
    public final List<Map<String, Object>> buildMaps(int count) {
        List<Map<String, Object>> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) list.add(buildMap());
        return list;
    }

    /** Override to transform the raw map to the desired type T. */
    @SuppressWarnings("unchecked")
    public T build() {
        return (T) buildMap();
    }

    public List<T> build(int count) {
        List<T> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) list.add(build());
        return list;
    }

    /** Merge override values for a single build (useful for specific scenarios). */
    public Map<String, Object> buildMap(Map<String, Object> overrides) {
        Map<String, Object> base = buildMap();
        base.putAll(overrides);
        return base;
    }

    private void ensureDefined() {
        if (!defined) {
            define();
            defined = true;
        }
    }
}

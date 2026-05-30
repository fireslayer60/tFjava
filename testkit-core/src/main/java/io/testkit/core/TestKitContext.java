package io.testkit.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Shared mutable bag of key→value pairs that flows through every step in a TestKit chain.
 * Steps can both read and write values, enabling late-binding (e.g. extracting a JWT in an
 * API step and injecting it into the next request without any boilerplate).
 */
public final class TestKitContext {

    private final Map<String, Object> store = new HashMap<>();

    public void put(String key, Object value) {
        store.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        Object val = store.get(key);
        if (val == null) {
            throw new TestKitException("Context key not found: '" + key + "'. Available keys: " + store.keySet());
        }
        return (T) val;
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(String key) {
        return Optional.ofNullable((T) store.get(key));
    }

    public boolean has(String key) {
        return store.containsKey(key);
    }

    /** Resolve a value that may be a literal or a context-lookup function. */
    @SuppressWarnings("unchecked")
    public <T> T resolve(Object valueOrFn) {
        if (valueOrFn instanceof Function<?, ?> fn) {
            return ((Function<TestKitContext, T>) fn).apply(this);
        }
        return (T) valueOrFn;
    }

    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(store));
    }

    public void merge(Map<String, Object> values) {
        store.putAll(values);
    }

    @Override
    public String toString() {
        return "TestKitContext" + store;
    }
}

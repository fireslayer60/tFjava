package io.testkit.seed;

import com.github.javafaker.Faker;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Lazy supplier for a single factory field value.
 * Wrap a {@link Faker} expression, a fixed literal, a sequence, or any lambda.
 */
@FunctionalInterface
public interface FieldSupplier<T> extends Supplier<T> {

    // ── Static factory helpers ────────────────────────────────────────────────

    static <T> FieldSupplier<T> fixed(T value) {
        return () -> value;
    }

    static FieldSupplier<String> uuid() {
        return () -> UUID.randomUUID().toString();
    }

    static FieldSupplier<String> email() {
        Faker f = new Faker();
        return () -> f.internet().emailAddress();
    }

    static FieldSupplier<String> name() {
        Faker f = new Faker();
        return f.name()::fullName;
    }

    static FieldSupplier<String> firstName() {
        Faker f = new Faker();
        return f.name()::firstName;
    }

    static FieldSupplier<String> lastName() {
        Faker f = new Faker();
        return f.name()::lastName;
    }

    static FieldSupplier<String> phone() {
        Faker f = new Faker();
        return f.phoneNumber()::cellPhone;
    }

    static FieldSupplier<String> password() {
        Faker f = new Faker();
        return () -> f.internet().password(12, 20, true, true, true);
    }

    static FieldSupplier<Long> sequence() {
        long[] counter = {0};
        return () -> ++counter[0];
    }

    static FieldSupplier<String> lorem(int words) {
        Faker f = new Faker();
        return () -> f.lorem().words(words).stream()
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
    }

    static FieldSupplier<String> address() {
        Faker f = new Faker();
        return f.address()::fullAddress;
    }

    static FieldSupplier<String> companyName() {
        Faker f = new Faker();
        return f.company()::name;
    }
}

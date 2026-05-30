package io.testkit.core;

/** Thrown when a TestKit step fails an assertion or encounters a fatal error. */
public class TestKitException extends RuntimeException {

    public TestKitException(String message) {
        super(message);
    }

    public TestKitException(String message, Throwable cause) {
        super(message, cause);
    }
}

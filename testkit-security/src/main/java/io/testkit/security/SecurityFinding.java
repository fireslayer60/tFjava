package io.testkit.security;

/** A single security issue discovered by a probe. */
public final class SecurityFinding {

    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }

    private final String probe;
    private final Severity severity;
    private final String description;
    private final String endpoint;
    private final String payload;
    private final String evidence;

    public SecurityFinding(String probe, Severity severity, String description,
                           String endpoint, String payload, String evidence) {
        this.probe       = probe;
        this.severity    = severity;
        this.description = description;
        this.endpoint    = endpoint;
        this.payload     = payload;
        this.evidence    = evidence;
    }

    public String probe()       { return probe; }
    public Severity severity()  { return severity; }
    public String description() { return description; }
    public String endpoint()    { return endpoint; }
    public String payload()     { return payload; }
    public String evidence()    { return evidence; }

    @Override
    public String toString() {
        return "[%s] %s — %s (endpoint: %s)".formatted(severity, probe, description, endpoint);
    }
}

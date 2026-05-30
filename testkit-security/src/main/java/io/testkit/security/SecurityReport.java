package io.testkit.security;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Aggregated output of a security scan run. */
public final class SecurityReport {

    private final List<SecurityFinding> findings;

    public SecurityReport(List<SecurityFinding> findings) {
        this.findings = List.copyOf(findings);
    }

    public List<SecurityFinding> findings() { return findings; }
    public boolean hasFindings()            { return !findings.isEmpty(); }

    public List<SecurityFinding> bySeverity(SecurityFinding.Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).toList();
    }

    public void print() {
        System.out.println("\n┌─ Security Scan Report ─────────────────────────────────────────");
        if (findings.isEmpty()) {
            System.out.println("│  ✓ No security issues found");
        } else {
            Map<SecurityFinding.Severity, Long> counts = findings.stream()
                    .collect(Collectors.groupingBy(SecurityFinding::severity, Collectors.counting()));

            counts.entrySet().stream()
                    .sorted(Comparator.comparingInt(e -> e.getKey().ordinal()))
                    .forEach(e -> System.out.printf("│  %-10s %d finding(s)%n", e.getKey(), e.getValue()));

            System.out.println("│");
            findings.stream()
                    .sorted(Comparator.comparingInt(f -> f.severity().ordinal()))
                    .forEach(f -> System.out.printf(
                            "│  [%s] %s%n│     Endpoint : %s%n│     Payload  : %s%n│     Evidence : %s%n│%n",
                            f.severity(), f.description(), f.endpoint(), f.payload(), f.evidence()));
        }
        System.out.println("└────────────────────────────────────────────────────────────────\n");
    }
}

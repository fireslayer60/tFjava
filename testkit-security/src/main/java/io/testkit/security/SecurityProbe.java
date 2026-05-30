package io.testkit.security;

import io.testkit.api.ApiConfig;

import java.util.List;

/** SPI for security probes. Each probe fires targeted requests and returns findings. */
public interface SecurityProbe {

    String name();

    List<SecurityFinding> probe(String targetPath, ApiConfig apiConfig);
}

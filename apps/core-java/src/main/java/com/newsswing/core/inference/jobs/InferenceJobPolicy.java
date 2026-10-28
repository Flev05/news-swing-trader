package com.newsswing.core.inference.jobs;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server-controlled policy snapshotted when ingestion creates a job. */
@ConfigurationProperties("trader.inference.jobs")
public record InferenceJobPolicy(int maxAssignments, String expectedBackend) {
  public InferenceJobPolicy {
    if (maxAssignments < 1) {
      throw new IllegalArgumentException("trader.inference.jobs.max-assignments must be positive");
    }
    if (expectedBackend == null
        || !Set.of("fake", "llama.cpp", "ollama").contains(expectedBackend)) {
      throw new IllegalArgumentException(
          "trader.inference.jobs.expected-backend must be fake, llama.cpp, or ollama");
    }
  }
}

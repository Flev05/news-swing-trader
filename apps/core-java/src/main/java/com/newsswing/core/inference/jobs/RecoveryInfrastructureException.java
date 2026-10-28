package com.newsswing.core.inference.jobs;

import com.newsswing.core.inference.jobs.InferenceJobLifecycleService.RecoverySummary;
import java.util.Optional;
import java.util.UUID;

/** Recovery stopped; committed progress is durable and the failed transaction was rolled back. */
public final class RecoveryInfrastructureException extends RuntimeException {
  private final RecoverySummary committedProgress;
  private final Optional<UUID> failedJobId;

  RecoveryInfrastructureException(
      RecoverySummary progress, UUID failedJobId, RuntimeException cause) {
    super(
        "Inference job recovery stopped after " + progress.processed() + " committed candidates",
        cause);
    this.committedProgress = progress;
    this.failedJobId = Optional.ofNullable(failedJobId);
  }

  public RecoverySummary committedProgress() {
    return committedProgress;
  }

  public Optional<UUID> failedJobId() {
    return failedJobId;
  }
}

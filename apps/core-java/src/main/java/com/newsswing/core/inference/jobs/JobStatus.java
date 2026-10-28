package com.newsswing.core.inference.jobs;

/** Durable states; schema support does not imply lifecycle operations are implemented. */
public enum JobStatus {
  PENDING,
  RUNNING,
  RETRY_WAIT,
  SUCCEEDED,
  FAILED,
  SUPERSEDED,
  EXPIRED;

  public boolean isTerminal() {
    return switch (this) {
      case SUCCEEDED, FAILED, SUPERSEDED, EXPIRED -> true;
      case PENDING, RUNNING, RETRY_WAIT -> false;
    };
  }
}

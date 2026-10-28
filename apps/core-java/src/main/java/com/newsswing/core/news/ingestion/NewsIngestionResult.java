package com.newsswing.core.news.ingestion;

import com.newsswing.core.inference.jobs.JobStatus;
import java.util.Optional;
import java.util.UUID;

/** Durable result of a completed news-ingestion request. */
public sealed interface NewsIngestionResult
    permits NewsIngestionResult.Queued,
        NewsIngestionResult.OutOfOrder,
        NewsIngestionResult.Duplicate {

  UUID eventId();

  UUID newsItemId();

  record Queued(UUID eventId, UUID newsItemId, UUID jobId) implements NewsIngestionResult {}

  record OutOfOrder(UUID eventId, UUID newsItemId) implements NewsIngestionResult {}

  record Duplicate(
      UUID requestedEventId,
      UUID eventId,
      UUID newsItemId,
      StoredAdmissionState storedAdmissionState,
      Optional<JobSnapshot> currentJob)
      implements NewsIngestionResult {}

  record JobSnapshot(UUID jobId, JobStatus status) {}

  enum StoredAdmissionState {
    QUEUED,
    OUT_OF_ORDER
  }
}

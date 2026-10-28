package com.newsswing.core.inference.api;

import com.newsswing.core.inference.jobs.InferenceJobLifecycleService.Assignment;
import com.newsswing.core.inference.jobs.InferenceJobLifecycleService.CompletionReceipt;
import java.time.Instant;
import java.util.UUID;

/**
 * Transport projections are independent of both domain JSON documents and Java service carriers.
 */
final class WorkerApiModels {
  private WorkerApiModels() {}

  record Reference(UUID jobId, int assignmentNumber, UUID ownershipToken) {
    @Override
    public String toString() {
      return "WorkerReference[redacted]";
    }
  }

  record ClaimedAssignment(
      Reference ref,
      String newsEventJson,
      UUID inferenceId,
      String expectedBackend,
      String resultSchemaVersion,
      Instant leaseExpiresAt) {
    @Override
    public String toString() {
      return "WorkerAssignment[redacted]";
    }
  }

  record Receipt(UUID jobId, UUID newsEventId, UUID inferenceId, Instant acceptedAt) {}

  static ClaimedAssignment assignment(Assignment assignment) {
    var ref = assignment.ref();
    return new ClaimedAssignment(
        new Reference(ref.jobId(), ref.assignmentNumber(), ref.ownershipToken()),
        assignment.newsEventJson(),
        assignment.inferenceId(),
        assignment.expectedBackend(),
        assignment.resultSchemaVersion(),
        assignment.leaseExpiresAt());
  }

  static Receipt receipt(CompletionReceipt receipt) {
    return new Receipt(
        receipt.jobId(), receipt.newsEventId(), receipt.inferenceId(), receipt.acceptedAt());
  }
}

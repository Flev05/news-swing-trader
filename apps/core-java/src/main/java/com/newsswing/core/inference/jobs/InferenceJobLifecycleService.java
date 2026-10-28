package com.newsswing.core.inference.jobs;

import com.newsswing.core.contracts.SharedContractValidator.Violation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Java-only lifecycle boundary. Calls own their transactions and reject ambient transactions. */
public interface InferenceJobLifecycleService {
  /** One bounded recovery pass followed by a bounded candidate scan; delivery follows commit. */
  ClaimOutcome claimNext();

  RenewOutcome renew(AssignmentRef assignment);

  FailOutcome fail(AssignmentRef assignment, FailureKind kind);

  CompletionOutcome complete(AssignmentRef assignment, String resultJson);

  Optional<CurrentEligibleResult> findCurrentEligibleResult(UUID newsItemId);

  record CompletionReceipt(UUID jobId, UUID newsEventId, UUID inferenceId, Instant acceptedAt) {}

  record CurrentEligibleResult(
      UUID newsItemId, CompletionReceipt receipt, String resultJson, Instant decisionAt) {}

  /** Earlier candidate commits survive a later failure; see RecoveryInfrastructureException. */
  RecoverySummary recoverBatch();

  record AssignmentRef(UUID jobId, int assignmentNumber, UUID ownershipToken) {}

  record Assignment(
      AssignmentRef ref,
      String newsEventJson,
      UUID inferenceId,
      String expectedBackend,
      String resultSchemaVersion,
      Instant leaseExpiresAt) {}

  enum FailureKind {
    RECOVERABLE,
    PERMANENT
  }

  enum FailureCode {
    WORKER_RECOVERABLE,
    WORKER_PERMANENT,
    LEASE_EXPIRED,
    INVALID_RESULT,
    PROVENANCE_MISMATCH
  }

  enum RejectionReason {
    INVALID_COMMAND,
    NOT_FOUND,
    NOT_RUNNING,
    OWNER_MISMATCH,
    REVISION_OBSOLETE,
    NEWS_EXPIRED,
    LEASE_EXPIRED
  }

  sealed interface ClaimOutcome permits Claimed, NoWork {}

  record Claimed(Assignment assignment) implements ClaimOutcome {}

  /** No assignment in this bounded call; neither queue emptiness nor a call deadline is implied. */
  record NoWork() implements ClaimOutcome {}

  sealed interface RenewOutcome permits Renewed, Rejected {}

  record Renewed(Instant leaseExpiresAt) implements RenewOutcome {}

  sealed interface FailOutcome permits RetryWaiting, Failed, Rejected {}

  record RetryWaiting() implements FailOutcome {}

  record Failed(FailureCode code) implements FailOutcome {}

  sealed interface CompletionOutcome
      permits Accepted, AlreadyAccepted, CompletionConflict, InvalidResult, Rejected {}

  record Accepted(CompletionReceipt receipt) implements CompletionOutcome {}

  record AlreadyAccepted(CompletionReceipt receipt) implements CompletionOutcome {}

  record CompletionConflict() implements CompletionOutcome {}

  record InvalidResult(FailureCode code, List<Violation> violations) implements CompletionOutcome {
    public InvalidResult {
      if (code != FailureCode.INVALID_RESULT && code != FailureCode.PROVENANCE_MISMATCH) {
        throw new IllegalArgumentException("Expected a result validation failure code");
      }
      violations = List.copyOf(violations);
    }
  }

  record Rejected(RejectionReason reason) implements RenewOutcome, FailOutcome, CompletionOutcome {}

  /** Counts committed candidate transactions, including skips and capacity deferrals. */
  record RecoverySummary(
      int processed,
      int skipped,
      int superseded,
      int expired,
      int retryWaiting,
      int failed,
      int requeued,
      int capacityDeferred) {}
}

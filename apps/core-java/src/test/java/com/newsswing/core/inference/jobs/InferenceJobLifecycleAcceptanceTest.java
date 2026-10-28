package com.newsswing.core.inference.jobs;

import static com.newsswing.core.inference.jobs.InferenceJobLifecycleService.*;
import static org.assertj.core.api.Assertions.*;

import com.newsswing.core.news.ingestion.NewsIngestionResult;
import com.newsswing.support.InferenceJobFixtures;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class InferenceJobLifecycleAcceptanceTest extends LifecycleTestSupport {
  @Test
  void claimsStoredPayloadAndPolicyAndRenewsWithoutChangingAuthority() {
    var queued = queue("one", 1);
    var a = claim();
    assertThat(a.ref().jobId()).isEqualTo(queued.jobId());
    assertThat(a.ref().assignmentNumber()).isEqualTo(1);
    assertThat(a.ref().ownershipToken()).isNotNull();
    assertThat(a.inferenceId()).isNotNull();
    assertThat(a.expectedBackend()).isEqualTo("fake");
    assertThat(a.resultSchemaVersion()).isEqualTo("1.0.0");
    assertThat(a.newsEventJson())
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT event_payload::text FROM trader.news_revisions WHERE event_id = ?",
                String.class,
                queued.eventId()));
    assertThat(a.leaseExpiresAt()).isEqualTo(NOW.plusSeconds(60));
    time.set(NOW.plusSeconds(30));
    assertThat(service.renew(a.ref())).isEqualTo(new Renewed(NOW.plusSeconds(90)));
    time.set(NOW.minusSeconds(10));
    assertThat(service.renew(a.ref())).isEqualTo(new Renewed(NOW.plusSeconds(90)));
    assertThat(count(queued.jobId())).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT ownership_token FROM trader.inference_jobs WHERE job_id = ?",
                UUID.class,
                queued.jobId()))
        .isEqualTo(a.ref().ownershipToken());
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 0, 1})
  void renewalBoundaryIsStrict(long micros) {
    var queued = queue("boundary", 1);
    var a = claim();
    time.set(a.leaseExpiresAt().plusNanos(micros * 1000));
    String before = snapshot(queued.jobId());
    if (micros < 0) {
      assertThat(service.renew(a.ref())).isEqualTo(new Renewed(time.value.get().plusSeconds(60)));
    } else {
      assertThat(service.renew(a.ref())).isEqualTo(new Rejected(RejectionReason.LEASE_EXPIRED));
      assertThat(service.fail(a.ref(), FailureKind.PERMANENT))
          .isEqualTo(new Rejected(RejectionReason.LEASE_EXPIRED));
      assertThat(snapshot(queued.jobId())).isEqualTo(before);
    }
  }

  @Test
  void clipsClaimAndRenewalAtNewsDeadlineAndRejectsEquality() {
    var q =
        (NewsIngestionResult.Queued) ingestion.ingest(event("deadline", 1, NOW.plusSeconds(45)));
    var a = claim();
    assertThat(a.leaseExpiresAt()).isEqualTo(NOW.plusSeconds(45));
    time.set(NOW.plusSeconds(44));
    assertThat(service.renew(a.ref())).isEqualTo(new Renewed(NOW.plusSeconds(45)));
    time.set(NOW.plusSeconds(45));
    String before = snapshot(q.jobId());
    assertThat(service.renew(a.ref())).isEqualTo(new Rejected(RejectionReason.NEWS_EXPIRED));
    assertThat(service.fail(a.ref(), FailureKind.RECOVERABLE))
        .isEqualTo(new Rejected(RejectionReason.NEWS_EXPIRED));
    assertThat(snapshot(q.jobId())).isEqualTo(before);
    assertThat(service.recoverBatch().expired()).isEqualTo(1);
    noOwner(q.jobId());
  }

  @Test
  void rejectsMalformedAndMismatchedOwnersWithoutMutation() {
    var q = queue("fence", 1);
    var a = claim();
    String before = snapshot(q.jobId());
    var invalid = new ArrayList<AssignmentRef>();
    invalid.add(null);
    invalid.add(new AssignmentRef(null, 1, UUID.randomUUID()));
    invalid.add(new AssignmentRef(q.jobId(), 0, UUID.randomUUID()));
    invalid.add(new AssignmentRef(q.jobId(), -1, UUID.randomUUID()));
    invalid.add(new AssignmentRef(q.jobId(), 1, null));
    for (var ref : invalid) {
      assertThat(service.renew(ref)).isEqualTo(new Rejected(RejectionReason.INVALID_COMMAND));
      assertThat(service.fail(ref, FailureKind.RECOVERABLE))
          .isEqualTo(new Rejected(RejectionReason.INVALID_COMMAND));
    }
    assertThat(service.fail(a.ref(), null))
        .isEqualTo(new Rejected(RejectionReason.INVALID_COMMAND));
    for (var ref :
        List.of(
            new AssignmentRef(q.jobId(), 2, a.ref().ownershipToken()),
            new AssignmentRef(q.jobId(), 1, UUID.randomUUID()))) {
      assertThat(service.renew(ref)).isEqualTo(new Rejected(RejectionReason.OWNER_MISMATCH));
      assertThat(service.fail(ref, FailureKind.PERMANENT))
          .isEqualTo(new Rejected(RejectionReason.OWNER_MISMATCH));
    }
    var missing = new AssignmentRef(UUID.randomUUID(), 1, UUID.randomUUID());
    assertThat(service.renew(missing)).isEqualTo(new Rejected(RejectionReason.NOT_FOUND));
    assertThat(service.fail(missing, FailureKind.RECOVERABLE))
        .isEqualTo(new Rejected(RejectionReason.NOT_FOUND));
    assertThat(snapshot(q.jobId())).isEqualTo(before);
  }

  @Test
  void interruptedAssignmentRecoversReassignsAndFencesOldOwner() {
    var q = queue("interruption", 1);
    var first = claim();
    time.set(first.leaseExpiresAt());
    assertThat(service.recoverBatch()).isEqualTo(new RecoverySummary(2, 0, 0, 0, 1, 0, 1, 0));
    assertThat(status(q.jobId())).isEqualTo("PENDING");
    noOwner(q.jobId());
    assertThat(count(q.jobId())).isEqualTo(1);
    var second = claim();
    assertThat(second.ref().assignmentNumber()).isEqualTo(2);
    assertThat(second.ref().ownershipToken()).isNotEqualTo(first.ref().ownershipToken());
    assertThat(second.inferenceId()).isNotEqualTo(first.inferenceId());
    String before = snapshot(q.jobId());
    assertThat(service.renew(first.ref())).isEqualTo(new Rejected(RejectionReason.OWNER_MISMATCH));
    assertThat(service.fail(first.ref(), FailureKind.PERMANENT))
        .isEqualTo(new Rejected(RejectionReason.OWNER_MISMATCH));
    assertThat(snapshot(q.jobId())).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void consumesExactlyThreeAssignmentsAndExhaustsStoredPolicy(boolean interrupted) {
    var q = queue("exhaust", 1);
    for (int number = 1; number <= 3; number++) {
      var a = claim();
      assertThat(a.ref().assignmentNumber()).isEqualTo(number);
      if (interrupted) {
        time.set(a.leaseExpiresAt());
        service.recoverBatch();
      } else {
        assertThat(service.fail(a.ref(), FailureKind.RECOVERABLE))
            .isEqualTo(
                number < 3 ? new RetryWaiting() : new Failed(FailureCode.WORKER_RECOVERABLE));
        service.recoverBatch();
      }
    }
    assertThat(count(q.jobId())).isEqualTo(3);
    assertThat(status(q.jobId())).isEqualTo("FAILED");
    noOwner(q.jobId());
    assertThat(service.claimNext()).isEqualTo(new NoWork());
  }

  @Test
  void permanentFailureIsTerminalWithoutExhaustion() {
    var q = queue("permanent", 1);
    var ref = claim().ref();
    assertThat(service.fail(ref, FailureKind.PERMANENT))
        .isEqualTo(new Failed(FailureCode.WORKER_PERMANENT));
    assertThat(count(q.jobId())).isEqualTo(1);
    noOwner(q.jobId());
    String before = snapshot(q.jobId());
    assertThat(service.renew(ref)).isEqualTo(new Rejected(RejectionReason.NOT_RUNNING));
    assertThat(service.fail(ref, FailureKind.RECOVERABLE))
        .isEqualTo(new Rejected(RejectionReason.NOT_RUNNING));
    assertThat(service.recoverBatch().processed()).isZero();
    assertThat(snapshot(q.jobId())).isEqualTo(before);
  }

  @Test
  void usesStoredNondefaultPolicy() {
    var q = queue("policy", 1);
    jdbc.update(
        "UPDATE trader.inference_jobs SET max_assignments = 1, expected_backend = 'ollama' WHERE job_id = ?",
        q.jobId());
    var a = claim();
    assertThat(a.expectedBackend()).isEqualTo("ollama");
    assertThat(service.fail(a.ref(), FailureKind.RECOVERABLE))
        .isEqualTo(new Failed(FailureCode.WORKER_RECOVERABLE));
    assertThat(count(q.jobId())).isEqualTo(1);
  }

  @Test
  void fullAndZeroCapacityWaitWithoutLeaseOrExtraAssignment() {
    var q = queue("waiting", 1);
    service.fail(claim().ref(), FailureKind.RECOVERABLE);
    queue("occupancy", 1);
    String before = snapshot(q.jobId());
    assertThat(service(1, 4).recoverBatch().capacityDeferred()).isEqualTo(1);
    assertThat(service(0, 4).recoverBatch().capacityDeferred()).isEqualTo(1);
    assertThat(snapshot(q.jobId())).isEqualTo(before);
    noOwner(q.jobId());
    assertThat(service(0, 4).claimNext()).isInstanceOf(Claimed.class);
    assertThat(service(0, 4).recoverBatch().capacityDeferred()).isEqualTo(1);
    time.set(NOW.plusSeconds(600));
    assertThat(service(0, 4).recoverBatch().expired()).isEqualTo(2);
  }

  @Test
  void boundedCleanupTakesPriorityAndSamePassRequeueCostsAnotherSlot() {
    var expired =
        (NewsIngestionResult.Queued) ingestion.ingest(event("expired", 1, NOW.plusSeconds(1)));
    var running = queue("running", 1);
    jdbc.update(
        "UPDATE trader.inference_jobs SET created_at = ? WHERE job_id = ?",
        Timestamp.from(NOW.minusSeconds(1)),
        running.jobId());
    var a = claim();
    assertThat(a.ref().jobId()).isEqualTo(running.jobId());
    time.set(a.leaseExpiresAt());
    var one = service(4, 1);
    assertThat(one.recoverBatch()).isEqualTo(new RecoverySummary(1, 0, 0, 1, 0, 0, 0, 0));
    assertThat(status(expired.jobId())).isEqualTo("EXPIRED");
    assertThat(one.recoverBatch()).isEqualTo(new RecoverySummary(1, 0, 0, 0, 1, 0, 0, 0));
    assertThat(status(running.jobId())).isEqualTo("RETRY_WAIT");
    assertThat(one.recoverBatch()).isEqualTo(new RecoverySummary(1, 0, 0, 0, 0, 0, 1, 0));
    assertThat(count(running.jobId())).isEqualTo(1);
  }

  @Test
  void supersessionAndExpiryPrecedeRetryAndExhaustion() {
    var old = queue("obsolete", 1);
    var a = claim();
    var newer = queue("obsolete", 2);
    String before = snapshot(old.jobId());
    assertThat(service.renew(a.ref())).isEqualTo(new Rejected(RejectionReason.NOT_RUNNING));
    assertThat(service.fail(a.ref(), FailureKind.PERMANENT))
        .isEqualTo(new Rejected(RejectionReason.NOT_RUNNING));
    assertThat(snapshot(old.jobId())).isEqualTo(before);
    jdbc.update(
        """
        UPDATE trader.inference_jobs SET status='RUNNING', assignment_count=3,
          ownership_token=?, assignment_inference_id=?, assigned_at=?, lease_expires_at=?,
          terminal_at=NULL, superseded_at=NULL, superseded_by_event_id=NULL WHERE job_id=?
        """,
        UUID.randomUUID(),
        UUID.randomUUID(),
        Timestamp.from(NOW),
        Timestamp.from(NOW.plusSeconds(60)),
        old.jobId());
    time.set(NOW.plusSeconds(600));
    var summary = service.recoverBatch();
    assertThat(summary.superseded()).isEqualTo(1);
    assertThat(summary.expired()).isEqualTo(1);
    assertThat(status(old.jobId())).isEqualTo("SUPERSEDED");
    assertThat(status(newer.jobId())).isEqualTo("EXPIRED");
    noOwner(old.jobId());
  }

  @Test
  void expiredPendingStillConsumesCapacityBeforeItsCleanup() {
    var stale =
        (NewsIngestionResult.Queued) ingestion.ingest(event("old-pending", 1, NOW.plusSeconds(1)));
    var retry = queue("retry", 1);
    InferenceJobFixtures.state(jdbc, retry.jobId(), JobStatus.RETRY_WAIT, NOW);
    time.set(NOW.plusSeconds(2));
    new TransactionTemplate(manager)
        .executeWithoutResult(
            ignored -> {
              jdbc.queryForObject(
                  "SELECT id FROM trader.news_items WHERE id=? FOR UPDATE",
                  UUID.class,
                  stale.newsItemId());
              var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
              try {
                var result =
                    executor
                        .submit(() -> service(1, 4).recoverBatch())
                        .get(15, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(result.skipped()).isEqualTo(1);
                assertThat(result.capacityDeferred()).isEqualTo(1);
                assertThat(pending()).isEqualTo(1);
              } catch (Exception e) {
                throw new AssertionError(e);
              } finally {
                executor.shutdownNow();
                try {
                  assertThat(executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS))
                      .isTrue();
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(interrupted);
                }
              }
            });
  }

  @Test
  void rejectsEveryAmbientEntryPointWithoutMutation() {
    var q = queue("ambient", 1);
    String before = snapshot(q.jobId());
    new TransactionTemplate(manager)
        .executeWithoutResult(
            ignored -> {
              assertThatThrownBy(service::claimNext)
                  .isInstanceOf(IllegalTransactionStateException.class);
              assertThatThrownBy(service::recoverBatch)
                  .isInstanceOf(IllegalTransactionStateException.class);
              assertThatThrownBy(() -> service.renew(null))
                  .isInstanceOf(IllegalTransactionStateException.class);
              assertThatThrownBy(() -> service.fail(null, null))
                  .isInstanceOf(IllegalTransactionStateException.class);
            });
    assertThat(snapshot(q.jobId())).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void claimUpdateAndDeferredCommitFailureDoNotConsumeAssignment(boolean deferred) {
    var q = queue("rollback", 1);
    String before = snapshot(q.jobId());
    installFailure(deferred, q.jobId());
    try {
      assertThatThrownBy(service::claimNext).isInstanceOf(RuntimeException.class);
      assertThat(snapshot(q.jobId())).isEqualTo(before);
      assertThat(count(q.jobId())).isZero();
    } finally {
      removeFailure();
    }
    assertThat(claim().ref().assignmentNumber()).isEqualTo(1);
  }

  @Test
  void recoveryFailureReportsEarlierCommitsAndPreventsClaiming() {
    var first =
        (NewsIngestionResult.Queued) ingestion.ingest(event("first", 1, NOW.plusSeconds(1)));
    var second =
        (NewsIngestionResult.Queued) ingestion.ingest(event("second", 1, NOW.plusSeconds(1)));
    jdbc.update(
        "UPDATE trader.inference_jobs SET created_at=? WHERE job_id=?",
        Timestamp.from(NOW.minusSeconds(1)),
        first.jobId());
    queue("claimable", 1);
    time.set(NOW.plusSeconds(2));
    String before = snapshot(second.jobId());
    installFailure(false, second.jobId());
    try {
      assertThatThrownBy(service::claimNext)
          .isInstanceOfSatisfying(
              RecoveryInfrastructureException.class,
              e -> {
                assertThat(e.committedProgress())
                    .isEqualTo(new RecoverySummary(1, 0, 0, 1, 0, 0, 0, 0));
                assertThat(e.failedJobId()).contains(second.jobId());
                assertThat(e.getCause()).isInstanceOf(DataAccessException.class);
              });
      assertThat(status(first.jobId())).isEqualTo("EXPIRED");
      assertThat(snapshot(second.jobId())).isEqualTo(before);
      assertThat(
              jdbc.queryForObject(
                  "SELECT sum(assignment_count) FROM trader.inference_jobs", Integer.class))
          .isZero();
    } finally {
      removeFailure();
    }
  }

  @Test
  void pendingCandidatesUseCreationTimeThenJobId() {
    var a = queue("order-a", 1);
    var b = queue("order-b", 1);
    UUID expected =
        java.util.stream.Stream.of(a.jobId(), b.jobId())
            .min(java.util.Comparator.comparing(UUID::toString))
            .orElseThrow();
    assertThat(claim().ref().jobId()).isEqualTo(expected);
    assertThat(pending()).isEqualTo(1);
  }

  @Test
  void dueLeaseOrderingPrecedesRetryCapacityWork() {
    var a = queue("lease-a", 1);
    var b = queue("lease-b", 1);
    InferenceJobFixtures.state(jdbc, a.jobId(), JobStatus.RUNNING, NOW);
    InferenceJobFixtures.state(jdbc, b.jobId(), JobStatus.RUNNING, NOW);
    jdbc.update(
        "UPDATE trader.inference_jobs SET lease_expires_at=? WHERE job_id=?",
        Timestamp.from(NOW.plusSeconds(10)),
        b.jobId());
    time.set(NOW.plusSeconds(60));
    assertThat(service(4, 1).recoverBatch().retryWaiting()).isEqualTo(1);
    assertThat(status(b.jobId())).isEqualTo("RETRY_WAIT");
    assertThat(status(a.jobId())).isEqualTo("RUNNING");
    assertThat(service(4, 1).recoverBatch().retryWaiting()).isEqualTo(1);
    assertThat(status(a.jobId())).isEqualTo("RETRY_WAIT");
  }

  @Test
  void requeuedPendingReplacementReceivesExactlyOneSlotCredit() {
    var q = queue("replacement", 1);
    service.fail(claim().ref(), FailureKind.RECOVERABLE);
    service.recoverBatch();
    for (int i = 0; i < 3; i++) {
      queue("slot-" + i, 1);
    }
    assertThat(pending()).isEqualTo(4);
    var newer = queue("replacement", 2);
    assertThat(pending()).isEqualTo(4);
    assertThat(status(q.jobId())).isEqualTo("SUPERSEDED");
    assertThat(count(q.jobId())).isEqualTo(1);
    assertThat(count(newer.jobId())).isZero();
    noOwner(q.jobId());
  }

  @Test
  void discoveryFailureCarriesCommittedProgressWithoutFailedJobId() {
    var q = queue("discovery-failure", 1);
    var a = claim();
    time.set(a.leaseExpiresAt());
    repository.observe(
        (point, id) -> {
          if (point.equals("retriesDiscovered")) {
            throw new org.springframework.dao.DataAccessResourceFailureException(
                "forced discovery failure");
          }
        });
    try {
      assertThatThrownBy(service::recoverBatch)
          .isInstanceOfSatisfying(
              RecoveryInfrastructureException.class,
              e -> {
                assertThat(e.committedProgress())
                    .isEqualTo(new RecoverySummary(1, 0, 0, 0, 1, 0, 0, 0));
                assertThat(e.failedJobId()).isEmpty();
                assertThat(e.getCause()).isInstanceOf(DataAccessException.class);
              });
      assertThat(status(q.jobId())).isEqualTo("RETRY_WAIT");
      noOwner(q.jobId());
      assertThat(count(q.jobId())).isEqualTo(1);
    } finally {
      repository.observe((point, id) -> {});
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"renew", "fail", "requeue"})
  void storageFailureRollsBackOtherLifecycleMutations(String operation) {
    var q = queue("write-rollback", 1);
    var a = claim();
    if (operation.equals("requeue")) {
      service.fail(a.ref(), FailureKind.RECOVERABLE);
    }
    String before = snapshot(q.jobId());
    installFailure(false, q.jobId());
    try {
      assertThatThrownBy(
              () -> {
                switch (operation) {
                  case "renew" -> service.renew(a.ref());
                  case "fail" -> service.fail(a.ref(), FailureKind.PERMANENT);
                  case "requeue" -> service.recoverBatch();
                  default -> throw new AssertionError(operation);
                }
              })
          .isInstanceOf(RuntimeException.class);
      assertThat(snapshot(q.jobId())).isEqualTo(before);
    } finally {
      removeFailure();
    }
  }

  private void installFailure(boolean deferred, UUID job) {
    jdbc.execute(
        """
        CREATE FUNCTION trader.m3a2_fail() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN IF NEW.job_id = '%s'::uuid THEN RAISE EXCEPTION 'forced lifecycle failure'; END IF; RETURN NEW; END; $$
        """
            .formatted(job));
    jdbc.execute(
        deferred
            ? "CREATE CONSTRAINT TRIGGER m3a2_fail AFTER UPDATE ON trader.inference_jobs DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trader.m3a2_fail()"
            : "CREATE TRIGGER m3a2_fail BEFORE UPDATE ON trader.inference_jobs FOR EACH ROW EXECUTE FUNCTION trader.m3a2_fail()");
  }

  private void removeFailure() {
    jdbc.execute("DROP TRIGGER m3a2_fail ON trader.inference_jobs");
    jdbc.execute("DROP FUNCTION trader.m3a2_fail()");
  }
}

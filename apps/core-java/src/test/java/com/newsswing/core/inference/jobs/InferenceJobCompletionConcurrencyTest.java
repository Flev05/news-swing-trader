package com.newsswing.core.inference.jobs;

import static com.newsswing.core.inference.jobs.InferenceJobLifecycleService.*;
import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class InferenceJobCompletionConcurrencyTest extends CompletionTestSupport {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void concurrentIdenticalOrConflictingCompletions(boolean conflict) throws Exception {
    var q = queue("compete", 1);
    var a = claim();
    String p = payload(a);
    var ready = new CyclicBarrier(2);
    repository.observe(
        (point, id) -> {
          if (point.equals("beforeItem")) {
            try {
              ready.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          }
        });
    try (var workers = new Pool()) {
      var first = workers.submit(() -> service.complete(a.ref(), p));
      var second =
          workers.submit(
              () ->
                  service.complete(a.ref(), conflict ? change(p, "/model/name", "different") : p));
      var outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
      assertThat(outcomes.stream().filter(Accepted.class::isInstance)).hasSize(1);
      assertThat(
              outcomes.stream()
                  .filter(
                      conflict
                          ? CompletionConflict.class::isInstance
                          : AlreadyAccepted.class::isInstance))
          .hasSize(1);
      if (!conflict) {
        var receipt =
            ((Accepted)
                    outcomes.stream().filter(Accepted.class::isInstance).findFirst().orElseThrow())
                .receipt();
        assertThat(
                ((AlreadyAccepted)
                        outcomes.stream()
                            .filter(AlreadyAccepted.class::isInstance)
                            .findFirst()
                            .orElseThrow())
                    .receipt())
            .isEqualTo(receipt);
      }
      assertThat(status(q.jobId())).isEqualTo("SUCCEEDED");
      assertThat(resultCount(q.jobId())).isEqualTo(1);
    }
  }

  @Test
  void receiptIsDeliveredAfterCommitAndGuardedDecisionCanPrecedeExpiry() throws Exception {
    var q = queue("visibility", 1);
    var a = claim();
    var committing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    repository.observe(
        (point, id) -> {
          if (point.equals("succeeded")) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                  public void beforeCommit(boolean readOnly) {
                    committing.countDown();
                    await(release);
                  }
                });
          }
        });
    try (var workers = new Pool()) {
      var result = workers.submit(() -> service.complete(a.ref(), payload(a)));
      try {
        await(committing);
        assertThat(result.isDone()).isFalse();
        assertThat(status(q.jobId())).isEqualTo("RUNNING");
        assertThat(resultCount(q.jobId())).isZero();
        time.set(NOW.plusSeconds(601));
      } finally {
        release.countDown();
      }
      var accepted = (Accepted) result.get(15, TimeUnit.SECONDS);
      assertThat(accepted.receipt().acceptedAt()).isEqualTo(NOW);
      assertThat(status(q.jobId())).isEqualTo("SUCCEEDED");
      assertThat(service.findCurrentEligibleResult(q.newsItemId())).isEmpty();
    } finally {
      release.countDown();
    }
  }

  static Stream<Arguments> races() {
    return Stream.of("renew", "recoverable", "permanent", "reassign", "ingest")
        .flatMap(operation -> Stream.of(true, false).map(first -> Arguments.of(operation, first)));
  }

  @ParameterizedTest
  @MethodSource("races")
  void completionRacesRespectBothOrders(String operation, boolean completionFirst)
      throws Exception {
    var q = queue("race", 1);
    var a = claim();
    String p = payload(a);
    var reached = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var gate = new java.util.concurrent.atomic.AtomicBoolean(true);
    repository.observe(
        (point, id) -> {
          if ((completionFirst && point.equals("succeeded"))
              || (!completionFirst
                  && point.equals("beforeItem")
                  && gate.compareAndSet(true, false))) {
            reached.countDown();
            await(release);
          }
        });
    try (var workers = new Pool()) {
      var completion = workers.submit(() -> service.complete(a.ref(), p));
      await(reached);
      Object other;
      if (completionFirst && operation.equals("reassign")) {
        time.set(a.leaseExpiresAt());
        other = service.recoverBatch(); // SKIP LOCKED, while acceptance is uncommitted.
        assertThat(((RecoverySummary) other).skipped()).isEqualTo(1);
        release.countDown();
      } else if (completionFirst) {
        var started = new CountDownLatch(1);
        var competing =
            workers.submit(
                () -> {
                  started.countDown();
                  return competing(operation, a);
                });
        try {
          await(started);
        } finally {
          release.countDown();
        }
        other = competing.get(15, TimeUnit.SECONDS);
      } else {
        try {
          if (operation.equals("renew")) {
            time.set(NOW.plusSeconds(30));
          }
          if (operation.equals("reassign")) {
            time.set(a.leaseExpiresAt());
          }
          other = competing(operation, a);
          if (operation.equals("renew")) {
            time.set(NOW.plusSeconds(61));
          }
        } finally {
          release.countDown();
        }
      }
      var outcome = completion.get(15, TimeUnit.SECONDS);
      if (completionFirst || operation.equals("renew")) {
        assertThat(outcome).isInstanceOf(Accepted.class);
        assertThat(resultCount(q.jobId())).isEqualTo(1);
        assertThat(status(q.jobId())).isEqualTo("SUCCEEDED");
        if (completionFirst && !operation.equals("ingest") && !operation.equals("reassign")) {
          assertThat(other).isEqualTo(new Rejected(RejectionReason.NOT_RUNNING));
        }
      } else {
        assertThat(outcome)
            .isEqualTo(
                new Rejected(
                    operation.equals("reassign")
                        ? RejectionReason.OWNER_MISMATCH
                        : RejectionReason.NOT_RUNNING));
        assertThat(resultCount(q.jobId())).isZero();
      }
      if (operation.equals("ingest") && completionFirst) {
        repository.observe((point, id) -> {});
        assertThat(service.complete(a.ref(), p)).isInstanceOf(AlreadyAccepted.class);
        assertThat(service.findCurrentEligibleResult(q.newsItemId())).isEmpty();
      }
    } finally {
      release.countDown();
    }
  }

  private Object competing(String operation, Assignment a) {
    return switch (operation) {
      case "renew" -> service.renew(a.ref());
      case "recoverable" -> service.fail(a.ref(), FailureKind.RECOVERABLE);
      case "permanent" -> service.fail(a.ref(), FailureKind.PERMANENT);
      case "reassign" -> {
        service.recoverBatch();
        yield claim();
      }
      case "ingest" -> queue("race", 2);
      default -> throw new AssertionError(operation);
    };
  }

  @ParameterizedTest
  @CsvSource({"item,false", "item,true", "mutex,false", "mutex,true"})
  void samplesTimeAfterLocksAndTemporalRejectionWins(String lockKind, boolean invalid)
      throws Exception {
    var q = queue("wait", 1);
    var a = claim();
    String before = snapshot(q.jobId());
    var attempted = new CountDownLatch(1);
    repository.observe(
        (point, id) -> {
          if (point.equals(lockKind.equals("item") ? "beforeItem" : "beforeMutex")) {
            attempted.countDown();
          }
        });
    try (var blocker = blocker();
        var workers = new Pool()) {
      execute(
          blocker,
          lockKind.equals("item")
              ? "SELECT id FROM trader.news_items WHERE id='" + q.newsItemId() + "' FOR UPDATE"
              : "SELECT queue_name FROM trader.queue_admission_locks WHERE queue_name='NEWS_INFERENCE' FOR UPDATE");
      var result = workers.submit(() -> service.complete(a.ref(), invalid ? "{" : payload(a)));
      try {
        await(attempted);
        if (lockKind.equals("mutex")) {
          execute(
              blocker,
              "SELECT job_id FROM trader.inference_jobs WHERE job_id='"
                  + q.jobId()
                  + "' FOR UPDATE NOWAIT");
        }
        time.set(a.leaseExpiresAt());
      } finally {
        blocker.rollback();
      }
      assertThat(result.get(15, TimeUnit.SECONDS))
          .isEqualTo(new Rejected(RejectionReason.LEASE_EXPIRED));
      assertThat(snapshot(q.jobId())).isEqualTo(before);
    }
  }

  @Test
  void lockTimeoutRollsBackAndRemainsInfrastructure() throws Exception {
    var q = queue("timeout", 1);
    var a = claim();
    String before = snapshot(q.jobId());
    try (var blocker = blocker();
        var workers = new Pool()) {
      execute(
          blocker,
          "SELECT queue_name FROM trader.queue_admission_locks WHERE queue_name='NEWS_INFERENCE' FOR UPDATE");
      var result = workers.submit(() -> service.complete(a.ref(), payload(a)));
      assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(DataAccessException.class);
      assertThat(snapshot(q.jobId())).isEqualTo(before);
      assertThat(resultCount(q.jobId())).isZero();
    }
  }

  @Test
  void rollbackOfTentativeAcceptanceLetsWaitingRenewalProceed() throws Exception {
    var q = queue("tentative", 1);
    var a = claim();
    var inserted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var renewing = new CountDownLatch(1);
    repository.observe(
        (point, id) -> {
          if (point.equals("resultInserted")) {
            inserted.countDown();
            await(release);
            throw new org.springframework.dao.DataAccessResourceFailureException(
                "forced acceptance rollback");
          }
          if (point.equals("beforeItem") && inserted.getCount() == 0) {
            renewing.countDown();
          }
        });
    try (var workers = new Pool()) {
      var completion = workers.submit(() -> service.complete(a.ref(), payload(a)));
      await(inserted);
      var renewal = workers.submit(() -> service.renew(a.ref()));
      try {
        await(renewing);
      } finally {
        release.countDown();
      }
      assertThatThrownBy(() -> completion.get(15, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(DataAccessException.class);
      assertThat(renewal.get(15, TimeUnit.SECONDS)).isInstanceOf(Renewed.class);
      assertThat(resultCount(q.jobId())).isZero();
      assertThat(status(q.jobId())).isEqualTo("RUNNING");
    } finally {
      release.countDown();
    }
  }
}

package com.newsswing.core.inference.jobs;

import static com.newsswing.core.inference.jobs.InferenceJobLifecycleService.*;
import static org.assertj.core.api.Assertions.*;

import com.newsswing.core.news.ingestion.NewsIngestionException;
import com.newsswing.core.news.ingestion.NewsIngestionResult;
import com.newsswing.support.InferenceJobFixtures;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class InferenceJobLifecycleConcurrencyTest extends LifecycleTestSupport {
  @Test
  void competingClaimsReceiveDistinctAssignments() throws Exception {
    var a = queue("a", 1);
    var b = queue("b", 1);
    var barrier = new CyclicBarrier(2);
    repository.observe(
        (point, id) -> {
          if (point.equals("pendingDiscovered")) {
            barrier(barrier);
          }
        });
    try (var workers = new Workers()) {
      var first = workers.submit(service::claimNext);
      var second = workers.submit(service::claimNext);
      var one = ((Claimed) first.get(15, TimeUnit.SECONDS)).assignment();
      var two = ((Claimed) second.get(15, TimeUnit.SECONDS)).assignment();
      assertThat(Set.of(one.ref().jobId(), two.ref().jobId()))
          .containsExactlyInAnyOrder(a.jobId(), b.jobId());
      assertThat(one.ref().ownershipToken()).isNotEqualTo(two.ref().ownershipToken());
      assertThat(count(a.jobId())).isEqualTo(1);
      assertThat(count(b.jobId())).isEqualTo(1);
    }
  }

  @Test
  void lockedHeadIsSkippedAndCandidateBudgetCanReturnNoWork() throws Exception {
    var head = queue("head", 1);
    var next = queue("next", 1);
    jdbc.update(
        "UPDATE trader.inference_jobs SET created_at=? WHERE job_id=?",
        Timestamp.from(NOW.minusSeconds(1)),
        head.jobId());
    try (var blocker = blocker()) {
      lock(
          blocker,
          "SELECT id FROM trader.news_items WHERE id='" + head.newsItemId() + "' FOR UPDATE");
      assertThat(service(4, 1).claimNext()).isEqualTo(new NoWork());
      var claimed = ((Claimed) service(4, 2).claimNext()).assignment();
      assertThat(claimed.ref().jobId()).isEqualTo(next.jobId());
      assertThat(count(head.jobId())).isZero();
    }
  }

  @Test
  void recoverySkipsLockedItemsWithoutWaitingOrMutatingThem() throws Exception {
    var q = queue("recover-locked", 1);
    var a = claim();
    time.set(a.leaseExpiresAt());
    String before = snapshot(q.jobId());
    try (var blocker = blocker()) {
      lock(
          blocker, "SELECT id FROM trader.news_items WHERE id='" + q.newsItemId() + "' FOR UPDATE");
      assertThat(service.recoverBatch()).isEqualTo(new RecoverySummary(1, 1, 0, 0, 0, 0, 0, 0));
      assertThat(snapshot(q.jobId())).isEqualTo(before);
    }
  }

  @Test
  void assignmentIsDeliveredOnlyAfterCommitAndInvisibleBeforeIt() throws Exception {
    var q = queue("commit", 1);
    var committing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    repository.observe(
        (point, id) -> {
          if (point.equals("claimed")) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                  @Override
                  public void beforeCommit(boolean readOnly) {
                    committing.countDown();
                    await(release);
                  }
                });
          }
        });
    try (var workers = new Workers()) {
      var result = workers.submit(service::claimNext);
      try {
        await(committing);
        assertThat(result.isDone()).isFalse();
        assertThat(status(q.jobId())).isEqualTo("PENDING");
        assertThat(count(q.jobId())).isZero();
      } finally {
        release.countDown();
      }
      assertThat(result.get(15, TimeUnit.SECONDS)).isInstanceOf(Claimed.class);
      assertThat(status(q.jobId())).isEqualTo("RUNNING");
    } finally {
      release.countDown();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"claim", "recover", "renew", "fail"})
  void samplesDecisionTimeAfterWaitingForMutex(String operation) throws Exception {
    var q = queue("time", 1);
    Assignment a = operation.equals("claim") ? null : claim();
    if (operation.equals("recover")) {
      // Discover a due lease, then make it live before blocking on the mutex.
      time.set(a.leaseExpiresAt());
    }
    var beforeMutex = new CountDownLatch(1);
    repository.observe(
        (point, id) -> {
          if (point.equals("beforeMutex")) {
            beforeMutex.countDown();
          }
        });
    try (var blocker = blocker();
        var workers = new Workers()) {
      lock(
          blocker,
          "SELECT queue_name FROM trader.queue_admission_locks WHERE queue_name='NEWS_INFERENCE' FOR UPDATE");
      var result = workers.submit(() -> operation(operation, a));
      try {
        await(beforeMutex);
        // Waiting lifecycle code has locked the item, but must not yet have locked the job.
        lock(
            blocker,
            "SELECT job_id FROM trader.inference_jobs WHERE job_id='"
                + q.jobId()
                + "' FOR UPDATE NOWAIT");
        time.set(
            operation.equals("claim")
                ? NOW.plusSeconds(600)
                : operation.equals("recover") ? NOW.plusSeconds(30) : a.leaseExpiresAt());
      } finally {
        blocker.rollback();
      }
      Object outcome = result.get(15, TimeUnit.SECONDS);
      switch (operation) {
        case "claim" -> {
          assertThat(outcome).isEqualTo(new NoWork());
          assertThat(count(q.jobId())).isZero();
        }
        case "recover" -> {
          assertThat(((RecoverySummary) outcome).skipped()).isEqualTo(1);
          assertThat(status(q.jobId())).isEqualTo("RUNNING");
        }
        default -> assertThat(outcome).isEqualTo(new Rejected(RejectionReason.LEASE_EXPIRED));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"renew", "fail"})
  void blockingOwnerItemLockAlsoSamplesTimeAfterAcquisition(String operation) throws Exception {
    var q = queue("item-time", 1);
    var a = claim();
    String before = snapshot(q.jobId());
    var attempting = new CountDownLatch(1);
    repository.observe(
        (point, id) -> {
          if (point.equals("beforeItem")) {
            attempting.countDown();
          }
        });
    try (var blocker = blocker();
        var workers = new Workers()) {
      lock(
          blocker, "SELECT id FROM trader.news_items WHERE id='" + q.newsItemId() + "' FOR UPDATE");
      var result = workers.submit(() -> operation(operation, a));
      try {
        await(attempting);
        time.set(a.leaseExpiresAt());
      } finally {
        blocker.rollback();
      }
      assertThat(result.get(15, TimeUnit.SECONDS))
          .isEqualTo(new Rejected(RejectionReason.LEASE_EXPIRED));
      assertThat(snapshot(q.jobId())).isEqualTo(before);
    }
  }

  static Stream<Arguments> raceOrders() {
    return Stream.of("claim", "renew", "fail", "recover")
        .flatMap(op -> Stream.of(Arguments.of(op, true), Arguments.of(op, false)));
  }

  @ParameterizedTest
  @MethodSource("raceOrders")
  void supersessionRacesRespectBothLockOrders(String operation, boolean lifecycleFirst)
      throws Exception {
    var q = queue("race", 1);
    Assignment a = operation.equals("claim") ? null : claim();
    if (operation.equals("recover")) {
      time.set(a.leaseExpiresAt());
    }
    var reached = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    repository.observe(
        (point, id) -> {
          if ((lifecycleFirst && point.equals("jobLocked") && q.jobId().equals(id))
              || (!lifecycleFirst && point.equals("beforeItem") && q.newsItemId().equals(id))) {
            reached.countDown();
            await(release);
          }
        });
    try (var workers = new Workers()) {
      var life = workers.submit(() -> operation(operation, a));
      await(reached);
      NewsIngestionResult.Queued newer;
      if (lifecycleFirst) {
        var started = new CountDownLatch(1);
        var admission =
            workers.submit(
                () -> {
                  started.countDown();
                  return queue("race", 2);
                });
        try {
          await(started);
        } finally {
          release.countDown();
        }
        newer = admission.get(15, TimeUnit.SECONDS);
      } else {
        try {
          newer = queue("race", 2);
        } finally {
          release.countDown();
        }
      }
      Object outcome = life.get(15, TimeUnit.SECONDS);
      if (!lifecycleFirst) {
        if (operation.equals("claim")) {
          assertThat(outcome).isEqualTo(new NoWork());
        } else if (operation.equals("recover")) {
          assertThat(((RecoverySummary) outcome).skipped()).isEqualTo(1);
        } else {
          assertThat(outcome).isEqualTo(new Rejected(RejectionReason.NOT_RUNNING));
        }
      } else {
        switch (operation) {
          case "claim" -> assertThat(outcome).isInstanceOf(Claimed.class);
          case "renew" -> assertThat(outcome).isInstanceOf(Renewed.class);
          case "fail" -> assertThat(outcome).isInstanceOf(RetryWaiting.class);
          case "recover" -> assertThat(((RecoverySummary) outcome).retryWaiting()).isEqualTo(1);
        }
      }
      assertThat(status(q.jobId())).isEqualTo("SUPERSEDED");
      noOwner(q.jobId());
      assertThat(
              jdbc.queryForObject(
                  "SELECT latest_event_id FROM trader.news_items WHERE id=?",
                  UUID.class,
                  q.newsItemId()))
          .isEqualTo(newer.eventId());
      if (a != null) {
        repository.observe((point, id) -> {});
        String before = snapshot(q.jobId());
        assertThat(service.renew(a.ref())).isEqualTo(new Rejected(RejectionReason.NOT_RUNNING));
        assertThat(snapshot(q.jobId())).isEqualTo(before);
      }
    } finally {
      release.countDown();
    }
  }

  @Test
  void concurrentRecoveryAndAdmissionShareExactCapacity() throws Exception {
    var a = queue("retry-a", 1);
    var b = queue("retry-b", 1);
    InferenceJobFixtures.state(jdbc, a.jobId(), JobStatus.RETRY_WAIT, NOW);
    InferenceJobFixtures.state(jdbc, b.jobId(), JobStatus.RETRY_WAIT, NOW);
    for (int i = 0; i < 3; i++) {
      queue("occupied-" + i, 1);
    }
    var ready = new CountDownLatch(2);
    repository.observe(
        (point, id) -> {
          if (point.equals("beforeMutex")) {
            ready.countDown();
          }
        });
    try (var blocker = blocker();
        var workers = new Workers()) {
      lock(
          blocker,
          "SELECT queue_name FROM trader.queue_admission_locks WHERE queue_name='NEWS_INFERENCE' FOR UPDATE");
      var first = workers.submit(service::recoverBatch);
      var second = workers.submit(service::recoverBatch);
      var admissionStarted = new CountDownLatch(1);
      var admission =
          workers.submit(
              () -> {
                admissionStarted.countDown();
                try {
                  return (Object) queue("admission", 1);
                } catch (NewsIngestionException e) {
                  return e;
                }
              });
      try {
        await(ready);
        await(admissionStarted);
      } finally {
        blocker.rollback();
      }
      first.get(15, TimeUnit.SECONDS);
      second.get(15, TimeUnit.SECONDS);
      var outcome = admission.get(15, TimeUnit.SECONDS);
      if (outcome instanceof NewsIngestionException e) {
        assertThat(e.code()).isEqualTo(NewsIngestionException.Code.CAPACITY_EXCEEDED);
      } else {
        assertThat(outcome).isInstanceOf(NewsIngestionResult.Queued.class);
      }
      assertThat(pending()).isEqualTo(4);
      assertThat(count(a.jobId())).isEqualTo(1);
      assertThat(count(b.jobId())).isEqualTo(1);
      noOwner(a.jobId());
      noOwner(b.jobId());
    }
  }

  @Test
  void rejectedNewRevisionAtCapacityPreservesActualOwnership() {
    var q = queue("protected", 1);
    var a = claim();
    for (int i = 0; i < 4; i++) {
      queue("full-" + i, 1);
    }
    String before = snapshot(q.jobId());
    assertThatThrownBy(() -> queue("protected", 2))
        .isInstanceOfSatisfying(
            NewsIngestionException.class,
            e -> assertThat(e.code()).isEqualTo(NewsIngestionException.Code.CAPACITY_EXCEEDED));
    assertThat(snapshot(q.jobId())).isEqualTo(before);
    assertThat(service.renew(a.ref())).isInstanceOf(Renewed.class);
  }

  @Test
  void lockTimeoutIsInfrastructureFailureAndRollsBack() throws Exception {
    var q = queue("timeout", 1);
    var a = claim();
    String before = snapshot(q.jobId());
    try (var blocker = blocker();
        var workers = new Workers()) {
      lock(
          blocker,
          "SELECT queue_name FROM trader.queue_admission_locks WHERE queue_name='NEWS_INFERENCE' FOR UPDATE");
      var result = workers.submit(() -> service.renew(a.ref()));
      assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(DataAccessException.class);
      assertThat(snapshot(q.jobId())).isEqualTo(before);
    }
  }

  private Object operation(String operation, Assignment a) {
    return switch (operation) {
      case "claim" -> service.claimNext();
      case "renew" -> service.renew(a.ref());
      case "fail" -> service.fail(a.ref(), FailureKind.RECOVERABLE);
      case "recover" -> service.recoverBatch();
      default -> throw new AssertionError(operation);
    };
  }

  private Connection blocker() throws SQLException {
    var connection = jdbc.getDataSource().getConnection();
    connection.setAutoCommit(false);
    try {
      lock(connection, "SET LOCAL statement_timeout = '5s'");
      return connection;
    } catch (SQLException failure) {
      connection.close();
      throw failure;
    }
  }

  private static void lock(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void barrier(CyclicBarrier barrier) {
    try {
      barrier.await(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static final class Workers implements AutoCloseable {
    final ExecutorService executor = Executors.newFixedThreadPool(3);

    <T> Future<T> submit(Callable<T> work) {
      return executor.submit(work);
    }

    public void close() throws InterruptedException {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).as("workers terminated").isTrue();
    }
  }
}

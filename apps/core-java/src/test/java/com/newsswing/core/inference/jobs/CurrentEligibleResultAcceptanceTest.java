package com.newsswing.core.inference.jobs;

import static com.newsswing.core.inference.jobs.InferenceJobLifecycleService.*;
import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CurrentEligibleResultAcceptanceTest extends CompletionTestSupport {
  @Test
  void readsOnlySucceededLatestAndNeverFallsBack() {
    assertThat(service.findCurrentEligibleResult(UUID.randomUUID())).isEmpty();
    var q = queue("eligible", 1);
    assertThat(service.findCurrentEligibleResult(q.newsItemId())).isEmpty();
    var a = claim();
    assertThat(service.findCurrentEligibleResult(q.newsItemId())).isEmpty();
    var accepted = (Accepted) service.complete(a.ref(), payload(a));
    var found = service.findCurrentEligibleResult(q.newsItemId()).orElseThrow();
    assertThat(found.newsItemId()).isEqualTo(q.newsItemId());
    assertThat(found.receipt()).isEqualTo(accepted.receipt());
    assertThat(found.decisionAt()).isEqualTo(NOW);
    assertThat(JSON.readTree(found.resultJson())).isEqualTo(JSON.readTree(payload(a)));
    assertThat(
            Arrays.stream(CurrentEligibleResult.class.getRecordComponents()).map(c -> c.getName()))
        .doesNotContain("ownershipToken", "acceptedOwnerToken");
    var newer = queue("eligible", 2);
    assertThat(service.findCurrentEligibleResult(q.newsItemId())).isEmpty();
    var next = claim();
    service.fail(next.ref(), FailureKind.PERMANENT);
    assertThat(service.findCurrentEligibleResult(q.newsItemId())).isEmpty();
    var third = queue("eligible", 3);
    var last = claim();
    var receipt = ((Accepted) service.complete(last.ref(), payload(last))).receipt();
    assertThat(service.findCurrentEligibleResult(q.newsItemId()).orElseThrow().receipt())
        .isEqualTo(receipt);
    assertThat(receipt.newsEventId()).isEqualTo(third.eventId());
    assertThat(resultCount(q.jobId())).isEqualTo(1);
    assertThat(resultCount(newer.jobId())).isZero();
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 0, 1})
  void deadlineIsStrictAtReadDecision(long micros) {
    var q = queue("expiry", 1);
    var a = claim();
    service.complete(a.ref(), payload(a));
    String before = snapshot(q.jobId());
    time.set(NOW.plusSeconds(600).plusNanos(micros * 1000));
    assertThat(service.findCurrentEligibleResult(q.newsItemId()).isPresent()).isEqualTo(micros < 0);
    assertThat(snapshot(q.jobId())).isEqualTo(before);
    assertThat(resultCount(q.jobId())).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void snapshotDoesNotMixConcurrentAdmissionOrAcceptance(boolean admission) throws Exception {
    var q = queue("snapshot", 1);
    var a = claim();
    if (admission) {
      service.complete(a.ref(), payload(a));
    }
    var snapshot = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var gate = new java.util.concurrent.atomic.AtomicBoolean(true);
    repository.observe(
        (point, id) -> {
          if (point.equals("snapshotEstablished") && gate.compareAndSet(true, false)) {
            snapshot.countDown();
            await(release);
          }
        });
    try (var workers = new Pool()) {
      var read = workers.submit(() -> service.findCurrentEligibleResult(q.newsItemId()));
      try {
        await(snapshot);
        if (admission) {
          queue("snapshot", 2);
        } else {
          service.complete(a.ref(), payload(a));
        }
      } finally {
        release.countDown();
      }
      assertThat(read.get(15, TimeUnit.SECONDS).isPresent()).isEqualTo(admission);
      assertThat(service.findCurrentEligibleResult(q.newsItemId()).isPresent())
          .isEqualTo(!admission);
    } finally {
      release.countDown();
    }
  }

  @Test
  void snapshotAndTimeShareReadOnlyRepeatableReadConnection() {
    var q = queue("isolation", 1);
    var a = claim();
    service.complete(a.ref(), payload(a));
    repository.observe(
        (point, id) -> {
          if (point.equals("snapshotEstablished")) {
            assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class))
                .isEqualTo("repeatable read");
            assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class))
                .isEqualTo("on");
            time.set(NOW.plusSeconds(600));
          }
        });
    assertThat(service.findCurrentEligibleResult(q.newsItemId())).isEmpty();
  }

  @Test
  void readsDoNotWaitForItemMutexOrJobLocks() throws Exception {
    var q = queue("nonlocking", 1);
    var a = claim();
    service.complete(a.ref(), payload(a));
    try (var blocker = blocker();
        var workers = new Pool()) {
      execute(
          blocker, "SELECT id FROM trader.news_items WHERE id='" + q.newsItemId() + "' FOR UPDATE");
      execute(blocker, "SELECT queue_name FROM trader.queue_admission_locks FOR UPDATE");
      execute(
          blocker,
          "SELECT job_id FROM trader.inference_jobs WHERE job_id='" + q.jobId() + "' FOR UPDATE");
      assertThat(
              workers
                  .submit(() -> service.findCurrentEligibleResult(q.newsItemId()))
                  .get(5, TimeUnit.SECONDS))
          .isPresent();
    }
  }
}

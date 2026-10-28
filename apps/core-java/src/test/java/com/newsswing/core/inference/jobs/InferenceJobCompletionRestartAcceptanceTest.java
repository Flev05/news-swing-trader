package com.newsswing.core.inference.jobs;

import static com.newsswing.core.inference.jobs.InferenceJobLifecycleService.*;
import static com.newsswing.core.inference.jobs.InferenceJobRestartRecoveryAcceptanceTest.*;
import static org.assertj.core.api.Assertions.*;

import com.newsswing.core.news.domain.NewsEvents.*;
import com.newsswing.core.news.ingestion.*;
import com.newsswing.support.InferenceJobFixtures;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

@Timeout(180)
class InferenceJobCompletionRestartAcceptanceTest {
  static final java.time.Instant NOW = LifecycleTestSupport.NOW;

  static Stream<Arguments> restarts() {
    return Stream.of(30, 601)
        .flatMap(seconds -> Stream.of(false, true).map(newer -> Arguments.of(seconds, newer)));
  }

  @ParameterizedTest
  @MethodSource("restarts")
  void historicalReceiptSurvivesClosedContextsExpiryAndNewerRevision(int seconds, boolean newer) {
    try (var postgres =
        new PostgreSQLContainer<>("postgres:18.6").withStartupTimeout(Duration.ofSeconds(120))) {
      postgres.start();
      String container = postgres.getContainerId();
      Assignment a;
      String p;
      CompletionReceipt receipt;
      String jobBefore;
      String resultBefore;
      List<String> schemaBefore;
      UUID itemId;
      var first = start(postgres, NOW, 3, "fake");
      var poolOne = first.getBean(HikariDataSource.class);
      try (first) {
        var ingest = first.getBean(NewsIngestionCoordinator.class);
        var e = event("completion-restart");
        var queued = (NewsIngestionResult.Queued) ingest.ingest(e);
        itemId = queued.newsItemId();
        var service = first.getBean(InferenceJobLifecycleService.class);
        a = ((Claimed) service.claimNext()).assignment();
        p =
            InferenceJobFixtures.resultPayload(
                a.newsEventJson(), a.inferenceId(), a.expectedBackend(), NOW);
        receipt = ((Accepted) service.complete(a.ref(), p)).receipt();
        if (newer) {
          ingest.ingest(
              new UnhashedNewsEvent(
                  e.schemaVersion(),
                  UUID.randomUUID(),
                  e.correlationId(),
                  e.causationId(),
                  new ProviderRevisionKey(e.source().provider(), e.source().newsId(), "r2"),
                  e.canonicalUrl(),
                  e.sourceCreatedAt(),
                  e.sourceUpdatedAt().plusSeconds(1),
                  e.receivedAt(),
                  e.deadlineAt(),
                  "New revision",
                  e.content(),
                  e.affectedSymbols()));
        }
        var jdbc = first.getBean(JdbcTemplate.class);
        jobBefore = InferenceJobFixtures.snapshot(jdbc, a.ref().jobId());
        resultBefore = result(jdbc, a.ref().jobId());
        schemaBefore = history(jdbc);
      }
      assertThat(first.isActive()).isFalse();
      assertThat(poolOne.isClosed()).isTrue();
      var second = start(postgres, NOW.plusSeconds(seconds), 1, "ollama");
      var poolTwo = second.getBean(HikariDataSource.class);
      try (second) {
        var service = second.getBean(InferenceJobLifecycleService.class);
        var jdbc = second.getBean(JdbcTemplate.class);
        assertThat(InferenceJobFixtures.snapshot(jdbc, a.ref().jobId())).isEqualTo(jobBefore);
        assertThat(service.complete(a.ref(), p)).isEqualTo(new AlreadyAccepted(receipt));
        assertThat(
                service.complete(
                    a.ref(), CompletionTestSupport.change(p, "/model/name", "different")))
            .isEqualTo(new CompletionConflict());
        assertThat(service.complete(a.ref(), CompletionTestSupport.incompatible(p, "\\u0000")))
            .isEqualTo(new CompletionConflict());
        assertThat(service.complete(new AssignmentRef(a.ref().jobId(), 1, UUID.randomUUID()), p))
            .isEqualTo(new Rejected(RejectionReason.OWNER_MISMATCH));
        assertThat(service.findCurrentEligibleResult(itemId).isPresent())
            .isEqualTo(!newer && seconds < 600);
        assertThat(InferenceJobFixtures.snapshot(jdbc, a.ref().jobId())).isEqualTo(jobBefore);
        assertThat(result(jdbc, a.ref().jobId())).isEqualTo(resultBefore);
        assertThat(history(jdbc)).isEqualTo(schemaBefore);
        assertThat(postgres.getContainerId()).isEqualTo(container);
      }
      assertThat(second.isActive()).isFalse();
      assertThat(poolTwo.isClosed()).isTrue();
    }
  }

  private static String result(JdbcTemplate jdbc, UUID id) {
    return jdbc.queryForObject(
        "SELECT to_jsonb(r)::text FROM trader.inference_results r WHERE job_id=?",
        String.class,
        id);
  }
}

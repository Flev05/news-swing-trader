package com.newsswing.core.news.ingestion;

import static com.newsswing.core.news.domain.NewsEvents.*;
import static org.assertj.core.api.Assertions.*;

import com.newsswing.core.inference.jobs.InferenceJobPolicy;
import com.newsswing.core.inference.jobs.JobStatus;
import com.newsswing.core.news.domain.NewsCanonicalizer;
import com.newsswing.support.InferenceJobFixtures;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "trader.news.ingestion.pending-capacity=1",
      "trader.inference.jobs.max-assignments=5",
      "trader.inference.jobs.expected-backend=ollama"
    })
@Testcontainers
@Import(NewsLifecycleCompatibilityTest.ClockConfiguration.class)
class NewsLifecycleCompatibilityTest {
  private static final Instant NOW = Instant.parse("2026-01-15T14:30:30Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6");

  @Autowired private NewsIngestionCoordinator coordinator;
  @Autowired private NewsIngestionRepository repository;
  @Autowired private NewsCanonicalizer canonicalizer;
  @Autowired private NewsEventContractValidator validator;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void isolateFixtures() {
    jdbc.execute(
        "TRUNCATE trader.inference_results, trader.inference_jobs, trader.news_revisions, trader.news_items");
  }

  @ParameterizedTest
  @EnumSource(value = JobStatus.class, names = "SUPERSEDED", mode = EnumSource.Mode.EXCLUDE)
  void replacesEveryLatestStateWithExactPendingCredit(JobStatus status) {
    var original = event("item", "r1", 0);
    var first = (NewsIngestionResult.Queued) coordinator.ingest(original);
    InferenceJobFixtures.state(jdbc, first.jobId(), status, NOW);
    String before = InferenceJobFixtures.snapshot(jdbc, first.jobId());
    String revisionBefore = revisionSnapshot(original.eventId());
    var next = (NewsIngestionResult.Queued) coordinator.ingest(event("item", "r2", 10));

    assertThat(pending()).isEqualTo(1);
    assertThat(latest(first.newsItemId())).isEqualTo(next.eventId());
    assertThat(revisionSnapshot(original.eventId())).isEqualTo(revisionBefore);
    if (status.isTerminal()) {
      assertThat(InferenceJobFixtures.snapshot(jdbc, first.jobId())).isEqualTo(before);
    } else {
      var old =
          jdbc.queryForMap("SELECT * FROM trader.inference_jobs WHERE job_id = ?", first.jobId());
      assertThat(old.get("status")).isEqualTo("SUPERSEDED");
      assertThat(old.get("superseded_by_event_id")).isEqualTo(next.eventId());
      assertThat(old.get("terminal_at")).isEqualTo(Timestamp.from(NOW));
      for (String field :
          List.of(
              "ownership_token", "assignment_inference_id", "assigned_at", "lease_expires_at")) {
        assertThat(old.get(field)).as(field).isNull();
      }
      assertThat(old.get("assignment_count")).isEqualTo(status == JobStatus.PENDING ? 0 : 1);
    }
    assertPolicy(next.jobId(), 5, "ollama");
  }

  @ParameterizedTest
  @EnumSource(
      value = JobStatus.class,
      names = {"RUNNING", "RETRY_WAIT", "SUCCEEDED", "FAILED", "EXPIRED"})
  void fullQueueRejectsWithoutRevokingOwnershipOrChangingHistory(JobStatus status) {
    var original = event("item", "r1", 0);
    var first = (NewsIngestionResult.Queued) coordinator.ingest(original);
    InferenceJobFixtures.state(jdbc, first.jobId(), status, NOW);
    coordinator.ingest(event("unrelated", "r1", 0));
    String before = InferenceJobFixtures.snapshot(jdbc, first.jobId());
    var replacement = event("item", "r2", 10);

    assertThatThrownBy(() -> coordinator.ingest(replacement))
        .isInstanceOfSatisfying(
            NewsIngestionException.class,
            failure ->
                assertThat(failure.code())
                    .isEqualTo(NewsIngestionException.Code.CAPACITY_EXCEEDED));
    assertThat(InferenceJobFixtures.snapshot(jdbc, first.jobId())).isEqualTo(before);
    assertThat(latest(first.newsItemId())).isEqualTo(original.eventId());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM trader.news_revisions WHERE event_id = ?",
                Integer.class,
                replacement.eventId()))
        .isZero();

    // Exact/provider duplicates precede stale/capacity checks in every new state.
    var staleDuplicate =
        new UnhashedNewsEvent(
            original.schemaVersion(),
            UUID.randomUUID(),
            original.correlationId(),
            null,
            original.source(),
            original.canonicalUrl(),
            original.sourceCreatedAt(),
            original.sourceUpdatedAt(),
            NOW.minusSeconds(10),
            NOW,
            original.headline(),
            original.content(),
            original.affectedSymbols());
    var duplicate = (NewsIngestionResult.Duplicate) coordinator.ingest(staleDuplicate);
    assertThat(duplicate.eventId()).isEqualTo(original.eventId());
    assertThat(duplicate.currentJob().orElseThrow().status()).isEqualTo(status);
    assertThat(coordinator.ingest(original)).isInstanceOf(NewsIngestionResult.Duplicate.class);
    var conflict =
        new UnhashedNewsEvent(
            original.schemaVersion(),
            original.eventId(),
            original.correlationId(),
            null,
            original.source(),
            original.canonicalUrl(),
            original.sourceCreatedAt(),
            original.sourceUpdatedAt(),
            original.receivedAt(),
            original.deadlineAt(),
            "Conflicting immutable headline",
            original.content(),
            original.affectedSymbols());
    assertThatThrownBy(() -> coordinator.ingest(conflict))
        .isInstanceOfSatisfying(
            NewsIngestionException.class,
            failure ->
                assertThat(failure.code())
                    .isEqualTo(NewsIngestionException.Code.PROVIDER_REVISION_CONFLICT));
    assertThat(InferenceJobFixtures.snapshot(jdbc, first.jobId())).isEqualTo(before);
  }

  @ParameterizedTest
  @EnumSource(
      value = JobStatus.class,
      names = {"RUNNING", "RETRY_WAIT", "SUCCEEDED", "FAILED", "EXPIRED"})
  void replacementInsertFailureRollsBackSupersessionAndPointer(JobStatus status) {
    var first = (NewsIngestionResult.Queued) coordinator.ingest(event("item", "r1", 0));
    InferenceJobFixtures.state(jdbc, first.jobId(), status, NOW);
    String before = InferenceJobFixtures.snapshot(jdbc, first.jobId());
    var replacement = event("item", "r2", 10);
    jdbc.execute(
        """
        CREATE FUNCTION trader.fail_m3a1_insert() RETURNS trigger LANGUAGE plpgsql AS
        $$ BEGIN RAISE EXCEPTION 'forced job insertion failure'; END; $$
        """);
    jdbc.execute(
        """
        CREATE TRIGGER fail_m3a1_insert BEFORE INSERT ON trader.inference_jobs
        FOR EACH ROW EXECUTE FUNCTION trader.fail_m3a1_insert()
        """);
    try {
      assertThatThrownBy(() -> coordinator.ingest(replacement))
          .isInstanceOf(DataAccessException.class);
    } finally {
      jdbc.execute("DROP TRIGGER fail_m3a1_insert ON trader.inference_jobs");
      jdbc.execute("DROP FUNCTION trader.fail_m3a1_insert()");
    }
    assertThat(InferenceJobFixtures.snapshot(jdbc, first.jobId())).isEqualTo(before);
    assertThat(latest(first.newsItemId())).isEqualTo(first.eventId());
    assertThat(pending()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM trader.news_revisions WHERE event_id = ?",
                Integer.class,
                replacement.eventId()))
        .isZero();
  }

  @Test
  void snapshotsNewPolicyWithoutChangingExistingJobs() {
    var first = (NewsIngestionResult.Queued) coordinator.ingest(event("item", "r1", 0));
    var otherPolicy =
        new NewsIngestionCoordinator(
            canonicalizer,
            validator,
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new NewsIngestionProperties(1),
            new InferenceJobPolicy(2, "fake"),
            transactions);
    var second = (NewsIngestionResult.Queued) otherPolicy.ingest(event("item", "r2", 10));
    assertPolicy(first.jobId(), 5, "ollama");
    assertPolicy(second.jobId(), 2, "fake");
  }

  private void assertPolicy(UUID jobId, int maximum, String backend) {
    var row = jdbc.queryForMap("SELECT * FROM trader.inference_jobs WHERE job_id = ?", jobId);
    assertThat(row.get("max_assignments")).isEqualTo(maximum);
    assertThat(row.get("expected_backend")).isEqualTo(backend);
  }

  private int pending() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM trader.inference_jobs WHERE status = 'PENDING'", Integer.class);
  }

  private UUID latest(UUID itemId) {
    return jdbc.queryForObject(
        "SELECT latest_event_id FROM trader.news_items WHERE id = ?", UUID.class, itemId);
  }

  private String revisionSnapshot(UUID eventId) {
    return jdbc.queryForObject(
        "SELECT to_jsonb(r)::text FROM trader.news_revisions r WHERE event_id = ?",
        String.class,
        eventId);
  }

  private UnhashedNewsEvent event(String newsId, String revision, int offset) {
    return new UnhashedNewsEvent(
        "1.0.0",
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        new ProviderRevisionKey("fixture_wire", newsId, revision),
        "https://news.example.test/" + newsId,
        NOW.minusSeconds(30),
        NOW.minusSeconds(30).plusSeconds(offset),
        NOW.minusSeconds(10),
        NOW.plusSeconds(300),
        "Headline " + revision,
        new Content("en", "Summary", null),
        List.of("ACME"));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}

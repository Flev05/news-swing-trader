package com.newsswing.core;

import static com.newsswing.core.news.domain.NewsEvents.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.newsswing.core.news.domain.NewsCanonicalizer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class V2ToM3aMigrationTest {
  private static final Instant NOW = Instant.parse("2026-01-15T14:30:30Z");

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6");

  @Test
  void upgradesPopulatedCommittedV2WithoutRewritingBusinessHistory() {
    migration("2").migrate();
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    UUID itemId = UUID.randomUUID();
    UUID pendingId = UUID.randomUUID();
    UUID supersededId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO trader.news_items (id, provider, provider_news_id, created_at, updated_at)
        VALUES (?, 'fixture_wire', 'migrated', ?, ?)
        """,
        itemId,
        Timestamp.from(NOW),
        Timestamp.from(NOW));
    UUID first = revision(jdbc, itemId, "r1", 10, "QUEUED");
    UUID latest = revision(jdbc, itemId, "r2", 20, "QUEUED");
    revision(jdbc, itemId, "historical", 0, "OUT_OF_ORDER");
    jdbc.update("UPDATE trader.news_items SET latest_event_id = ? WHERE id = ?", latest, itemId);
    jdbc.update(
        """
        INSERT INTO trader.inference_jobs (job_id, news_revision_id, job_kind, input_schema_version,
            idempotency_key, status, created_at, superseded_at, superseded_by_event_id)
        VALUES (?, ?, 'NEWS_INFERENCE', '1.0.0', ?, 'SUPERSEDED', ?, ?, ?)
        """,
        supersededId,
        first,
        "a".repeat(64),
        Timestamp.from(NOW),
        Timestamp.from(NOW.plusSeconds(1)),
        latest);
    jdbc.update(
        """
        INSERT INTO trader.inference_jobs (job_id, news_revision_id, job_kind, input_schema_version,
            idempotency_key, status, created_at)
        VALUES (?, ?, 'NEWS_INFERENCE', '1.0.0', ?, 'PENDING', ?)
        """,
        pendingId,
        latest,
        "b".repeat(64),
        Timestamp.from(NOW));
    List<String> items = rows(jdbc, "news_items", "id");
    List<String> revisions = rows(jdbc, "news_revisions", "event_id");
    List<String> jobs = rows(jdbc, "inference_jobs", "job_id");
    List<String> history =
        jdbc.queryForList(
            """
        SELECT version || ':' || checksum FROM trader.flyway_schema_history
        WHERE version IS NOT NULL ORDER BY installed_rank
        """,
            String.class);

    var flyway = migration(null);
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    assertThat(rows(jdbc, "news_items", "id")).isEqualTo(items);
    assertThat(rows(jdbc, "news_revisions", "event_id")).isEqualTo(revisions);
    assertThat(
            jdbc.queryForList(
                """
        SELECT (to_jsonb(j) - ARRAY['assignment_count','max_assignments','expected_backend',
          'ownership_token','assignment_inference_id','assigned_at','lease_expires_at',
          'terminal_at','last_failure_code'])::text
        FROM trader.inference_jobs j ORDER BY job_id
        """,
                String.class))
        .isEqualTo(jobs);
    assertThat(
            jdbc.queryForList(
                """
        SELECT version || ':' || checksum FROM trader.flyway_schema_history
        WHERE version IN ('1','2') ORDER BY installed_rank
        """,
                String.class))
        .isEqualTo(history);
    assertThat(
            jdbc.queryForObject(
                "SELECT metadata_value FROM trader.platform_metadata WHERE metadata_key = 'milestone'",
                String.class))
        .isEqualTo("3A");
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM trader.queue_admission_locks", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM trader.inference_results", Integer.class))
        .isZero();
    for (var job : jdbc.queryForList("SELECT * FROM trader.inference_jobs")) {
      assertThat(job.get("assignment_count")).isEqualTo(0);
      assertThat(job.get("max_assignments")).isEqualTo(3);
      assertThat(job.get("expected_backend")).isEqualTo("fake");
      for (String field :
          List.of(
              "ownership_token",
              "assignment_inference_id",
              "assigned_at",
              "lease_expires_at",
              "last_failure_code")) {
        assertThat(job.get(field)).as(field).isNull();
      }
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT terminal_at FROM trader.inference_jobs WHERE job_id = ?",
                Timestamp.class,
                supersededId))
        .isEqualTo(Timestamp.from(NOW.plusSeconds(1)));
    assertThat(
            jdbc.queryForObject(
                "SELECT terminal_at FROM trader.inference_jobs WHERE job_id = ?",
                Timestamp.class,
                pendingId))
        .isNull();
    assertThat(
            jdbc.queryForList(
                """
        SELECT indexname FROM pg_indexes WHERE schemaname = 'trader'
          AND indexname IN ('ix_inference_jobs_pending', 'ix_inference_jobs_running_lease',
                            'ix_inference_jobs_retry_wait')
        """,
                String.class))
        .hasSize(3);
  }

  private Flyway migration(String target) {
    var config =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .defaultSchema("trader")
            .schemas("trader")
            .locations("classpath:db/migration");
    if (target != null) {
      config.target(target);
    }
    return config.load();
  }

  private List<String> rows(JdbcTemplate jdbc, String table, String key) {
    return jdbc.queryForList(
        "SELECT to_jsonb(t)::text FROM trader." + table + " t ORDER BY " + key, String.class);
  }

  private UUID revision(
      JdbcTemplate jdbc, UUID itemId, String revisionId, int offset, String admission) {
    var event =
        new NewsCanonicalizer()
            .canonicalize(
                new UnhashedNewsEvent(
                    "1.0.0",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    new ProviderRevisionKey("fixture_wire", "migrated", revisionId),
                    "https://news.example.test/migrated",
                    NOW.minusSeconds(30),
                    NOW.minusSeconds(30).plusSeconds(offset),
                    NOW,
                    NOW.plusSeconds(300),
                    "Headline " + revisionId,
                    new Content("en", "Summary", null),
                    List.of("ACME")));
    jdbc.update(
        """
        INSERT INTO trader.news_revisions (
            event_id, news_item_id, provider, provider_news_id, provider_revision_id, canonical_url,
            source_created_at, source_updated_at, received_at, deadline_at, content_hash,
            provider_fingerprint, event_payload, admission_state, persisted_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
        """,
        event.eventId(),
        itemId,
        event.source().provider(),
        event.source().newsId(),
        event.source().revisionId(),
        event.canonicalUrl(),
        Timestamp.from(event.sourceCreatedAt()),
        Timestamp.from(event.sourceUpdatedAt()),
        Timestamp.from(event.receivedAt()),
        Timestamp.from(event.deadlineAt()),
        event.contentHash(),
        event.providerFingerprint(),
        event.payloadJson(),
        admission,
        Timestamp.from(NOW));
    return event.eventId();
  }
}

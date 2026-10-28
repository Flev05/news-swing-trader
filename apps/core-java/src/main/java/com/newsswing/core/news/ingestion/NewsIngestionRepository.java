package com.newsswing.core.news.ingestion;

import static com.newsswing.core.news.domain.NewsEvents.CanonicalNewsEvent;

import com.newsswing.core.inference.jobs.InferenceJobPolicy;
import com.newsswing.core.inference.jobs.JobStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Package-private SQL primitives that participate in the coordinator transaction. */
@Repository
class NewsIngestionRepository {

  private final JdbcTemplate jdbcTemplate;

  NewsIngestionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  ExistingRevision findByEventId(UUID eventId) {
    List<ExistingRevision> revisions =
        jdbcTemplate.query(
            """
            SELECT r.event_id, r.news_item_id, i.provider, i.provider_news_id, r.provider_revision_id,
                   r.provider_fingerprint, r.admission_state, j.job_id, j.status
            FROM trader.news_revisions r
            JOIN trader.news_items i ON i.id = r.news_item_id
            LEFT JOIN trader.inference_jobs j ON j.news_revision_id = r.event_id
            WHERE r.event_id = ?
            """,
            this::mapExistingRevision,
            eventId);
    return revisions.isEmpty() ? null : revisions.getFirst();
  }

  UUID findNewsItemId(String provider, String newsId) {
    List<UUID> ids =
        jdbcTemplate.query(
            """
            SELECT id
            FROM trader.news_items
            WHERE provider = ? AND provider_news_id = ?
            """,
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
            provider,
            newsId);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  UUID findOrCreateAndLockItem(CanonicalNewsEvent event, Instant requestNow) {
    jdbcTemplate.update(
        """
        INSERT INTO trader.news_items (id, provider, provider_news_id, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (provider, provider_news_id) DO NOTHING
        """,
        UUID.randomUUID(),
        event.source().provider(),
        event.source().newsId(),
        Timestamp.from(requestNow),
        Timestamp.from(requestNow));

    return jdbcTemplate.queryForObject(
        """
        SELECT id
        FROM trader.news_items
        WHERE provider = ? AND provider_news_id = ?
        FOR UPDATE
        """,
        UUID.class,
        event.source().provider(),
        event.source().newsId());
  }

  ExistingRevision findByProviderRevision(UUID itemId, String revisionId) {
    List<ExistingRevision> revisions =
        jdbcTemplate.query(
            """
            SELECT r.event_id, r.news_item_id, i.provider, i.provider_news_id, r.provider_revision_id,
                   r.provider_fingerprint, r.admission_state, j.job_id, j.status
            FROM trader.news_revisions r
            JOIN trader.news_items i ON i.id = r.news_item_id
            LEFT JOIN trader.inference_jobs j ON j.news_revision_id = r.event_id
            WHERE r.news_item_id = ? AND r.provider_revision_id = ?
            """,
            this::mapExistingRevision,
            itemId,
            revisionId);
    return revisions.isEmpty() ? null : revisions.getFirst();
  }

  LatestRevision findLatest(UUID itemId) {
    List<LatestRevision> revisions =
        jdbcTemplate.query(
            """
            SELECT r.event_id, r.source_updated_at
            FROM trader.news_items i
            JOIN trader.news_revisions r ON r.event_id = i.latest_event_id
            WHERE i.id = ?
            """,
            (resultSet, rowNumber) ->
                new LatestRevision(
                    resultSet.getObject("event_id", UUID.class),
                    resultSet.getTimestamp("source_updated_at").toInstant()),
            itemId);
    return revisions.isEmpty() ? null : revisions.getFirst();
  }

  CurrentJob findJobForUpdate(UUID eventId) {
    List<CurrentJob> jobs =
        jdbcTemplate.query(
            """
            SELECT job_id, status
            FROM trader.inference_jobs
            WHERE news_revision_id = ?
            FOR UPDATE
            """,
            (resultSet, rowNumber) ->
                new CurrentJob(
                    resultSet.getObject("job_id", UUID.class),
                    JobStatus.valueOf(resultSet.getString("status"))),
            eventId);
    return jobs.isEmpty() ? null : jobs.getFirst();
  }

  void lockQueueAdmission() {
    String lockName =
        jdbcTemplate.queryForObject(
            """
            SELECT queue_name
            FROM trader.queue_admission_locks
            WHERE queue_name = ?
            FOR UPDATE
            """,
            String.class,
            "NEWS_INFERENCE");
    if (!"NEWS_INFERENCE".equals(lockName)) {
      throw new IllegalStateException("NEWS_INFERENCE queue-admission lock is missing");
    }
  }

  long countPendingJobs() {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM trader.inference_jobs WHERE status = ?", Long.class, "PENDING");
    return count == null ? 0 : count;
  }

  void insertRevision(
      UUID itemId, CanonicalNewsEvent event, AdmissionState admissionState, Instant requestNow) {
    jdbcTemplate.update(
        """
        INSERT INTO trader.news_revisions (
            event_id, news_item_id, provider, provider_news_id, provider_revision_id,
            canonical_url, source_created_at, source_updated_at, received_at, deadline_at,
            content_hash, provider_fingerprint, event_payload, admission_state, persisted_at
        )
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
        admissionState.name(),
        Timestamp.from(requestNow));
  }

  int supersedeActiveJob(UUID previousEventId, UUID replacementEventId, Instant requestNow) {
    return jdbcTemplate.update(
        """
        UPDATE trader.inference_jobs
        SET status = 'SUPERSEDED', superseded_at = ?, superseded_by_event_id = ?,
            terminal_at = ?, ownership_token = NULL, assignment_inference_id = NULL,
            assigned_at = NULL, lease_expires_at = NULL
        WHERE news_revision_id = ? AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
        """,
        Timestamp.from(requestNow),
        replacementEventId,
        Timestamp.from(requestNow),
        previousEventId);
  }

  void insertPendingJob(
      UUID jobId, CanonicalNewsEvent event, Instant requestNow, InferenceJobPolicy policy) {
    jdbcTemplate.update(
        """
        INSERT INTO trader.inference_jobs (
            job_id, news_revision_id, job_kind, input_schema_version, idempotency_key, status,
            created_at, max_assignments, expected_backend
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        jobId,
        event.eventId(),
        "NEWS_INFERENCE",
        "1.0.0",
        event.jobIdempotencyKey(),
        "PENDING",
        Timestamp.from(requestNow),
        policy.maxAssignments(),
        policy.expectedBackend());
  }

  void updateLatestPointer(UUID itemId, UUID eventId, Instant requestNow) {
    jdbcTemplate.update(
        """
        UPDATE trader.news_items
        SET latest_event_id = ?, updated_at = ?
        WHERE id = ?
        """,
        eventId,
        Timestamp.from(requestNow),
        itemId);
  }

  private ExistingRevision mapExistingRevision(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new ExistingRevision(
        resultSet.getObject("event_id", UUID.class),
        resultSet.getObject("news_item_id", UUID.class),
        resultSet.getString("provider"),
        resultSet.getString("provider_news_id"),
        resultSet.getString("provider_revision_id"),
        resultSet.getString("provider_fingerprint"),
        AdmissionState.valueOf(resultSet.getString("admission_state")),
        resultSet.getObject("job_id", UUID.class),
        resultSet.getString("status") == null
            ? null
            : JobStatus.valueOf(resultSet.getString("status")));
  }
}

enum AdmissionState {
  QUEUED,
  OUT_OF_ORDER
}

record ExistingRevision(
    UUID eventId,
    UUID newsItemId,
    String provider,
    String newsId,
    String revisionId,
    String providerFingerprint,
    AdmissionState admissionState,
    UUID jobId,
    JobStatus jobStatus) {

  boolean matches(CanonicalNewsEvent event) {
    return matchesProviderRevision(event)
        && providerFingerprint.equals(event.providerFingerprint());
  }

  boolean matchesProviderRevision(CanonicalNewsEvent event) {
    return provider.equals(event.source().provider())
        && newsId.equals(event.source().newsId())
        && revisionId.equals(event.source().revisionId());
  }
}

record LatestRevision(UUID eventId, Instant sourceUpdatedAt) {}

record CurrentJob(UUID jobId, JobStatus status) {}

package com.newsswing.core.news.api;

import com.newsswing.core.inference.jobs.JobStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class NewsStatusRepository {

  private final JdbcTemplate jdbcTemplate;

  NewsStatusRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  Optional<RevisionRow> findRevision(UUID eventId) {
    List<RevisionRow> rows =
        jdbcTemplate.query(
            """
            SELECT r.event_payload::text AS event_payload, r.news_item_id, r.admission_state,
                   r.persisted_at, j.job_id, j.status, j.created_at, j.superseded_at,
                   j.superseded_by_event_id
            FROM trader.news_revisions r
            LEFT JOIN trader.inference_jobs j ON j.news_revision_id = r.event_id
            WHERE r.event_id = ?
            """,
            this::mapRevision,
            eventId);
    return rows.stream().findFirst();
  }

  Optional<JobRow> findJob(UUID jobId) {
    List<JobRow> rows =
        jdbcTemplate.query(
            """
            SELECT job_id, news_revision_id, job_kind, input_schema_version, status, created_at,
                   superseded_at, superseded_by_event_id
            FROM trader.inference_jobs
            WHERE job_id = ?
            """,
            this::mapJob,
            jobId);
    return rows.stream().findFirst();
  }

  private RevisionRow mapRevision(ResultSet resultSet, int rowNumber) throws SQLException {
    return new RevisionRow(
        resultSet.getString("event_payload"),
        resultSet.getObject("news_item_id", UUID.class),
        resultSet.getString("admission_state"),
        instant(resultSet.getTimestamp("persisted_at")),
        resultSet.getObject("job_id", UUID.class),
        resultSet.getString("status") == null
            ? null
            : JobStatus.valueOf(resultSet.getString("status")),
        instant(resultSet.getTimestamp("created_at")),
        instant(resultSet.getTimestamp("superseded_at")),
        resultSet.getObject("superseded_by_event_id", UUID.class));
  }

  private JobRow mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
    return new JobRow(
        resultSet.getObject("job_id", UUID.class),
        resultSet.getObject("news_revision_id", UUID.class),
        resultSet.getString("job_kind"),
        resultSet.getString("input_schema_version"),
        resultSet.getString("status") == null
            ? null
            : JobStatus.valueOf(resultSet.getString("status")),
        instant(resultSet.getTimestamp("created_at")),
        instant(resultSet.getTimestamp("superseded_at")),
        resultSet.getObject("superseded_by_event_id", UUID.class));
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}

record RevisionRow(
    String eventPayload,
    UUID newsItemId,
    String admissionState,
    Instant persistedAt,
    UUID jobId,
    JobStatus jobStatus,
    Instant jobCreatedAt,
    Instant jobSupersededAt,
    UUID supersededByEventId) {}

record JobRow(
    UUID jobId,
    UUID newsEventId,
    String jobKind,
    String inputSchemaVersion,
    JobStatus status,
    Instant createdAt,
    Instant supersededAt,
    UUID supersededByEventId) {}

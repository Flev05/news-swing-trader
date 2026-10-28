package com.newsswing.core.inference.jobs;

import static com.newsswing.core.inference.jobs.InferenceJobLifecycleService.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Transaction-bound SQL primitives. Discovery never acquires job locks. */
@Repository
class InferenceJobLifecycleRepository {
  private final JdbcTemplate jdbc;
  private final LifecycleTimeSource timeSource;

  InferenceJobLifecycleRepository(JdbcTemplate jdbc, LifecycleTimeSource timeSource) {
    this.jdbc = jdbc;
    this.timeSource = timeSource;
  }

  void configureTransaction() {
    jdbc.execute("SET LOCAL lock_timeout = '2s'");
    jdbc.execute("SET LOCAL statement_timeout = '5s'");
  }

  Instant now() {
    return jdbc.execute((ConnectionCallback<Instant>) timeSource::now);
  }

  List<Candidate> discoverPending(int limit) {
    return jdbc.query(
        """
        SELECT j.job_id, r.news_item_id
        FROM trader.inference_jobs j
        JOIN trader.news_revisions r ON r.event_id = j.news_revision_id
        WHERE j.status = 'PENDING'
        ORDER BY j.created_at, j.job_id LIMIT ?
        """,
        (rs, n) -> new Candidate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
        limit);
  }

  List<Candidate> discoverCleanup(Instant cutoff, int limit) {
    var time = Timestamp.from(cutoff);
    return jdbc.query(
        """
        SELECT j.job_id, r.news_item_id
        FROM trader.inference_jobs j
        JOIN trader.news_revisions r ON r.event_id = j.news_revision_id
        JOIN trader.news_items i ON i.id = r.news_item_id
        WHERE j.status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
          AND (i.latest_event_id IS DISTINCT FROM j.news_revision_id OR r.deadline_at <= ?
               OR (j.status = 'RUNNING' AND j.lease_expires_at <= ?))
        ORDER BY
          CASE WHEN i.latest_event_id IS DISTINCT FROM j.news_revision_id
                    OR r.deadline_at <= ? THEN 0 ELSE 1 END,
          CASE WHEN i.latest_event_id IS DISTINCT FROM j.news_revision_id
                    OR r.deadline_at <= ? THEN j.created_at ELSE j.lease_expires_at END,
          j.job_id
        LIMIT ?
        """,
        (rs, n) -> new Candidate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
        time,
        time,
        time,
        time,
        limit);
  }

  List<Candidate> discoverRetries(int limit) {
    return jdbc.query(
        """
        SELECT j.job_id, r.news_item_id
        FROM trader.inference_jobs j
        JOIN trader.news_revisions r ON r.event_id = j.news_revision_id
        WHERE j.status = 'RETRY_WAIT'
        ORDER BY j.created_at, j.job_id LIMIT ?
        """,
        (rs, n) -> new Candidate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
        limit);
  }

  Candidate findCandidate(UUID jobId) {
    var rows =
        jdbc.query(
            """
        SELECT j.job_id, r.news_item_id
        FROM trader.inference_jobs j
        JOIN trader.news_revisions r ON r.event_id = j.news_revision_id
        WHERE j.job_id = ?
        """,
            (rs, n) -> new Candidate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
            jobId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  ItemRow lockItem(UUID itemId, boolean skipLocked) {
    var rows =
        jdbc.query(
            """
        SELECT latest_event_id FROM trader.news_items i WHERE i.id = ?
        FOR UPDATE OF i
        """
                + (skipLocked ? " SKIP LOCKED" : ""),
            (rs, n) -> new ItemRow(rs.getObject(1, UUID.class)),
            itemId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  void lockAdmission() {
    var name =
        jdbc.queryForObject(
            """
        SELECT queue_name FROM trader.queue_admission_locks
        WHERE queue_name = 'NEWS_INFERENCE' FOR UPDATE
        """,
            String.class);
    if (!"NEWS_INFERENCE".equals(name)) {
      throw new IllegalStateException("NEWS_INFERENCE admission mutex is missing");
    }
  }

  JobRow lockJob(UUID jobId) {
    var rows =
        jdbc.query(
            """
        SELECT j.job_id, j.news_revision_id, j.status, j.assignment_count, j.max_assignments,
               j.expected_backend, j.ownership_token, j.assignment_inference_id,
               j.lease_expires_at, j.last_failure_code, r.deadline_at,
               r.event_payload::text AS event_payload, r.content_hash, j.input_schema_version
        FROM trader.inference_jobs j
        JOIN trader.news_revisions r ON r.event_id = j.news_revision_id
        WHERE j.job_id = ? FOR UPDATE OF j
        """,
            (rs, n) ->
                new JobRow(
                    rs.getObject("job_id", UUID.class),
                    rs.getObject("news_revision_id", UUID.class),
                    JobStatus.valueOf(rs.getString("status")),
                    rs.getInt("assignment_count"),
                    rs.getInt("max_assignments"),
                    rs.getString("expected_backend"),
                    rs.getObject("ownership_token", UUID.class),
                    rs.getObject("assignment_inference_id", UUID.class),
                    rs.getTimestamp("lease_expires_at") == null
                        ? null
                        : rs.getTimestamp("lease_expires_at").toInstant(),
                    rs.getString("last_failure_code"),
                    rs.getTimestamp("deadline_at").toInstant(),
                    rs.getString("event_payload"),
                    rs.getString("content_hash"),
                    rs.getString("input_schema_version")),
            jobId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  AcceptedRow findAccepted(UUID jobId) {
    var rows =
        jdbc.query(
            """
        SELECT job_id, news_revision_id, inference_id, accepted_at,
               assignment_number, accepted_owner_token
        FROM trader.inference_results WHERE job_id = ?
        """,
            (rs, n) ->
                new AcceptedRow(
                    new CompletionReceipt(
                        rs.getObject("job_id", UUID.class),
                            rs.getObject("news_revision_id", UUID.class),
                        rs.getObject("inference_id", UUID.class),
                            rs.getTimestamp("accepted_at").toInstant()),
                    rs.getInt("assignment_number"),
                    rs.getObject("accepted_owner_token", UUID.class)),
            jobId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  boolean payloadEquals(UUID jobId, String json) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT result_payload = CAST(? AS jsonb) FROM trader.inference_results WHERE job_id = ?",
            Boolean.class,
            json,
            jobId));
  }

  void insertResult(JobRow job, String json, Instant now) {
    requireOne(
        jdbc.update(
            """
        INSERT INTO trader.inference_results (
            job_id, news_revision_id, inference_id, news_event_sha256, result_schema_version,
            backend, result_payload, assignment_number, accepted_owner_token, accepted_at)
        VALUES (?, ?, ?, ?, '1.0.0', ?, CAST(? AS jsonb), ?, ?, ?)
        """,
            job.jobId(),
            job.revisionId(),
            job.inferenceId(),
            job.contentHash(),
            job.expectedBackend(),
            json,
            job.assignmentCount(),
            job.token(),
            Timestamp.from(now)));
  }

  void succeed(JobRow job, Instant now) {
    requireOne(
        jdbc.update(
            """
        UPDATE trader.inference_jobs
        SET status = 'SUCCEEDED', terminal_at = ?, ownership_token = NULL,
            assignment_inference_id = NULL, assigned_at = NULL, lease_expires_at = NULL,
            last_failure_code = NULL
        WHERE job_id = ? AND status = 'RUNNING' AND assignment_count = ?
          AND ownership_token = ? AND assignment_inference_id = ?
        """,
            Timestamp.from(now),
            job.jobId(),
            job.assignmentCount(),
            job.token(),
            job.inferenceId()));
  }

  void establishReadSnapshot() {
    jdbc.queryForObject("SELECT pg_current_snapshot()::text", String.class);
  }

  Optional<CurrentEligibleResult> findCurrentEligibleResult(UUID itemId, Instant decisionAt) {
    return jdbc
        .query(
            """
        SELECT a.job_id, a.news_revision_id, a.inference_id, a.accepted_at,
               a.result_payload::text AS result_payload
        FROM trader.news_items i
        JOIN trader.news_revisions r ON r.event_id = i.latest_event_id AND r.news_item_id = i.id
        JOIN trader.inference_jobs j ON j.news_revision_id = r.event_id
        JOIN trader.inference_results a ON a.job_id = j.job_id AND a.news_revision_id = r.event_id
        WHERE i.id = ? AND j.status = 'SUCCEEDED' AND r.deadline_at > ?
        """,
            (rs, n) ->
                new CurrentEligibleResult(
                    itemId,
                    new CompletionReceipt(
                        rs.getObject("job_id", UUID.class),
                            rs.getObject("news_revision_id", UUID.class),
                        rs.getObject("inference_id", UUID.class),
                            rs.getTimestamp("accepted_at").toInstant()),
                    rs.getString("result_payload"),
                    decisionAt),
            itemId,
            Timestamp.from(decisionAt))
        .stream()
        .findFirst();
  }

  record AcceptedRow(CompletionReceipt receipt, int assignmentNumber, UUID token) {}

  long countPending() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM trader.inference_jobs WHERE status = 'PENDING'", Long.class);
  }

  void claim(JobRow job, UUID token, UUID inferenceId, Instant now, Instant expiry) {
    requireOne(
        jdbc.update(
            """
        UPDATE trader.inference_jobs
        SET status = 'RUNNING', assignment_count = assignment_count + 1,
            ownership_token = ?, assignment_inference_id = ?, assigned_at = ?, lease_expires_at = ?
        WHERE job_id = ? AND status = 'PENDING' AND assignment_count = ?
          AND assignment_count < max_assignments
        """,
            token,
            inferenceId,
            Timestamp.from(now),
            Timestamp.from(expiry),
            job.jobId(),
            job.assignmentCount()));
  }

  void renew(JobRow job, Instant expiry) {
    requireOne(
        jdbc.update(
            """
        UPDATE trader.inference_jobs SET lease_expires_at = ?
        WHERE job_id = ? AND status = 'RUNNING' AND assignment_count = ?
          AND ownership_token = ? AND lease_expires_at = ?
        """,
            Timestamp.from(expiry),
            job.jobId(),
            job.assignmentCount(),
            job.token(),
            Timestamp.from(job.leaseExpiresAt())));
  }

  void retryWaiting(JobRow job, FailureCode code, Instant now) {
    transition(job, JobStatus.RETRY_WAIT, code.name(), null, now);
  }

  void fail(JobRow job, FailureCode code, Instant now) {
    transition(job, JobStatus.FAILED, code.name(), null, now);
  }

  void expire(JobRow job, Instant now) {
    transition(job, JobStatus.EXPIRED, job.lastFailureCode(), null, now);
  }

  void supersede(JobRow job, UUID latest, Instant now) {
    if (latest == null) {
      throw new IllegalStateException("A job's item must have a latest queued revision");
    }
    transition(job, JobStatus.SUPERSEDED, job.lastFailureCode(), latest, now);
  }

  void requeue(JobRow job, Instant now) {
    transition(job, JobStatus.PENDING, job.lastFailureCode(), null, now);
  }

  private void transition(JobRow job, JobStatus target, String failure, UUID latest, Instant now) {
    requireOne(
        jdbc.update(
            """
        UPDATE trader.inference_jobs
        SET status = ?, ownership_token = NULL, assignment_inference_id = NULL,
            assigned_at = NULL, lease_expires_at = NULL, last_failure_code = ?,
            terminal_at = ?, superseded_at = ?, superseded_by_event_id = ?
        WHERE job_id = ? AND status = ? AND assignment_count = ?
          AND ownership_token IS NOT DISTINCT FROM ?
        """,
            target.name(),
            failure,
            target.isTerminal() ? Timestamp.from(now) : null,
            target == JobStatus.SUPERSEDED ? Timestamp.from(now) : null,
            latest,
            job.jobId(),
            job.status().name(),
            job.assignmentCount(),
            job.token()));
  }

  private static void requireOne(int count) {
    if (count != 1) {
      throw new IllegalStateException("Expected exactly one guarded lifecycle update");
    }
  }

  record Candidate(UUID jobId, UUID itemId) {}

  record ItemRow(UUID latestEventId) {}

  record JobRow(
      UUID jobId,
      UUID revisionId,
      JobStatus status,
      int assignmentCount,
      int maxAssignments,
      String expectedBackend,
      UUID token,
      UUID inferenceId,
      Instant leaseExpiresAt,
      String lastFailureCode,
      Instant deadlineAt,
      String eventPayload,
      String contentHash,
      String inputSchemaVersion) {}
}

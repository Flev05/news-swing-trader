package com.newsswing.support;

import com.newsswing.core.inference.jobs.JobStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Explicit SQL fixtures only. These are not implementations of lifecycle operations. */
public final class InferenceJobFixtures {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private InferenceJobFixtures() {}

  public static void state(JdbcTemplate jdbc, UUID jobId, JobStatus status, Instant now) {
    if (status == JobStatus.SUPERSEDED) {
      throw new IllegalArgumentException(
          "Construct supersession by admitting an actual newer revision");
    }
    new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
        .executeWithoutResult(
            ignored -> {
              boolean running = status == JobStatus.RUNNING;
              int count =
                  switch (status) {
                    case RUNNING, RETRY_WAIT, SUCCEEDED, FAILED -> 1;
                    default -> 0;
                  };
              String failure =
                  switch (status) {
                    case RETRY_WAIT -> "WORKER_RECOVERABLE";
                    case FAILED -> "WORKER_PERMANENT";
                    default -> null;
                  };
              jdbc.update(
                  """
              UPDATE trader.inference_jobs
              SET status = ?, assignment_count = ?, ownership_token = ?,
                  assignment_inference_id = ?, assigned_at = ?, lease_expires_at = ?,
                  terminal_at = ?, last_failure_code = ?
              WHERE job_id = ?
              """,
                  status.name(),
                  count,
                  running ? UUID.randomUUID() : null,
                  running ? UUID.randomUUID() : null,
                  running ? Timestamp.from(now) : null,
                  running ? Timestamp.from(now.plusSeconds(60)) : null,
                  status.isTerminal() ? Timestamp.from(now) : null,
                  failure,
                  jobId);
              if (status == JobStatus.SUCCEEDED) {
                insertResult(jdbc, jobId, UUID.randomUUID(), UUID.randomUUID(), now);
              }
            });
  }

  public static String insertResult(
      JdbcTemplate jdbc, UUID jobId, UUID inferenceId, UUID acceptedToken, Instant now) {
    var row =
        jdbc.queryForMap(
            """
        SELECT j.news_revision_id, j.assignment_count, j.expected_backend, r.content_hash,
               r.event_payload::text AS event_payload
        FROM trader.inference_jobs j
        JOIN trader.news_revisions r ON r.event_id = j.news_revision_id
        WHERE j.job_id = ?
        """,
            jobId);
    List<Map<String, Object>> tickers = new ArrayList<>();
    JSON.readTree((String) row.get("event_payload"))
        .path("affected_symbols")
        .forEach(
            symbol -> {
              Map<String, Object> ticker = new LinkedHashMap<>();
              ticker.put("ticker", symbol.asString());
              for (String field :
                  List.of(
                      "issuer_role",
                      "event_type",
                      "direction",
                      "materiality",
                      "relevance",
                      "novelty",
                      "expected_horizon",
                      "schedule_status",
                      "information_status")) {
                ticker.put(field, "unknown");
              }
              ticker.put("abstain", true);
              ticker.put("reason_codes", List.of("insufficient_source_detail"));
              tickers.add(ticker);
            });
    String payload =
        JSON.writeValueAsString(
            Map.of(
                "schema_version",
                "1.0.0",
                "inference_id",
                inferenceId.toString(),
                "news_event_id",
                row.get("news_revision_id").toString(),
                "news_event_sha256",
                row.get("content_hash"),
                "generated_at",
                now.toString(),
                "model",
                Map.of(
                    "backend",
                    row.get("expected_backend"),
                    "name",
                    "explicit-test-fixture",
                    "version",
                    "1",
                    "prompt_template_version",
                    "1.0.0"),
                "ticker_results",
                tickers));
    jdbc.update(
        """
        INSERT INTO trader.inference_results (
            job_id, news_revision_id, inference_id, news_event_sha256, result_schema_version,
            backend, result_payload, assignment_number, accepted_owner_token, accepted_at)
        VALUES (?, ?, ?, ?, '1.0.0', ?, CAST(? AS jsonb), ?, ?, ?)
        """,
        jobId,
        row.get("news_revision_id"),
        inferenceId,
        row.get("content_hash"),
        row.get("expected_backend"),
        payload,
        row.get("assignment_count"),
        acceptedToken,
        Timestamp.from(now));
    return payload;
  }

  public static String snapshot(JdbcTemplate jdbc, UUID jobId) {
    return jdbc.queryForObject(
        "SELECT to_jsonb(j)::text FROM trader.inference_jobs j WHERE job_id = ?",
        String.class,
        jobId);
  }
}

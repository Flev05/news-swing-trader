package com.newsswing.core.inference.jobs;

import static com.newsswing.core.news.domain.NewsEvents.*;
import static org.assertj.core.api.Assertions.*;

import com.newsswing.core.contracts.SharedContractValidator;
import com.newsswing.core.news.ingestion.NewsIngestionCoordinator;
import com.newsswing.core.news.ingestion.NewsIngestionResult;
import com.newsswing.support.InferenceJobFixtures;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class LifecycleSchemaConstraintTest {
  private static final Instant NOW = Instant.parse("2026-01-15T14:30:30Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NewsIngestionCoordinator coordinator;
  private UUID jobId;

  @BeforeEach
  void seedPendingJob() {
    var event =
        new UnhashedNewsEvent(
            "1.0.0",
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            new ProviderRevisionKey("fixture_wire", UUID.randomUUID().toString(), "r1"),
            "https://news.example.test/constraints",
            NOW,
            NOW,
            NOW,
            Instant.parse("2099-01-15T14:35:00Z"),
            "Headline",
            new Content("en", "Summary", null),
            List.of("ACME"));
    jobId = ((NewsIngestionResult.Queued) coordinator.ingest(event)).jobId();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidJobs")
  void rejectsInvalidJobRowsWithoutChangingTheOriginal(
      String name, String update, String constraint) {
    String before = InferenceJobFixtures.snapshot(jdbc, jobId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE trader.inference_jobs SET " + update + " WHERE job_id = ?", jobId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining(constraint);
    assertThat(InferenceJobFixtures.snapshot(jdbc, jobId)).isEqualTo(before);
  }

  static Stream<Arguments> invalidJobs() {
    String owner =
        "status='RUNNING', assignment_count=1, ownership_token='11111111-1111-4111-8111-111111111111', "
            + "assignment_inference_id='22222222-2222-4222-8222-222222222222', "
            + "assigned_at='2026-01-15T14:30:30Z', lease_expires_at='2026-01-15T14:31:30Z'";
    return Stream.of(
        Arguments.of(
            "negative assignments", "assignment_count=-1", "ck_inference_jobs_assignments"),
        Arguments.of(
            "zero maximum",
            "status='EXPIRED', terminal_at=CURRENT_TIMESTAMP, max_assignments=0",
            "ck_inference_jobs_assignments"),
        Arguments.of(
            "over maximum",
            "status='EXPIRED', terminal_at=CURRENT_TIMESTAMP, assignment_count=4",
            "ck_inference_jobs_assignments"),
        Arguments.of(
            "exhausted pending", "assignment_count=3", "ck_inference_jobs_assignment_eligibility"),
        Arguments.of(
            "unsupported backend", "expected_backend='external'", "ck_inference_jobs_backend"),
        Arguments.of("unknown status", "status='UNKNOWN'", "ck_inference_jobs_status"),
        Arguments.of(
            "unassigned running",
            owner.replace("assignment_count=1", "assignment_count=0"),
            "ck_inference_jobs_assignment_eligibility"),
        Arguments.of(
            "missing token",
            owner.replace(
                "ownership_token='11111111-1111-4111-8111-111111111111'", "ownership_token=NULL"),
            "ck_inference_jobs_ownership"),
        Arguments.of(
            "missing inference id",
            owner.replace(
                "assignment_inference_id='22222222-2222-4222-8222-222222222222'",
                "assignment_inference_id=NULL"),
            "ck_inference_jobs_ownership"),
        Arguments.of(
            "missing assignment start",
            owner.replace("assigned_at='2026-01-15T14:30:30Z'", "assigned_at=NULL"),
            "ck_inference_jobs_ownership"),
        Arguments.of(
            "missing lease",
            owner.replace("lease_expires_at='2026-01-15T14:31:30Z'", "lease_expires_at=NULL"),
            "ck_inference_jobs_ownership"),
        Arguments.of(
            "empty lease interval",
            owner.replace("14:31:30Z", "14:30:30Z"),
            "ck_inference_jobs_ownership"),
        Arguments.of(
            "owner outside running",
            "ownership_token='11111111-1111-4111-8111-111111111111'",
            "ck_inference_jobs_ownership"),
        Arguments.of(
            "missing terminal time",
            "status='SUCCEEDED', assignment_count=1",
            "ck_inference_jobs_terminal"),
        Arguments.of(
            "terminal time on pending",
            "terminal_at=CURRENT_TIMESTAMP",
            "ck_inference_jobs_terminal"),
        Arguments.of(
            "missing supersession",
            "status='SUPERSEDED', terminal_at=CURRENT_TIMESTAMP",
            "ck_inference_jobs_supersession"),
        Arguments.of(
            "supersession on pending",
            "superseded_by_event_id=news_revision_id",
            "ck_inference_jobs_supersession"),
        Arguments.of(
            "missing failure reason",
            "status='FAILED', assignment_count=1, terminal_at=CURRENT_TIMESTAMP",
            "ck_inference_jobs_failure_state"),
        Arguments.of(
            "unknown failure code",
            "assignment_count=1, last_failure_code='UNKNOWN'",
            "ck_inference_jobs_failure_code"),
        Arguments.of(
            "permanent retry",
            "status='RETRY_WAIT', assignment_count=1, last_failure_code='WORKER_PERMANENT'",
            "ck_inference_jobs_failure_state"),
        Arguments.of(
            "failure before exhaustion",
            "status='FAILED', assignment_count=1, terminal_at=CURRENT_TIMESTAMP, last_failure_code='LEASE_EXPIRED'",
            "ck_inference_jobs_failure_state"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "result_payload - 'schema_version'",
        "result_payload - 'inference_id'",
        "result_payload - 'news_event_id'",
        "result_payload - 'news_event_sha256'",
        "result_payload - 'model'",
        "'[]'::jsonb",
        "'null'::jsonb",
        "jsonb_set(result_payload, '{news_event_sha256}', to_jsonb(repeat('0',64)))",
        "jsonb_set(result_payload, '{model,backend}', '\"ollama\"'::jsonb)"
      })
  void rejectsMissingOrMismatchedResultIdentity(String payloadExpression) {
    InferenceJobFixtures.state(jdbc, jobId, JobStatus.SUCCEEDED, NOW);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
        INSERT INTO trader.inference_results
        SELECT job_id, news_revision_id, inference_id, news_event_sha256, result_schema_version,
               backend, %s, assignment_number, accepted_owner_token, accepted_at
        FROM trader.inference_results WHERE job_id = ?
        """
                        .formatted(payloadExpression),
                    jobId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("ck_inference_results_payload");
  }

  @ParameterizedTest
  @ValueSource(strings = {"news_revision_id", "assignment_number", "backend", "news_event_sha256"})
  void rejectsResultProvenanceNotBoundToItsActualJob(String mismatch) {
    InferenceJobFixtures.state(jdbc, jobId, JobStatus.SUCCEEDED, NOW);
    UUID source = jobId;
    seedPendingJob();
    InferenceJobFixtures.state(jdbc, jobId, JobStatus.RUNNING, NOW);
    // Copy to a second job with a fresh inference ID, changing exactly one relational provenance
    // field.
    String revision =
        mismatch.equals("news_revision_id") ? "s.news_revision_id" : "t.news_revision_id";
    String attempt = mismatch.equals("assignment_number") ? "2" : "t.assignment_count";
    String backend = mismatch.equals("backend") ? "'ollama'" : "t.expected_backend";
    String hash = mismatch.equals("news_event_sha256") ? "repeat('0',64)" : "r.content_hash";
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
        INSERT INTO trader.inference_results
        SELECT t.job_id, %s, gen_random_uuid(), %s, '1.0.0', %s,
            jsonb_build_object('schema_version','1.0.0', 'inference_id', i.id::text,
              'news_event_id', %s::text, 'news_event_sha256', %s,
              'model', jsonb_build_object('backend',%s)),
            %s, s.accepted_owner_token, s.accepted_at
        FROM trader.inference_results s
        JOIN trader.inference_jobs t ON t.job_id = ?
        JOIN trader.news_revisions r ON r.event_id = t.news_revision_id
        CROSS JOIN (SELECT gen_random_uuid() AS id) i
        WHERE s.job_id = ?
        """
                        .formatted(revision, hash, backend, revision, hash, backend, attempt)
                        // Use the same generated identity in the column and payload.
                        .replace("gen_random_uuid(),", "i.id,"),
                    jobId,
                    source))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("fk_inference_results_");
  }

  @Test
  void preservesAcceptedResultAndRejectsOverwriteDeleteAndDuplicateAcceptance() throws Exception {
    InferenceJobFixtures.state(jdbc, jobId, JobStatus.SUCCEEDED, NOW);
    String before =
        jdbc.queryForObject(
            "SELECT to_jsonb(r)::text FROM trader.inference_results r WHERE job_id = ?",
            String.class,
            jobId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE trader.inference_results SET accepted_at=CURRENT_TIMESTAMP WHERE job_id=?",
                    jobId))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("inference results are immutable");
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM trader.inference_results WHERE job_id=?", jobId))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("inference results are immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO trader.inference_results SELECT * FROM trader.inference_results WHERE job_id=?",
                    jobId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT to_jsonb(r)::text FROM trader.inference_results r WHERE job_id = ?",
                String.class,
                jobId))
        .isEqualTo(before);
    String payload =
        jdbc.queryForObject(
            "SELECT result_payload::text FROM trader.inference_results WHERE job_id=?",
            String.class,
            jobId);
    var schema =
        new org.springframework.core.io.ClassPathResource(
            "contracts/v1/inference-result.schema.json");
    try (var stream = schema.getInputStream()) {
      assertThat(
              SharedContractValidator.validate(
                  new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                  payload))
          .isEmpty();
    }
  }
}

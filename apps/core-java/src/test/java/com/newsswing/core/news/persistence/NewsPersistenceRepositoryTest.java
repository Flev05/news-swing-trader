package com.newsswing.core.news.persistence;

import static com.newsswing.core.news.domain.NewsEvents.Content;
import static com.newsswing.core.news.domain.NewsEvents.ProviderRevisionKey;
import static com.newsswing.core.news.domain.NewsEvents.UnhashedNewsEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newsswing.core.news.domain.NewsCanonicalizer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class NewsPersistenceRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6");

  @DynamicPropertySource
  static void configureFlywaySchema(DynamicPropertyRegistry registry) {
    registry.add("spring.flyway.default-schema", () -> "trader");
    registry.add("spring.flyway.schemas", () -> "trader");
  }

  private final NewsCanonicalizer canonicalizer = new NewsCanonicalizer();

  @Autowired private NewsPersistenceRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void persistsQueuedRevisionJobAndLatestPointerAtomically() {
    var event = canonical("queued-" + UUID.randomUUID(), "revision-1", "ACME reports results", 0);

    PersistedRevision persisted = repository.persistQueued(event);

    assertThat(persisted.outcome()).isEqualTo(PersistenceOutcome.CREATED);
    assertThat(persisted.admissionState()).isEqualTo(AdmissionState.QUEUED);
    assertThat(persisted.jobId()).isNotNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT latest_event_id FROM trader.news_items WHERE id = ?",
                UUID.class,
                persisted.newsItemId()))
        .isEqualTo(event.eventId());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM trader.inference_jobs WHERE news_revision_id = ?",
                String.class,
                event.eventId()))
        .isEqualTo("PENDING");
  }

  @Test
  void returnsDuplicateWithoutMutationAndDetectsConflicts() {
    String newsId = "duplicate-" + UUID.randomUUID();
    var original = canonical(newsId, "revision-1", "Original headline", 0);

    PersistedRevision first = repository.persistQueued(original);
    PersistedRevision duplicate = repository.persistQueued(original);

    assertThat(duplicate.outcome()).isEqualTo(PersistenceOutcome.DUPLICATE);
    assertThat(duplicate.jobId()).isEqualTo(first.jobId());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trader.news_revisions WHERE news_item_id = ?",
                Integer.class,
                first.newsItemId()))
        .isEqualTo(1);

    var changedContent = canonical(newsId, "revision-1", "Changed headline", 0);
    assertThatThrownBy(() -> repository.persistQueued(changedContent))
        .isInstanceOf(ProviderRevisionConflictException.class);

    var reusedEventId =
        canonicalWithEventId(
            original.eventId(), "other-" + UUID.randomUUID(), "revision-1", "Other headline", 0);
    assertThatThrownBy(() -> repository.persistQueued(reusedEventId))
        .isInstanceOf(EventIdConflictException.class);
  }

  @Test
  void storesOutOfOrderShapeWithoutJobOrLatestPointerMutation() {
    String newsId = "out-of-order-" + UUID.randomUUID();
    var latest = canonical(newsId, "revision-2", "Latest revision", 10);
    PersistedRevision queued = repository.persistQueued(latest);
    var historical = canonical(newsId, "revision-1", "Historical revision", 0);

    PersistedRevision persisted = repository.persistOutOfOrder(historical);

    assertThat(persisted.admissionState()).isEqualTo(AdmissionState.OUT_OF_ORDER);
    assertThat(persisted.jobId()).isNull();
    assertThat(persisted.jobStatus()).isNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT latest_event_id FROM trader.news_items WHERE id = ?",
                UUID.class,
                queued.newsItemId()))
        .isEqualTo(latest.eventId());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trader.inference_jobs WHERE news_revision_id = ?",
                Integer.class,
                historical.eventId()))
        .isZero();
  }

  @Test
  void serializesConcurrentDuplicatePersistence() throws Exception {
    var event =
        canonical("concurrent-" + UUID.randomUUID(), "revision-1", "Concurrent revision", 0);
    Callable<PersistedRevision> operation = () -> repository.persistQueued(event);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(operation);
      var second = executor.submit(operation);
      var outcomes = List.of(first.get(), second.get());

      assertThat(outcomes)
          .extracting(PersistedRevision::outcome)
          .containsExactlyInAnyOrder(PersistenceOutcome.CREATED, PersistenceOutcome.DUPLICATE);
    }
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trader.news_revisions WHERE event_id = ?",
                Integer.class,
                event.eventId()))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trader.inference_jobs WHERE news_revision_id = ?",
                Integer.class,
                event.eventId()))
        .isEqualTo(1);
  }

  @Test
  void reportsConcurrentCrossSourceEventIdReuseAsAnEventIdConflict() throws Exception {
    UUID eventId = UUID.randomUUID();
    var firstEvent =
        canonicalWithEventId(
            eventId, "event-id-a-" + UUID.randomUUID(), "revision-1", "First source", 0);
    var secondEvent =
        canonicalWithEventId(
            eventId, "event-id-b-" + UUID.randomUUID(), "revision-1", "Second source", 0);
    List<Throwable> failures = new ArrayList<>();
    int successes = 0;

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> repository.persistQueued(firstEvent));
      var second = executor.submit(() -> repository.persistQueued(secondEvent));
      for (var result : List.of(first, second)) {
        try {
          result.get();
          successes++;
        } catch (ExecutionException exception) {
          failures.add(exception.getCause());
        }
      }
    }

    assertThat(successes).isEqualTo(1);
    assertThat(failures).singleElement().isInstanceOf(EventIdConflictException.class);
  }

  @Test
  void rollsBackItemAndRevisionWhenJobInsertFails() {
    String newsId = "rollback-" + UUID.randomUUID();
    jdbcTemplate.execute(
        """
        CREATE FUNCTION trader.fail_m2a_job_insert()
        RETURNS TRIGGER
        LANGUAGE plpgsql
        AS $$
        BEGIN
            RAISE EXCEPTION 'forced job insert failure';
        END;
        $$
        """);
    jdbcTemplate.execute(
        """
        CREATE TRIGGER trg_fail_m2a_job_insert
        BEFORE INSERT ON trader.inference_jobs
        FOR EACH ROW
        EXECUTE FUNCTION trader.fail_m2a_job_insert()
        """);
    try {
      assertThatThrownBy(
              () -> repository.persistQueued(canonical(newsId, "revision-1", "Rollback", 0)))
          .isInstanceOf(DataAccessException.class);
    } finally {
      jdbcTemplate.execute(
          "DROP TRIGGER IF EXISTS trg_fail_m2a_job_insert ON trader.inference_jobs");
      jdbcTemplate.execute("DROP FUNCTION IF EXISTS trader.fail_m2a_job_insert()");
    }

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trader.news_items WHERE provider_news_id = ?",
                Integer.class,
                newsId))
        .isZero();
  }

  @Test
  void databaseRejectsJsonPayloadWhoseSourceDoesNotMatchRelationalIdentity() {
    String newsId = "json-consistency-" + UUID.randomUUID();
    PersistedRevision queued =
        repository.persistQueued(canonical(newsId, "revision-1", "Relational identity", 0));
    var inconsistent = canonical(newsId, "revision-2", "Mismatched payload", 0);
    String mismatchedPayload =
        inconsistent
            .payloadJson()
            .replace("\"provider\":\"fixture_wire\"", "\"provider\":\"other_wire\"");

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    INSERT INTO trader.news_revisions (
                        event_id, news_item_id, provider, provider_news_id, provider_revision_id,
                        canonical_url, source_created_at, source_updated_at, received_at, deadline_at,
                        content_hash, provider_fingerprint, event_payload, admission_state
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'OUT_OF_ORDER')
                    """,
                    inconsistent.eventId(),
                    queued.newsItemId(),
                    inconsistent.source().provider(),
                    inconsistent.source().newsId(),
                    inconsistent.source().revisionId(),
                    inconsistent.canonicalUrl(),
                    Timestamp.from(inconsistent.sourceCreatedAt()),
                    Timestamp.from(inconsistent.sourceUpdatedAt()),
                    Timestamp.from(inconsistent.receivedAt()),
                    Timestamp.from(inconsistent.deadlineAt()),
                    inconsistent.contentHash(),
                    inconsistent.providerFingerprint(),
                    mismatchedPayload))
        .isInstanceOf(DataAccessException.class);

    String missingSchemaVersion =
        inconsistent.payloadJson().replace("\"schema_version\":\"1.0.0\",", "");
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    INSERT INTO trader.news_revisions (
                        event_id, news_item_id, provider, provider_news_id, provider_revision_id,
                        canonical_url, source_created_at, source_updated_at, received_at, deadline_at,
                        content_hash, provider_fingerprint, event_payload, admission_state
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'OUT_OF_ORDER')
                    """,
                    inconsistent.eventId(),
                    queued.newsItemId(),
                    inconsistent.source().provider(),
                    inconsistent.source().newsId(),
                    inconsistent.source().revisionId(),
                    inconsistent.canonicalUrl(),
                    Timestamp.from(inconsistent.sourceCreatedAt()),
                    Timestamp.from(inconsistent.sourceUpdatedAt()),
                    Timestamp.from(inconsistent.receivedAt()),
                    Timestamp.from(inconsistent.deadlineAt()),
                    inconsistent.contentHash(),
                    inconsistent.providerFingerprint(),
                    missingSchemaVersion))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void databaseRejectsRevisionMutation() {
    var event = canonical("immutable-" + UUID.randomUUID(), "revision-1", "Immutable", 0);
    repository.persistQueued(event);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE trader.news_revisions SET content_hash = ? WHERE event_id = ?",
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    event.eventId()))
        .isInstanceOf(DataAccessException.class);
  }

  private com.newsswing.core.news.domain.NewsEvents.CanonicalNewsEvent canonical(
      String newsId, String revisionId, String headline, int sourceUpdateOffsetSeconds) {
    return canonicalWithEventId(
        UUID.randomUUID(), newsId, revisionId, headline, sourceUpdateOffsetSeconds);
  }

  private com.newsswing.core.news.domain.NewsEvents.CanonicalNewsEvent canonicalWithEventId(
      UUID eventId,
      String newsId,
      String revisionId,
      String headline,
      int sourceUpdateOffsetSeconds) {
    Instant created = Instant.parse("2026-01-15T14:30:00Z");
    return canonicalizer.canonicalize(
        new UnhashedNewsEvent(
            "1.0.0",
            eventId,
            UUID.randomUUID(),
            null,
            new ProviderRevisionKey("fixture_wire", newsId, revisionId),
            "https://news.example.test/" + newsId,
            created,
            created.plusSeconds(sourceUpdateOffsetSeconds),
            created.plusSeconds(30),
            created.plusSeconds(330),
            headline,
            new Content("en", "A durable summary.", null),
            List.of("ACME")));
  }
}

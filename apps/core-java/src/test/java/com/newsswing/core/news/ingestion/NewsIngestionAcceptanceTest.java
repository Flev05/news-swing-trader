package com.newsswing.core.news.ingestion;

import static com.newsswing.core.news.domain.NewsEvents.Content;
import static com.newsswing.core.news.domain.NewsEvents.ProviderRevisionKey;
import static com.newsswing.core.news.domain.NewsEvents.UnhashedNewsEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newsswing.core.inference.jobs.JobStatus;
import com.newsswing.core.news.domain.NewsCanonicalizer;
import com.newsswing.core.news.domain.NewsEvents.CanonicalNewsEvent;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "trader.news.ingestion.pending-capacity=1")
@Testcontainers
@Import(NewsIngestionAcceptanceTest.ClockConfiguration.class)
class NewsIngestionAcceptanceTest {

  private static final Instant BASE = Instant.parse("2026-01-15T14:30:00Z");
  private static final Instant NOW = BASE.plusSeconds(30);

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6");

  @DynamicPropertySource
  static void configureFlywaySchema(DynamicPropertyRegistry registry) {
    registry.add("spring.flyway.default-schema", () -> "trader");
    registry.add("spring.flyway.schemas", () -> "trader");
  }

  @Autowired private NewsIngestionCoordinator coordinator;
  @Autowired private NewsCanonicalizer canonicalizer;
  @Autowired private NewsEventContractValidator contractValidator;
  @Autowired private NewsIngestionRepository repository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private MutableCountingClock clock;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void resetQueueAndClock() {
    jdbcTemplate.update(
        """
        UPDATE trader.inference_jobs
        SET status = ?, superseded_at = ?, terminal_at = ?, superseded_by_event_id = news_revision_id
        WHERE status = ?
        """,
        "SUPERSEDED",
        Timestamp.from(NOW),
        Timestamp.from(NOW),
        "PENDING");
    clock.reset(NOW);
  }

  @Test
  void capturesExactlyOneClockInstantForASuccessfulRequest() {
    coordinator.ingest(event("one-clock-" + UUID.randomUUID(), "revision-1", 0));

    assertThat(clock.calls()).isEqualTo(1);
  }

  @Test
  void rejectsProviderRevisionAndEventIdConflictsWithoutAdditionalWrites() {
    String newsId = "conflict-" + UUID.randomUUID();
    var original = event(newsId, "revision-1", 0);
    coordinator.ingest(original);

    var providerConflict =
        event(
            UUID.randomUUID(),
            newsId,
            "revision-1",
            0,
            "Changed immutable headline",
            NOW.plusSeconds(300));
    assertRejection(
        NewsIngestionException.Code.PROVIDER_REVISION_CONFLICT,
        () -> coordinator.ingest(providerConflict));

    var eventIdConflict =
        event(
            original.eventId(),
            "other-" + UUID.randomUUID(),
            "revision-9",
            0,
            "Different provider revision",
            NOW.plusSeconds(300));
    assertRejection(
        NewsIngestionException.Code.EVENT_ID_CONFLICT, () -> coordinator.ingest(eventIdConflict));

    assertThat(count("news_revisions", "event_id", providerConflict.eventId())).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trader.news_items WHERE provider_news_id = ?",
                Integer.class,
                eventIdConflict.source().newsId()))
        .isZero();
  }

  @Test
  void rejectsDeadlinesEqualToOrEarlierThanRequestNowAsStale() {
    var equal =
        event(
            UUID.randomUUID(),
            "stale-equal-" + UUID.randomUUID(),
            "revision-1",
            0,
            "Equal deadline",
            NOW);
    var earlier =
        event(
            UUID.randomUUID(),
            "stale-earlier-" + UUID.randomUUID(),
            "revision-1",
            0,
            "Earlier deadline",
            NOW.minusSeconds(1));

    assertRejection(NewsIngestionException.Code.STALE, () -> coordinator.ingest(equal));
    assertRejection(NewsIngestionException.Code.STALE, () -> coordinator.ingest(earlier));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trader.news_revisions WHERE event_id IN (?, ?)",
                Integer.class,
                equal.eventId(),
                earlier.eventId()))
        .isZero();
  }

  @Test
  void duplicateResolutionPrecedesStalenessAndCapacityAndReportsCurrentJobStatus() {
    String newsId = "duplicate-precedence-" + UUID.randomUUID();
    var original = event(newsId, "revision-1", 0);
    coordinator.ingest(original);

    clock.reset(NOW.plusSeconds(600));
    var pendingDuplicate = (NewsIngestionResult.Duplicate) coordinator.ingest(original);
    assertThat(pendingDuplicate.storedAdmissionState())
        .isEqualTo(NewsIngestionResult.StoredAdmissionState.QUEUED);
    assertThat(pendingDuplicate.currentJob()).isPresent();
    assertThat(pendingDuplicate.currentJob().orElseThrow().status()).isEqualTo(JobStatus.PENDING);

    clock.reset(NOW);
    coordinator.ingest(event(newsId, "revision-2", 10));
    clock.reset(NOW.plusSeconds(600));
    var supersededDuplicate = (NewsIngestionResult.Duplicate) coordinator.ingest(original);
    assertThat(supersededDuplicate.currentJob()).isPresent();
    assertThat(supersededDuplicate.currentJob().orElseThrow().status())
        .isEqualTo(JobStatus.SUPERSEDED);
  }

  @Test
  void capacityZeroRejectsAnUnseenQueueAdmissionWithoutDurableWrites() {
    var zeroCapacityCoordinator =
        new NewsIngestionCoordinator(
            canonicalizer,
            contractValidator,
            repository,
            clock,
            new NewsIngestionProperties(0),
            new com.newsswing.core.inference.jobs.InferenceJobPolicy(3, "fake"),
            transactionManager);
    var rejected = event("zero-capacity-" + UUID.randomUUID(), "revision-1", 0);

    assertRejection(
        NewsIngestionException.Code.CAPACITY_EXCEEDED,
        () -> zeroCapacityCoordinator.ingest(rejected));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trader.news_items WHERE provider_news_id = ?",
                Integer.class,
                rejected.source().newsId()))
        .isZero();
  }

  @Test
  void revisionInsertFailurePreservesPreviousPendingJobAndLatestPointer() {
    String newsId = "revision-failure-" + UUID.randomUUID();
    var original = event(newsId, "revision-1", 0);
    var replacement = event(newsId, "revision-2", 10);
    coordinator.ingest(original);
    installFailureTrigger("news_revisions", "fail_m2b1_revision_insert", "INSERT");
    try {
      assertThatThrownBy(() -> coordinator.ingest(replacement))
          .isInstanceOf(DataAccessException.class);
    } finally {
      removeFailureTrigger("news_revisions", "fail_m2b1_revision_insert");
    }

    assertReplacementRollback(newsId, original.eventId(), replacement.eventId());
  }

  @Test
  void jobInsertFailureRestoresPreviousPendingJobAndLatestPointer() {
    String newsId = "job-failure-" + UUID.randomUUID();
    var original = event(newsId, "revision-1", 0);
    var replacement = event(newsId, "revision-2", 10);
    coordinator.ingest(original);
    installFailureTrigger("inference_jobs", "fail_m2b1_job_insert", "INSERT");
    try {
      assertThatThrownBy(() -> coordinator.ingest(replacement))
          .isInstanceOf(DataAccessException.class);
    } finally {
      removeFailureTrigger("inference_jobs", "fail_m2b1_job_insert");
    }

    assertReplacementRollback(newsId, original.eventId(), replacement.eventId());
  }

  @Test
  void latestPointerFailureRestoresPreviousPendingJobAndLatestPointer() {
    String newsId = "pointer-failure-" + UUID.randomUUID();
    var original = event(newsId, "revision-1", 0);
    var replacement = event(newsId, "revision-2", 10);
    coordinator.ingest(original);
    installFailureTrigger("news_items", "fail_m2b1_latest_pointer", "UPDATE");
    try {
      assertThatThrownBy(() -> coordinator.ingest(replacement))
          .isInstanceOf(DataAccessException.class);
    } finally {
      removeFailureTrigger("news_items", "fail_m2b1_latest_pointer");
    }

    assertReplacementRollback(newsId, original.eventId(), replacement.eventId());
  }

  @Test
  void concurrentRevisionsLeaveGreatestSourceTimestampLatestAndOnePendingJob() throws Exception {
    String newsId = "concurrent-revisions-" + UUID.randomUUID();
    var original = event(newsId, "revision-1", 0);
    var middle = event(newsId, "revision-2", 10);
    var greatest = event(newsId, "revision-3", 20);
    coordinator.ingest(original);
    var barrier = new CyclicBarrier(2);
    List<NewsIngestionResult> outcomes = new ArrayList<>();
    List<Throwable> failures = new ArrayList<>();

    try (var executor = Executors.newFixedThreadPool(2)) {
      var middleFuture = executor.submit(() -> ingestAfterBarrier(barrier, middle));
      var greatestFuture = executor.submit(() -> ingestAfterBarrier(barrier, greatest));
      for (var future : List.of(middleFuture, greatestFuture)) {
        try {
          outcomes.add(future.get(10, TimeUnit.SECONDS));
        } catch (ExecutionException exception) {
          failures.add(exception.getCause());
        }
      }
    }

    assertThat(failures).isEmpty();
    assertThat(outcomes).hasSize(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT latest_event_id FROM trader.news_items WHERE provider_news_id = ?",
                UUID.class,
                newsId))
        .isEqualTo(greatest.eventId());
    assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM trader.inference_jobs j
                JOIN trader.news_revisions r ON r.event_id = j.news_revision_id
                JOIN trader.news_items i ON i.id = r.news_item_id
                WHERE i.provider_news_id = ? AND j.status = ?
                """,
                Integer.class,
                newsId,
                "PENDING"))
        .isEqualTo(1);
  }

  @Test
  void resolvesPostRollbackOutOfOrderEventIdRaceAsTypedConflict() throws Exception {
    String firstNewsId = "post-rollback-first-" + UUID.randomUUID();
    String secondNewsId = "post-rollback-second-" + UUID.randomUUID();
    var firstLatest = event(firstNewsId, "revision-2", 20);
    coordinator.ingest(firstLatest);
    supersedePendingJobsForSetup();
    var secondLatest = event(secondNewsId, "revision-2", 20);
    coordinator.ingest(secondLatest);
    supersedePendingJobsForSetup();

    UUID sharedEventId = UUID.randomUUID();
    var firstOutOfOrder =
        event(
            sharedEventId,
            firstNewsId,
            "revision-1",
            10,
            "First out-of-order revision",
            NOW.plusSeconds(300));
    var secondOutOfOrder =
        event(
            sharedEventId,
            secondNewsId,
            "revision-1",
            10,
            "Second out-of-order revision",
            NOW.plusSeconds(300));
    var racingCoordinator =
        new NewsIngestionCoordinator(
            canonicalizer,
            contractValidator,
            new BarrierBeforeOutOfOrderInsertRepository(jdbcTemplate, sharedEventId),
            clock,
            new NewsIngestionProperties(1),
            new com.newsswing.core.inference.jobs.InferenceJobPolicy(3, "fake"),
            transactionManager);
    List<NewsIngestionResult> outcomes = new ArrayList<>();
    List<Throwable> failures = new ArrayList<>();

    try (var executor = Executors.newFixedThreadPool(2)) {
      var firstFuture = executor.submit(() -> racingCoordinator.ingest(firstOutOfOrder));
      var secondFuture = executor.submit(() -> racingCoordinator.ingest(secondOutOfOrder));
      for (var future : List.of(firstFuture, secondFuture)) {
        try {
          outcomes.add(future.get(10, TimeUnit.SECONDS));
        } catch (ExecutionException exception) {
          failures.add(exception.getCause());
        }
      }
    }

    assertThat(outcomes).singleElement().isInstanceOf(NewsIngestionResult.OutOfOrder.class);
    assertThat(failures).singleElement().isExactlyInstanceOf(NewsIngestionException.class);
    assertThat(((NewsIngestionException) failures.getFirst()).code())
        .isEqualTo(NewsIngestionException.Code.EVENT_ID_CONFLICT);
    assertThat(count("news_revisions", "event_id", sharedEventId)).isEqualTo(1);
    assertThat(count("inference_jobs", "news_revision_id", sharedEventId)).isZero();
    assertThat(latestEventId(firstNewsId)).isEqualTo(firstLatest.eventId());
    assertThat(latestEventId(secondNewsId)).isEqualTo(secondLatest.eventId());
  }

  private void supersedePendingJobsForSetup() {
    jdbcTemplate.update(
        """
        UPDATE trader.inference_jobs
        SET status = ?, superseded_at = ?, terminal_at = ?, superseded_by_event_id = news_revision_id
        WHERE status = ?
        """,
        "SUPERSEDED",
        Timestamp.from(NOW),
        Timestamp.from(NOW),
        "PENDING");
  }

  private UUID latestEventId(String newsId) {
    return jdbcTemplate.queryForObject(
        "SELECT latest_event_id FROM trader.news_items WHERE provider_news_id = ?",
        UUID.class,
        newsId);
  }

  private static final class BarrierBeforeOutOfOrderInsertRepository
      extends NewsIngestionRepository {

    private final UUID targetEventId;
    private final CyclicBarrier beforeInsert = new CyclicBarrier(2);

    private BarrierBeforeOutOfOrderInsertRepository(JdbcTemplate jdbcTemplate, UUID targetEventId) {
      super(jdbcTemplate);
      this.targetEventId = targetEventId;
    }

    @Override
    void insertRevision(
        UUID itemId, CanonicalNewsEvent event, AdmissionState admissionState, Instant requestNow) {
      if (admissionState == AdmissionState.OUT_OF_ORDER && targetEventId.equals(event.eventId())) {
        awaitCompetingInsert();
      }
      super.insertRevision(itemId, event, admissionState, requestNow);
    }

    private void awaitCompetingInsert() {
      try {
        beforeInsert.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted before competing OUT_OF_ORDER inserts", exception);
      } catch (Exception exception) {
        throw new AssertionError(
            "Competing OUT_OF_ORDER inserts did not reach the barrier", exception);
      }
    }
  }

  private NewsIngestionResult ingestAfterBarrier(CyclicBarrier barrier, UnhashedNewsEvent event)
      throws Exception {
    barrier.await(5, TimeUnit.SECONDS);
    return coordinator.ingest(event);
  }

  private void assertReplacementRollback(String newsId, UUID originalEventId, UUID failedEventId) {
    assertThat(count("news_revisions", "event_id", failedEventId)).isZero();
    assertThat(count("inference_jobs", "news_revision_id", failedEventId)).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM trader.inference_jobs WHERE news_revision_id = ?",
                String.class,
                originalEventId))
        .isEqualTo("PENDING");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT latest_event_id FROM trader.news_items WHERE provider_news_id = ?",
                UUID.class,
                newsId))
        .isEqualTo(originalEventId);
  }

  private int count(String table, String column, Object value) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM trader." + table + " WHERE " + column + " = ?", Integer.class, value);
  }

  private void installFailureTrigger(String table, String name, String operation) {
    jdbcTemplate.execute(
        "CREATE FUNCTION trader."
            + name
            + "() RETURNS TRIGGER LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''forced "
            + name
            + "''; END'");
    jdbcTemplate.execute(
        "CREATE TRIGGER "
            + name
            + " BEFORE "
            + operation
            + " ON trader."
            + table
            + " FOR EACH ROW EXECUTE FUNCTION trader."
            + name
            + "()");
  }

  private void removeFailureTrigger(String table, String name) {
    jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + name + " ON trader." + table);
    jdbcTemplate.execute("DROP FUNCTION IF EXISTS trader." + name + "()");
  }

  private void assertRejection(NewsIngestionException.Code code, ThrowingCall call) {
    assertThatThrownBy(call::invoke)
        .isInstanceOf(NewsIngestionException.class)
        .extracting(exception -> ((NewsIngestionException) exception).code())
        .isEqualTo(code);
  }

  private UnhashedNewsEvent event(String newsId, String revisionId, int updateOffsetSeconds) {
    return event(
        UUID.randomUUID(),
        newsId,
        revisionId,
        updateOffsetSeconds,
        "Headline " + revisionId,
        NOW.plusSeconds(300));
  }

  private UnhashedNewsEvent event(
      UUID eventId,
      String newsId,
      String revisionId,
      int updateOffsetSeconds,
      String headline,
      Instant deadline) {
    return new UnhashedNewsEvent(
        "1.0.0",
        eventId,
        UUID.randomUUID(),
        null,
        new ProviderRevisionKey("fixture_wire", newsId, revisionId),
        "https://news.example.test/" + newsId,
        BASE,
        BASE.plusSeconds(updateOffsetSeconds),
        BASE.plusSeconds(20),
        deadline,
        headline,
        new Content("en", "A durable summary.", null),
        List.of("ACME"));
  }

  @FunctionalInterface
  private interface ThrowingCall {
    void invoke();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {

    @Bean
    @Primary
    MutableCountingClock testClock() {
      return new MutableCountingClock(NOW);
    }
  }

  static final class MutableCountingClock extends Clock {

    private final AtomicReference<Instant> value;
    private final AtomicInteger calls = new AtomicInteger();

    MutableCountingClock(Instant initialValue) {
      value = new AtomicReference<>(initialValue);
    }

    void reset(Instant newValue) {
      value.set(newValue);
      calls.set(0);
    }

    int calls() {
      return calls.get();
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      calls.incrementAndGet();
      return value.get();
    }
  }
}

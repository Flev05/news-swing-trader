package com.newsswing.core.news.ingestion;

import static com.newsswing.core.news.domain.NewsEvents.Content;
import static com.newsswing.core.news.domain.NewsEvents.ProviderRevisionKey;
import static com.newsswing.core.news.domain.NewsEvents.UnhashedNewsEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newsswing.core.inference.jobs.JobStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "trader.news.ingestion.pending-capacity=1")
@Testcontainers
@Import(NewsIngestionCoordinatorTest.FixedClockConfiguration.class)
class NewsIngestionCoordinatorTest {

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
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void supersedeResidualPendingJobs() {
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

  @Test
  void queuesThenReturnsTheStoredDuplicateStateAtLookupTime() {
    var event = event("duplicate-" + UUID.randomUUID(), "revision-1", 0, false);

    NewsIngestionResult queuedResult = coordinator.ingest(event);
    assertThat(queuedResult).isInstanceOf(NewsIngestionResult.Queued.class);
    NewsIngestionResult.Queued queued = (NewsIngestionResult.Queued) queuedResult;
    NewsIngestionResult duplicateResult = coordinator.ingest(event);
    assertThat(duplicateResult).isInstanceOf(NewsIngestionResult.Duplicate.class);
    NewsIngestionResult.Duplicate duplicate = (NewsIngestionResult.Duplicate) duplicateResult;

    assertThat(duplicate.eventId()).isEqualTo(queued.eventId());
    assertThat(duplicate.storedAdmissionState())
        .isEqualTo(NewsIngestionResult.StoredAdmissionState.QUEUED);
    assertThat(duplicate.currentJob()).isPresent();
    assertThat(duplicate.currentJob().orElseThrow().status()).isEqualTo(JobStatus.PENDING);
  }

  @Test
  void supersedesThePreviousPendingJobAtomicallyAtCapacity() {
    String newsId = "supersede-" + UUID.randomUUID();
    var first = event(newsId, "revision-1", 0, false);
    var replacement = event(newsId, "revision-2", 10, false);

    coordinator.ingest(first);
    NewsIngestionResult.Queued queued =
        (NewsIngestionResult.Queued) coordinator.ingest(replacement);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trader.inference_jobs WHERE status = ?",
                Integer.class,
                "PENDING"))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM trader.inference_jobs WHERE news_revision_id = ?",
                String.class,
                first.eventId()))
        .isEqualTo("SUPERSEDED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT latest_event_id FROM trader.news_items WHERE provider_news_id = ?",
                UUID.class,
                newsId))
        .isEqualTo(queued.eventId());
  }

  @Test
  void storesOutOfOrderButRejectsEqualTimestampsWithoutWrites() {
    String newsId = "ordering-" + UUID.randomUUID();
    var latest = event(newsId, "revision-2", 10, false);
    var historical = event(newsId, "revision-1", 0, false);
    var ambiguous = event(newsId, "revision-3", 10, false);

    coordinator.ingest(latest);
    assertThat(coordinator.ingest(historical)).isInstanceOf(NewsIngestionResult.OutOfOrder.class);
    var historicalDuplicate = (NewsIngestionResult.Duplicate) coordinator.ingest(historical);
    assertThat(historicalDuplicate.storedAdmissionState())
        .isEqualTo(NewsIngestionResult.StoredAdmissionState.OUT_OF_ORDER);
    assertThat(historicalDuplicate.currentJob()).isEmpty();

    assertThatThrownBy(() -> coordinator.ingest(ambiguous))
        .isInstanceOf(NewsIngestionException.class)
        .extracting(exception -> ((NewsIngestionException) exception).code())
        .isEqualTo(NewsIngestionException.Code.AMBIGUOUS_SOURCE_UPDATED_AT);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trader.inference_jobs WHERE news_revision_id = ?",
                Integer.class,
                historical.eventId()))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT admission_state FROM trader.news_revisions WHERE event_id = ?",
                String.class,
                historical.eventId()))
        .isEqualTo("OUT_OF_ORDER");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trader.news_revisions WHERE event_id = ?",
                Integer.class,
                ambiguous.eventId()))
        .isZero();
  }

  @Test
  void rejectsStaleUnseenEventsAndAppliesAnExactCapacityLimit() {
    var stale = event("stale-" + UUID.randomUUID(), "revision-1", 0, true);
    assertThatThrownBy(() -> coordinator.ingest(stale))
        .isInstanceOf(NewsIngestionException.class)
        .extracting(exception -> ((NewsIngestionException) exception).code())
        .isEqualTo(NewsIngestionException.Code.STALE);

    coordinator.ingest(event("capacity-a-" + UUID.randomUUID(), "revision-1", 0, false));
    var rejected = event("capacity-b-" + UUID.randomUUID(), "revision-1", 0, false);
    assertThatThrownBy(() -> coordinator.ingest(rejected))
        .isInstanceOf(NewsIngestionException.class)
        .extracting(exception -> ((NewsIngestionException) exception).code())
        .isEqualTo(NewsIngestionException.Code.CAPACITY_EXCEEDED);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trader.news_items WHERE provider_news_id = ?",
                Integer.class,
                rejected.source().newsId()))
        .isZero();
  }

  @Test
  void serializesConcurrentRequestsAtTheAdmissionLockWithoutSleeps() throws Exception {
    var first = event("concurrent-a-" + UUID.randomUUID(), "revision-1", 0, false);
    var second = event("concurrent-b-" + UUID.randomUUID(), "revision-1", 0, false);
    var barrier = new CyclicBarrier(2);
    List<NewsIngestionResult> successes = new ArrayList<>();
    List<Throwable> failures = new ArrayList<>();

    try (var executor = Executors.newFixedThreadPool(2)) {
      var firstFuture = executor.submit(() -> ingestAfterBarrier(barrier, first));
      var secondFuture = executor.submit(() -> ingestAfterBarrier(barrier, second));
      for (var future : List.of(firstFuture, secondFuture)) {
        try {
          successes.add(future.get(10, TimeUnit.SECONDS));
        } catch (ExecutionException exception) {
          failures.add(exception.getCause());
        }
      }
    }

    assertThat(successes).hasSize(1).allMatch(NewsIngestionResult.Queued.class::isInstance);
    assertThat(failures).singleElement().isInstanceOf(NewsIngestionException.class);
    assertThat(((NewsIngestionException) failures.getFirst()).code())
        .isEqualTo(NewsIngestionException.Code.CAPACITY_EXCEEDED);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trader.inference_jobs WHERE status = ?",
                Integer.class,
                "PENDING"))
        .isEqualTo(1);
  }

  @Test
  void serializesConcurrentDuplicatesWithoutCapacityRejection() throws Exception {
    var event = event("concurrent-duplicate-" + UUID.randomUUID(), "revision-1", 0, false);
    var barrier = new CyclicBarrier(2);
    List<NewsIngestionResult> successes = new ArrayList<>();
    List<Throwable> failures = new ArrayList<>();

    try (var executor = Executors.newFixedThreadPool(2)) {
      var firstFuture = executor.submit(() -> ingestAfterBarrier(barrier, event));
      var secondFuture = executor.submit(() -> ingestAfterBarrier(barrier, event));
      for (var future : List.of(firstFuture, secondFuture)) {
        try {
          successes.add(future.get(10, TimeUnit.SECONDS));
        } catch (ExecutionException exception) {
          failures.add(exception.getCause());
        }
      }
    }

    assertThat(successes).hasSize(2);
    assertThat(successes).anyMatch(NewsIngestionResult.Queued.class::isInstance);
    assertThat(successes).anyMatch(NewsIngestionResult.Duplicate.class::isInstance);
    assertThat(failures).isEmpty();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trader.news_revisions WHERE event_id = ?",
                Integer.class,
                event.eventId()))
        .isEqualTo(1);
  }

  private NewsIngestionResult ingestAfterBarrier(CyclicBarrier barrier, UnhashedNewsEvent event)
      throws Exception {
    barrier.await(5, TimeUnit.SECONDS);
    return coordinator.ingest(event);
  }

  private UnhashedNewsEvent event(
      String newsId, String revisionId, int sourceUpdateOffsetSeconds, boolean stale) {
    return new UnhashedNewsEvent(
        "1.0.0",
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        new ProviderRevisionKey("fixture_wire", newsId, revisionId),
        "https://news.example.test/" + newsId,
        BASE,
        BASE.plusSeconds(sourceUpdateOffsetSeconds),
        stale ? BASE : NOW,
        stale ? NOW : BASE.plusSeconds(300),
        "Headline " + revisionId,
        new Content("en", "A durable summary.", null),
        List.of("ACME"));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}

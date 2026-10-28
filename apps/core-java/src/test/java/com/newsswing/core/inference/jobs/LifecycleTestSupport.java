package com.newsswing.core.inference.jobs;

import static com.newsswing.core.inference.jobs.InferenceJobLifecycleService.*;
import static org.assertj.core.api.Assertions.*;

import com.newsswing.core.news.domain.NewsEvents.*;
import com.newsswing.core.news.ingestion.NewsIngestionCoordinator;
import com.newsswing.core.news.ingestion.NewsIngestionProperties;
import com.newsswing.core.news.ingestion.NewsIngestionResult;
import com.newsswing.support.InferenceJobFixtures;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "trader.news.ingestion.pending-capacity=4",
      "trader.inference.jobs.lease-duration=60s",
      "trader.inference.jobs.recovery-batch-size=4",
      "trader.inference.jobs.max-assignments=3",
      "trader.inference.jobs.expected-backend=fake",
      "spring.datasource.hikari.maximum-pool-size=8",
      "spring.datasource.hikari.connection-timeout=2000"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(LifecycleTestSupport.Configuration.class)
@Timeout(90)
abstract class LifecycleTestSupport {
  static final Instant NOW = Instant.parse("2026-01-15T14:30:30.123456Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.6").withStartupTimeout(Duration.ofSeconds(120));

  @Autowired InferenceJobLifecycleService service;
  @Autowired NewsIngestionCoordinator ingestion;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  @Autowired ControlledTime time;
  @Autowired ProbeRepository repository;

  @BeforeEach
  void reset() {
    repository.observe((point, id) -> {});
    time.set(NOW);
    jdbc.execute(
        "TRUNCATE trader.inference_results, trader.inference_jobs, trader.news_revisions, trader.news_items");
  }

  InferenceJobLifecycleService service(int capacity, int batch) {
    return new JdbcInferenceJobLifecycleService(
        repository,
        new InferenceJobLifecycleProperties(Duration.ofSeconds(60), batch),
        new NewsIngestionProperties(capacity),
        manager);
  }

  NewsIngestionResult.Queued queue(String item, int revision) {
    return (NewsIngestionResult.Queued)
        ingestion.ingest(event(item, revision, NOW.plusSeconds(600)));
  }

  UnhashedNewsEvent event(String item, int revision, Instant deadline) {
    return new UnhashedNewsEvent(
        "1.0.0",
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        new ProviderRevisionKey("fixture_wire", item, "r" + revision),
        "https://news.example.test/" + item,
        NOW.minusSeconds(30),
        NOW.minusSeconds(30).plusSeconds(revision),
        NOW.minusSeconds(5),
        deadline,
        "Headline " + revision,
        new Content("en", "Summary", null),
        List.of("ACME"));
  }

  Assignment claim() {
    return ((Claimed) service.claimNext()).assignment();
  }

  String snapshot(UUID job) {
    return InferenceJobFixtures.snapshot(jdbc, job);
  }

  String status(UUID job) {
    return jdbc.queryForObject(
        "SELECT status FROM trader.inference_jobs WHERE job_id = ?", String.class, job);
  }

  int count(UUID job) {
    return jdbc.queryForObject(
        "SELECT assignment_count FROM trader.inference_jobs WHERE job_id = ?", Integer.class, job);
  }

  int pending() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM trader.inference_jobs WHERE status = 'PENDING'", Integer.class);
  }

  void noOwner(UUID job) {
    var row = jdbc.queryForMap("SELECT * FROM trader.inference_jobs WHERE job_id = ?", job);
    for (var col :
        List.of("ownership_token", "assignment_inference_id", "assigned_at", "lease_expires_at")) {
      assertThat(row.get(col)).as(col).isNull();
    }
  }

  static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).as("bounded synchronization").isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  static final class ControlledTime implements LifecycleTimeSource {
    final AtomicReference<Instant> value = new AtomicReference<>(NOW);

    void set(Instant instant) {
      value.set(instant);
    }

    public Instant now(Connection connection) throws java.sql.SQLException {
      assertThat(connection.getAutoCommit()).isFalse();
      return value.get();
    }
  }

  static class ProbeRepository extends InferenceJobLifecycleRepository {
    volatile BiConsumer<String, UUID> probe = (point, id) -> {};

    void observe(BiConsumer<String, UUID> observer) {
      this.probe = observer;
    }

    ProbeRepository(JdbcTemplate jdbc, LifecycleTimeSource source) {
      super(jdbc, source);
    }

    @Override
    ItemRow lockItem(UUID id, boolean skip) {
      probe.accept("beforeItem", id);
      var row = super.lockItem(id, skip);
      if (row != null) {
        probe.accept("itemLocked", id);
      }
      return row;
    }

    @Override
    void lockAdmission() {
      probe.accept("beforeMutex", null);
      super.lockAdmission();
      probe.accept("mutexLocked", null);
    }

    @Override
    JobRow lockJob(UUID id) {
      var row = super.lockJob(id);
      probe.accept("jobLocked", id);
      return row;
    }

    @Override
    List<Candidate> discoverPending(int limit) {
      var rows = super.discoverPending(limit);
      probe.accept("pendingDiscovered", null);
      return rows;
    }

    @Override
    List<Candidate> discoverCleanup(Instant cutoff, int limit) {
      var rows = super.discoverCleanup(cutoff, limit);
      probe.accept("cleanupDiscovered", null);
      return rows;
    }

    @Override
    List<Candidate> discoverRetries(int limit) {
      var rows = super.discoverRetries(limit);
      probe.accept("retriesDiscovered", null);
      return rows;
    }

    @Override
    void claim(JobRow job, UUID token, UUID inference, Instant now, Instant expiry) {
      super.claim(job, token, inference, now, expiry);
      probe.accept("claimed", job.jobId());
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Configuration {
    @Bean
    @Primary
    ControlledTime controlledLifecycleTime() {
      return new ControlledTime();
    }

    @Bean
    @Primary
    Clock lifecycleIngestionClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    @Primary
    ProbeRepository probeRepository(JdbcTemplate jdbc, LifecycleTimeSource source) {
      return new ProbeRepository(jdbc, source);
    }
  }
}

package com.newsswing.core.inference.jobs;

import static com.newsswing.core.inference.jobs.InferenceJobLifecycleService.*;
import static org.assertj.core.api.Assertions.*;

import com.newsswing.core.CoreApplication;
import com.newsswing.core.news.domain.NewsEvents.*;
import com.newsswing.core.news.ingestion.NewsIngestionCoordinator;
import com.newsswing.core.news.ingestion.NewsIngestionResult;
import com.newsswing.support.InferenceJobFixtures;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

@Timeout(180)
class InferenceJobRestartRecoveryAcceptanceTest {
  private static final Instant NOW = LifecycleTestSupport.NOW;

  @ParameterizedTest
  @ValueSource(ints = {30, 60, 61})
  void preservesLiveAndExpiredOwnershipAcrossFullyClosedApplications(int restartSeconds) {
    try (var postgres =
        new PostgreSQLContainer<>("postgres:18.6").withStartupTimeout(Duration.ofSeconds(120))) {
      postgres.start();
      String containerId = postgres.getContainerId();
      Assignment first;
      UUID job;
      String before;
      List<String> history;
      var firstContext = start(postgres, NOW, 3, "fake");
      var firstPool = firstContext.getBean(HikariDataSource.class);
      try (firstContext) {
        var ingestion = firstContext.getBean(NewsIngestionCoordinator.class);
        job = ((NewsIngestionResult.Queued) ingestion.ingest(event("restart"))).jobId();
        first =
            ((Claimed) firstContext.getBean(InferenceJobLifecycleService.class).claimNext())
                .assignment();
        var jdbc = firstContext.getBean(JdbcTemplate.class);
        before = InferenceJobFixtures.snapshot(jdbc, job);
        history = history(jdbc);
      }
      assertThat(firstContext.isActive()).isFalse();
      assertThat(firstPool.isClosed()).isTrue();
      assertThat(postgres.getContainerId()).isEqualTo(containerId);

      var secondContext = start(postgres, NOW.plusSeconds(restartSeconds), 1, "ollama");
      var secondPool = secondContext.getBean(HikariDataSource.class);
      try (secondContext) {
        var service = secondContext.getBean(InferenceJobLifecycleService.class);
        var jdbc = secondContext.getBean(JdbcTemplate.class);
        var time = secondContext.getBean(RestartTime.class);
        assertThat(InferenceJobFixtures.snapshot(jdbc, job)).isEqualTo(before); // No startup sweep.
        assertThat(history(jdbc)).isEqualTo(history);
        assertThat(
                jdbc.queryForObject(
                    "SELECT max_assignments FROM trader.inference_jobs WHERE job_id=?",
                    Integer.class,
                    job))
            .isEqualTo(3);
        assertThat(
                jdbc.queryForObject(
                    "SELECT expected_backend FROM trader.inference_jobs WHERE job_id=?",
                    String.class,
                    job))
            .isEqualTo("fake");
        if (restartSeconds < 60) {
          assertThat(service.recoverBatch().processed()).isZero();
          assertThat(service.renew(first.ref())).isEqualTo(new Renewed(NOW.plusSeconds(90)));
          var newJob =
              (NewsIngestionResult.Queued)
                  secondContext.getBean(NewsIngestionCoordinator.class).ingest(event("new-policy"));
          var next = ((Claimed) service.claimNext()).assignment();
          assertThat(next.ref().jobId()).isEqualTo(newJob.jobId());
          assertThat(next.expectedBackend()).isEqualTo("ollama");
          assertThat(service.fail(next.ref(), FailureKind.RECOVERABLE))
              .isEqualTo(new Failed(FailureCode.WORKER_RECOVERABLE));
        } else {
          assertThat(service.renew(first.ref()))
              .isEqualTo(new Rejected(RejectionReason.LEASE_EXPIRED));
          assertThat(service.fail(first.ref(), FailureKind.PERMANENT))
              .isEqualTo(new Rejected(RejectionReason.LEASE_EXPIRED));
          assertThat(InferenceJobFixtures.snapshot(jdbc, job)).isEqualTo(before);
          assertThat(service.recoverBatch()).isEqualTo(new RecoverySummary(2, 0, 0, 0, 1, 0, 1, 0));
          var second = ((Claimed) service.claimNext()).assignment();
          assertThat(second.ref().assignmentNumber()).isEqualTo(2);
          assertThat(second.ref().ownershipToken()).isNotEqualTo(first.ref().ownershipToken());
          assertThat(second.inferenceId()).isNotEqualTo(first.inferenceId());
          String replacement = InferenceJobFixtures.snapshot(jdbc, job);
          assertThat(service.renew(first.ref()))
              .isEqualTo(new Rejected(RejectionReason.OWNER_MISMATCH));
          assertThat(service.fail(first.ref(), FailureKind.PERMANENT))
              .isEqualTo(new Rejected(RejectionReason.OWNER_MISMATCH));
          assertThat(InferenceJobFixtures.snapshot(jdbc, job)).isEqualTo(replacement);
          time.value.set(second.leaseExpiresAt());
          service.recoverBatch();
          var third = ((Claimed) service.claimNext()).assignment();
          assertThat(third.ref().assignmentNumber()).isEqualTo(3);
          time.value.set(third.leaseExpiresAt());
          assertThat(service.recoverBatch().failed()).isEqualTo(1);
          assertThat(
                  jdbc.queryForObject(
                      "SELECT status FROM trader.inference_jobs WHERE job_id=?", String.class, job))
              .isEqualTo("FAILED");
          assertThat(service.claimNext()).isEqualTo(new NoWork());
        }
      }
      assertThat(secondPool.isClosed()).isTrue();
      assertThat(secondContext.isActive()).isFalse();
      assertThat(postgres.getContainerId()).isEqualTo(containerId);
    }
  }

  static ConfigurableApplicationContext start(
      PostgreSQLContainer<?> postgres, Instant now, int max, String backend) {
    return new SpringApplication(CoreApplication.class, RestartConfiguration.class)
        .run(
            "--spring.main.web-application-type=none",
            "--spring.datasource.url="
                + postgres.getJdbcUrl()
                + "?connectTimeout=2&socketTimeout=5",
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--spring.datasource.hikari.maximum-pool-size=2",
            "--spring.datasource.hikari.minimum-idle=0",
            "--spring.datasource.hikari.connection-timeout=2000",
            "--spring.lifecycle.timeout-per-shutdown-phase=5s",
            "--trader.news.ingestion.pending-capacity=4",
            "--trader.inference.jobs.lease-duration=60s",
            "--trader.inference.jobs.recovery-batch-size=4",
            "--trader.inference.jobs.max-assignments=" + max,
            "--trader.inference.jobs.expected-backend=" + backend,
            "--test.lifecycle-now=" + now);
  }

  static List<String> history(JdbcTemplate jdbc) {
    return jdbc.queryForList(
        "SELECT version || ':' || checksum FROM trader.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
        String.class);
  }

  static UnhashedNewsEvent event(String item) {
    return new UnhashedNewsEvent(
        "1.0.0",
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        new ProviderRevisionKey("fixture_wire", item, "r1"),
        "https://news.example.test/" + item,
        NOW.minusSeconds(30),
        NOW.minusSeconds(20),
        NOW.minusSeconds(5),
        NOW.plusSeconds(600),
        "Restart headline",
        new Content("en", "Summary", null),
        List.of("ACME"));
  }

  static final class RestartTime implements LifecycleTimeSource {
    final AtomicReference<Instant> value;

    RestartTime(Instant now) {
      value = new AtomicReference<>(now);
    }

    public Instant now(Connection connection) throws java.sql.SQLException {
      assertThat(connection.getAutoCommit()).isFalse();
      return value.get();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class RestartConfiguration {
    @Bean
    @Primary
    RestartTime restartTime(@Value("${test.lifecycle-now}") String now) {
      return new RestartTime(Instant.parse(now));
    }

    @Bean
    @Primary
    Clock restartIngestionClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}

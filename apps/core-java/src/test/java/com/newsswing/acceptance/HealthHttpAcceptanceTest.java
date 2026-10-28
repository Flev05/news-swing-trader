package com.newsswing.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.testcontainers.containers.PostgreSQLContainer;

@Timeout(value = 180, threadMode = Timeout.ThreadMode.SAME_THREAD)
class HealthHttpAcceptanceTest {
  @Test
  void keepsLivenessIndependentOfDatabaseAndRecoversReadinessWithoutLeakingDetails()
      throws Exception {
    try (var postgres = HttpAcceptanceSupport.postgres("health_acceptance")) {
      postgres.start();
      try (var app = HttpAcceptanceSupport.start(postgres, null)) {
        // The restart fixture's @TestConfiguration cannot leak into a normal context.
        assertThat(app.context.getBeansOfType(Clock.class)).containsOnlyKeys("newsIngestionClock");
        assertThat(app.context.getBean("lifecycleTimeSource").getClass().getSimpleName())
            .isEqualTo("PostgresLifecycleTimeSource");
        var groups = app.context.getBean(HealthEndpointGroups.class);
        assertThat(groups.getNames()).containsExactlyInAnyOrder("liveness", "readiness");
        for (String contributor :
            List.of(
                "livenessState",
                "readinessState",
                "db",
                "ping",
                "diskSpace",
                "ssl",
                "unexpected")) {
          assertThat(groups.get("liveness").isMember(contributor))
              .as("liveness member %s", contributor)
              .isEqualTo(contributor.equals("livenessState"));
          assertThat(groups.get("readiness").isMember(contributor))
              .as("readiness member %s", contributor)
              .isEqualTo(contributor.equals("readinessState") || contributor.equals("db"));
        }
        assertHealth(app, postgres, "/actuator/health", 200, "UP");
        assertHealth(app, postgres, "/actuator/health/liveness", 200, "UP");
        assertHealth(app, postgres, "/actuator/health/readiness", 200, "UP");
        var before = HttpAcceptanceSupport.snapshot(postgres);
        try (var control =
                DriverManager.getConnection(
                    HttpAcceptanceSupport.jdbcUrl(postgres, "postgres"),
                    postgres.getUsername(),
                    postgres.getPassword());
            var outage = new DatabaseAccessGuard(control)) {
          outage.disable();
          assertHealth(app, postgres, "/actuator/health/liveness", 200, "UP");
          assertHealth(app, postgres, "/actuator/health/readiness", 503, "DOWN");
          assertHealth(app, postgres, "/actuator/health", 503, "DOWN");
        }
        // Only restoration may poll: Hikari can recreate connections asynchronously.
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        HttpResponse<String> recovered = null;
        while (System.nanoTime() < deadline) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            break;
          }
          recovered =
              app.request(
                  "GET",
                  "/actuator/health/readiness",
                  null,
                  Duration.ofNanos(
                      Math.min(remaining, HttpAcceptanceSupport.REQUEST_TIMEOUT.toNanos())));
          assertPrivateHealth(app, postgres, recovered, "DOWN", "UP");
          assertThat(recovered.statusCode()).isIn(200, 503);
          if (recovered.statusCode() == 200) {
            break;
          }
        }
        assertThat(recovered).as("readiness response within recovery deadline").isNotNull();
        assertThat(recovered.statusCode()).as("readiness recovered before deadline").isEqualTo(200);
        assertThat(app.json(recovered).path("status").stringValue()).isEqualTo("UP");
        assertHealth(app, postgres, "/actuator/health/liveness", 200, "UP");
        assertHealth(app, postgres, "/actuator/health", 200, "UP");
        assertThat(HttpAcceptanceSupport.snapshot(postgres)).isEqualTo(before);
      }
    }
  }

  @Test
  void exposesOnlyHealthAndReturnsNotFoundForUnmappedPaths() throws Exception {
    try (var app = HttpAcceptanceSupport.startWithoutDatabase()) {
      assertThat(app.context.getBeansOfType(Clock.class)).containsOnlyKeys("newsIngestionClock");
      assertThat(app.context.getBean("lifecycleTimeSource").getClass().getSimpleName())
          .isEqualTo("PostgresLifecycleTimeSource");
      for (String endpoint :
          List.of(
              "metrics",
              "env",
              "configprops",
              "loggers",
              "mappings",
              "heapdump",
              "threaddump",
              "shutdown",
              "beans",
              "conditions",
              "info",
              "flyway",
              "caches",
              "scheduledtasks",
              "startup",
              "sessions",
              "httpexchanges",
              "integrationgraph",
              "quartz",
              "liquibase",
              "auditevents",
              "logfile",
              "prometheus")) {
        assertThat(app.get("/actuator/" + endpoint).statusCode())
            .as("unexposed %s", endpoint)
            .isEqualTo(404);
      }
      for (String path : List.of("/actuator", "/livez", "/readyz", "/not-a-resource")) {
        assertThat(app.get(path).statusCode()).as("unmapped %s", path).isEqualTo(404);
      }
      assertThat(app.post("/actuator/shutdown", "{}").statusCode()).isEqualTo(404);
      var liveness = app.get("/actuator/health/liveness");
      assertThat(liveness.statusCode()).isEqualTo(200);
      assertThat(app.json(liveness)).isEqualTo(app.mapper.readTree("{\"status\":\"UP\"}"));
    }
  }

  private static void assertHealth(
      HttpAcceptanceSupport.RunningApplication app,
      PostgreSQLContainer<?> postgres,
      String path,
      int status,
      String health)
      throws Exception {
    var response = app.get(path);
    assertThat(response.statusCode()).as(path).isEqualTo(status);
    assertPrivateHealth(app, postgres, response, health);
  }

  private static void assertPrivateHealth(
      HttpAcceptanceSupport.RunningApplication app,
      PostgreSQLContainer<?> postgres,
      HttpResponse<String> response,
      String... statuses) {
    var body = app.json(response);
    assertThat(body.path("status").stringValue()).isIn(statuses);
    if (response.request().uri().getPath().equals("/actuator/health")) {
      assertThat(body.propertyNames()).contains("status").isSubsetOf("status", "groups");
      if (body.has("groups")) {
        assertThat(body.path("groups").toString()).isEqualTo("[\"liveness\",\"readiness\"]");
      }
    } else {
      assertThat(body.propertyNames()).containsExactly("status");
    }
    assertThat(response.body())
        .doesNotContain(postgres.getUsername(), postgres.getPassword())
        .doesNotContainIgnoringCase("jdbc:")
        .doesNotContainIgnoringCase("postgresql")
        .doesNotContainIgnoringCase("select ")
        .doesNotContainIgnoringCase("exception")
        .doesNotContainIgnoringCase("stacktrace")
        .doesNotContainIgnoringCase("configuration")
        .doesNotContain("components", "details", "health_acceptance", "ALLOW_CONNECTIONS");
  }

  /** Created before fault injection, so even a partially failed disable always restores access. */
  private static final class DatabaseAccessGuard implements AutoCloseable {
    private final Connection control;

    DatabaseAccessGuard(Connection control) {
      this.control = control;
    }

    void disable() throws Exception {
      execute("ALTER DATABASE health_acceptance ALLOW_CONNECTIONS false");
      try (var statement = control.createStatement()) {
        statement.setQueryTimeout(10);
        try (var results =
            statement.executeQuery(
                "SELECT pg_terminate_backend(pid, 5000) FROM pg_stat_activity WHERE datname = 'health_acceptance' AND backend_type = 'client backend'")) {
          while (results.next()) {
            assertThat(results.getBoolean(1)).as("application backend terminated").isTrue();
          }
        }
      }
    }

    private void execute(String sql) throws Exception {
      try (var statement = control.createStatement()) {
        statement.setQueryTimeout(10);
        statement.execute(sql);
      }
    }

    @Override
    public void close() throws Exception {
      execute("ALTER DATABASE health_acceptance ALLOW_CONNECTIONS true");
    }
  }
}

package com.newsswing.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.newsswing.core.CoreApplication;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test fixtures live outside CoreApplication's scan; clocks are registered only as explicit
 * sources.
 */
final class HttpAcceptanceSupport {
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private HttpAcceptanceSupport() {}

  static PostgreSQLContainer<?> postgres(String database) {
    return new PostgreSQLContainer<>("postgres:18.6")
        .withDatabaseName(database)
        .withStartupTimeout(Duration.ofSeconds(120));
  }

  static RunningApplication startWithoutDatabase() {
    return start(null, null);
  }

  static RunningApplication start(PostgreSQLContainer<?> postgres, Instant now) {
    SpringApplication application =
        now == null
            ? new SpringApplication(CoreApplication.class)
            : new SpringApplication(CoreApplication.class, FixedClockConfiguration.class);
    var arguments =
        new ArrayList<>(
            List.of(
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--spring.datasource.url="
                    + (postgres == null
                        ? "jdbc:postgresql://127.0.0.1:1/unavailable?connectTimeout=2&socketTimeout=5"
                        : jdbcUrl(postgres, postgres.getDatabaseName())),
                "--spring.datasource.username="
                    + (postgres == null ? "test" : postgres.getUsername()),
                "--spring.datasource.password="
                    + (postgres == null ? "test" : postgres.getPassword()),
                // Flyway can hold one connection while acquiring another during migration.
                "--spring.datasource.hikari.maximum-pool-size=2",
                "--spring.datasource.hikari.minimum-idle=0",
                "--spring.datasource.hikari.connection-timeout=1000",
                "--spring.datasource.hikari.validation-timeout=500",
                "--spring.lifecycle.timeout-per-shutdown-phase=5s",
                "--trader.news.ingestion.pending-capacity=1"));
    if (postgres == null) {
      // Route handling needs no storage. Only this exposure test disables migrations.
      arguments.add("--spring.flyway.enabled=false");
      arguments.add("--spring.sql.init.mode=never");
      arguments.add("--spring.datasource.hikari.initialization-fail-timeout=-1");
    }
    if (now != null) {
      arguments.add("--test.ingestion-now=" + now);
    }
    var context =
        (ServletWebServerApplicationContext) application.run(arguments.toArray(String[]::new));
    try {
      return new RunningApplication(context);
    } catch (RuntimeException | Error failure) {
      try {
        context.close();
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  static String jdbcUrl(PostgreSQLContainer<?> postgres, String database) {
    return "jdbc:postgresql://"
        + postgres.getHost()
        + ":"
        + postgres.getMappedPort(5432)
        + "/"
        + database
        + "?connectTimeout=2&socketTimeout=5";
  }

  static List<List<String>> snapshot(PostgreSQLContainer<?> postgres) throws Exception {
    // Each query has an explicit ordering. Only durable business rows and stable schema metadata.
    List<String> queries =
        List.of(
            "SELECT row_to_json(t)::text FROM (SELECT * FROM trader.news_items ORDER BY id) t",
            "SELECT row_to_json(t)::text FROM (SELECT * FROM trader.news_revisions ORDER BY event_id) t",
            "SELECT row_to_json(t)::text FROM (SELECT * FROM trader.inference_jobs ORDER BY job_id) t",
            "SELECT row_to_json(t)::text FROM (SELECT * FROM trader.queue_admission_locks ORDER BY queue_name) t",
            "SELECT row_to_json(t)::text FROM (SELECT * FROM trader.platform_metadata ORDER BY metadata_key) t",
            "SELECT row_to_json(t)::text FROM (SELECT installed_rank, version, description, type, script, checksum, success FROM trader.flyway_schema_history ORDER BY installed_rank) t",
            "SELECT row_to_json(t)::text FROM (SELECT table_name, column_name, ordinal_position, data_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = 'trader' ORDER BY table_name, ordinal_position) t");
    var snapshot = new ArrayList<List<String>>();
    try (var connection =
        DriverManager.getConnection(
            jdbcUrl(postgres, postgres.getDatabaseName()),
            postgres.getUsername(),
            postgres.getPassword())) {
      connection.setReadOnly(true);
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
      for (String query : queries) {
        var rows = new ArrayList<String>();
        try (var statement = connection.createStatement()) {
          statement.setQueryTimeout(10);
          try (var results = statement.executeQuery(query)) {
            while (results.next()) {
              rows.add(results.getString(1));
            }
          }
        }
        snapshot.add(List.copyOf(rows));
      }
      connection.rollback();
    }
    return List.copyOf(snapshot);
  }

  static final class RunningApplication implements AutoCloseable {
    final ServletWebServerApplicationContext context;
    final HikariDataSource datasource;
    final JsonMapper mapper;
    private final HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final int port;

    RunningApplication(ServletWebServerApplicationContext context) {
      this.context = context;
      this.datasource = context.getBean(HikariDataSource.class);
      this.mapper = context.getBean(JsonMapper.class);
      this.port = context.getWebServer().getPort();
      assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
      assertThat(context.getEnvironment().containsProperty("management.server.port")).isFalse();
      assertThat(context.getEnvironment().containsProperty("management.server.address")).isFalse();
    }

    HttpResponse<String> get(String path) throws Exception {
      return request("GET", path, null, REQUEST_TIMEOUT);
    }

    HttpResponse<String> post(String path, String body) throws Exception {
      return request("POST", path, body, REQUEST_TIMEOUT);
    }

    HttpResponse<String> request(String method, String path, String body, Duration timeout)
        throws Exception {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    JsonNode json(HttpResponse<String> response) {
      return mapper.readTree(response.body());
    }

    @Override
    public void close() {
      try {
        client.close();
      } finally {
        context.close();
      }
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {
    @Bean
    @Primary
    Clock acceptanceClock(@Value("${test.ingestion-now}") String now) {
      return Clock.fixed(Instant.parse(now), ZoneOffset.UTC);
    }
  }
}

package com.newsswing.core.inference.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"server.port=0", "trader.news.ingestion.pending-capacity=4"})
@Testcontainers
@Import(PythonWorkerProcessAcceptanceTest.TimeConfiguration.class)
@Timeout(90)
class PythonWorkerProcessAcceptanceTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6");

  static final Instant NOW = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Path ROOT = findRoot();
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ControlledTime time;

  static Path findRoot() {
    Path path = Path.of("").toAbsolutePath();
    while (path != null && !Files.exists(path.resolve("apps/ai-worker/uv.lock")))
      path = path.getParent();
    if (path == null)
      throw new IllegalStateException("Run acceptance from the repository checkout");
    return path;
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TimeConfiguration {
    @Bean
    @Primary
    ControlledTime processLifecycleTime() {
      return new ControlledTime();
    }

    @Bean
    @Primary
    Clock processIngestionClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  static final class ControlledTime implements LifecycleTimeSource {
    final AtomicReference<Instant> value = new AtomicReference<>(NOW);

    public Instant now(java.sql.Connection connection) {
      return value.get();
    }
  }

  enum Scenario {
    NORMAL,
    LOST_COMPLETION,
    AMBIGUOUS_CLAIM,
    INTERRUPTED,
    AUTHORITY_LOSS,
    SHUTDOWN
  }

  @BeforeEach
  void reset() {
    time.value.set(NOW);
    jdbc.execute(
        "TRUNCATE trader.inference_results, trader.inference_jobs, trader.news_revisions, trader.news_items");
  }

  @ParameterizedTest
  @EnumSource(Scenario.class)
  void realPythonVerticalSlice(Scenario scenario) throws Exception {
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var gate = new Gate(client, scenario)) {
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/news-events"))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(news()))
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(202);
      String job = JSON.readTree(response.body()).at("/job/job_id").asString();
      try (var worker = launch(gate.port())) {
        if (EnumSet.of(Scenario.INTERRUPTED, Scenario.AUTHORITY_LOSS, Scenario.SHUTDOWN)
            .contains(scenario)) {
          assertThat(gate.arrived.await(15, TimeUnit.SECONDS)).isTrue();
          if (scenario == Scenario.INTERRUPTED) {
            worker.process.destroyForcibly();
            assertThat(worker.process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            time.value.set(NOW.plusSeconds(61));
          } else if (scenario == Scenario.AUTHORITY_LOSS) {
            time.value.set(NOW.plusSeconds(61));
          } else {
            worker.process.destroy(); // SIGTERM; handler is installed before its first request.
          }
          gate.release.countDown();
        }
        int expected =
            switch (scenario) {
              case AMBIGUOUS_CLAIM -> 3;
              case AUTHORITY_LOSS -> 4;
              default -> 0;
            };
        if (scenario != Scenario.INTERRUPTED) assertThat(worker.exit()).isEqualTo(expected);
        if (scenario == Scenario.AMBIGUOUS_CLAIM || scenario == Scenario.AUTHORITY_LOSS) {
          assertThat(status(job)).isEqualTo("RUNNING");
          assertThat(count("inference_results")).isZero();
          assertThat(gate.claims.get()).isEqualTo(1);
        } else if (scenario == Scenario.SHUTDOWN) {
          assertThat(status(job)).isEqualTo("RETRY_WAIT");
          assertThat(count("inference_results")).isZero();
          assertThat(gate.operations).containsExactly("claim", "renew", "fail");
        } else {
          if (scenario == Scenario.INTERRUPTED) {
            try (var replacement = launch(port)) {
              assertThat(replacement.exit()).isZero();
            }
          }
          assertThat(status(job)).isEqualTo("SUCCEEDED");
          assertThat(
                  jdbc.queryForObject(
                      "SELECT assignment_count FROM trader.inference_jobs", Integer.class))
              .isEqualTo(scenario == Scenario.INTERRUPTED ? 2 : 1);
          assertThat(count("inference_results")).isEqualTo(1);
          String result =
              jdbc.queryForObject(
                  "SELECT result_payload::text FROM trader.inference_results", String.class);
          var payload = JSON.readTree(result);
          assertThat(payload.at("/model/name").asString()).isEqualTo("deterministic-abstention");
          assertThat(payload.at("/ticker_results/0/ticker").asString()).isEqualTo("ACME");
          assertThat(payload.at("/ticker_results/1/ticker").asString()).isEqualTo("BETA");
          assertThat(payload.at("/ticker_results/0/abstain").asBoolean()).isTrue();
          if (scenario == Scenario.LOST_COMPLETION) {
            assertThat(gate.completions).hasSize(2);
            assertThat(gate.completions.get(0)).isEqualTo(gate.completions.get(1));
            assertThat(gate.outcomes).containsExactly("ACCEPTED", "ALREADY_ACCEPTED");
          }
        }
        assertThat(worker.output())
            .doesNotContain("ownership_token", "result_json", "news_event_json");
        System.out.println(
            "M3B2 "
                + scenario
                + ": HTTP "
                + response.statusCode()
                + ", "
                + status(job)
                + ", assignment_count="
                + jdbc.queryForObject(
                    "SELECT assignment_count FROM trader.inference_jobs", Integer.class)
                + ", results="
                + count("inference_results"));
      }
      assertThat(gate.failure.get()).isNull();
    }
  }

  String status(String job) {
    return jdbc.queryForObject(
        "SELECT status FROM trader.inference_jobs WHERE job_id=?::uuid", String.class, job);
  }

  int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM trader." + table, Integer.class);
  }

  String news() {
    return JSON.writeValueAsString(
        Map.ofEntries(
            Map.entry("schema_version", "1.0.0"),
            Map.entry("event_id", UUID.randomUUID().toString()),
            Map.entry("correlation_id", UUID.randomUUID().toString()),
            Map.entry(
                "source",
                Map.of(
                    "provider",
                    "fixture_wire",
                    "news_id",
                    "process-demo",
                    "revision_id",
                    "1",
                    "canonical_url",
                    "https://example.test/process-demo")),
            Map.entry("source_created_at", NOW.minusSeconds(30).toString()),
            Map.entry("source_updated_at", NOW.minusSeconds(20).toString()),
            Map.entry("received_at", NOW.minusSeconds(5).toString()),
            Map.entry("deadline_at", NOW.plusSeconds(600).toString()),
            Map.entry("headline", "Synthetic process demonstration"),
            Map.entry("content", Map.of("language", "en", "summary", "Synthetic fixture.")),
            Map.entry("affected_symbols", List.of("ACME", "BETA"))));
  }

  static RunningWorker launch(int targetPort) throws IOException {
    Path uv = ROOT.resolve(".tools/uv/0.12.9/uv");
    if (!Files.isExecutable(uv)
        || !Files.isExecutable(ROOT.resolve("apps/ai-worker/.venv/bin/python")))
      throw new IllegalStateException(
          "M3B2 requires repository-managed tooling: run make bootstrap in WSL Ubuntu");
    Path output = Files.createTempFile("m3b2-worker-", ".log");
    var builder =
        new ProcessBuilder(
                uv.toString(),
                "run",
                "--directory",
                "apps/ai-worker",
                "--locked",
                "--no-sync",
                "--offline",
                "python",
                "-m",
                "news_swing_ai.worker",
                "--base-url",
                "http://127.0.0.1:" + targetPort,
                "--once")
            .directory(ROOT.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile());
    builder.environment().put("UV_CACHE_DIR", ROOT.resolve(".tools/cache").toString());
    builder.environment().put("UV_PYTHON_INSTALL_DIR", ROOT.resolve(".tools/python").toString());
    return new RunningWorker(builder.start(), output);
  }

  record RunningWorker(Process process, Path log) implements AutoCloseable {
    int exit() throws Exception {
      assertThat(process.waitFor(25, TimeUnit.SECONDS))
          .as("Python worker must finish; output: %s", output())
          .isTrue();
      return process.exitValue();
    }

    String output() throws IOException {
      return Files.readString(log);
    }

    public void close() throws Exception {
      try {
        if (process.isAlive()) {
          process.destroyForcibly();
          assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
      } finally {
        Files.deleteIfExists(log);
      }
    }
  }

  final class Gate implements AutoCloseable {
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final ExecutorService executor = Executors.newCachedThreadPool();
    final CountDownLatch arrived = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger claims = new AtomicInteger();
    final List<String> operations = new CopyOnWriteArrayList<>();
    final List<byte[]> completions = new CopyOnWriteArrayList<>();
    final List<String> outcomes = new CopyOnWriteArrayList<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();

    Gate(HttpClient client, Scenario scenario) throws IOException {
      server.setExecutor(executor);
      server.createContext(
          "/api/v1/worker/",
          exchange -> {
            try (exchange) {
              String operation =
                  exchange.getRequestURI().getPath().substring("/api/v1/worker/".length());
              operations.add(operation);
              byte[] body = exchange.getRequestBody().readAllBytes();
              if (operation.equals("claim")) claims.incrementAndGet();
              if (scenario == Scenario.AUTHORITY_LOSS && operation.equals("renew")) {
                arrived.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("gate deadline");
              }
              var response =
                  client.send(
                      HttpRequest.newBuilder(
                              URI.create(
                                  "http://127.0.0.1:" + port + "/api/v1/worker/" + operation))
                          .timeout(Duration.ofSeconds(10))
                          .header("Content-Type", "application/json")
                          .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                          .build(),
                      HttpResponse.BodyHandlers.ofByteArray());
              if (operation.equals("claim")
                  && EnumSet.of(Scenario.INTERRUPTED, Scenario.SHUTDOWN).contains(scenario)) {
                arrived.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("gate deadline");
                if (scenario == Scenario.INTERRUPTED) return;
              }
              if (operation.equals("complete")) {
                completions.add(body);
                outcomes.add(JSON.readTree(response.body()).get("outcome").asString());
                if (scenario == Scenario.LOST_COMPLETION && completions.size() == 1) return;
              }
              if (operation.equals("claim") && scenario == Scenario.AMBIGUOUS_CLAIM) return;
              exchange
                  .getResponseHeaders()
                  .set("Content-Type", response.headers().firstValue("Content-Type").orElseThrow());
              exchange.sendResponseHeaders(response.statusCode(), response.body().length);
              exchange.getResponseBody().write(response.body());
            } catch (Exception error) {
              failure.compareAndSet(null, error);
            }
          });
      server.start();
    }

    int port() {
      return server.getAddress().getPort();
    }

    public void close() throws InterruptedException {
      release.countDown();
      server.stop(0);
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}

package com.newsswing.core.inference.api;

import static com.newsswing.core.inference.jobs.WorkerLifecycleTestConfiguration.NOW;
import static org.assertj.core.api.Assertions.*;

import com.newsswing.core.inference.jobs.WorkerLifecycleTestConfiguration;
import com.newsswing.core.inference.jobs.WorkerLifecycleTestConfiguration.ControlledTime;
import com.newsswing.support.InferenceJobFixtures;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "server.port=0", "trader.news.ingestion.pending-capacity=4",
      "trader.inference.jobs.recovery-batch-size=2", "trader.news.api.max-request-bytes=2048"
    })
@Testcontainers
@Import(WorkerLifecycleTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@Timeout(60)
class WorkerHttpApiIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6");

  static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  static final JsonMapper JSON = JsonMapper.builder().build();
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ControlledTime time;

  @BeforeEach
  void reset() {
    time.set(NOW);
    jdbc.execute(
        "TRUNCATE trader.inference_results, trader.inference_jobs, trader.news_revisions, trader.news_items");
  }

  @AfterAll
  static void closeClient() {
    CLIENT.close();
  }

  @Test
  void claimsRenewsCompletesAndReplaysWithoutExposingOwnership(CapturedOutput logs)
      throws Exception {
    String news = news("slice", 1);
    var admission = json(ingest(news));
    String job = admission.at("/job/job_id").asString();
    var assignment = claim();
    assertThat(assignment.propertyNames())
        .containsExactlyInAnyOrder(
            "ref",
            "news_event_json",
            "inference_id",
            "expected_backend",
            "result_schema_version",
            "lease_expires_at");
    assertThat(assignment.get("ref").propertyNames())
        .containsExactlyInAnyOrder("job_id", "assignment_number", "ownership_token");
    assertThat(assignment.at("/ref/job_id").asString()).isEqualTo(job);
    assertThat(assignment.at("/ref/assignment_number").intValue()).isEqualTo(1);
    assertThat(assignment.get("expected_backend").asString()).isEqualTo("fake");
    assertThat(assignment.get("result_schema_version").asString()).isEqualTo("1.0.0");
    var event = JSON.readTree(assignment.get("news_event_json").asString());
    assertThat(event.get("content_hash").asString()).matches("[a-f0-9]{64}");
    assertThat(event.get("affected_symbols").size()).isEqualTo(2);
    assertThat(status(job)).isEqualTo("RUNNING");
    assertThat(
            sqlInt(
                "SELECT assignment_count FROM trader.inference_jobs WHERE job_id = ?::uuid", job))
        .isEqualTo(1);

    time.set(NOW.plusSeconds(20));
    var renewed = post("renew", command(assignment));
    success(renewed, "RENEWED");
    assertThat(json(renewed).get("lease_expires_at").asString())
        .isEqualTo(NOW.plusSeconds(80).toString());

    String result = payload(assignment);
    var accepted = post("complete", complete(assignment, result));
    success(accepted, "ACCEPTED");
    assertThat(json(accepted).get("receipt").propertyNames())
        .containsExactlyInAnyOrder("job_id", "news_event_id", "inference_id", "accepted_at");
    assertThat(status(job)).isEqualTo("SUCCEEDED");
    assertThat(results()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT ownership_token IS NULL FROM trader.inference_jobs WHERE job_id=?::uuid",
                Boolean.class,
                job))
        .isTrue();

    // Expiry and newer admission do not prevent historical acknowledgement.
    ingest(news("slice", 2));
    time.set(NOW.plusSeconds(601));
    var replay = post("complete", complete(assignment, result));
    success(replay, "ALREADY_ACCEPTED");
    assertThat(json(replay).get("receipt")).isEqualTo(json(accepted).get("receipt"));
    problem(
        post("complete", complete(assignment, result.replace("explicit-test-fixture", "changed"))),
        409,
        "COMPLETION_CONFLICT");
    assertThat(results()).isEqualTo(1);

    String token = assignment.at("/ref/ownership_token").asString();
    for (var response :
        List.of(
            get("/api/v1/inference-jobs/" + job),
            get("/api/v1/news-events/" + event.get("event_id").asString()),
            ingest(news))) {
      assertThat(response.body())
          .doesNotContain(token, "ownership_token", "assignment_number", "assignment_inference_id");
      assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
      assertThat(response.headers().firstValue("Location").orElse("")).doesNotContain(token);
    }
    assertThat(json(get("/api/v1/inference-jobs/" + job)).propertyNames())
        .containsExactlyInAnyOrder(
            "job_id",
            "news_event_id",
            "job_kind",
            "input_schema_version",
            "current_status",
            "created_at",
            "superseded_at",
            "superseded_by_event_id");
    assertThat(logs.getAll()).doesNotContain(token, result);
    problem(get("/api/v1/inference-results/" + job), 404, "RESOURCE_NOT_FOUND");
  }

  @Test
  void failuresRecoverWithFreshAuthorityAndExhaustStoredBudget() throws Exception {
    String job = json(ingest(news("failure", 1))).at("/job/job_id").asString();
    var first = claim();
    success(post("fail", withKind(first, "RECOVERABLE")), "RETRY_WAITING");
    assertThat(status(job)).isEqualTo("RETRY_WAIT");
    var second = claim();
    assertThat(second.at("/ref/assignment_number").intValue()).isEqualTo(2);
    assertThat(second.at("/ref/ownership_token")).isNotEqualTo(first.at("/ref/ownership_token"));
    problem(post("renew", command(first)), 409, "OWNER_MISMATCH");
    success(post("fail", withKind(second, "RECOVERABLE")), "RETRY_WAITING");
    var third = claim();
    var failed = post("fail", withKind(third, "RECOVERABLE"));
    success(failed, "FAILED");
    assertThat(json(failed).get("failure_code").asString()).isEqualTo("WORKER_RECOVERABLE");
    assertThat(status(job)).isEqualTo("FAILED");
    assertThat(results()).isZero();
    var noWork = post("claim", "{}");
    success(noWork, "NO_WORK");
    assertThat(noWork.headers().firstValue("Retry-After")).contains("2");
    assertThat(json(noWork).size()).isEqualTo(1);
  }

  @Test
  void permanentFailureTerminatesImmediately() throws Exception {
    String job = json(ingest(news("permanent", 1))).at("/job/job_id").asString();
    var assignment = claim();
    var failed = post("fail", withKind(assignment, "PERMANENT"));
    success(failed, "FAILED");
    assertThat(json(failed).get("failure_code").asString()).isEqualTo("WORKER_PERMANENT");
    assertThat(status(job)).isEqualTo("FAILED");
    assertThat(
            sqlInt("SELECT assignment_count FROM trader.inference_jobs WHERE job_id=?::uuid", job))
        .isEqualTo(1);
  }

  @Test
  void noWorkDoesNotMeanRecoveryOrQueueIsEmpty() throws Exception {
    // Cleanup consumes both slots; eligible RETRY_WAIT is outside this call's budget.
    ingest(news("waiting", 1));
    var a = claim();
    success(post("fail", withKind(a, "RECOVERABLE")), "RETRY_WAITING");
    ingest(news("expired-a", 1));
    ingest(news("expired-b", 1));
    time.set(NOW.plusSeconds(31));
    var response = post("claim", "{}");
    success(response, "NO_WORK");
    assertThat(sqlInt("SELECT count(*) FROM trader.inference_jobs WHERE status='RETRY_WAIT'"))
        .isEqualTo(1);
    assertThat(sqlInt("SELECT count(*) FROM trader.inference_jobs WHERE status='EXPIRED'"))
        .isEqualTo(2);
  }

  static Stream<Arguments> invalidResults() {
    return Stream.of(
            "malformed",
            "duplicate",
            "nestedDuplicate",
            "trailing",
            "nul",
            "surrogate",
            "closed",
            "provenance")
        .flatMap(
            kind -> Stream.of("live", "wrong", "accepted").map(owner -> Arguments.of(kind, owner)));
  }

  @ParameterizedTest
  @MethodSource("invalidResults")
  void innerInvalidDocumentsRetainLifecyclePrecedence(
      String kind, String owner, CapturedOutput logs) throws Exception {
    String job = json(ingest(news("invalid", 1))).at("/job/job_id").asString();
    var assignment = claim();
    String valid = payload(assignment);
    if (owner.equals("accepted"))
      success(post("complete", complete(assignment, valid)), "ACCEPTED");
    String invalid =
        switch (kind) {
          case "malformed" -> "{";
          case "duplicate" -> valid.replace("\"model\":", "\"model\":{},\"model\":");
          case "nestedDuplicate" ->
              valid.replace("\"backend\":\"fake\"", "\"backend\":\"fake\",\"backend\":\"fake\"");
          case "trailing" -> valid + " {}";
          case "nul" -> valid.replace("explicit-test-fixture", "\\u0000");
          case "surrogate" -> valid.replace("explicit-test-fixture", "\\ud800");
          case "closed" ->
              valid.replace("\"model\":{", "\"model\":{\"PRIVATE_RESULT_SENTINEL\":true,");
          case "provenance" ->
              valid.replace(
                  assignment.get("inference_id").asString(), UUID.randomUUID().toString());
          default -> throw new AssertionError(kind);
        };
    var requestAssignment = assignment.deepCopy();
    if (owner.equals("wrong"))
      ((ObjectNode) requestAssignment.get("ref"))
          .put("ownership_token", UUID.randomUUID().toString());
    var response = post("complete", complete(requestAssignment, invalid));
    if (owner.equals("live")) {
      problem(response, 422, kind.equals("provenance") ? "PROVENANCE_MISMATCH" : "INVALID_RESULT");
      assertThat(status(job)).isEqualTo("FAILED");
      assertThat(results()).isZero();
    } else if (owner.equals("wrong")) {
      problem(response, 409, "OWNER_MISMATCH");
      assertThat(status(job)).isEqualTo("RUNNING");
      assertThat(results()).isZero();
    } else {
      problem(response, 409, "COMPLETION_CONFLICT");
      assertThat(status(job)).isEqualTo("SUCCEEDED");
      assertThat(results()).isEqualTo(1);
    }
    assertThat(response.body())
        .doesNotContain(
            "PRIVATE_RESULT_SENTINEL",
            "ownership_token",
            "violations",
            assignment.at("/ref/ownership_token").asString());
    assertThat(logs.getAll())
        .doesNotContain(
            "PRIVATE_RESULT_SENTINEL", assignment.at("/ref/ownership_token").asString());
  }

  @ParameterizedTest
  @MethodSource("com.newsswing.core.inference.api.WorkerEnvelopeParserTest#invalidEnvelopes")
  void rejectsBadEnvelopesBeforeMutation(
      WorkerEnvelopeParser.Operation operation, String input, String code) throws Exception {
    String job = json(ingest(news("untouched", 1))).at("/job/job_id").asString();
    problem(post(operation.name().toLowerCase(Locale.ROOT), input), 400, code);
    assertThat(status(job)).isEqualTo("PENDING");
    assertThat(
            sqlInt("SELECT assignment_count FROM trader.inference_jobs WHERE job_id=?::uuid", job))
        .isZero();
    assertThat(results()).isZero();
  }

  static Stream<Arguments> bodyLimits() {
    return Stream.of("claim", "renew", "fail", "complete")
        .flatMap(
            op ->
                Stream.of(false, true)
                    .flatMap(
                        chunked ->
                            Stream.of(false, true)
                                .map(oversize -> Arguments.of(op, chunked, oversize))));
  }

  @ParameterizedTest
  @MethodSource("bodyLimits")
  void enforcesExactRawByteLimits(String operation, boolean chunked, boolean oversize)
      throws Exception {
    String job = json(ingest(news("limits", 1))).at("/job/job_id").asString();
    var assignment = claim();
    String input =
        switch (operation) {
          case "claim" -> "{}";
          case "renew" -> command(assignment);
          case "fail" -> withKind(assignment, "PERMANENT");
          case "complete" -> complete(assignment, payload(assignment));
          default -> throw new AssertionError(operation);
        };
    int limit = operation.equals("complete") ? 1048576 : 8192;
    byte[] bytes =
        (input
                + " "
                    .repeat(
                        limit - input.getBytes(StandardCharsets.UTF_8).length + (oversize ? 1 : 0)))
            .getBytes(StandardCharsets.UTF_8);
    HttpRequest.BodyPublisher publisher =
        chunked
            ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
            : HttpRequest.BodyPublishers.ofByteArray(bytes);
    var response =
        send("POST", "/api/v1/worker/" + operation, publisher, "application/json", null, null);
    if (oversize) {
      problem(response, 413, "PAYLOAD_TOO_LARGE");
      assertThat(status(job)).isEqualTo("RUNNING");
      assertThat(results()).isZero();
    } else {
      assertThat(response.statusCode()).isEqualTo(200);
      if (operation.equals("complete")) assertThat(results()).isEqualTo(1);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"renew", "fail", "complete"})
  void rejectsUnknownAndExpiredAuthority(String operation) throws Exception {
    ingest(news("authority", 1));
    var assignment = claim();
    var missing = assignment.deepCopy();
    ((ObjectNode) missing.get("ref")).put("job_id", UUID.randomUUID().toString());
    problem(post(operation, operationBody(operation, missing)), 404, "ASSIGNMENT_NOT_FOUND");
    time.set(NOW.plusSeconds(60));
    problem(post(operation, operationBody(operation, assignment)), 409, "LEASE_EXPIRED");
    time.set(NOW.plusSeconds(600));
    problem(post(operation, operationBody(operation, assignment)), 409, "NEWS_EXPIRED");
    time.set(NOW);
    ingest(news("authority", 2));
    problem(post(operation, operationBody(operation, assignment)), 409, "NOT_RUNNING");
  }

  @Test
  void supersessionAndRecoveryFenceOldOwner() throws Exception {
    String job = json(ingest(news("interrupted", 1))).at("/job/job_id").asString();
    var first = claim();
    time.set(NOW.plusSeconds(60));
    var second = claim();
    assertThat(second.at("/ref/assignment_number").intValue()).isEqualTo(2);
    for (String op : List.of("renew", "fail", "complete"))
      problem(post(op, operationBody(op, first)), 409, "OWNER_MISMATCH");
    success(post("complete", complete(second, payload(second))), "ACCEPTED");
    assertThat(status(job)).isEqualTo("SUCCEEDED");
    assertThat(results()).isEqualTo(1);
  }

  @Test
  void mapsHttpErrorsAndMalformedUtf8WithoutLeakingQueryValues(CapturedOutput logs)
      throws Exception {
    problem(
        send(
            "POST",
            "/api/v1/worker/claim",
            HttpRequest.BodyPublishers.ofString("{}"),
            "text/plain",
            null,
            null),
        415,
        "UNSUPPORTED_MEDIA_TYPE");
    problem(
        send(
            "POST",
            "/api/v1/worker/claim",
            HttpRequest.BodyPublishers.ofString("{}"),
            "application/json;charset=ISO-8859-1",
            null,
            null),
        415,
        "UNSUPPORTED_MEDIA_TYPE");
    problem(
        send(
            "POST",
            "/api/v1/worker/claim",
            HttpRequest.BodyPublishers.ofString("{}"),
            null,
            null,
            null),
        415,
        "UNSUPPORTED_MEDIA_TYPE");
    problem(
        send(
            "POST",
            "/api/v1/worker/claim",
            HttpRequest.BodyPublishers.ofString("{}"),
            "application/json",
            "gzip",
            null),
        415,
        "UNSUPPORTED_CONTENT_ENCODING");
    problem(
        send(
            "POST",
            "/api/v1/worker/claim",
            HttpRequest.BodyPublishers.ofString("{}"),
            "application/json",
            null,
            "text/plain"),
        406,
        "NOT_ACCEPTABLE");
    problem(get("/api/v1/worker/claim"), 405, "METHOD_NOT_ALLOWED");
    problem(get("/api/v1/worker/missing"), 404, "RESOURCE_NOT_FOUND");
    var query = post("claim?token=PRIVATE_QUERY_SENTINEL", "{}");
    problem(query, 400, "INVALID_COMMAND");
    assertThat(query.body()).doesNotContain("PRIVATE_QUERY_SENTINEL");
    assertThat(logs.getAll()).doesNotContain("PRIVATE_QUERY_SENTINEL");
    problem(
        send(
            "POST",
            "/api/v1/worker/claim",
            HttpRequest.BodyPublishers.ofByteArray(new byte[] {(byte) 0xc3, (byte) 0x28}),
            "application/json",
            null,
            null),
        400,
        "MALFORMED_JSON");
    success(
        send(
            "POST",
            "/api/v1/worker/claim",
            HttpRequest.BodyPublishers.ofString("{}"),
            "application/json;charset=UTF-8",
            "identity",
            null),
        "NO_WORK");
  }

  @Test
  void storageFailureIsSanitizedAndRollsBack(CapturedOutput logs) throws Exception {
    String job = json(ingest(news("rollback", 1))).at("/job/job_id").asString();
    var assignment = claim();
    jdbc.execute(
        "CREATE FUNCTION trader.worker_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'PRIVATE_SQL_SENTINEL'; END $$");
    jdbc.execute(
        "CREATE TRIGGER worker_failure BEFORE INSERT ON trader.inference_results FOR EACH ROW EXECUTE FUNCTION trader.worker_failure()");
    try {
      var response = post("complete", complete(assignment, payload(assignment)));
      problem(response, 503, "INFRASTRUCTURE_FAILURE");
      assertThat(response.body())
          .doesNotContain("PRIVATE_SQL_SENTINEL", "PSQLException", "jdbc:", "ownership_token");
      assertThat(logs.getAll())
          .doesNotContain("PRIVATE_SQL_SENTINEL", assignment.at("/ref/ownership_token").asString());
      assertThat(status(job)).isEqualTo("RUNNING");
      assertThat(results()).isZero();
    } finally {
      jdbc.execute("DROP TRIGGER worker_failure ON trader.inference_results");
      jdbc.execute("DROP FUNCTION trader.worker_failure()");
    }
    success(post("complete", complete(assignment, payload(assignment))), "ACCEPTED");
  }

  @Test
  void recoveryInfrastructureFailureIs503AndKeepsEarlierCommits() throws Exception {
    String first = json(ingest(news("first", 1))).at("/job/job_id").asString();
    String second = json(ingest(news("second", 1))).at("/job/job_id").asString();
    jdbc.update(
        "UPDATE trader.inference_jobs SET created_at=created_at - interval '1 day' WHERE job_id=?::uuid",
        first);
    jdbc.execute(
        "CREATE FUNCTION trader.worker_recovery_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.job_id='"
            + second
            + "'::uuid THEN RAISE EXCEPTION 'PRIVATE_RECOVERY_SENTINEL'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER worker_recovery_failure BEFORE UPDATE ON trader.inference_jobs FOR EACH ROW EXECUTE FUNCTION trader.worker_recovery_failure()");
    time.set(NOW.plusSeconds(600));
    try {
      var response = post("claim", "{}");
      problem(response, 503, "INFRASTRUCTURE_FAILURE");
      assertThat(response.body())
          .doesNotContain(second, "PRIVATE_RECOVERY_SENTINEL", "committedProgress");
      assertThat(status(first)).isEqualTo("EXPIRED");
      assertThat(status(second)).isEqualTo("PENDING");
    } finally {
      jdbc.execute("DROP TRIGGER worker_recovery_failure ON trader.inference_jobs");
      jdbc.execute("DROP FUNCTION trader.worker_recovery_failure()");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"claim", "renew", "fail", "complete"})
  void countsUtf8BytesRatherThanCharacters(String operation) throws Exception {
    int limit = operation.equals("complete") ? 1048576 : 8192;
    String body = "{\"x\":\"" + "é".repeat(limit / 2) + "\"}";
    assertThat(body.length()).isLessThan(limit);
    problem(post(operation, body), 413, "PAYLOAD_TOO_LARGE");
    assertThat(sqlInt("SELECT count(*) FROM trader.inference_jobs")).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"é😀", "literal\\\\u0000", "literal\\\\ud800"})
  void validUnicodeAndLiteralEscapesSurviveHttpAndJsonb(String replacement) throws Exception {
    ingest(news("unicode", 1));
    var assignment = claim();
    String result = payload(assignment).replace("explicit-test-fixture", replacement);
    success(post("complete", complete(assignment, result)), "ACCEPTED");
    String stored =
        jdbc.queryForObject(
            "SELECT result_payload::text FROM trader.inference_results", String.class);
    assertThat(JSON.readTree(stored)).isEqualTo(JSON.readTree(result));
    success(post("complete", complete(assignment, result)), "ALREADY_ACCEPTED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/worker/%63laim", "/api/v1/worker/claim;transport=test"})
  void containerRouteAliasesCannotBypassBodyProtection(String path) throws Exception {
    success(
        send(
            "POST",
            path,
            HttpRequest.BodyPublishers.ofString("{}"),
            "application/json",
            null,
            null),
        "NO_WORK");
    problem(
        send(
            "POST",
            path,
            HttpRequest.BodyPublishers.ofString("{}" + " ".repeat(8191)),
            "application/json",
            null,
            null),
        413,
        "PAYLOAD_TOO_LARGE");
  }

  private String operationBody(String op, JsonNode assignment) {
    return switch (op) {
      case "renew" -> command(assignment);
      case "fail" -> withKind(assignment, "PERMANENT");
      case "complete" -> complete(assignment, payload(assignment));
      default -> throw new AssertionError(op);
    };
  }

  private String news(String item, int revision) {
    return JSON.writeValueAsString(
        Map.ofEntries(
            Map.entry("schema_version", "1.0.0"),
            Map.entry("event_id", stableId(item, revision).toString()),
            Map.entry("correlation_id", stableId(item, 0).toString()),
            Map.entry(
                "source",
                Map.of(
                    "provider",
                    "fixture_wire",
                    "news_id",
                    item,
                    "revision_id",
                    "r" + revision,
                    "canonical_url",
                    "https://example.test/" + item)),
            Map.entry("source_created_at", NOW.minusSeconds(30).toString()),
            Map.entry("source_updated_at", NOW.minusSeconds(20).plusSeconds(revision).toString()),
            Map.entry("received_at", NOW.minusSeconds(5).toString()),
            Map.entry(
                "deadline_at", NOW.plusSeconds(item.startsWith("expired-") ? 30 : 600).toString()),
            Map.entry("headline", "Synthetic " + revision),
            Map.entry("content", Map.of("language", "en", "summary", "Synthetic fixture.")),
            Map.entry("affected_symbols", List.of("ACME", "BETA"))));
  }

  private static UUID stableId(String item, int revision) {
    return UUID.nameUUIDFromBytes((item + revision).getBytes(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> ingest(String news) throws Exception {
    return send(
        "POST",
        "/api/v1/news-events",
        HttpRequest.BodyPublishers.ofString(news),
        "application/json",
        null,
        null);
  }

  private JsonNode claim() throws Exception {
    var response = post("claim", "{}");
    success(response, "CLAIMED");
    return json(response).get("assignment");
  }

  private String payload(JsonNode assignment) {
    return InferenceJobFixtures.resultPayload(
        assignment.get("news_event_json").asString(),
        UUID.fromString(assignment.get("inference_id").asString()),
        "fake",
        NOW);
  }

  private static String command(JsonNode assignment) {
    return JSON.writeValueAsString(Map.of("assignment", assignment.get("ref")));
  }

  private static String withKind(JsonNode assignment, String kind) {
    return JSON.writeValueAsString(Map.of("assignment", assignment.get("ref"), "kind", kind));
  }

  private static String complete(JsonNode assignment, String result) {
    return JSON.writeValueAsString(
        Map.of("assignment", assignment.get("ref"), "result_json", result));
  }

  private HttpResponse<String> post(String op, String body) throws Exception {
    return send(
        "POST",
        "/api/v1/worker/" + op,
        HttpRequest.BodyPublishers.ofString(body),
        "application/json",
        null,
        null);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send("GET", path, HttpRequest.BodyPublishers.noBody(), null, null, null);
  }

  private HttpResponse<String> send(
      String method,
      String path,
      HttpRequest.BodyPublisher body,
      String media,
      String encoding,
      String accept)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(30))
            .method(method, body);
    if (media != null) builder.header("Content-Type", media);
    if (encoding != null) builder.header("Content-Encoding", encoding);
    if (accept != null) builder.header("Accept", accept);
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode json(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }

  private static void success(HttpResponse<String> response, String outcome) {
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json(response).get("outcome").asString()).isEqualTo(outcome);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(response.headers().firstValue("Location")).isEmpty();
  }

  private static void problem(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/problem+json");
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(json(response).get("code").asString()).isEqualTo(code);
    assertThat(json(response).propertyNames())
        .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code");
    assertThat(response.headers().firstValue("Location")).isEmpty();
  }

  private String status(String job) {
    return jdbc.queryForObject(
        "SELECT status FROM trader.inference_jobs WHERE job_id=?::uuid", String.class, job);
  }

  private int results() {
    return sqlInt("SELECT count(*) FROM trader.inference_results");
  }

  private int sqlInt(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }
}

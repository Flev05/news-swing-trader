package com.newsswing.core.news.api;

import static com.newsswing.core.inference.jobs.InferenceJobLifecycleService.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.newsswing.core.inference.jobs.InferenceJobLifecycleService;
import com.newsswing.core.inference.jobs.LifecycleHttpTestConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "server.port=0",
      "trader.news.ingestion.pending-capacity=1",
      "trader.news.api.max-request-bytes=1024"
    })
@Testcontainers
@Import({NewsHttpApiIntegrationTest.ClockConfiguration.class, LifecycleHttpTestConfiguration.class})
@Timeout(30)
class NewsHttpApiIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-01-15T14:30:30Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6");

  @DynamicPropertySource
  static void configureFlywaySchema(DynamicPropertyRegistry registry) {
    registry.add("spring.flyway.default-schema", () -> "trader");
    registry.add("spring.flyway.schemas", () -> "trader");
  }

  @LocalServerPort private int port;
  @Autowired private JsonMapper jsonMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private InferenceJobLifecycleService lifecycle;

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @BeforeEach
  void clearPendingCapacity() {
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
  void realLifecycleTransitionsKeepExistingHttpFieldsPrivate() throws Exception {
    // Historical status fixtures in other methods can leave live RUNNING/RETRY_WAIT rows.
    // Isolate this claim/recovery scenario from their ordering.
    jdbcTemplate.execute(
        "TRUNCATE trader.inference_results, trader.inference_jobs, trader.news_revisions, trader.news_items");
    UUID eventId = UUID.randomUUID();
    String body = event(eventId, "lifecycle-" + UUID.randomUUID(), "revision-1", 0);
    var admission = json(post(body));
    UUID jobId = UUID.fromString(admission.path("job").path("job_id").stringValue());
    var assignment = ((Claimed) lifecycle.claimNext()).assignment();
    assertThat(assignment.ref().jobId()).isEqualTo(jobId);
    assertLifecycleHttp(body, eventId, jobId, "RUNNING");
    assertThat(lifecycle.renew(assignment.ref())).isInstanceOf(Renewed.class);
    assertThat(lifecycle.fail(assignment.ref(), FailureKind.RECOVERABLE))
        .isInstanceOf(RetryWaiting.class);
    assertLifecycleHttp(body, eventId, jobId, "RETRY_WAIT");
    assertThat(lifecycle.recoverBatch().requeued()).isEqualTo(1);
    assertLifecycleHttp(body, eventId, jobId, "PENDING");
    var second = ((Claimed) lifecycle.claimNext()).assignment();
    assertThat(lifecycle.fail(second.ref(), FailureKind.PERMANENT)).isInstanceOf(Failed.class);
    assertLifecycleHttp(body, eventId, jobId, "FAILED");
  }

  private void assertLifecycleHttp(String requestBody, UUID eventId, UUID jobId, String status)
      throws Exception {
    var response = get("/api/v1/inference-jobs/" + jobId);
    assertThat(response.statusCode()).isEqualTo(200);
    assertSuccessJson(response);
    var job = json(response);
    assertFields(
        job,
        "job_id",
        "news_event_id",
        "job_kind",
        "input_schema_version",
        "current_status",
        "created_at",
        "superseded_at",
        "superseded_by_event_id");
    assertThat(job.path("current_status").stringValue()).isEqualTo(status);
    assertThat(job.path("superseded_at").isNull()).isTrue();
    assertThat(job.path("superseded_by_event_id").isNull()).isTrue();
    var revisionResponse = get("/api/v1/news-events/" + eventId);
    assertSuccessJson(revisionResponse);
    var revision = json(revisionResponse);
    assertFields(revision, "event", "news_item_id", "admission_state", "persisted_at", "job");
    assertFields(
        revision.path("job"),
        "job_id",
        "current_status",
        "created_at",
        "superseded_at",
        "superseded_by_event_id");
    assertThat(revision.path("job").path("current_status").stringValue()).isEqualTo(status);
    var duplicateResponse = post(requestBody);
    assertThat(duplicateResponse.statusCode()).isEqualTo(200);
    assertSuccessJson(duplicateResponse);
    var duplicate = json(duplicateResponse);
    assertFields(
        duplicate,
        "outcome",
        "requested_event_id",
        "stored_event_id",
        "news_item_id",
        "admission_state",
        "job");
    assertFields(duplicate.path("job"), "job_id", "current_status");
    assertThat(duplicate.path("job").path("current_status").stringValue()).isEqualTo(status);
  }

  @Test
  void queuesReadsSupersedesAndResolvesDuplicatesThroughRealHttp() throws Exception {
    String newsId = "http-queued-" + UUID.randomUUID();
    UUID first = UUID.randomUUID();
    HttpResponse<String> queued = post(event(first, newsId, "revision-1", 0));

    assertThat(queued.statusCode()).isEqualTo(202);
    assertThat(queued.headers().firstValue("location"))
        .hasValueSatisfying(value -> assertThat(value).startsWith("/api/v1/inference-jobs/"));
    assertNoStore(queued);
    assertThat(queued.body()).contains("\"outcome\":\"QUEUED\"", first.toString());

    HttpResponse<String> eventRead = get("/api/v1/news-events/" + first);
    assertThat(eventRead.statusCode()).isEqualTo(200);
    assertNoStore(eventRead);
    assertThat(eventRead.body())
        .contains("\"admission_state\":\"QUEUED\"", "\"current_status\":\"PENDING\"");

    HttpResponse<String> duplicate = post(event(first, newsId, "revision-1", 0));
    assertThat(duplicate.statusCode()).isEqualTo(200);
    assertNoStore(duplicate);
    assertThat(duplicate.headers().firstValue("location")).isEmpty();
    assertThat(duplicate.headers().firstValue("content-location")).isEmpty();
    assertThat(duplicate.body())
        .contains("\"outcome\":\"DUPLICATE\"", "\"current_status\":\"PENDING\"");

    UUID replacement = UUID.randomUUID();
    assertThat(post(event(replacement, newsId, "revision-2", 10)).statusCode()).isEqualTo(202);

    HttpResponse<String> superseded = post(event(first, newsId, "revision-1", 0));
    assertThat(superseded.statusCode()).isEqualTo(200);
    assertThat(superseded.body()).contains("\"current_status\":\"SUPERSEDED\"");

    String jobLocation = queued.headers().firstValue("location").orElseThrow();
    HttpResponse<String> jobRead = get(jobLocation);
    assertThat(jobRead.statusCode()).isEqualTo(200);
    assertThat(jobRead.body())
        .contains("\"news_event_id\":\"" + first + "\"", "\"current_status\":\"SUPERSEDED\"");

    assertProblem(get("/api/v1/news-events/" + UUID.randomUUID()), 404, "NEWS_EVENT_NOT_FOUND");
    assertProblem(
        get("/api/v1/inference-jobs/" + UUID.randomUUID()), 404, "INFERENCE_JOB_NOT_FOUND");
    assertProblem(get("/api/v1/news-events/not-a-uuid"), 400, "INVALID_PATH_PARAMETER");
  }

  @Test
  void storesOutOfOrderAndMapsStrictAndDomainFailures() throws Exception {
    String newsId = "http-ordering-" + UUID.randomUUID();
    UUID latest = UUID.randomUUID();
    assertThat(post(event(latest, newsId, "revision-2", 10)).statusCode()).isEqualTo(202);

    UUID older = UUID.randomUUID();
    HttpResponse<String> outOfOrder = post(event(older, newsId, "revision-1", 0));
    assertThat(outOfOrder.statusCode()).isEqualTo(201);
    assertThat(outOfOrder.headers().firstValue("location"))
        .hasValue("/api/v1/news-events/" + older);
    assertThat(outOfOrder.body()).contains("\"outcome\":\"OUT_OF_ORDER\"", "\"job\":null");

    assertProblem(
        post(event(UUID.randomUUID(), newsId, "revision-3", 10)),
        409,
        "AMBIGUOUS_SOURCE_UPDATED_AT");
    assertProblem(
        post(
            event(UUID.randomUUID(), "stale-" + UUID.randomUUID(), "revision-1", 0)
                .replace("2026-01-15T14:35:30Z", "2026-01-15T14:30:30Z")),
        422,
        "STALE");
    assertProblem(
        post(event(UUID.randomUUID(), "other-" + UUID.randomUUID(), "revision-1", 0)),
        503,
        "CAPACITY_EXCEEDED");
    assertProblem(post("{\"source\":null}"), 422, "INVALID_EVENT");
    assertProblem(post("{\"event_id\":\"invalid\"}"), 400, "MALFORMED_JSON");
    assertProblem(
        post(
            event(UUID.randomUUID(), "unknown-" + UUID.randomUUID(), "revision-1", 0)
                .replaceFirst("\\{", "{\"content_hash\":\"client-value\",")),
        400,
        "UNKNOWN_FIELD");
    assertProblem(post("{"), 400, "MALFORMED_JSON");
    assertProblem(post(""), 400, "MALFORMED_JSON");
    assertProblem(post("null"), 400, "MALFORMED_JSON");
    assertProblem(postText("text/plain", "{}"), 415, "UNSUPPORTED_MEDIA_TYPE");
  }

  @Test
  void enforcesRawByteLimitWithoutRejectingTheExactBoundary() throws Exception {
    String exact = "{}" + " ".repeat(1022);
    assertThat(exact.getBytes(StandardCharsets.UTF_8)).hasSize(1024);
    HttpResponse<String> exactResponse = post(exact);
    assertThat(exactResponse.statusCode()).isNotEqualTo(413);

    assertProblem(post(exact + " "), 413, "PAYLOAD_TOO_LARGE");
    String multibyte = "{\"x\":\"" + "\u00e9".repeat(510) + "\"}";
    assertThat(multibyte.length()).isLessThan(1024);
    assertThat(multibyte.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(1024);
    assertProblem(postChunked(multibyte), 413, "PAYLOAD_TOO_LARGE");
  }

  @Test
  void mapsIdentityConflictsWithoutCreatingDurableRows() throws Exception {
    UUID storedEventId = UUID.randomUUID();
    String newsId = "http-identity-" + UUID.randomUUID();
    assertThat(post(event(storedEventId, newsId, "revision-1", 0)).statusCode()).isEqualTo(202);

    DbCounts beforeEventConflict = counts();
    assertProblem(
        post(event(storedEventId, "other-" + UUID.randomUUID(), "revision-9", 9)),
        409,
        "EVENT_ID_CONFLICT");
    assertThat(counts()).isEqualTo(beforeEventConflict);

    DbCounts beforeProviderConflict = counts();
    String conflictingFingerprint =
        event(UUID.randomUUID(), newsId, "revision-1", 0)
            .replace("Headline revision-1", "Materially different headline");
    assertProblem(post(conflictingFingerprint), 409, "PROVIDER_REVISION_CONFLICT");
    assertThat(counts()).isEqualTo(beforeProviderConflict);
  }

  @Test
  void resolvesDuplicateProviderIdentityToStoredEventWithoutWriting() throws Exception {
    String newsId = "http-duplicate-" + UUID.randomUUID();
    UUID storedEventId = UUID.randomUUID();
    JsonNode queued = json(post(event(storedEventId, newsId, "revision-1", 0)));
    UUID storedJobId = UUID.fromString(queued.path("job").path("job_id").stringValue());
    DbCounts beforeDuplicate = counts();

    UUID requestedEventId = UUID.randomUUID();
    HttpResponse<String> response = post(event(requestedEventId, newsId, "revision-1", 0));

    assertThat(response.statusCode()).isEqualTo(200);
    assertSuccessJson(response);
    assertThat(response.headers().firstValue("location")).isEmpty();
    assertThat(response.headers().firstValue("content-location")).isEmpty();
    JsonNode body = json(response);
    assertFields(
        body,
        "outcome",
        "requested_event_id",
        "stored_event_id",
        "news_item_id",
        "admission_state",
        "job");
    assertThat(body.path("outcome").stringValue()).isEqualTo("DUPLICATE");
    assertThat(body.path("requested_event_id").stringValue())
        .isEqualTo(requestedEventId.toString())
        .isNotEqualTo(body.path("stored_event_id").stringValue());
    assertThat(body.path("stored_event_id").stringValue()).isEqualTo(storedEventId.toString());
    assertThat(body.path("admission_state").stringValue()).isEqualTo("QUEUED");
    assertThat(body.path("job").path("job_id").stringValue()).isEqualTo(storedJobId.toString());
    assertThat(body.path("job").path("current_status").stringValue()).isEqualTo("PENDING");
    assertThat(counts()).isEqualTo(beforeDuplicate);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("semanticInvalidCases")
  void mapsNullSafeSemanticFailuresWithoutDatabaseWrites(
      String description, String field, Mutation mutation) throws Exception {
    ObjectNode request =
        (ObjectNode)
            jsonMapper.readTree(
                event(UUID.randomUUID(), "semantic-" + UUID.randomUUID(), "revision-1", 0));
    if (mutation == Mutation.MISSING) {
      request.remove(field);
    } else if (mutation == Mutation.NULL) {
      request.putNull(field);
    } else {
      ((ArrayNode) request.path("affected_symbols")).addNull();
    }
    DbCounts before = counts();

    assertProblem(post(jsonMapper.writeValueAsString(request)), 422, "INVALID_EVENT");
    assertThat(counts()).isEqualTo(before);
  }

  static Stream<Arguments> semanticInvalidCases() {
    Stream<Arguments> requiredFields =
        Stream.of(
                "source",
                "content",
                "correlation_id",
                "source_created_at",
                "source_updated_at",
                "received_at",
                "deadline_at",
                "headline",
                "affected_symbols")
            .flatMap(
                field ->
                    Stream.of(
                        Arguments.of(field + " missing", field, Mutation.MISSING),
                        Arguments.of(field + " null", field, Mutation.NULL)));
    return Stream.concat(
        requiredFields,
        Stream.of(
            Arguments.of(
                "affected_symbols null element", "affected_symbols", Mutation.NULL_ELEMENT)));
  }

  @ParameterizedTest(name = "binding failure: {0}")
  @MethodSource("bindingFailureBodies")
  void mapsBindingFailuresToMalformedJson(String description, String body) throws Exception {
    DbCounts before = counts();
    assertProblem(post(body), 400, "MALFORMED_JSON");
    assertThat(counts()).isEqualTo(before);
  }

  static Stream<Arguments> bindingFailureBodies() {
    return Stream.of(
        Arguments.of("invalid UUID", "{\"event_id\":\"not-a-uuid\"}"),
        Arguments.of("invalid timestamp", "{\"source_updated_at\":\"not-an-instant\"}"),
        Arguments.of("scalar for object", "{\"source\":42}"),
        Arguments.of("array for object", "{\"content\":[]}"),
        Arguments.of("object for array", "{\"affected_symbols\":{}}"),
        Arguments.of("array for scalar", "{\"headline\":[]}"),
        Arguments.of("malformed syntax", "{"),
        Arguments.of("empty body", ""),
        Arguments.of("JSON null", "null"));
  }

  @ParameterizedTest(name = "unknown field: {0}")
  @ValueSource(strings = {"root", "source", "content", "content_hash"})
  void rejectsUnknownFieldsAtEveryDtoLevel(String location) throws Exception {
    ObjectNode request =
        (ObjectNode)
            jsonMapper.readTree(
                event(UUID.randomUUID(), "unknown-" + UUID.randomUUID(), "revision-1", 0));
    switch (location) {
      case "root" -> request.put("unexpected", true);
      case "source" -> ((ObjectNode) request.path("source")).put("unexpected", true);
      case "content" -> ((ObjectNode) request.path("content")).put("unexpected", true);
      case "content_hash" -> request.put("content_hash", "client-value");
      default -> throw new IllegalArgumentException(location);
    }
    DbCounts before = counts();

    assertProblem(post(jsonMapper.writeValueAsString(request)), 400, "UNKNOWN_FIELD");
    assertThat(counts()).isEqualTo(before);
  }

  @Test
  void mapsHttpProtocolErrorsAndAcceptsJsonWithCharset() throws Exception {
    String requestBody = event(UUID.randomUUID(), "protocol-" + UUID.randomUUID(), "revision-1", 0);
    assertProblem(
        request("POST", "/api/v1/news-events", null, "application/json", requestBody),
        415,
        "UNSUPPORTED_MEDIA_TYPE");
    assertProblem(
        request("POST", "/api/v1/news-events", "text/plain", "application/json", requestBody),
        415,
        "UNSUPPORTED_MEDIA_TYPE");
    assertProblem(
        request("PUT", "/api/v1/news-events", "application/json", "application/json", requestBody),
        405,
        "METHOD_NOT_ALLOWED");
    assertProblem(
        request("GET", "/api/v1/news-events/" + UUID.randomUUID(), null, "application/xml", null),
        406,
        "NOT_ACCEPTABLE");

    HttpResponse<String> accepted =
        request(
            "POST",
            "/api/v1/news-events",
            "application/json; charset=UTF-8",
            "application/json",
            requestBody);
    assertThat(accepted.statusCode()).isEqualTo(202);
    assertSuccessJson(accepted);
  }

  @Test
  void reconstructsCompleteDurableEventAndJobRepresentations() throws Exception {
    String newsId = "read-model-" + UUID.randomUUID();
    UUID firstEventId = UUID.randomUUID();
    String firstRequest = event(firstEventId, newsId, "revision-1", 0);
    JsonNode requestedEvent = jsonMapper.readTree(firstRequest);

    HttpResponse<String> queuedResponse = post(firstRequest);
    assertThat(queuedResponse.statusCode()).isEqualTo(202);
    assertSuccessJson(queuedResponse);
    JsonNode queued = json(queuedResponse);
    assertFields(
        queued,
        "outcome",
        "requested_event_id",
        "stored_event_id",
        "news_item_id",
        "admission_state",
        "job");
    assertThat(queued.path("outcome").stringValue()).isEqualTo("QUEUED");
    assertThat(queued.path("requested_event_id").stringValue()).isEqualTo(firstEventId.toString());
    assertThat(queued.path("stored_event_id").stringValue()).isEqualTo(firstEventId.toString());
    assertThat(queued.path("admission_state").stringValue()).isEqualTo("QUEUED");
    assertFields(queued.path("job"), "job_id", "current_status");
    assertThat(queued.path("job").path("current_status").stringValue()).isEqualTo("PENDING");
    UUID firstJobId = UUID.fromString(queued.path("job").path("job_id").stringValue());
    assertThat(queuedResponse.headers().firstValue("location"))
        .hasValue("/api/v1/inference-jobs/" + firstJobId);

    UUID replacementEventId = UUID.randomUUID();
    assertThat(post(event(replacementEventId, newsId, "revision-2", 10)).statusCode())
        .isEqualTo(202);

    HttpResponse<String> eventReadResponse = get("/api/v1/news-events/" + firstEventId);
    assertThat(eventReadResponse.statusCode()).isEqualTo(200);
    assertSuccessJson(eventReadResponse);
    JsonNode eventRead = json(eventReadResponse);
    assertFields(eventRead, "event", "news_item_id", "admission_state", "persisted_at", "job");
    assertThat(eventRead.path("admission_state").stringValue()).isEqualTo("QUEUED");
    assertCanonicalEvent(eventRead.path("event"), requestedEvent, firstEventId);
    JsonNode revisionJob = eventRead.path("job");
    assertFields(
        revisionJob,
        "job_id",
        "current_status",
        "created_at",
        "superseded_at",
        "superseded_by_event_id");
    assertThat(revisionJob.path("job_id").stringValue()).isEqualTo(firstJobId.toString());
    assertThat(revisionJob.path("current_status").stringValue()).isEqualTo("SUPERSEDED");
    assertThat(revisionJob.path("created_at").stringValue()).isEqualTo(NOW.toString());
    assertThat(revisionJob.path("superseded_at").stringValue()).isEqualTo(NOW.toString());
    assertThat(revisionJob.path("superseded_by_event_id").stringValue())
        .isEqualTo(replacementEventId.toString());

    HttpResponse<String> jobReadResponse = get("/api/v1/inference-jobs/" + firstJobId);
    assertThat(jobReadResponse.statusCode()).isEqualTo(200);
    assertSuccessJson(jobReadResponse);
    JsonNode jobRead = json(jobReadResponse);
    assertFields(
        jobRead,
        "job_id",
        "news_event_id",
        "job_kind",
        "input_schema_version",
        "current_status",
        "created_at",
        "superseded_at",
        "superseded_by_event_id");
    assertThat(jobRead.path("job_id").stringValue()).isEqualTo(firstJobId.toString());
    assertThat(jobRead.path("news_event_id").stringValue()).isEqualTo(firstEventId.toString());
    assertThat(jobRead.path("job_kind").stringValue()).isEqualTo("NEWS_INFERENCE");
    assertThat(jobRead.path("input_schema_version").stringValue()).isEqualTo("1.0.0");
    assertThat(jobRead.path("current_status").stringValue()).isEqualTo("SUPERSEDED");
    assertThat(jobRead.has("idempotency_key")).isFalse();

    UUID outOfOrderEventId = UUID.randomUUID();
    assertThat(post(event(outOfOrderEventId, newsId, "revision-old", 5)).statusCode())
        .isEqualTo(201);
    HttpResponse<String> outOfOrderRead = get("/api/v1/news-events/" + outOfOrderEventId);
    assertThat(outOfOrderRead.statusCode()).isEqualTo(200);
    assertSuccessJson(outOfOrderRead);
    JsonNode outOfOrder = json(outOfOrderRead);
    assertThat(outOfOrder.path("admission_state").stringValue()).isEqualTo("OUT_OF_ORDER");
    assertThat(outOfOrder.has("job")).isTrue();
    assertThat(outOfOrder.path("job").isNull()).isTrue();
  }

  @Test
  void sanitizesDeterministicPostgresqlFailureAndRestoresDatabase() throws Exception {
    String requestBody =
        event(UUID.randomUUID(), "fault-" + UUID.randomUUID(), "revision-1", 0)
            .replace("Headline revision-1", "TOP_SECRET_REQUEST_SENTINEL");
    DbCounts before = counts();
    jdbcTemplate.execute(
        "ALTER TABLE trader.news_items RENAME TO news_items_http_failure_sentinel");
    HttpResponse<String> response = null;
    try {
      response = post(requestBody);
    } finally {
      jdbcTemplate.execute(
          "ALTER TABLE trader.news_items_http_failure_sentinel RENAME TO news_items");
    }

    assertThat(response).isNotNull();
    assertProblem(response, 503, "INFRASTRUCTURE_FAILURE");
    assertThat(response.body())
        .doesNotContainIgnoringCase("TOP_SECRET_REQUEST_SENTINEL")
        .doesNotContainIgnoringCase("news_items_http_failure_sentinel")
        .doesNotContainIgnoringCase("jdbc:postgresql")
        .doesNotContainIgnoringCase("select ")
        .doesNotContainIgnoringCase("alter table")
        .doesNotContainIgnoringCase("constraint")
        .doesNotContainIgnoringCase("trigger")
        .doesNotContain("DataAccessException")
        .doesNotContain("PSQLException");
    assertThat(counts()).isEqualTo(before);
  }

  private void assertCanonicalEvent(JsonNode actual, JsonNode requested, UUID eventId) {
    assertFields(
        actual,
        "schema_version",
        "event_id",
        "correlation_id",
        "source",
        "source_created_at",
        "source_updated_at",
        "received_at",
        "deadline_at",
        "content_hash",
        "headline",
        "content",
        "affected_symbols");
    assertThat(actual.path("schema_version").stringValue()).isEqualTo("1.0.0");
    assertThat(actual.path("event_id").stringValue()).isEqualTo(eventId.toString());
    assertThat(actual.path("correlation_id").stringValue())
        .isEqualTo(requested.path("correlation_id").stringValue());
    assertThat(actual.has("causation_id")).isFalse();
    assertThat(actual.path("source")).isEqualTo(requested.path("source"));
    assertThat(actual.path("source_created_at")).isEqualTo(requested.path("source_created_at"));
    assertThat(actual.path("source_updated_at")).isEqualTo(requested.path("source_updated_at"));
    assertThat(actual.path("received_at")).isEqualTo(requested.path("received_at"));
    assertThat(actual.path("deadline_at")).isEqualTo(requested.path("deadline_at"));
    assertThat(actual.path("headline")).isEqualTo(requested.path("headline"));
    assertFields(actual.path("content"), "language", "summary");
    assertThat(actual.path("content")).isEqualTo(requested.path("content"));
    assertThat(actual.path("content").has("body")).isFalse();
    assertThat(actual.path("affected_symbols")).isEqualTo(requested.path("affected_symbols"));
    assertThat(actual.path("content_hash").stringValue()).matches("[0-9a-f]{64}");
  }

  private HttpResponse<String> request(
      String method, String path, String contentType, String accept, String body) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5));
    if (contentType != null) {
      request.header("Content-Type", contentType);
    }
    if (accept != null) {
      request.header("Accept", accept);
    }
    return client.send(
        request
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode json(HttpResponse<String> response) throws Exception {
    return jsonMapper.readTree(response.body());
  }

  private void assertSuccessJson(HttpResponse<String> response) {
    assertThat(response.headers().firstValue("content-type"))
        .hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
    assertNoStore(response);
  }

  private static void assertFields(JsonNode object, String... names) {
    assertThat(object.isObject()).isTrue();
    assertThat(object.propertyNames()).containsExactlyInAnyOrder(names);
  }

  private DbCounts counts() {
    return new DbCounts(
        count("trader.news_items"), count("trader.news_revisions"), count("trader.inference_jobs"));
  }

  private long count(String table) {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String body) throws Exception {
    return postText("application/json", body);
  }

  private HttpResponse<String> postText(String contentType, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri("/api/v1/news-events"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> postChunked(String body) throws Exception {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    return client.send(
        HttpRequest.newBuilder(uri("/api/v1/news-events"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(bytes)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private static void assertNoStore(HttpResponse<String> response) {
    assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
  }

  private void assertProblem(HttpResponse<String> response, int status, String code)
      throws Exception {
    ProblemExpectation expected = problem(code);
    assertThat(status).isEqualTo(expected.status());
    assertThat(response.statusCode()).isEqualTo(expected.status());
    assertThat(response.headers().firstValue("content-type"))
        .hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
    assertNoStore(response);

    JsonNode body = json(response);
    assertFields(body, "type", "title", "status", "detail", "instance", "code");
    assertThat(body.path("type").stringValue())
        .isEqualTo("urn:news-swing:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    assertThat(body.path("title").stringValue()).isEqualTo(expected.title());
    assertThat(body.path("status").intValue()).isEqualTo(expected.status());
    assertThat(body.path("detail").stringValue()).isEqualTo(expected.detail());
    assertThat(body.path("instance").stringValue()).isEqualTo(response.request().uri().getPath());
    assertThat(body.path("code").stringValue()).isEqualTo(code);
    assertThat(body.toString())
        .doesNotContain("SQLException")
        .doesNotContain("DataIntegrityViolationException")
        .doesNotContain("stackTrace")
        .doesNotContain("exception")
        .doesNotContain("sql");
  }

  private static ProblemExpectation problem(String code) {
    return switch (code) {
      case "INVALID_EVENT" ->
          new ProblemExpectation(
              422, "Invalid event", "The event does not satisfy the News Event v1 requirements.");
      case "EVENT_ID_CONFLICT" ->
          new ProblemExpectation(
              409,
              "Event ID conflict",
              "The event ID is already bound to another provider revision.");
      case "PROVIDER_REVISION_CONFLICT" ->
          new ProblemExpectation(
              409,
              "Provider revision conflict",
              "The provider revision is already bound to different immutable content.");
      case "STALE" ->
          new ProblemExpectation(
              422, "Stale event", "The previously unseen event deadline has elapsed.");
      case "AMBIGUOUS_SOURCE_UPDATED_AT" ->
          new ProblemExpectation(
              409,
              "Ambiguous source timestamp",
              "A distinct revision already has the same source updated timestamp.");
      case "CAPACITY_EXCEEDED" ->
          new ProblemExpectation(
              503, "Pending capacity exceeded", "The news inference queue is at capacity.");
      case "MALFORMED_JSON" ->
          new ProblemExpectation(400, "Malformed JSON", "The JSON request cannot be read.");
      case "UNKNOWN_FIELD" ->
          new ProblemExpectation(
              400, "Unknown field", "The JSON request contains an unsupported field.");
      case "UNSUPPORTED_MEDIA_TYPE" ->
          new ProblemExpectation(
              415, "Unsupported media type", "The request must use application/json.");
      case "PAYLOAD_TOO_LARGE" ->
          new ProblemExpectation(
              413, "Payload too large", "The JSON request body exceeds the configured byte limit.");
      case "INVALID_PATH_PARAMETER" ->
          new ProblemExpectation(
              400, "Invalid path parameter", "A path parameter has an invalid value.");
      case "NEWS_EVENT_NOT_FOUND" ->
          new ProblemExpectation(
              404, "News event not found", "No persisted news event has the requested event ID.");
      case "INFERENCE_JOB_NOT_FOUND" ->
          new ProblemExpectation(
              404,
              "Inference job not found",
              "No persisted inference job has the requested job ID.");
      case "METHOD_NOT_ALLOWED" ->
          new ProblemExpectation(
              405,
              "Method not allowed",
              "The requested HTTP method is not supported for this resource.");
      case "NOT_ACCEPTABLE" ->
          new ProblemExpectation(
              406,
              "Unsupported response media type",
              "The requested response representation is unavailable.");
      case "INFRASTRUCTURE_FAILURE" ->
          new ProblemExpectation(
              503, "Infrastructure unavailable", "The service cannot access durable storage.");
      default -> throw new IllegalArgumentException(code);
    };
  }

  enum Mutation {
    MISSING,
    NULL,
    NULL_ELEMENT
  }

  record DbCounts(long newsItems, long revisions, long jobs) {}

  record ProblemExpectation(int status, String title, String detail) {}

  private static String event(UUID eventId, String newsId, String revisionId, int updateSeconds) {
    return """
        {
          "schema_version":"1.0.0",
          "event_id":"%s",
          "correlation_id":"%s",
          "source":{
            "provider":"fixture_wire",
            "news_id":"%s",
            "revision_id":"%s",
            "canonical_url":"https://news.example.test/%s"
          },
          "source_created_at":"2026-01-15T14:30:00Z",
          "source_updated_at":"2026-01-15T14:30:%02dZ",
          "received_at":"2026-01-15T14:30:20Z",
          "deadline_at":"2026-01-15T14:35:30Z",
          "headline":"Headline %s",
          "content":{"language":"en","summary":"A durable summary."},
          "affected_symbols":["ACME"]
        }
        """
        .formatted(
            eventId, UUID.randomUUID(), newsId, revisionId, newsId, updateSeconds, revisionId);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(com.newsswing.core.inference.jobs.JobStatus.class)
  void representsEveryDurableStatusWithoutOwnershipMetadata(
      com.newsswing.core.inference.jobs.JobStatus status) throws Exception {
    String newsId = "http-lifecycle-" + UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    String request = event(eventId, newsId, "revision-1", 0);
    JsonNode admission = json(post(request));
    UUID jobId = UUID.fromString(admission.path("job").path("job_id").stringValue());
    if (status == com.newsswing.core.inference.jobs.JobStatus.SUPERSEDED) {
      assertThat(post(event(UUID.randomUUID(), newsId, "revision-2", 10)).statusCode())
          .isEqualTo(202);
    } else {
      com.newsswing.support.InferenceJobFixtures.state(jdbcTemplate, jobId, status, NOW);
    }
    String before = com.newsswing.support.InferenceJobFixtures.snapshot(jdbcTemplate, jobId);
    DbCounts beforeCounts = counts();
    var jobResponse = get("/api/v1/inference-jobs/" + jobId);
    assertThat(jobResponse.statusCode()).isEqualTo(200);
    assertSuccessJson(jobResponse);
    JsonNode job = json(jobResponse);
    assertFields(
        job,
        "job_id",
        "news_event_id",
        "job_kind",
        "input_schema_version",
        "current_status",
        "created_at",
        "superseded_at",
        "superseded_by_event_id");
    assertThat(job.path("current_status").stringValue()).isEqualTo(status.name());
    var revisionResponse = get("/api/v1/news-events/" + eventId);
    assertThat(revisionResponse.statusCode()).isEqualTo(200);
    assertSuccessJson(revisionResponse);
    JsonNode revisionJob = json(revisionResponse).path("job");
    assertFields(
        revisionJob,
        "job_id",
        "current_status",
        "created_at",
        "superseded_at",
        "superseded_by_event_id");
    assertThat(revisionJob.path("current_status").stringValue()).isEqualTo(status.name());
    for (String duplicateRequest :
        java.util.List.of(
            request, request.replace(eventId.toString(), UUID.randomUUID().toString()))) {
      var response = post(duplicateRequest);
      assertThat(response.statusCode()).isEqualTo(200);
      assertSuccessJson(response);
      JsonNode duplicate = json(response);
      assertFields(duplicate.path("job"), "job_id", "current_status");
      assertThat(duplicate.path("job").path("current_status").stringValue())
          .isEqualTo(status.name());
      assertThat(duplicate.path("stored_event_id").stringValue()).isEqualTo(eventId.toString());
    }
    if (status != com.newsswing.core.inference.jobs.JobStatus.SUPERSEDED) {
      assertThat(job.path("superseded_at").isNull()).isTrue();
      assertThat(job.path("superseded_by_event_id").isNull()).isTrue();
    }
    assertThat(counts()).isEqualTo(beforeCounts);
    assertThat(com.newsswing.support.InferenceJobFixtures.snapshot(jdbcTemplate, jobId))
        .isEqualTo(before);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}

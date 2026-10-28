package com.newsswing.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

@Timeout(value = 180, threadMode = Timeout.ThreadMode.SAME_THREAD)
class NewsRestartRecoveryAcceptanceTest {
  private static final Instant FIRST_NOW = Instant.parse("2026-01-15T14:30:30Z");
  private static final Instant RESTART_NOW = Instant.parse("2026-01-15T14:32:00Z");
  private static final String FIRST_ID = "11111111-1111-4111-8111-111111111111";
  private static final String SECOND_ID = "22222222-2222-4222-8222-222222222222";
  private static final String UNRELATED_ID = "33333333-3333-4333-8333-333333333333";

  @Test
  void recoversDurableResourcesAndDuplicatePrecedenceAcrossTwoFullyClosedContexts()
      throws Exception {
    try (var postgres = HttpAcceptanceSupport.postgres("restart_acceptance")) {
      postgres.start();
      String containerId = postgres.getContainerId();
      String firstRequest =
          event(
              FIRST_ID,
              "restart-news",
              "revision-1",
              "2026-01-15T14:30:00Z",
              "2026-01-15T14:30:20Z",
              "2026-01-15T14:31:00Z");
      String secondRequest =
          event(
              SECOND_ID,
              "restart-news",
              "revision-2",
              "2026-01-15T14:30:10Z",
              "2026-01-15T14:30:20Z",
              "2026-01-15T14:35:00Z");
      List<List<String>> durableBefore;
      Map<String, JsonNode> resourcesBefore;
      String itemId;
      String firstJobId;
      String secondJobId;
      var firstApp = HttpAcceptanceSupport.start(postgres, FIRST_NOW);
      try (firstApp) {
        var firstAdmission = firstApp.post("/api/v1/news-events", firstRequest);
        assertThat(firstAdmission.statusCode()).isEqualTo(202);
        var first = firstApp.json(firstAdmission);
        itemId = first.path("news_item_id").stringValue();
        firstJobId = first.path("job").path("job_id").stringValue();
        assertAdmission(first, "QUEUED", FIRST_ID, itemId, firstJobId, "PENDING");
        assertThat(firstAdmission.headers().firstValue("location"))
            .hasValue("/api/v1/inference-jobs/" + firstJobId);

        var secondAdmission = firstApp.post("/api/v1/news-events", secondRequest);
        assertThat(secondAdmission.statusCode()).isEqualTo(202);
        var second = firstApp.json(secondAdmission);
        secondJobId = second.path("job").path("job_id").stringValue();
        assertThat(secondJobId).isNotEqualTo(firstJobId);
        assertAdmission(second, "QUEUED", SECOND_ID, itemId, secondJobId, "PENDING");
        assertThat(secondAdmission.headers().firstValue("location"))
            .hasValue("/api/v1/inference-jobs/" + secondJobId);

        resourcesBefore = resources(firstApp, firstJobId, secondJobId);
        assertRevision(
            resourcesBefore.get("/api/v1/news-events/" + FIRST_ID),
            FIRST_ID,
            itemId,
            firstJobId,
            "SUPERSEDED",
            SECOND_ID);
        assertRevision(
            resourcesBefore.get("/api/v1/news-events/" + SECOND_ID),
            SECOND_ID,
            itemId,
            secondJobId,
            "PENDING",
            null);
        assertJob(
            resourcesBefore.get("/api/v1/inference-jobs/" + firstJobId),
            FIRST_ID,
            firstJobId,
            "SUPERSEDED",
            SECOND_ID);
        assertJob(
            resourcesBefore.get("/api/v1/inference-jobs/" + secondJobId),
            SECOND_ID,
            secondJobId,
            "PENDING",
            null);
        durableBefore = HttpAcceptanceSupport.snapshot(postgres);
        assertDurableState(firstApp, durableBefore, itemId);
      }
      assertThat(firstApp.context.isActive()).isFalse();
      assertThat(firstApp.datasource.isClosed()).isTrue();
      assertThat(postgres.isRunning()).isTrue();
      assertThat(postgres.getContainerId()).isEqualTo(containerId);

      var secondApp = HttpAcceptanceSupport.start(postgres, RESTART_NOW);
      try (secondApp) {
        assertThat(secondApp.context).isNotSameAs(firstApp.context);
        assertThat(secondApp.datasource).isNotSameAs(firstApp.datasource);
        assertThat(secondApp.context.getBean(java.time.Clock.class).instant())
            .isEqualTo(RESTART_NOW);
        assertThat(
                Instant.parse(
                    secondApp.mapper.readTree(firstRequest).path("deadline_at").stringValue()))
            .isBefore(RESTART_NOW);
        assertThat(HttpAcceptanceSupport.snapshot(postgres)).isEqualTo(durableBefore);
        assertThat(resources(secondApp, firstJobId, secondJobId)).isEqualTo(resourcesBefore);

        // No retries: these assertions must succeed on the first request after restart.
        var oldDuplicate = secondApp.post("/api/v1/news-events", firstRequest);
        assertThat(oldDuplicate.statusCode()).isEqualTo(200);
        assertAdmission(
            secondApp.json(oldDuplicate), "DUPLICATE", FIRST_ID, itemId, firstJobId, "SUPERSEDED");
        assertUnchanged(
            secondApp, postgres, durableBefore, resourcesBefore, firstJobId, secondJobId);

        var currentDuplicate = secondApp.post("/api/v1/news-events", secondRequest);
        assertThat(currentDuplicate.statusCode()).isEqualTo(200);
        assertAdmission(
            secondApp.json(currentDuplicate),
            "DUPLICATE",
            SECOND_ID,
            itemId,
            secondJobId,
            "PENDING");
        assertUnchanged(
            secondApp, postgres, durableBefore, resourcesBefore, firstJobId, secondJobId);

        var rejected =
            secondApp.post(
                "/api/v1/news-events",
                event(
                    UNRELATED_ID,
                    "unrelated-news",
                    "revision-1",
                    "2026-01-15T14:32:00Z",
                    "2026-01-15T14:32:00Z",
                    "2026-01-15T14:35:00Z"));
        assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(rejected.headers().firstValue("content-type"))
            .hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(rejected.headers().firstValue("cache-control")).hasValue("no-store");
        var problem = secondApp.json(rejected);
        assertThat(problem.propertyNames())
            .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code");
        assertThat(problem.path("type").stringValue())
            .isEqualTo("urn:news-swing:problem:capacity-exceeded");
        assertThat(problem.path("title").stringValue()).isEqualTo("Pending capacity exceeded");
        assertThat(problem.path("status").intValue()).isEqualTo(503);
        assertThat(problem.path("detail").stringValue())
            .isEqualTo("The news inference queue is at capacity.");
        assertThat(problem.path("instance").stringValue()).isEqualTo("/api/v1/news-events");
        assertThat(problem.path("code").stringValue()).isEqualTo("CAPACITY_EXCEEDED");
        assertUnchanged(
            secondApp, postgres, durableBefore, resourcesBefore, firstJobId, secondJobId);
        assertThat(secondApp.get("/api/v1/news-events/" + UNRELATED_ID).statusCode())
            .isEqualTo(404);
        assertDurableState(secondApp, HttpAcceptanceSupport.snapshot(postgres), itemId);
      }
      assertThat(secondApp.context.isActive()).isFalse();
      assertThat(secondApp.datasource.isClosed()).isTrue();
      assertThat(postgres.getContainerId()).isEqualTo(containerId);
    }
  }

  private static void assertUnchanged(
      HttpAcceptanceSupport.RunningApplication app,
      org.testcontainers.containers.PostgreSQLContainer<?> postgres,
      List<List<String>> expectedRows,
      Map<String, JsonNode> expectedResources,
      String firstJobId,
      String secondJobId)
      throws Exception {
    assertThat(HttpAcceptanceSupport.snapshot(postgres)).isEqualTo(expectedRows);
    assertThat(resources(app, firstJobId, secondJobId)).isEqualTo(expectedResources);
  }

  private static Map<String, JsonNode> resources(
      HttpAcceptanceSupport.RunningApplication app, String firstJobId, String secondJobId)
      throws Exception {
    var resources = new LinkedHashMap<String, JsonNode>();
    for (String path :
        List.of(
            "/api/v1/news-events/" + FIRST_ID,
            "/api/v1/news-events/" + SECOND_ID,
            "/api/v1/inference-jobs/" + firstJobId,
            "/api/v1/inference-jobs/" + secondJobId)) {
      HttpResponse<String> response = app.get(path);
      assertThat(response.statusCode()).as(path).isEqualTo(200);
      assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
      resources.put(path, app.json(response));
    }
    return resources;
  }

  private static void assertAdmission(
      JsonNode admission,
      String outcome,
      String eventId,
      String itemId,
      String jobId,
      String status) {
    assertThat(admission.path("outcome").stringValue()).isEqualTo(outcome);
    assertThat(admission.path("requested_event_id").stringValue()).isEqualTo(eventId);
    assertThat(admission.path("stored_event_id").stringValue()).isEqualTo(eventId);
    assertThat(admission.path("news_item_id").stringValue()).isEqualTo(itemId);
    assertThat(admission.path("admission_state").stringValue()).isEqualTo("QUEUED");
    assertThat(admission.path("job").path("job_id").stringValue()).isEqualTo(jobId);
    assertThat(admission.path("job").path("current_status").stringValue()).isEqualTo(status);
  }

  private static void assertRevision(
      JsonNode revision,
      String eventId,
      String itemId,
      String jobId,
      String status,
      String supersededBy) {
    assertThat(revision.path("event").path("event_id").stringValue()).isEqualTo(eventId);
    assertThat(revision.path("event").path("content_hash").stringValue()).matches("[0-9a-f]{64}");
    assertThat(revision.path("news_item_id").stringValue()).isEqualTo(itemId);
    assertThat(revision.path("admission_state").stringValue()).isEqualTo("QUEUED");
    assertThat(revision.path("persisted_at").stringValue()).isEqualTo(FIRST_NOW.toString());
    assertJobState(revision.path("job"), jobId, status, supersededBy);
  }

  private static void assertJob(
      JsonNode job, String eventId, String jobId, String status, String supersededBy) {
    assertThat(job.path("news_event_id").stringValue()).isEqualTo(eventId);
    assertThat(job.path("job_kind").stringValue()).isEqualTo("NEWS_INFERENCE");
    assertThat(job.path("input_schema_version").stringValue()).isEqualTo("1.0.0");
    assertJobState(job, jobId, status, supersededBy);
  }

  private static void assertJobState(
      JsonNode job, String jobId, String status, String supersededBy) {
    assertThat(job.path("job_id").stringValue()).isEqualTo(jobId);
    assertThat(job.path("current_status").stringValue()).isEqualTo(status);
    assertThat(job.path("created_at").stringValue()).isEqualTo(FIRST_NOW.toString());
    if (supersededBy == null) {
      assertThat(job.path("superseded_at").isNull()).isTrue();
      assertThat(job.path("superseded_by_event_id").isNull()).isTrue();
    } else {
      assertThat(job.path("superseded_at").stringValue()).isEqualTo(FIRST_NOW.toString());
      assertThat(job.path("superseded_by_event_id").stringValue()).isEqualTo(supersededBy);
    }
  }

  private static void assertDurableState(
      HttpAcceptanceSupport.RunningApplication app, List<List<String>> rows, String itemId) {
    assertThat(rows.get(0)).hasSize(1);
    assertThat(rows.get(1)).hasSize(2);
    assertThat(rows.get(2)).hasSize(2);
    assertThat(rows.get(3)).hasSize(1);
    JsonNode item = app.mapper.readTree(rows.get(0).getFirst());
    assertThat(item.path("id").stringValue()).isEqualTo(itemId);
    assertThat(item.path("provider").stringValue()).isEqualTo("fixture_wire");
    assertThat(item.path("provider_news_id").stringValue()).isEqualTo("restart-news");
    assertThat(item.path("latest_event_id").stringValue()).isEqualTo(SECOND_ID);
    assertThat(
            rows.get(2).stream()
                .map(app.mapper::readTree)
                .filter(job -> job.path("status").stringValue().equals("PENDING")))
        .hasSize(1);
    assertThat(rows.get(4)).hasSize(1);
    assertThat(app.mapper.readTree(rows.get(4).getFirst()).path("metadata_value").stringValue())
        .isEqualTo("2A");
    assertThat(
            rows.get(5).stream()
                .map(app.mapper::readTree)
                .filter(row -> !row.path("version").isNull())
                .map(row -> row.path("version").stringValue()))
        .containsExactly("1", "2");
  }

  private static String event(
      String id, String newsId, String revision, String updated, String received, String deadline) {
    return """
        {
          "schema_version":"1.0.0",
          "event_id":"%s",
          "correlation_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
          "source":{"provider":"fixture_wire","news_id":"%s","revision_id":"%s",
                    "canonical_url":"https://news.example.test/%s"},
          "source_created_at":"2026-01-15T14:30:00Z",
          "source_updated_at":"%s",
          "received_at":"%s",
          "deadline_at":"%s",
          "headline":"Headline %s",
          "content":{"language":"en","summary":"A durable summary."},
          "affected_symbols":["ACME"]
        }
        """
        .formatted(id, newsId, revision, newsId, updated, received, deadline, revision);
  }
}

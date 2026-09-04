package com.newsswing.core.news.api;

import com.newsswing.core.news.ingestion.NewsIngestionCoordinator;
import com.newsswing.core.news.ingestion.NewsIngestionResult;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class NewsApiController {

  private final NewsIngestionCoordinator coordinator;
  private final NewsStatusQueryService queryService;

  NewsApiController(NewsIngestionCoordinator coordinator, NewsStatusQueryService queryService) {
    this.coordinator = coordinator;
    this.queryService = queryService;
  }

  @PostMapping(path = "/news-events", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<NewsApiModels.AdmissionResponse> ingest(
      @RequestBody(required = false) NewsApiModels.NewsEventRequest request) {
    if (request == null) {
      throw new NewsApiMalformedJsonException();
    }
    NewsIngestionResult result = coordinator.ingest(request.toCommand());
    return switch (result) {
      case NewsIngestionResult.Queued queued ->
          ResponseEntity.accepted()
              .location(URI.create("/api/v1/inference-jobs/" + queued.jobId()))
              .cacheControl(CacheControl.noStore())
              .body(
                  new NewsApiModels.AdmissionResponse(
                      "QUEUED",
                      queued.eventId(),
                      queued.eventId(),
                      queued.newsItemId(),
                      "QUEUED",
                      new NewsApiModels.AdmissionJobResponse(queued.jobId(), "PENDING")));
      case NewsIngestionResult.OutOfOrder outOfOrder ->
          ResponseEntity.created(URI.create("/api/v1/news-events/" + outOfOrder.eventId()))
              .cacheControl(CacheControl.noStore())
              .body(
                  new NewsApiModels.AdmissionResponse(
                      "OUT_OF_ORDER",
                      outOfOrder.eventId(),
                      outOfOrder.eventId(),
                      outOfOrder.newsItemId(),
                      "OUT_OF_ORDER",
                      null));
      case NewsIngestionResult.Duplicate duplicate ->
          ResponseEntity.ok()
              .cacheControl(CacheControl.noStore())
              .body(
                  new NewsApiModels.AdmissionResponse(
                      "DUPLICATE",
                      duplicate.requestedEventId(),
                      duplicate.eventId(),
                      duplicate.newsItemId(),
                      duplicate.storedAdmissionState().name(),
                      duplicate
                          .currentJob()
                          .map(
                              job ->
                                  new NewsApiModels.AdmissionJobResponse(
                                      job.jobId(), job.status().name()))
                          .orElse(null)));
    };
  }

  @GetMapping("/news-events/{eventId}")
  ResponseEntity<NewsApiModels.NewsRevisionResponse> getRevision(@PathVariable UUID eventId) {
    var response =
        queryService
            .findRevision(eventId)
            .orElseThrow(
                () ->
                    new NewsApiNotFoundException(
                        "NEWS_EVENT_NOT_FOUND",
                        "News event not found",
                        "No persisted news event has the requested event ID."));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
  }

  @GetMapping("/inference-jobs/{jobId}")
  ResponseEntity<NewsApiModels.InferenceJobResponse> getJob(@PathVariable UUID jobId) {
    var response =
        queryService
            .findJob(jobId)
            .orElseThrow(
                () ->
                    new NewsApiNotFoundException(
                        "INFERENCE_JOB_NOT_FOUND",
                        "Inference job not found",
                        "No persisted inference job has the requested job ID."));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
  }
}

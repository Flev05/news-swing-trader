package com.newsswing.core.news.api;

import static com.newsswing.core.news.domain.NewsEvents.Content;
import static com.newsswing.core.news.domain.NewsEvents.ProviderRevisionKey;
import static com.newsswing.core.news.domain.NewsEvents.UnhashedNewsEvent;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Stable JSON request and response models for the local News Event v1 HTTP API. */
public final class NewsApiModels {

  private NewsApiModels() {}

  public record NewsEventRequest(
      String schemaVersion,
      UUID eventId,
      UUID correlationId,
      UUID causationId,
      SourceRequest source,
      Instant sourceCreatedAt,
      Instant sourceUpdatedAt,
      Instant receivedAt,
      Instant deadlineAt,
      String headline,
      ContentRequest content,
      List<String> affectedSymbols) {

    UnhashedNewsEvent toCommand() {
      ProviderRevisionKey sourceKey =
          source == null
              ? null
              : new ProviderRevisionKey(source.provider(), source.newsId(), source.revisionId());
      String canonicalUrl = source == null ? null : source.canonicalUrl();
      Content eventContent =
          content == null
              ? null
              : new Content(content.language(), content.summary(), content.body());
      return new UnhashedNewsEvent(
          schemaVersion,
          eventId,
          correlationId,
          causationId,
          sourceKey,
          canonicalUrl,
          sourceCreatedAt,
          sourceUpdatedAt,
          receivedAt,
          deadlineAt,
          headline,
          eventContent,
          affectedSymbols);
    }
  }

  public record SourceRequest(
      String provider, String newsId, String revisionId, String canonicalUrl) {}

  public record ContentRequest(String language, String summary, String body) {}

  public record AdmissionResponse(
      String outcome,
      UUID requestedEventId,
      UUID storedEventId,
      UUID newsItemId,
      String admissionState,
      AdmissionJobResponse job) {}

  public record AdmissionJobResponse(UUID jobId, String currentStatus) {}

  public record NewsRevisionResponse(
      CanonicalNewsEventResponse event,
      UUID newsItemId,
      String admissionState,
      Instant persistedAt,
      RevisionJobResponse job) {}

  public record RevisionJobResponse(
      UUID jobId,
      String currentStatus,
      Instant createdAt,
      Instant supersededAt,
      UUID supersededByEventId) {}

  public record InferenceJobResponse(
      UUID jobId,
      UUID newsEventId,
      String jobKind,
      String inputSchemaVersion,
      String currentStatus,
      Instant createdAt,
      Instant supersededAt,
      UUID supersededByEventId) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CanonicalNewsEventResponse(
      String schemaVersion,
      UUID eventId,
      UUID correlationId,
      UUID causationId,
      CanonicalSourceResponse source,
      Instant sourceCreatedAt,
      Instant sourceUpdatedAt,
      Instant receivedAt,
      Instant deadlineAt,
      String contentHash,
      String headline,
      CanonicalContentResponse content,
      List<String> affectedSymbols) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CanonicalSourceResponse(
      String provider, String newsId, String revisionId, String canonicalUrl) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CanonicalContentResponse(String language, String summary, String body) {}
}

package com.newsswing.core.news.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Internal value types for the durable News Event v1 persistence boundary. */
public final class NewsEvents {

  private NewsEvents() {}

  public record ProviderRevisionKey(String provider, String newsId, String revisionId) {}

  public record Content(String language, String summary, String body) {}

  /** A News Event v1 payload before the server supplies its read-only content hash. */
  public record UnhashedNewsEvent(
      String schemaVersion,
      UUID eventId,
      UUID correlationId,
      UUID causationId,
      ProviderRevisionKey source,
      String canonicalUrl,
      Instant sourceCreatedAt,
      Instant sourceUpdatedAt,
      Instant receivedAt,
      Instant deadlineAt,
      String headline,
      Content content,
      List<String> affectedSymbols) {}

  /** Canonical, immutable state that is ready for persistence. */
  public record CanonicalNewsEvent(
      String schemaVersion,
      UUID eventId,
      UUID correlationId,
      UUID causationId,
      ProviderRevisionKey source,
      String canonicalUrl,
      Instant sourceCreatedAt,
      Instant sourceUpdatedAt,
      Instant receivedAt,
      Instant deadlineAt,
      String headline,
      Content content,
      List<String> affectedSymbols,
      String contentHash,
      String providerFingerprint,
      String jobIdempotencyKey,
      String payloadJson) {}
}

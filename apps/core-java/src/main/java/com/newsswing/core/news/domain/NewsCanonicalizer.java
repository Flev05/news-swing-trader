package com.newsswing.core.news.domain;

import static com.newsswing.core.news.domain.NewsEvents.CanonicalNewsEvent;
import static com.newsswing.core.news.domain.NewsEvents.Content;
import static com.newsswing.core.news.domain.NewsEvents.UnhashedNewsEvent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Canonicalizes source content and computes durable News Event v1 hashes. */
public final class NewsCanonicalizer {

  private static final String SCHEMA_VERSION = "1.0.0";
  private static final byte[] CONTENT_PREFIX =
      "news-content-v1\0".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] FINGERPRINT_PREFIX =
      "provider-revision-v1\0".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] JOB_PREFIX =
      "news-inference-v1\0".getBytes(StandardCharsets.US_ASCII);
  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

  public CanonicalNewsEvent canonicalize(UnhashedNewsEvent input) {
    require(input != null, "event is required");
    require(SCHEMA_VERSION.equals(input.schemaVersion()), "schema_version must be 1.0.0");
    require(input.eventId() != null, "event_id is required");
    require(input.correlationId() != null, "correlation_id is required");
    validateSource(input);
    validateUrl(input.canonicalUrl());
    validateTimestamps(input);

    String headline = canonicalText(input.headline(), "headline", 500);
    Content content = canonicalContent(input.content());
    List<String> symbols = canonicalSymbols(input.affectedSymbols());
    String contentHash = contentHash(headline, content, symbols);
    String providerFingerprint = providerFingerprint(input, contentHash);
    String jobIdempotencyKey = jobIdempotencyKey(input.eventId(), contentHash);

    return new CanonicalNewsEvent(
        SCHEMA_VERSION,
        input.eventId(),
        input.correlationId(),
        input.causationId(),
        input.source(),
        input.canonicalUrl(),
        input.sourceCreatedAt(),
        input.sourceUpdatedAt(),
        input.receivedAt(),
        input.deadlineAt(),
        headline,
        content,
        symbols,
        contentHash,
        providerFingerprint,
        jobIdempotencyKey,
        payloadJson(input, headline, content, symbols, contentHash));
  }

  private static void validateSource(UnhashedNewsEvent input) {
    require(input.source() != null, "source is required");
    requireMatches(input.source().provider(), "provider", "[a-z][a-z0-9_-]+", 2, 32);
    requireNonblank(input.source().newsId(), "news_id", 256);
    requireNonblank(input.source().revisionId(), "revision_id", 256);
  }

  private static void validateUrl(String value) {
    require(value != null && value.length() <= 2048, "canonical_url has an invalid length");
    try {
      URI uri = URI.create(value);
      require(
          value.startsWith("https://") && "https".equals(uri.getScheme()) && uri.getHost() != null,
          "canonical_url must be an absolute https URL");
    } catch (IllegalArgumentException exception) {
      throw new NewsCanonicalizationException("canonical_url must be a URI");
    }
  }

  private static void validateTimestamps(UnhashedNewsEvent input) {
    require(input.sourceCreatedAt() != null, "source_created_at is required");
    require(input.sourceUpdatedAt() != null, "source_updated_at is required");
    require(input.receivedAt() != null, "received_at is required");
    require(input.deadlineAt() != null, "deadline_at is required");
    require(
        !input.sourceCreatedAt().isAfter(input.sourceUpdatedAt()),
        "source_created_at must not be after source_updated_at");
    require(
        input.receivedAt().isBefore(input.deadlineAt()), "received_at must be before deadline_at");
  }

  private static Content canonicalContent(Content content) {
    require(content != null, "content is required");
    requireMatches(content.language(), "language", "[a-z]{2}(?:-[A-Z]{2})?", 2, 5);
    String summary = optionalCanonicalText(content.summary(), "summary", 4000);
    String body = optionalCanonicalText(content.body(), "body", 50000);
    require(summary != null || body != null, "content requires summary or body");
    return new Content(content.language(), summary, body);
  }

  private static List<String> canonicalSymbols(List<String> values) {
    require(
        values != null && !values.isEmpty() && values.size() <= 50,
        "affected_symbols must contain 1 to 50 symbols");
    Set<String> unique = new HashSet<>();
    List<String> symbols = new ArrayList<>();
    for (String value : values) {
      requireMatches(value, "affected_symbol", "[A-Z][A-Z0-9]{0,4}(?:[.-][A-Z])?", 1, 7);
      require(unique.add(value), "affected_symbols must be unique");
      symbols.add(value);
    }
    symbols.sort(Comparator.naturalOrder());
    return List.copyOf(symbols);
  }

  private static String canonicalText(String value, String field, int maximumLength) {
    require(value != null, field + " is required");
    String canonical =
        Normalizer.normalize(value.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC)
            .strip();
    require(!canonical.isEmpty(), field + " must not be blank");
    require(canonical.length() <= maximumLength, field + " exceeds maximum length");
    return canonical;
  }

  private static String optionalCanonicalText(String value, String field, int maximumLength) {
    return value == null ? null : canonicalText(value, field, maximumLength);
  }

  private static String contentHash(String headline, Content content, List<String> symbols) {
    return sha256(
        framed(
            CONTENT_PREFIX,
            java.util.Arrays.asList(
                headline, content.language(), content.summary(), content.body()),
            symbols));
  }

  private static String providerFingerprint(UnhashedNewsEvent input, String contentHash) {
    return sha256(
        framed(
            FINGERPRINT_PREFIX,
            List.of(
                input.source().provider(),
                input.source().newsId(),
                input.source().revisionId(),
                input.canonicalUrl(),
                input.sourceCreatedAt().toString(),
                input.sourceUpdatedAt().toString(),
                contentHash),
            List.of()));
  }

  private static String jobIdempotencyKey(UUID eventId, String contentHash) {
    return sha256(
        concatenate(
            JOB_PREFIX,
            eventId.toString().getBytes(StandardCharsets.US_ASCII),
            new byte[] {0},
            contentHash.getBytes(StandardCharsets.US_ASCII)));
  }

  private static byte[] framed(byte[] prefix, List<String> fields, List<String> symbols) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream output = new DataOutputStream(bytes);
      output.write(prefix);
      for (String field : fields) {
        writeOptionalField(output, field);
      }
      output.writeInt(symbols.size());
      for (String symbol : symbols) {
        writeOptionalField(output, symbol);
      }
      output.flush();
      return bytes.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to frame canonical news bytes", exception);
    }
  }

  private static void writeOptionalField(DataOutputStream output, String value) throws IOException {
    if (value == null) {
      output.writeInt(-1);
      return;
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static String payloadJson(
      UnhashedNewsEvent input,
      String headline,
      Content content,
      List<String> symbols,
      String contentHash) {
    ObjectNode event = JSON_MAPPER.createObjectNode();
    event.put("schema_version", SCHEMA_VERSION);
    event.put("event_id", input.eventId().toString());
    event.put("correlation_id", input.correlationId().toString());
    if (input.causationId() != null) {
      event.put("causation_id", input.causationId().toString());
    }
    ObjectNode source = event.putObject("source");
    source.put("provider", input.source().provider());
    source.put("news_id", input.source().newsId());
    source.put("revision_id", input.source().revisionId());
    source.put("canonical_url", input.canonicalUrl());
    event.put("source_created_at", input.sourceCreatedAt().toString());
    event.put("source_updated_at", input.sourceUpdatedAt().toString());
    event.put("received_at", input.receivedAt().toString());
    event.put("deadline_at", input.deadlineAt().toString());
    event.put("content_hash", contentHash);
    event.put("headline", headline);
    ObjectNode contentNode = event.putObject("content");
    contentNode.put("language", content.language());
    if (content.summary() != null) {
      contentNode.put("summary", content.summary());
    }
    if (content.body() != null) {
      contentNode.put("body", content.body());
    }
    ArrayNode symbolNodes = event.putArray("affected_symbols");
    symbols.forEach(symbolNodes::add);
    try {
      return JSON_MAPPER.writeValueAsString(event);
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to serialize canonical news event", exception);
    }
  }

  private static byte[] concatenate(byte[]... pieces) {
    int length = 0;
    for (byte[] piece : pieces) {
      length += piece.length;
    }
    byte[] result = new byte[length];
    int offset = 0;
    for (byte[] piece : pieces) {
      System.arraycopy(piece, 0, result, offset, piece.length);
      offset += piece.length;
    }
    return result;
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void requireNonblank(String value, String field, int maximumLength) {
    require(
        value != null && value.length() <= maximumLength && !value.strip().isEmpty(),
        field + " is invalid");
  }

  private static void requireMatches(
      String value, String field, String regex, int minimumLength, int maximumLength) {
    require(
        value != null
            && value.length() >= minimumLength
            && value.length() <= maximumLength
            && value.matches(regex),
        field + " is invalid");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new NewsCanonicalizationException(message);
    }
  }
}

package com.newsswing.core.news.domain;

import static com.newsswing.core.news.domain.NewsEvents.Content;
import static com.newsswing.core.news.domain.NewsEvents.ProviderRevisionKey;
import static com.newsswing.core.news.domain.NewsEvents.UnhashedNewsEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class NewsCanonicalizerTest {

  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
  private static final NewsCanonicalizer CANONICALIZER = new NewsCanonicalizer();

  @ParameterizedTest(name = "{0}")
  @MethodSource("vectors")
  void canonicalizesGoldenVectors(String name, JsonNode vector) {
    var event = CANONICALIZER.canonicalize(toEvent(vector));

    assertThat(event.headline()).isEqualTo(vector.get("expected_headline").asString());
    assertThat(event.content().summary()).isEqualTo(nullableText(vector, "expected_summary"));
    assertThat(event.content().body()).isEqualTo(nullableText(vector, "expected_body"));
    assertThat(event.affectedSymbols())
        .containsExactlyElementsOf(
            vector.get("expected_symbols").valueStream().map(JsonNode::asString).toList());
    assertThat(event.contentHash()).isEqualTo(vector.get("expected_content_hash").asString());
    assertThat(event.payloadJson()).contains("\"content_hash\":\"" + event.contentHash() + "\"");
  }

  @Test
  void rejectsHttpsUrisThatDoNotMeetTheV1PrefixRequirement() {
    assertThatThrownBy(
            () ->
                CANONICALIZER.canonicalize(
                    new UnhashedNewsEvent(
                        "1.0.0",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        new ProviderRevisionKey("fixture_wire", "opaque-url", "revision-1"),
                        "https:opaque",
                        Instant.parse("2026-01-15T14:30:00Z"),
                        Instant.parse("2026-01-15T14:30:00Z"),
                        Instant.parse("2026-01-15T14:30:30Z"),
                        Instant.parse("2026-01-15T14:35:30Z"),
                        "Opaque URL",
                        new Content("en", "Summary", null),
                        java.util.List.of("ACME"))))
        .isInstanceOf(NewsCanonicalizationException.class);
  }

  static Stream<Arguments> vectors() throws IOException {
    JsonNode vectors =
        JSON_MAPPER
            .readTree(
                Files.readString(
                    repositoryRoot().resolve("tests/fixtures/ingestion/v1/canonicalization.json")))
            .get("vectors");
    return vectors.valueStream().map(vector -> Arguments.of(vector.get("name").asString(), vector));
  }

  private static UnhashedNewsEvent toEvent(JsonNode vector) {
    return new UnhashedNewsEvent(
        "1.0.0",
        UUID.fromString(vector.get("event_id").asString()),
        UUID.fromString(vector.get("correlation_id").asString()),
        null,
        new ProviderRevisionKey(
            vector.get("provider").asString(),
            vector.get("news_id").asString(),
            vector.get("revision_id").asString()),
        vector.get("canonical_url").asString(),
        Instant.parse(vector.get("source_created_at").asString()),
        Instant.parse(vector.get("source_updated_at").asString()),
        Instant.parse(vector.get("received_at").asString()),
        Instant.parse(vector.get("deadline_at").asString()),
        vector.get("headline").asString(),
        new Content(
            vector.get("language").asString(),
            nullableText(vector, "summary"),
            nullableText(vector, "body")),
        vector.get("affected_symbols").valueStream().map(JsonNode::asString).toList());
  }

  private static String nullableText(JsonNode object, String field) {
    JsonNode value = object.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.isDirectory(candidate.resolve("contracts"))
          && Files.isDirectory(candidate.resolve("apps"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Could not locate repository root");
  }
}

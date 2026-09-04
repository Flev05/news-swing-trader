package com.newsswing.core.news.ingestion;

import static com.newsswing.core.news.domain.NewsEvents.Content;
import static com.newsswing.core.news.domain.NewsEvents.ProviderRevisionKey;
import static com.newsswing.core.news.domain.NewsEvents.UnhashedNewsEvent;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newsswing.core.news.domain.NewsCanonicalizer;
import com.newsswing.core.news.domain.NewsEvents.CanonicalNewsEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class NewsPersistenceInvariantTest {

  private static final Instant CREATED = Instant.parse("2026-01-15T14:30:00Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6");

  @DynamicPropertySource
  static void configureFlywaySchema(DynamicPropertyRegistry registry) {
    registry.add("spring.flyway.default-schema", () -> "trader");
    registry.add("spring.flyway.schemas", () -> "trader");
  }

  @Autowired private NewsIngestionCoordinator coordinator;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final NewsCanonicalizer canonicalizer = new NewsCanonicalizer();

  @Test
  void databaseRejectsImmutableRevisionMutation() {
    var event = event("immutable-" + UUID.randomUUID(), "revision-1", "Immutable");
    coordinator.ingest(event);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE trader.news_revisions SET content_hash = ? WHERE event_id = ?",
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    event.eventId()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void databaseRejectsRelationalJsonMismatchAndMissingRequiredIdentityField() {
    String newsId = "json-consistency-" + UUID.randomUUID();
    NewsIngestionResult.Queued queued =
        (NewsIngestionResult.Queued)
            coordinator.ingest(event(newsId, "revision-1", "Relational identity"));
    CanonicalNewsEvent inconsistent =
        canonicalizer.canonicalize(event(newsId, "revision-2", "Mismatched payload"));

    String mismatchedPayload =
        inconsistent
            .payloadJson()
            .replace("\"provider\":\"fixture_wire\"", "\"provider\":\"other_wire\"");
    assertThatThrownBy(() -> insertRevision(queued.newsItemId(), inconsistent, mismatchedPayload))
        .isInstanceOf(DataAccessException.class);

    List<String> missingRequiredIdentityFields =
        List.of(
            inconsistent.payloadJson().replace("\"schema_version\":\"1.0.0\",", ""),
            inconsistent
                .payloadJson()
                .replace("\"event_id\":\"" + inconsistent.eventId() + "\",", ""),
            inconsistent.payloadJson().replace("\"provider\":\"fixture_wire\",", ""));
    for (String missingIdentityField : missingRequiredIdentityFields) {
      assertThatThrownBy(
              () -> insertRevision(queued.newsItemId(), inconsistent, missingIdentityField))
          .isInstanceOf(DataAccessException.class);
    }
  }

  private void insertRevision(UUID itemId, CanonicalNewsEvent event, String payloadJson) {
    jdbcTemplate.update(
        """
        INSERT INTO trader.news_revisions (
            event_id, news_item_id, provider, provider_news_id, provider_revision_id,
            canonical_url, source_created_at, source_updated_at, received_at, deadline_at,
            content_hash, provider_fingerprint, event_payload, admission_state
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
        """,
        event.eventId(),
        itemId,
        event.source().provider(),
        event.source().newsId(),
        event.source().revisionId(),
        event.canonicalUrl(),
        Timestamp.from(event.sourceCreatedAt()),
        Timestamp.from(event.sourceUpdatedAt()),
        Timestamp.from(event.receivedAt()),
        Timestamp.from(event.deadlineAt()),
        event.contentHash(),
        event.providerFingerprint(),
        payloadJson,
        "OUT_OF_ORDER");
  }

  private UnhashedNewsEvent event(String newsId, String revisionId, String headline) {
    return new UnhashedNewsEvent(
        "1.0.0",
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        new ProviderRevisionKey("fixture_wire", newsId, revisionId),
        "https://news.example.test/" + newsId,
        CREATED,
        CREATED,
        CREATED.plusSeconds(30),
        Instant.parse("2099-01-15T14:35:00Z"),
        headline,
        new Content("en", "A durable summary.", null),
        List.of("ACME"));
  }
}

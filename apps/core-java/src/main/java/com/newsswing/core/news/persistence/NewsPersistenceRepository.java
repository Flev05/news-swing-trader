package com.newsswing.core.news.persistence;

import static com.newsswing.core.news.domain.NewsEvents.CanonicalNewsEvent;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Internal persistence primitive for M2A. M2B's coordinator will be the only future policy entry
 * point that chooses which operation to call.
 */
@Repository
class NewsPersistenceRepository {

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;

  NewsPersistenceRepository(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
  }

  PersistedRevision persistQueued(CanonicalNewsEvent event) {
    return persist(event, AdmissionState.QUEUED);
  }

  PersistedRevision persistOutOfOrder(CanonicalNewsEvent event) {
    return persist(event, AdmissionState.OUT_OF_ORDER);
  }

  private PersistedRevision persist(CanonicalNewsEvent event, AdmissionState requestedState) {
    try {
      return Objects.requireNonNull(
          transactionTemplate.execute(
              ignored -> {
                ExistingRevision byEventId = findByEventId(event.eventId());
                if (byEventId != null) {
                  return duplicateOrEventConflict(byEventId, event);
                }

                UUID itemId = findOrCreateAndLockItem(event);
                ExistingRevision byProviderRevision =
                    findByProviderRevision(itemId, event.source().revisionId());
                if (byProviderRevision != null) {
                  return duplicateOrProviderConflict(byProviderRevision, event);
                }

                insertRevision(itemId, event, requestedState);
                UUID jobId = null;
                if (requestedState == AdmissionState.QUEUED) {
                  jobId = UUID.randomUUID();
                  insertPendingJob(jobId, event);
                  updateLatestPointer(itemId, event.eventId());
                }
                String jobStatus = requestedState == AdmissionState.QUEUED ? "PENDING" : null;
                return new PersistedRevision(
                    event.eventId(),
                    itemId,
                    requestedState,
                    PersistenceOutcome.CREATED,
                    jobId,
                    jobStatus);
              }));
    } catch (DataIntegrityViolationException exception) {
      ExistingRevision existing = findByEventId(event.eventId());
      if (existing != null) {
        return duplicateOrEventConflict(existing, event);
      }
      throw exception;
    }
  }

  private PersistedRevision duplicateOrEventConflict(
      ExistingRevision existing, CanonicalNewsEvent event) {
    if (existing.matches(event)) {
      return existing.asDuplicate();
    }
    if (existing.matchesProviderRevision(event)) {
      throw new ProviderRevisionConflictException(event.source());
    }
    throw new EventIdConflictException(event.eventId());
  }

  private PersistedRevision duplicateOrProviderConflict(
      ExistingRevision existing, CanonicalNewsEvent event) {
    if (existing.providerFingerprint().equals(event.providerFingerprint())) {
      return existing.asDuplicate();
    }
    throw new ProviderRevisionConflictException(event.source());
  }

  private UUID findOrCreateAndLockItem(CanonicalNewsEvent event) {
    jdbcTemplate.update(
        """
        INSERT INTO trader.news_items (id, provider, provider_news_id)
        VALUES (?, ?, ?)
        ON CONFLICT (provider, provider_news_id) DO NOTHING
        """,
        UUID.randomUUID(),
        event.source().provider(),
        event.source().newsId());

    return jdbcTemplate.queryForObject(
        """
        SELECT id
        FROM trader.news_items
        WHERE provider = ? AND provider_news_id = ?
        FOR UPDATE
        """,
        UUID.class,
        event.source().provider(),
        event.source().newsId());
  }

  private ExistingRevision findByEventId(UUID eventId) {
    List<ExistingRevision> revisions =
        jdbcTemplate.query(
            """
            SELECT r.event_id, r.news_item_id, i.provider, i.provider_news_id, r.provider_revision_id,
                   r.provider_fingerprint, r.admission_state, j.job_id, j.status
            FROM trader.news_revisions r
            JOIN trader.news_items i ON i.id = r.news_item_id
            LEFT JOIN trader.inference_jobs j ON j.news_revision_id = r.event_id
            WHERE r.event_id = ?
            """,
            this::mapExistingRevision,
            eventId);
    return revisions.isEmpty() ? null : revisions.getFirst();
  }

  private ExistingRevision findByProviderRevision(UUID itemId, String revisionId) {
    List<ExistingRevision> revisions =
        jdbcTemplate.query(
            """
            SELECT r.event_id, r.news_item_id, i.provider, i.provider_news_id, r.provider_revision_id,
                   r.provider_fingerprint, r.admission_state, j.job_id, j.status
            FROM trader.news_revisions r
            JOIN trader.news_items i ON i.id = r.news_item_id
            LEFT JOIN trader.inference_jobs j ON j.news_revision_id = r.event_id
            WHERE r.news_item_id = ? AND r.provider_revision_id = ?
            """,
            this::mapExistingRevision,
            itemId,
            revisionId);
    return revisions.isEmpty() ? null : revisions.getFirst();
  }

  private ExistingRevision mapExistingRevision(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new ExistingRevision(
        resultSet.getObject("event_id", UUID.class),
        resultSet.getObject("news_item_id", UUID.class),
        resultSet.getString("provider"),
        resultSet.getString("provider_news_id"),
        resultSet.getString("provider_revision_id"),
        resultSet.getString("provider_fingerprint"),
        AdmissionState.valueOf(resultSet.getString("admission_state")),
        resultSet.getObject("job_id", UUID.class),
        resultSet.getString("status"));
  }

  private void insertRevision(
      UUID itemId, CanonicalNewsEvent event, AdmissionState admissionState) {
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
        event.payloadJson(),
        admissionState.name());
  }

  private void insertPendingJob(UUID jobId, CanonicalNewsEvent event) {
    jdbcTemplate.update(
        """
        INSERT INTO trader.inference_jobs (
            job_id, news_revision_id, job_kind, input_schema_version, idempotency_key, status
        )
        VALUES (?, ?, 'NEWS_INFERENCE', '1.0.0', ?, 'PENDING')
        """,
        jobId,
        event.eventId(),
        event.jobIdempotencyKey());
  }

  private void updateLatestPointer(UUID itemId, UUID eventId) {
    jdbcTemplate.update(
        """
        UPDATE trader.news_items
        SET latest_event_id = ?, updated_at = CURRENT_TIMESTAMP
        WHERE id = ?
        """,
        eventId,
        itemId);
  }
}

enum AdmissionState {
  QUEUED,
  OUT_OF_ORDER
}

enum PersistenceOutcome {
  CREATED,
  DUPLICATE
}

record PersistedRevision(
    UUID eventId,
    UUID newsItemId,
    AdmissionState admissionState,
    PersistenceOutcome outcome,
    UUID jobId,
    String jobStatus) {}

record ExistingRevision(
    UUID eventId,
    UUID newsItemId,
    String provider,
    String newsId,
    String revisionId,
    String providerFingerprint,
    AdmissionState admissionState,
    UUID jobId,
    String jobStatus) {

  boolean matches(CanonicalNewsEvent event) {
    return matchesProviderRevision(event)
        && providerFingerprint.equals(event.providerFingerprint());
  }

  boolean matchesProviderRevision(CanonicalNewsEvent event) {
    return provider.equals(event.source().provider())
        && newsId.equals(event.source().newsId())
        && revisionId.equals(event.source().revisionId());
  }

  PersistedRevision asDuplicate() {
    return new PersistedRevision(
        eventId, newsItemId, admissionState, PersistenceOutcome.DUPLICATE, jobId, jobStatus);
  }
}

final class ProviderRevisionConflictException extends RuntimeException {

  ProviderRevisionConflictException(Object ignored) {
    super("Provider revision conflicts with immutable persisted content");
  }
}

final class EventIdConflictException extends RuntimeException {

  EventIdConflictException(UUID eventId) {
    super("Event ID already belongs to a different provider revision: " + eventId);
  }
}

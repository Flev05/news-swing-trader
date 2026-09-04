package com.newsswing.core.news.ingestion;

import static com.newsswing.core.news.domain.NewsEvents.CanonicalNewsEvent;
import static com.newsswing.core.news.domain.NewsEvents.UnhashedNewsEvent;

import com.newsswing.core.contracts.SharedContractValidator.Violation;
import com.newsswing.core.news.domain.NewsCanonicalizationException;
import com.newsswing.core.news.domain.NewsCanonicalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The sole application-level entry point for durable News Event v1 ingestion. */
@Service
public final class NewsIngestionCoordinator {

  private final NewsCanonicalizer canonicalizer;
  private final NewsEventContractValidator contractValidator;
  private final NewsIngestionRepository repository;
  private final Clock clock;
  private final int pendingCapacity;
  private final TransactionTemplate writeTransactions;
  private final TransactionTemplate readOnlyTransactions;

  NewsIngestionCoordinator(
      NewsCanonicalizer canonicalizer,
      NewsEventContractValidator contractValidator,
      NewsIngestionRepository repository,
      Clock newsIngestionClock,
      NewsIngestionProperties properties,
      PlatformTransactionManager transactionManager) {
    this.canonicalizer = canonicalizer;
    this.contractValidator = contractValidator;
    this.repository = repository;
    this.clock = newsIngestionClock;
    this.pendingCapacity = properties.pendingCapacity();
    this.writeTransactions = new TransactionTemplate(transactionManager);
    this.readOnlyTransactions = new TransactionTemplate(transactionManager);
    this.readOnlyTransactions.setReadOnly(true);
  }

  public NewsIngestionResult ingest(UnhashedNewsEvent command) {
    Instant requestNow = clock.instant();
    CanonicalNewsEvent event = canonicalizeAndValidate(command);
    try {
      return Objects.requireNonNull(
          writeTransactions.execute(ignored -> ingestWithinTransaction(event, requestNow)));
    } catch (DataIntegrityViolationException exception) {
      return resolveAfterRollback(event, exception);
    }
  }

  private CanonicalNewsEvent canonicalizeAndValidate(UnhashedNewsEvent command) {
    CanonicalNewsEvent event;
    try {
      event = canonicalizer.canonicalize(command);
    } catch (NewsCanonicalizationException exception) {
      throw new NewsIngestionException(
          NewsIngestionException.Code.INVALID_EVENT, exception.getMessage());
    }

    List<Violation> violations = contractValidator.validate(event.payloadJson());
    if (!violations.isEmpty()) {
      throw new NewsIngestionException(
          NewsIngestionException.Code.INVALID_EVENT,
          "Canonical News Event v1 payload violates the authoritative schema",
          violations);
    }
    return event;
  }

  private NewsIngestionResult ingestWithinTransaction(
      CanonicalNewsEvent event, Instant requestNow) {
    ExistingRevision existingByEvent = repository.findByEventId(event.eventId());
    if (existingByEvent != null) {
      return resolveEventId(existingByEvent, event);
    }

    UUID itemId = repository.findOrCreateAndLockItem(event, requestNow);
    existingByEvent = repository.findByEventId(event.eventId());
    if (existingByEvent != null) {
      return resolveEventId(existingByEvent, event);
    }

    ExistingRevision existingByProvider =
        repository.findByProviderRevision(itemId, event.source().revisionId());
    if (existingByProvider != null) {
      return resolveProviderRevision(existingByProvider, event);
    }

    if (!event.deadlineAt().isAfter(requestNow)) {
      throw new NewsIngestionException(
          NewsIngestionException.Code.STALE,
          "Previously unseen event deadline is not after the captured request instant");
    }

    LatestRevision latest = repository.findLatest(itemId);
    if (latest != null) {
      int ordering = event.sourceUpdatedAt().compareTo(latest.sourceUpdatedAt());
      if (ordering < 0) {
        repository.insertRevision(itemId, event, AdmissionState.OUT_OF_ORDER, requestNow);
        return new NewsIngestionResult.OutOfOrder(event.eventId(), itemId);
      }
      if (ordering == 0) {
        throw new NewsIngestionException(
            NewsIngestionException.Code.AMBIGUOUS_SOURCE_UPDATED_AT,
            "Distinct provider revisions have the same source_updated_at");
      }
    }

    repository.lockQueueAdmission();
    existingByEvent = repository.findByEventId(event.eventId());
    if (existingByEvent != null) {
      return resolveEventId(existingByEvent, event);
    }
    existingByProvider = repository.findByProviderRevision(itemId, event.source().revisionId());
    if (existingByProvider != null) {
      return resolveProviderRevision(existingByProvider, event);
    }

    CurrentJob previousJob = null;
    if (latest != null) {
      previousJob = repository.findJobForUpdate(latest.eventId());
      if (previousJob == null || !"PENDING".equals(previousJob.status())) {
        throw new IllegalStateException("Latest queued revision does not have a PENDING job");
      }
    }

    long projectedPending = repository.countPendingJobs() + 1L - (previousJob == null ? 0L : 1L);
    if (projectedPending > pendingCapacity) {
      throw new NewsIngestionException(
          NewsIngestionException.Code.CAPACITY_EXCEEDED,
          "NEWS_INFERENCE pending capacity would be exceeded");
    }

    repository.insertRevision(itemId, event, AdmissionState.QUEUED, requestNow);
    if (previousJob != null
        && repository.supersedePendingJob(latest.eventId(), event.eventId(), requestNow) != 1) {
      throw new IllegalStateException("Expected exactly one PENDING job to be superseded");
    }
    UUID jobId = UUID.randomUUID();
    repository.insertPendingJob(jobId, event, requestNow);
    repository.updateLatestPointer(itemId, event.eventId(), requestNow);
    return new NewsIngestionResult.Queued(event.eventId(), itemId, jobId);
  }

  private NewsIngestionResult resolveAfterRollback(
      CanonicalNewsEvent event, DataIntegrityViolationException failedWrite) {
    return Objects.requireNonNull(
        readOnlyTransactions.execute(
            ignored -> {
              ExistingRevision byEvent = repository.findByEventId(event.eventId());
              if (byEvent != null) {
                return resolveEventId(byEvent, event);
              }
              UUID itemId =
                  repository.findNewsItemId(event.source().provider(), event.source().newsId());
              if (itemId != null) {
                ExistingRevision byProvider =
                    repository.findByProviderRevision(itemId, event.source().revisionId());
                if (byProvider != null) {
                  return resolveProviderRevision(byProvider, event);
                }
              }
              throw failedWrite;
            }));
  }

  private NewsIngestionResult resolveEventId(ExistingRevision existing, CanonicalNewsEvent event) {
    if (existing.matches(event)) {
      return duplicate(existing, event.eventId());
    }
    if (existing.matchesProviderRevision(event)) {
      throw new NewsIngestionException(
          NewsIngestionException.Code.PROVIDER_REVISION_CONFLICT,
          "Provider revision conflicts with immutable persisted content");
    }
    throw new NewsIngestionException(
        NewsIngestionException.Code.EVENT_ID_CONFLICT,
        "Event ID already belongs to a different provider revision");
  }

  private NewsIngestionResult resolveProviderRevision(
      ExistingRevision existing, CanonicalNewsEvent event) {
    if (existing.providerFingerprint().equals(event.providerFingerprint())) {
      return duplicate(existing, event.eventId());
    }
    throw new NewsIngestionException(
        NewsIngestionException.Code.PROVIDER_REVISION_CONFLICT,
        "Provider revision conflicts with immutable persisted content");
  }

  private static NewsIngestionResult.Duplicate duplicate(
      ExistingRevision existing, UUID requestedEventId) {
    Optional<NewsIngestionResult.JobSnapshot> currentJob =
        existing.jobId() == null
            ? Optional.empty()
            : Optional.of(
                new NewsIngestionResult.JobSnapshot(
                    existing.jobId(), NewsIngestionResult.JobStatus.valueOf(existing.jobStatus())));
    return new NewsIngestionResult.Duplicate(
        requestedEventId,
        existing.eventId(),
        existing.newsItemId(),
        NewsIngestionResult.StoredAdmissionState.valueOf(existing.admissionState().name()),
        currentJob);
  }
}

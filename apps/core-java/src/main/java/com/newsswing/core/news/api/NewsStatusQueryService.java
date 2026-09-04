package com.newsswing.core.news.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
class NewsStatusQueryService {

  private final NewsStatusRepository repository;
  private final JsonMapper jsonMapper;
  private final TransactionTemplate readOnlyTransactions;

  NewsStatusQueryService(
      NewsStatusRepository repository,
      JsonMapper jsonMapper,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.jsonMapper = jsonMapper;
    this.readOnlyTransactions = new TransactionTemplate(transactionManager);
    this.readOnlyTransactions.setReadOnly(true);
  }

  Optional<NewsApiModels.NewsRevisionResponse> findRevision(UUID eventId) {
    return Objects.requireNonNull(
        readOnlyTransactions.execute(
            ignored -> repository.findRevision(eventId).map(this::toRevisionResponse)));
  }

  Optional<NewsApiModels.InferenceJobResponse> findJob(UUID jobId) {
    return Objects.requireNonNull(
        readOnlyTransactions.execute(
            ignored -> repository.findJob(jobId).map(this::toInferenceJobResponse)));
  }

  private NewsApiModels.NewsRevisionResponse toRevisionResponse(RevisionRow row) {
    try {
      var event =
          jsonMapper.readValue(row.eventPayload(), NewsApiModels.CanonicalNewsEventResponse.class);
      var job =
          row.jobId() == null
              ? null
              : new NewsApiModels.RevisionJobResponse(
                  row.jobId(),
                  row.jobStatus(),
                  row.jobCreatedAt(),
                  row.jobSupersededAt(),
                  row.supersededByEventId());
      return new NewsApiModels.NewsRevisionResponse(
          event, row.newsItemId(), row.admissionState(), row.persistedAt(), job);
    } catch (Exception exception) {
      throw new NewsApiInfrastructureException(
          "Unable to decode persisted News Event v1 payload", exception);
    }
  }

  private NewsApiModels.InferenceJobResponse toInferenceJobResponse(JobRow row) {
    return new NewsApiModels.InferenceJobResponse(
        row.jobId(),
        row.newsEventId(),
        row.jobKind(),
        row.inputSchemaVersion(),
        row.status(),
        row.createdAt(),
        row.supersededAt(),
        row.supersededByEventId());
  }
}

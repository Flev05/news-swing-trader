package com.newsswing.core.news.ingestion;

import com.newsswing.core.contracts.SharedContractValidator.Violation;
import java.util.List;

/** Unchecked domain rejection from the news-ingestion coordinator. */
public final class NewsIngestionException extends RuntimeException {

  public enum Code {
    INVALID_EVENT,
    EVENT_ID_CONFLICT,
    PROVIDER_REVISION_CONFLICT,
    STALE,
    AMBIGUOUS_SOURCE_UPDATED_AT,
    CAPACITY_EXCEEDED
  }

  private final Code code;
  private final List<Violation> violations;

  NewsIngestionException(Code code, String message) {
    this(code, message, List.of());
  }

  NewsIngestionException(Code code, String message, List<Violation> violations) {
    super(message);
    this.code = code;
    this.violations = List.copyOf(violations);
  }

  public Code code() {
    return code;
  }

  public List<Violation> violations() {
    return violations;
  }
}

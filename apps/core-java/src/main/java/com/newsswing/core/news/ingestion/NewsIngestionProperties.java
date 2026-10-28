package com.newsswing.core.news.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("trader.news.ingestion")
public record NewsIngestionProperties(int pendingCapacity) {

  public NewsIngestionProperties {
    if (pendingCapacity < 0) {
      throw new IllegalArgumentException(
          "trader.news.ingestion.pending-capacity must be non-negative");
    }
  }
}

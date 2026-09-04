package com.newsswing.core.news.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("trader.news.api")
record NewsApiProperties(int maxRequestBytes) {

  static final int MAX_REQUEST_BYTES_UPPER_BOUND = 16 * 1024 * 1024;

  NewsApiProperties {
    if (maxRequestBytes < 1 || maxRequestBytes > MAX_REQUEST_BYTES_UPPER_BOUND) {
      throw new IllegalArgumentException(
          "trader.news.api.max-request-bytes must be between 1 and 16777216");
    }
  }
}

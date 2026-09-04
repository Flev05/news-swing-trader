package com.newsswing.core.news.api;

/** Signals an empty or JSON-null ingestion body that cannot represent an event object. */
final class NewsApiMalformedJsonException extends RuntimeException {

  NewsApiMalformedJsonException() {}
}

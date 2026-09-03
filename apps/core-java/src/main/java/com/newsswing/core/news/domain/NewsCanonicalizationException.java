package com.newsswing.core.news.domain;

/** Raised when an input cannot produce a valid canonical News Event v1. */
public final class NewsCanonicalizationException extends IllegalArgumentException {

  public NewsCanonicalizationException(String message) {
    super(message);
  }
}

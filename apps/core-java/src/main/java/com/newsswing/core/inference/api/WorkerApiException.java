package com.newsswing.core.inference.api;

/** Bounded transport diagnostics; no request values or parser causes. */
final class WorkerApiException extends RuntimeException {
  final int status;
  final String code;

  WorkerApiException(int status, String code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

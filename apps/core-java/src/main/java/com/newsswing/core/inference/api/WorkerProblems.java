package com.newsswing.core.inference.api;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.ProblemDetail;

final class WorkerProblems {
  private WorkerProblems() {}

  static ProblemDetail problem(int status, String code, String path) {
    String detail =
        switch (code) {
          case "MALFORMED_JSON" -> "The JSON request cannot be read.";
          case "UNKNOWN_FIELD" -> "The JSON request contains an unsupported field.";
          case "INVALID_COMMAND" ->
              "The worker command does not satisfy the transport requirements.";
          case "PAYLOAD_TOO_LARGE" -> "The JSON request body exceeds the byte limit.";
          case "UNSUPPORTED_MEDIA_TYPE" -> "The request must use UTF-8 application/json.";
          case "UNSUPPORTED_CONTENT_ENCODING" -> "The request content encoding is unsupported.";
          case "INFRASTRUCTURE_FAILURE" -> "The service cannot access durable storage.";
          case "INTERNAL_ERROR" -> "The service could not complete the request.";
          case "NOT_ACCEPTABLE" -> "The requested response representation is unavailable.";
          case "INVALID_RESULT", "PROVENANCE_MISMATCH" ->
              "The result does not satisfy the assignment requirements.";
          case "COMPLETION_CONFLICT" -> "The completion conflicts with the accepted result.";
          default -> "The assignment command was rejected.";
        };
    var problem =
        ProblemDetail.forStatusAndDetail(
            org.springframework.http.HttpStatusCode.valueOf(status), detail);
    problem.setTitle(code.replace('_', ' '));
    problem.setType(
        URI.create("urn:news-swing:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
    problem.setInstance(URI.create(path));
    problem.setProperty("code", code);
    return problem;
  }
}

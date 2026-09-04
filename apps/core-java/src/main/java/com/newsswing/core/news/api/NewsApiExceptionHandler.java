package com.newsswing.core.news.api;

import com.newsswing.core.news.ingestion.NewsIngestionException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class NewsApiExceptionHandler {

  @ExceptionHandler(NewsApiMalformedJsonException.class)
  ResponseEntity<ProblemDetail> handleMissingOrNullJson(HttpServletRequest request) {
    return NewsApiProblemFactory.response(
        new ProblemSpec(
            HttpStatus.BAD_REQUEST,
            "MALFORMED_JSON",
            "Malformed JSON",
            "The JSON request cannot be read."),
        request);
  }

  @ExceptionHandler(NewsIngestionException.class)
  ResponseEntity<ProblemDetail> handleIngestion(
      NewsIngestionException exception, HttpServletRequest request) {
    return NewsApiProblemFactory.response(domainSpec(exception.code()), request);
  }

  @ExceptionHandler(NewsApiNotFoundException.class)
  ResponseEntity<ProblemDetail> handleNotFound(
      NewsApiNotFoundException exception, HttpServletRequest request) {
    return NewsApiProblemFactory.response(
        new ProblemSpec(
            HttpStatus.NOT_FOUND, exception.code(), exception.title(), exception.detail()),
        request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> handleUnreadable(
      HttpMessageNotReadableException exception, HttpServletRequest request) {
    String code =
        causedBy(exception, "tools.jackson.databind.exc.UnrecognizedPropertyException")
            ? "UNKNOWN_FIELD"
            : "MALFORMED_JSON";
    return NewsApiProblemFactory.response(
        code.equals("UNKNOWN_FIELD")
            ? new ProblemSpec(
                HttpStatus.BAD_REQUEST,
                code,
                "Unknown field",
                "The JSON request contains an unsupported field.")
            : new ProblemSpec(
                HttpStatus.BAD_REQUEST, code, "Malformed JSON", "The JSON request cannot be read."),
        request);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ProblemDetail> handleUnsupportedMedia(
      HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
    return NewsApiProblemFactory.response(
        new ProblemSpec(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "UNSUPPORTED_MEDIA_TYPE",
            "Unsupported media type",
            "The request must use application/json."),
        request);
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  ResponseEntity<ProblemDetail> handleNotAcceptable(
      HttpMediaTypeNotAcceptableException exception, HttpServletRequest request) {
    return NewsApiProblemFactory.response(
        new ProblemSpec(
            HttpStatus.NOT_ACCEPTABLE,
            "NOT_ACCEPTABLE",
            "Unsupported response media type",
            "The requested response representation is unavailable."),
        request);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ProblemDetail> handlePathType(
      MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
    return NewsApiProblemFactory.response(
        new ProblemSpec(
            HttpStatus.BAD_REQUEST,
            "INVALID_PATH_PARAMETER",
            "Invalid path parameter",
            "A path parameter has an invalid value."),
        request);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ProblemDetail> handleMethod(
      HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
    return NewsApiProblemFactory.response(
        new ProblemSpec(
            HttpStatus.METHOD_NOT_ALLOWED,
            "METHOD_NOT_ALLOWED",
            "Method not allowed",
            "The requested HTTP method is not supported for this resource."),
        request);
  }

  @ExceptionHandler({
    DataAccessException.class,
    TransactionException.class,
    NewsApiInfrastructureException.class
  })
  ResponseEntity<ProblemDetail> handleInfrastructure(
      RuntimeException exception, HttpServletRequest request) {
    return NewsApiProblemFactory.response(
        new ProblemSpec(
            HttpStatus.SERVICE_UNAVAILABLE,
            "INFRASTRUCTURE_FAILURE",
            "Infrastructure unavailable",
            "The service cannot access durable storage."),
        request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
    return NewsApiProblemFactory.response(
        new ProblemSpec(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "Internal server error",
            "The service could not complete the request."),
        request);
  }

  private static ProblemSpec domainSpec(NewsIngestionException.Code code) {
    return switch (code) {
      case INVALID_EVENT ->
          new ProblemSpec(
              HttpStatus.UNPROCESSABLE_CONTENT,
              "INVALID_EVENT",
              "Invalid event",
              "The event does not satisfy the News Event v1 requirements.");
      case EVENT_ID_CONFLICT ->
          new ProblemSpec(
              HttpStatus.CONFLICT,
              "EVENT_ID_CONFLICT",
              "Event ID conflict",
              "The event ID is already bound to another provider revision.");
      case PROVIDER_REVISION_CONFLICT ->
          new ProblemSpec(
              HttpStatus.CONFLICT,
              "PROVIDER_REVISION_CONFLICT",
              "Provider revision conflict",
              "The provider revision is already bound to different immutable content.");
      case STALE ->
          new ProblemSpec(
              HttpStatus.UNPROCESSABLE_CONTENT,
              "STALE",
              "Stale event",
              "The previously unseen event deadline has elapsed.");
      case AMBIGUOUS_SOURCE_UPDATED_AT ->
          new ProblemSpec(
              HttpStatus.CONFLICT,
              "AMBIGUOUS_SOURCE_UPDATED_AT",
              "Ambiguous source timestamp",
              "A distinct revision already has the same source updated timestamp.");
      case CAPACITY_EXCEEDED ->
          new ProblemSpec(
              HttpStatus.SERVICE_UNAVAILABLE,
              "CAPACITY_EXCEEDED",
              "Pending capacity exceeded",
              "The news inference queue is at capacity.");
    };
  }

  private static boolean causedBy(Throwable exception, String className) {
    Throwable current = exception;
    while (current != null) {
      if (className.equals(current.getClass().getName())) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}

record ProblemSpec(HttpStatus status, String code, String title, String detail) {}

final class NewsApiProblemFactory {

  private NewsApiProblemFactory() {}

  static ResponseEntity<ProblemDetail> response(ProblemSpec spec, HttpServletRequest request) {
    return ResponseEntity.status(spec.status())
        .cacheControl(CacheControl.noStore())
        .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem(spec, request.getRequestURI()));
  }

  static ProblemDetail problem(ProblemSpec spec, String instance) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(spec.status(), spec.detail());
    problem.setType(
        URI.create(
            "urn:news-swing:problem:" + spec.code().toLowerCase(Locale.ROOT).replace('_', '-')));
    problem.setTitle(spec.title());
    problem.setInstance(URI.create(instance));
    problem.setProperty("code", spec.code());
    return problem;
  }
}

final class NewsApiNotFoundException extends RuntimeException {

  private final String code;
  private final String title;
  private final String detail;

  NewsApiNotFoundException(String code, String title, String detail) {
    this.code = code;
    this.title = title;
    this.detail = detail;
  }

  String code() {
    return code;
  }

  String title() {
    return title;
  }

  String detail() {
    return detail;
  }
}

final class NewsApiInfrastructureException extends RuntimeException {

  NewsApiInfrastructureException(String message, Throwable cause) {
    super(message, cause);
  }
}

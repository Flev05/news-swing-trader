package com.newsswing.core.inference.api;

import com.newsswing.core.inference.jobs.RecoveryInfrastructureException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = WorkerApiController.class)
final class WorkerApiExceptionHandler {
  @ExceptionHandler(WorkerApiException.class)
  ResponseEntity<ProblemDetail> transport(
      WorkerApiException exception, HttpServletRequest request) {
    return response(exception.status, exception.code, request);
  }

  @ExceptionHandler({
    DataAccessException.class,
    TransactionException.class,
    RecoveryInfrastructureException.class
  })
  ResponseEntity<ProblemDetail> infrastructure(
      RuntimeException exception, HttpServletRequest request) {
    return response(503, "INFRASTRUCTURE_FAILURE", request);
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  ResponseEntity<ProblemDetail> notAcceptable(HttpServletRequest request) {
    return response(406, "NOT_ACCEPTABLE", request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
    return response(500, "INTERNAL_ERROR", request);
  }

  private static ResponseEntity<ProblemDetail> response(
      int status, String code, HttpServletRequest request) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(WorkerProblems.problem(status, code, request.getRequestURI()));
  }
}

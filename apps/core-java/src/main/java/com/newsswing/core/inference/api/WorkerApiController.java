package com.newsswing.core.inference.api;

import static com.newsswing.core.inference.jobs.InferenceJobLifecycleService.*;

import com.newsswing.core.inference.jobs.InferenceJobLifecycleService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/worker", produces = MediaType.APPLICATION_JSON_VALUE)
final class WorkerApiController {
  private final InferenceJobLifecycleService service;

  WorkerApiController(InferenceJobLifecycleService service) {
    this.service = service;
  }

  @PostMapping("/claim")
  ResponseEntity<?> claim(HttpServletRequest request) {
    command(request, WorkerEnvelopeParser.Operation.CLAIM);
    return switch (service.claimNext()) {
      case Claimed claimed ->
          ok(
              Map.of(
                  "outcome",
                  "CLAIMED",
                  "assignment",
                  WorkerApiModels.assignment(claimed.assignment())));
      case NoWork ignored ->
          ResponseEntity.ok()
              .cacheControl(CacheControl.noStore())
              .header("Retry-After", "2")
              .body(Map.of("outcome", "NO_WORK"));
    };
  }

  @PostMapping("/renew")
  ResponseEntity<?> renew(HttpServletRequest request) {
    var command = command(request, WorkerEnvelopeParser.Operation.RENEW);
    return switch (service.renew(command.assignment())) {
      case Renewed renewed ->
          ok(Map.of("outcome", "RENEWED", "lease_expires_at", renewed.leaseExpiresAt()));
      case Rejected rejected -> rejected(rejected);
    };
  }

  @PostMapping("/fail")
  ResponseEntity<?> fail(HttpServletRequest request) {
    var command = command(request, WorkerEnvelopeParser.Operation.FAIL);
    return switch (service.fail(command.assignment(), command.kind())) {
      case RetryWaiting ignored -> ok(Map.of("outcome", "RETRY_WAITING"));
      case Failed failed -> ok(Map.of("outcome", "FAILED", "failure_code", failed.code().name()));
      case Rejected rejected -> rejected(rejected);
    };
  }

  @PostMapping("/complete")
  ResponseEntity<?> complete(HttpServletRequest request) {
    var command = command(request, WorkerEnvelopeParser.Operation.COMPLETE);
    return switch (service.complete(command.assignment(), command.resultJson())) {
      case Accepted accepted ->
          ok(Map.of("outcome", "ACCEPTED", "receipt", WorkerApiModels.receipt(accepted.receipt())));
      case AlreadyAccepted accepted ->
          ok(
              Map.of(
                  "outcome",
                  "ALREADY_ACCEPTED",
                  "receipt",
                  WorkerApiModels.receipt(accepted.receipt())));
      case CompletionConflict ignored -> throw new WorkerApiException(409, "COMPLETION_CONFLICT");
      case InvalidResult invalid -> throw new WorkerApiException(422, invalid.code().name());
      case Rejected rejected -> rejected(rejected);
    };
  }

  private static WorkerEnvelopeParser.Command command(
      HttpServletRequest request, WorkerEnvelopeParser.Operation operation) {
    return WorkerEnvelopeParser.parse(
        (byte[]) request.getAttribute(WorkerRequestFilter.BODY), operation);
  }

  private static ResponseEntity<?> rejected(Rejected rejected) {
    throw switch (rejected.reason()) {
      case INVALID_COMMAND -> new WorkerApiException(400, "INVALID_COMMAND");
      case NOT_FOUND -> new WorkerApiException(404, "ASSIGNMENT_NOT_FOUND");
      default -> new WorkerApiException(409, rejected.reason().name());
    };
  }

  private static ResponseEntity<?> ok(Object body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}

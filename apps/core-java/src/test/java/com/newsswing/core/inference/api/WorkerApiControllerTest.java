package com.newsswing.core.inference.api;

import static com.newsswing.core.inference.jobs.InferenceJobLifecycleService.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.newsswing.core.inference.jobs.InferenceJobLifecycleService;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;
import tools.jackson.databind.json.JsonMapper;

class WorkerApiControllerTest {
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final AssignmentRef REF =
      new AssignmentRef(
          UUID.fromString("11111111-1111-1111-1111-111111111111"),
          1,
          UUID.fromString("22222222-2222-2222-2222-222222222222"));

  static Stream<Arguments> rejections() {
    return Stream.of(RejectionReason.values())
        .flatMap(
            reason -> Stream.of("renew", "fail", "complete").map(op -> Arguments.of(op, reason)));
  }

  @ParameterizedTest
  @MethodSource("rejections")
  void mapsEveryServiceRejectionWithoutReinterpretingIt(String operation, RejectionReason reason)
      throws Exception {
    var service = mock(InferenceJobLifecycleService.class);
    var rejected = new Rejected(reason);
    when(service.renew(REF)).thenReturn(rejected);
    when(service.fail(REF, FailureKind.RECOVERABLE)).thenReturn(rejected);
    when(service.complete(REF, "{")).thenReturn(rejected);
    var ref =
        Map.of(
            "job_id",
            REF.jobId().toString(),
            "assignment_number",
            1,
            "ownership_token",
            REF.ownershipToken().toString());
    var body =
        switch (operation) {
          case "renew" -> Map.of("assignment", ref);
          case "fail" -> Map.of("assignment", ref, "kind", "RECOVERABLE");
          case "complete" -> Map.of("assignment", ref, "result_json", "{");
          default -> throw new AssertionError(operation);
        };
    var mvc =
        MockMvcBuilders.standaloneSetup(new WorkerApiController(service))
            .setControllerAdvice(new WorkerApiExceptionHandler())
            .addFilters(new WorkerRequestFilter(JSON))
            .build();
    var response =
        mvc.perform(
                post("/api/v1/worker/" + operation)
                    .contentType("application/json")
                    .content(JSON.writeValueAsBytes(body)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus())
        .isEqualTo(
            switch (reason) {
              case INVALID_COMMAND -> 400;
              case NOT_FOUND -> 404;
              default -> 409;
            });
    assertThat(JSON.readTree(response.getContentAsString()).get("code").asString())
        .isEqualTo(reason == RejectionReason.NOT_FOUND ? "ASSIGNMENT_NOT_FOUND" : reason.name());
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getContentAsString()).doesNotContain(REF.ownershipToken().toString());
    switch (operation) {
      case "renew" -> verify(service).renew(REF);
      case "fail" -> verify(service).fail(REF, FailureKind.RECOVERABLE);
      case "complete" -> verify(service).complete(REF, "{");
      default -> throw new AssertionError(operation);
    }
    verifyNoMoreInteractions(service);
  }

  @ParameterizedTest
  @MethodSource("infrastructureFailures")
  void sanitizesInfrastructureAndUnexpectedErrors(RuntimeException failure, int status, String code)
      throws Exception {
    var service = mock(InferenceJobLifecycleService.class);
    when(service.claimNext()).thenThrow(failure);
    var mvc =
        MockMvcBuilders.standaloneSetup(new WorkerApiController(service))
            .setControllerAdvice(new WorkerApiExceptionHandler())
            .addFilters(new WorkerRequestFilter(JSON))
            .build();
    var response =
        mvc.perform(post("/api/v1/worker/claim").contentType("application/json").content("{}"))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(status);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getContentAsString())
        .contains("\"code\":\"" + code + "\"")
        .doesNotContain("PRIVATE_EXCEPTION_SENTINEL", "Exception");
  }

  static Stream<Arguments> infrastructureFailures() {
    return Stream.of(
        Arguments.of(
            new DataAccessResourceFailureException("PRIVATE_EXCEPTION_SENTINEL"),
            503,
            "INFRASTRUCTURE_FAILURE"),
        Arguments.of(
            new TransactionSystemException("PRIVATE_EXCEPTION_SENTINEL"),
            503,
            "INFRASTRUCTURE_FAILURE"),
        Arguments.of(
            new IllegalStateException("PRIVATE_EXCEPTION_SENTINEL"), 500, "INTERNAL_ERROR"));
  }

  @Test
  void passesTheDecodedInnerStringUnchangedToExactlyOneServiceCall() {
    var service = mock(InferenceJobLifecycleService.class);
    String result = " \n{\"x\":1,\"x\":2,\"literal\":\"\\\\u0000\",\"nul\":\"\\u0000\"} {} ";
    when(service.complete(REF, result)).thenReturn(new CompletionConflict());
    var controller = new WorkerApiController(service);
    var request = new MockHttpServletRequest();
    request.setAttribute(
        WorkerRequestFilter.BODY,
        JSON.writeValueAsBytes(
            Map.of(
                "assignment",
                Map.of(
                    "job_id",
                    REF.jobId().toString(),
                    "assignment_number",
                    1,
                    "ownership_token",
                    REF.ownershipToken().toString()),
                "result_json",
                result)));
    assertThatThrownBy(() -> controller.complete(request)).isInstanceOf(WorkerApiException.class);
    verify(service).complete(REF, result);
    verifyNoMoreInteractions(service);
  }
}

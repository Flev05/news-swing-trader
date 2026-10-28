package com.newsswing.core.inference.api;

import static com.newsswing.core.inference.api.WorkerEnvelopeParser.Operation.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class WorkerEnvelopeParserTest {
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String REF =
      "{\"job_id\":\"11111111-1111-1111-1111-111111111111\",\"assignment_number\":1,\"ownership_token\":\"22222222-2222-2222-2222-222222222222\"}";

  static Stream<Arguments> invalidEnvelopes() {
    return Stream.of(
        Arguments.of(CLAIM, "", "INVALID_COMMAND"),
        Arguments.of(CLAIM, "null", "INVALID_COMMAND"),
        Arguments.of(CLAIM, "[]", "INVALID_COMMAND"),
        Arguments.of(CLAIM, "1", "INVALID_COMMAND"),
        Arguments.of(CLAIM, "{", "MALFORMED_JSON"),
        Arguments.of(CLAIM, "{} {}", "MALFORMED_JSON"),
        Arguments.of(CLAIM, "{} garbage", "MALFORMED_JSON"),
        Arguments.of(CLAIM, "{\"x\":1}", "UNKNOWN_FIELD"),
        Arguments.of(
            RENEW, "{\"assignment\":" + REF + ",\"assignment\":" + REF + "}", "MALFORMED_JSON"),
        Arguments.of(
            RENEW,
            "{\"assignment\":" + REF + ",\"assig\\u006ement\":" + REF + "}",
            "MALFORMED_JSON"),
        Arguments.of(RENEW, "{}", "INVALID_COMMAND"),
        Arguments.of(RENEW, "{\"assignment\":null}", "INVALID_COMMAND"),
        Arguments.of(RENEW, "{\"assignment\":[]}", "INVALID_COMMAND"),
        Arguments.of(
            RENEW,
            "{\"assignment\":"
                + REF.replace(
                    "\"assignment_number\":1", "\"assignment_number\":1,\"assignment_number\":2")
                + "}",
            "MALFORMED_JSON"),
        Arguments.of(
            RENEW,
            "{\"assignment\":"
                + REF.replace(
                    "\"assignment_number\":1",
                    "\"assignment_number\":1,\"assignment_\\u006eumber\":2")
                + "}",
            "MALFORMED_JSON"),
        Arguments.of(
            RENEW,
            "{\"assignment\":" + REF.replace("\"assignment_number\":1,", "") + "}",
            "INVALID_COMMAND"),
        Arguments.of(
            RENEW,
            "{\"assignment\":"
                + REF.replace("\"assignment_number\":1", "\"assignment_number\":1,\"x\":0")
                + "}",
            "UNKNOWN_FIELD"),
        Arguments.of(FAIL, "{\"assignment\":" + REF + "}", "INVALID_COMMAND"),
        Arguments.of(
            FAIL, "{\"assignment\":" + REF + ",\"kind\":\"recoverable\"}", "INVALID_COMMAND"),
        Arguments.of(FAIL, "{\"assignment\":" + REF + ",\"kind\":1}", "INVALID_COMMAND"),
        Arguments.of(COMPLETE, "{\"assignment\":" + REF + "}", "INVALID_COMMAND"),
        Arguments.of(
            COMPLETE, "{\"assignment\":" + REF + ",\"result_json\":null}", "INVALID_COMMAND"),
        Arguments.of(
            COMPLETE, "{\"assignment\":" + REF + ",\"result_json\":{}}", "INVALID_COMMAND"),
        Arguments.of(
            COMPLETE,
            "{\"assignment\":" + REF + ",\"result_json\":\"a\",\"result_json\":\"b\"}",
            "MALFORMED_JSON"),
        Arguments.of(
            COMPLETE,
            "{\"assignment\":" + REF + ",\"result_json\":\"a\",\"x\":0}",
            "UNKNOWN_FIELD"));
  }

  @ParameterizedTest
  @MethodSource("invalidEnvelopes")
  void rejectsStrictEnvelopeErrors(
      WorkerEnvelopeParser.Operation operation, String input, String code) {
    assertThatThrownBy(
            () -> WorkerEnvelopeParser.parse(input.getBytes(StandardCharsets.UTF_8), operation))
        .isInstanceOfSatisfying(
            WorkerApiException.class,
            error -> {
              assertThat(error.status).isEqualTo(400);
              assertThat(error.code).isEqualTo(code);
              assertThat(error.getCause()).isNull();
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "2147483648", "1.0", "1e0", "\"1\"", "true", "null", "[]"})
  void rejectsAssignmentNumberCoercion(String number) {
    String input =
        "{\"assignment\":"
            + REF.replace("\"assignment_number\":1", "\"assignment_number\":" + number)
            + "}";
    assertThatThrownBy(
            () -> WorkerEnvelopeParser.parse(input.getBytes(StandardCharsets.UTF_8), RENEW))
        .isInstanceOfSatisfying(
            WorkerApiException.class, error -> assertThat(error.code).isEqualTo("INVALID_COMMAND"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\"1-1-1-1-1\"",
        "\"bad\"",
        "null",
        "123",
        "\"11111111-1111-1111-1111-111111111111 \""
      })
  void rejectsMalformedIds(String value) {
    String input =
        "{\"assignment\":" + REF.replace("\"11111111-1111-1111-1111-111111111111\"", value) + "}";
    assertThatThrownBy(
            () -> WorkerEnvelopeParser.parse(input.getBytes(StandardCharsets.UTF_8), RENEW))
        .isInstanceOfSatisfying(
            WorkerApiException.class, error -> assertThat(error.code).isEqualTo("INVALID_COMMAND"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "{",
        "{} {}",
        "{\"model\":{\"backend\":\"fake\",\"backend\":\"fake\"}}",
        "{\"x\":\"\\u0000\"}",
        "{\"x\":\"\\ud800\"}",
        "{\"x\":\"\\\\u0000\"}",
        "  { \"x\": \"😀\" } \n"
      })
  void preservesInnerDocumentExactly(String result) {
    String envelope =
        JSON.writeValueAsString(Map.of("assignment", JSON.readTree(REF), "result_json", result));
    var command = WorkerEnvelopeParser.parse(envelope.getBytes(StandardCharsets.UTF_8), COMPLETE);
    assertThat(command.resultJson()).isEqualTo(result);
    assertThat(command.toString()).isEqualTo("WorkerCommand[redacted]");
  }

  @Test
  void rejectsMalformedUtf8WithoutReplacement() {
    assertThatThrownBy(
            () ->
                WorkerEnvelopeParser.parse(
                    new byte[] {'{', '"', (byte) 0xc3, '"', ':', '0', '}'}, CLAIM))
        .isInstanceOfSatisfying(
            WorkerApiException.class, error -> assertThat(error.code).isEqualTo("MALFORMED_JSON"));
  }

  @Test
  void acceptsCanonicalCommands() {
    assertThat(
            WorkerEnvelopeParser.parse("{}".getBytes(StandardCharsets.UTF_8), CLAIM).assignment())
        .isNull();
    for (String kind : new String[] {"RECOVERABLE", "PERMANENT"}) {
      String input = "{\"assignment\":" + REF + ",\"kind\":\"" + kind + "\"}";
      assertThat(
              WorkerEnvelopeParser.parse(input.getBytes(StandardCharsets.UTF_8), FAIL)
                  .kind()
                  .name())
          .isEqualTo(kind);
    }
  }
}

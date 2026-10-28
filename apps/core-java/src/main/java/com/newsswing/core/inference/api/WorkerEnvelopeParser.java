package com.newsswing.core.inference.api;

import com.newsswing.core.inference.jobs.InferenceJobLifecycleService.AssignmentRef;
import com.newsswing.core.inference.jobs.InferenceJobLifecycleService.FailureKind;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Only the envelope is parsed: result_json is never parsed or rewritten here. */
final class WorkerEnvelopeParser {
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(WorkerRequestFilter.COMPLETION_LIMIT)
                          .maxStringLength(WorkerRequestFilter.COMPLETION_LIMIT)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  enum Operation {
    CLAIM,
    RENEW,
    FAIL,
    COMPLETE
  }

  record Command(AssignmentRef assignment, FailureKind kind, String resultJson) {
    @Override
    public String toString() {
      return "WorkerCommand[redacted]";
    }
  }

  static Command parse(byte[] bytes, Operation operation) {
    final JsonNode root;
    try {
      String input =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      root = JSON.readTree(input);
    } catch (JacksonException | CharacterCodingException exception) {
      throw new WorkerApiException(400, "MALFORMED_JSON");
    }
    Set<String> fields =
        switch (operation) {
          case CLAIM -> Set.of();
          case RENEW -> Set.of("assignment");
          case FAIL -> Set.of("assignment", "kind");
          case COMPLETE -> Set.of("assignment", "result_json");
        };
    object(root, fields);
    if (operation == Operation.CLAIM) return new Command(null, null, null);
    var ref = root.get("assignment");
    object(ref, Set.of("job_id", "assignment_number", "ownership_token"));
    var number = ref.get("assignment_number");
    if (!number.isIntegralNumber() || !number.canConvertToInt() || number.intValue() < 1) invalid();
    var assignment =
        new AssignmentRef(
            uuid(ref.get("job_id")), number.intValue(), uuid(ref.get("ownership_token")));
    FailureKind kind = null;
    if (operation == Operation.FAIL) {
      String value = string(root.get("kind"));
      if (!Set.of("RECOVERABLE", "PERMANENT").contains(value)) invalid();
      kind = FailureKind.valueOf(value);
    }
    return new Command(
        assignment, kind, operation == Operation.COMPLETE ? string(root.get("result_json")) : null);
  }

  private static void object(JsonNode node, Set<String> fields) {
    if (node == null || !node.isObject()) invalid();
    for (var entry : node.properties()) {
      if (!fields.contains(entry.getKey())) throw new WorkerApiException(400, "UNKNOWN_FIELD");
    }
    if (node.size() != fields.size()) invalid();
  }

  private static String string(JsonNode node) {
    if (node == null || !node.isString()) invalid();
    return node.asString();
  }

  private static UUID uuid(JsonNode node) {
    String value = string(node);
    if (!value.matches(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) invalid();
    return UUID.fromString(value);
  }

  private static void invalid() {
    throw new WorkerApiException(400, "INVALID_COMMAND");
  }
}

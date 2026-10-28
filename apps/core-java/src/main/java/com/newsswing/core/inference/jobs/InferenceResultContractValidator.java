package com.newsswing.core.inference.jobs;

import com.newsswing.core.contracts.SharedContractValidator;
import com.newsswing.core.contracts.SharedContractValidator.Violation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict preparation records diagnostics; ownership and time determine whether they take effect.
 */
final class InferenceResultContractValidator {
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final SharedContractValidator.CompiledContract contract;

  InferenceResultContractValidator(String schema) throws IOException {
    contract = SharedContractValidator.compile(schema);
  }

  Prepared prepare(String input) {
    if (input == null || input.isBlank()) {
      return invalid(input, "json", "", "Expected one JSON document");
    }
    final JsonNode document;
    try {
      document = JSON.readTree(input);
    } catch (JacksonException exception) {
      return invalid(input, "json", "", "Invalid strict JSON document");
    }
    var unicode = new ArrayList<Violation>();
    checkUnicode(document, "", unicode);
    if (!unicode.isEmpty()) {
      return new Prepared(input, unicode, null);
    }
    final List<Violation> violations;
    try {
      violations = contract.validate(input);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to validate prepared Inference Result v1", exception);
    }
    if (!violations.isEmpty()) {
      return new Prepared(input, violations, null);
    }
    var tickers =
        document
            .path("ticker_results")
            .valueStream()
            .map(ticker -> ticker.path("ticker").asString())
            .collect(Collectors.toUnmodifiableSet());
    return new Prepared(
        input,
        List.of(),
        new Provenance(
            UUID.fromString(document.path("inference_id").asString()),
            UUID.fromString(document.path("news_event_id").asString()),
            document.path("news_event_sha256").asString(),
            document.path("schema_version").asString(),
            document.path("model").path("backend").asString(),
            tickers));
  }

  private static Prepared invalid(String input, String keyword, String path, String message) {
    return new Prepared(input, List.of(new Violation(keyword, path, message)), null);
  }

  private static void checkUnicode(JsonNode node, String path, List<Violation> violations) {
    if (node.isString()) {
      checkString(node.asString(), path, violations);
    } else if (node.isObject()) {
      node.properties()
          .forEach(
              entry -> {
                String key = entry.getKey();
                if (checkString(key, path, violations)) {
                  checkUnicode(
                      entry.getValue(),
                      path + "/" + key.replace("~", "~0").replace("/", "~1"),
                      violations);
                }
              });
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        checkUnicode(node.get(i), path + "/" + i, violations);
      }
    }
  }

  private static boolean checkString(String value, String path, List<Violation> violations) {
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (current == 0
          || Character.isLowSurrogate(current)
          || (Character.isHighSurrogate(current)
              && (i + 1 == value.length() || !Character.isLowSurrogate(value.charAt(i + 1))))) {
        violations.add(new Violation("unicode", path, "Decoded NUL or unpaired surrogate"));
        return false;
      }
      if (Character.isHighSurrogate(current)) {
        i++;
      }
    }
    return true;
  }

  record Prepared(String json, List<Violation> violations, Provenance provenance) {
    Prepared {
      violations = List.copyOf(violations);
    }

    boolean valid() {
      return violations.isEmpty();
    }
  }

  record Provenance(
      UUID inferenceId,
      UUID eventId,
      String hash,
      String schemaVersion,
      String backend,
      Set<String> tickers) {
    Provenance {
      tickers = Set.copyOf(tickers);
    }
  }
}

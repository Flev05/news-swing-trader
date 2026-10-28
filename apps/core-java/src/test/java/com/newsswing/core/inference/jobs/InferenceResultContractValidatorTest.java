package com.newsswing.core.inference.jobs;

import static org.assertj.core.api.Assertions.*;

import com.newsswing.support.InferenceJobFixtures;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;

class InferenceResultContractValidatorTest {
  static InferenceResultContractValidator validator;
  static String valid;

  @BeforeAll
  static void setup() throws Exception {
    try (var resource =
        new ClassPathResource("contracts/v1/inference-result.schema.json").getInputStream()) {
      validator =
          new InferenceResultContractValidator(
              new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    var event =
        """
        {"event_id":"11111111-1111-4111-8111-111111111111",
         "content_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "affected_symbols":["ACME"]}
        """;
    valid =
        InferenceJobFixtures.resultPayload(
            event, UUID.randomUUID(), "fake", Instant.parse("2026-01-15T14:30:30Z"));
  }

  static Stream<String> invalidJson() {
    return Stream.of(
        "",
        " ",
        "{",
        "null",
        "[]",
        valid + " {}",
        valid + "garbage",
        valid.replace("\"model\":", "\"model\":{},\"model\":"),
        valid.replace("\"backend\":\"fake\"", "\"backend\":\"fake\",\"backend\":\"fake\""));
  }

  @ParameterizedTest
  @MethodSource("invalidJson")
  void rejectsStrictInvalidJson(String input) {
    assertThat(validator.prepare(input).valid()).isFalse();
  }

  static Stream<String> badUnicode() {
    return Stream.of(
        "\\u0000",
        "\\ud800",
        "\\udc00",
        "\\ud800x",
        "\\ud800\\ud800",
        String.valueOf((char) 0xd800),
        String.valueOf((char) 0xdc00));
  }

  @ParameterizedTest
  @MethodSource("badUnicode")
  void checksDecodedUnicode(String replacement) {
    var result = validator.prepare(valid.replace("explicit-test-fixture", replacement));
    assertThat(result.valid()).isFalse();
    assertThat(result.violations()).isNotEmpty();
  }

  static Stream<String> goodUnicode() {
    return Stream.of(
        "caf\\u00e9",
        "\\ud83d\\ude80",
        "\\\\u0000",
        "\\\\ud800",
        "rocket " + new String(Character.toChars(0x1f680)));
  }

  @ParameterizedTest
  @MethodSource("goodUnicode")
  void preservesUnicodeAndLiteralBackslashes(String replacement) {
    String input = valid.replace("explicit-test-fixture", replacement);
    var prepared = validator.prepare(input);
    assertThat(prepared.valid()).isTrue();
    assertThat(prepared.json()).isEqualTo(input);
  }

  @Test
  void checksPropertyNamesAndNullAndReturnsImmutableDiagnostics() {
    assertThat(validator.prepare(null).valid()).isFalse();
    var prepared = validator.prepare(valid.replace("\"name\"", "\"na\\u0000me\""));
    assertThat(prepared.violations()).anyMatch(v -> v.keyword().equals("unicode"));
    assertThatThrownBy(() -> prepared.violations().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void reusesAuthoritativeResultCorpus() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.isDirectory(root.resolve("contracts"))) {
      root = root.getParent();
    }
    var json = tools.jackson.databind.json.JsonMapper.builder().build();
    var cases =
        json.readTree(Files.readString(root.resolve("tests/fixtures/contracts/v1/corpus.json")))
            .path("cases");
    for (var fixture : cases) {
      if (fixture.path("schema").asString().endsWith("inference-result.schema.json")) {
        var result =
            validator.prepare(Files.readString(root.resolve(fixture.path("fixture").asString())));
        assertThat(result.valid())
            .as(fixture.path("name").asString())
            .isEqualTo(fixture.path("expected_valid").asBoolean());
      }
    }
  }
}

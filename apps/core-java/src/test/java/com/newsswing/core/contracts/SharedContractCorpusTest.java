package com.newsswing.core.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SharedContractCorpusTest {

  private static final Path REPOSITORY_ROOT = findRepositoryRoot();
  private static final Path CORPUS =
      REPOSITORY_ROOT.resolve("tests/fixtures/contracts/v1/corpus.json");
  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

  @Test
  void corpusIsDeterministicAndCoversBothContracts() throws IOException {
    JsonNode cases = JSON_MAPPER.readTree(Files.readString(CORPUS)).get("cases");
    Set<String> names = new HashSet<>();
    Set<String> schemas = new HashSet<>();

    cases.forEach(
        fixtureCase -> {
          names.add(fixtureCase.get("name").asString());
          schemas.add(fixtureCase.get("schema").asString());
          assertThat(fixtureCase.get("expected_violations").isEmpty())
              .isEqualTo(fixtureCase.get("expected_valid").asBoolean());
          if (!fixtureCase.get("expected_valid").asBoolean()) {
            assertThat(fixtureCase.get("intended_rule").isString()).isTrue();
          }
        });

    assertThat(names).hasSize(cases.size());
    assertThat(schemas)
        .containsExactlyInAnyOrder(
            "contracts/v1/news-event.schema.json", "contracts/v1/inference-result.schema.json");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("contractCases")
  void validatesSharedCorpus(
      String name,
      String schemaRelativePath,
      String fixtureRelativePath,
      boolean expectedValid,
      JsonNode expectedViolations)
      throws IOException {
    Path schema = REPOSITORY_ROOT.resolve(schemaRelativePath);
    Path fixture = REPOSITORY_ROOT.resolve(fixtureRelativePath);

    assertThat(schema).as("%s schema", name).isRegularFile();
    assertThat(fixture).as("%s fixture", name).isRegularFile();

    var errors = SharedContractValidator.validate(schema, fixture);
    assertThat(errors.isEmpty())
        .as("%s expected valid=%s, errors=%s", name, expectedValid, errors)
        .isEqualTo(expectedValid);

    Set<String> actualRules =
        errors.stream()
            .map(error -> error.keyword() + "@" + error.instancePath())
            .collect(Collectors.toSet());
    Set<String> intendedRules =
        expectedViolations
            .valueStream()
            .map(
                violation ->
                    violation.get("keyword").asString()
                        + "@"
                        + violation.get("instance_path").asString())
            .collect(Collectors.toSet());
    assertThat(actualRules)
        .as("%s must fail only for its intended rule and location", name)
        .containsExactlyInAnyOrderElementsOf(intendedRules);
  }

  static Stream<Arguments> contractCases() throws IOException {
    JsonNode cases = JSON_MAPPER.readTree(Files.readString(CORPUS)).get("cases");

    return cases
        .valueStream()
        .map(
            fixtureCase ->
                Arguments.of(
                    fixtureCase.get("name").asString(),
                    fixtureCase.get("schema").asString(),
                    fixtureCase.get("fixture").asString(),
                    fixtureCase.get("expected_valid").asBoolean(),
                    fixtureCase.get("expected_violations")));
  }

  private static Path findRepositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.isDirectory(candidate.resolve("contracts"))
          && Files.isDirectory(candidate.resolve("apps"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Could not locate repository root");
  }
}

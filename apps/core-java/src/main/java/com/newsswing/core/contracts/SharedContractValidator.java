package com.newsswing.core.contracts;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validates JSON payloads against an explicit authoritative contract file. */
public final class SharedContractValidator {

  private static final SchemaRegistry SCHEMA_REGISTRY =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

  private SharedContractValidator() {}

  public static List<Violation> validate(Path schemaPath, Path payloadPath) throws IOException {
    return compile(Files.readString(schemaPath)).validate(Files.readString(payloadPath));
  }

  public static List<Violation> validate(String schemaData, String payloadData) throws IOException {
    return compile(schemaData).validate(payloadData);
  }

  /** Compiles and parses authoritative schema data for reuse across payload validations. */
  public static CompiledContract compile(String schemaData) throws IOException {
    JsonNode schemaDocument = JSON_MAPPER.readTree(schemaData);
    Schema schema = SCHEMA_REGISTRY.getSchema(schemaData, InputFormat.JSON);
    return new CompiledContract(schema, schemaDocument);
  }

  public static final class CompiledContract {

    private final Schema schema;
    private final JsonNode schemaDocument;

    private CompiledContract(Schema schema, JsonNode schemaDocument) {
      this.schema = schema;
      this.schemaDocument = schemaDocument;
    }

    public List<Violation> validate(String payloadData) throws IOException {
      JsonNode payload = JSON_MAPPER.readTree(payloadData);
      List<Violation> violations =
          new ArrayList<>(
              schema
                  .validate(
                      payloadData,
                      InputFormat.JSON,
                      executionContext ->
                          executionContext.executionConfig(
                              executionConfig -> executionConfig.formatAssertionsEnabled(true)))
                  .stream()
                  .map(
                      error ->
                          new Violation(
                              error.getKeyword(),
                              error.getInstanceLocation().toString(),
                              error.getMessage()))
                  .toList());

      addUniqueByViolations(schemaDocument, payload, violations);
      violations.sort(
          Comparator.comparing(Violation::instancePath)
              .thenComparing(Violation::keyword)
              .thenComparing(Violation::message));
      return List.copyOf(violations);
    }
  }

  private static void addUniqueByViolations(
      JsonNode schema, JsonNode payload, List<Violation> violations) {
    JsonNode properties = schema.get("properties");
    JsonNode resultsSchema = properties == null ? null : properties.get("ticker_results");
    JsonNode results = payload.get("ticker_results");
    if (resultsSchema == null || results == null || !results.isArray()) {
      return;
    }

    JsonNode uniqueBy = resultsSchema.get("x-unique-by");
    if (uniqueBy == null || !uniqueBy.isString()) {
      return;
    }

    String field = uniqueBy.asString();
    Set<String> seen = new HashSet<>();
    for (JsonNode result : results) {
      JsonNode value = result.get(field);
      if (value != null && value.isString() && !seen.add(value.asString())) {
        violations.add(
            new Violation(
                "x-unique-by",
                "/ticker_results",
                "duplicate " + field + " value '" + value.asString() + "'"));
      }
    }
  }

  public record Violation(String keyword, String instancePath, String message) {}
}

package com.newsswing.core.news.ingestion;

import com.newsswing.core.contracts.SharedContractValidator;
import com.newsswing.core.contracts.SharedContractValidator.CompiledContract;
import com.newsswing.core.contracts.SharedContractValidator.Violation;
import java.io.IOException;
import java.util.List;

/** Cached validation of canonical payloads against the authoritative News Event v1 schema. */
final class NewsEventContractValidator {

  private final CompiledContract contract;

  NewsEventContractValidator(String schemaData) throws IOException {
    this.contract = SharedContractValidator.compile(schemaData);
  }

  List<Violation> validate(String payloadJson) {
    try {
      return contract.validate(payloadJson);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Unable to validate canonical News Event v1 payload", exception);
    }
  }
}

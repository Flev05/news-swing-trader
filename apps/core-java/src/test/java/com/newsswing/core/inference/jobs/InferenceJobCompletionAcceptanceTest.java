package com.newsswing.core.inference.jobs;

import static com.newsswing.core.inference.jobs.InferenceJobLifecycleService.*;
import static org.assertj.core.api.Assertions.*;

import com.newsswing.core.news.domain.NewsEvents.*;
import com.newsswing.core.news.ingestion.NewsIngestionResult;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class InferenceJobCompletionAcceptanceTest extends CompletionTestSupport {
  @ParameterizedTest
  @ValueSource(strings = {"fake", "llama.cpp", "ollama"})
  void acceptsStoredProvenanceAndPolicyWithAuthoritativeTime(String backend) {
    var q = queue("accept", 1);
    jdbc.update(
        "UPDATE trader.inference_jobs SET expected_backend=?, max_assignments=2 WHERE job_id=?",
        backend,
        q.jobId());
    var a = claim();
    String p = change(payload(a), "/generated_at", "2099-01-01T00:00:00Z");
    time.set(NOW.plusSeconds(1));
    var receipt = ((Accepted) service.complete(a.ref(), p)).receipt();
    assertThat(receipt)
        .isEqualTo(
            new CompletionReceipt(q.jobId(), q.eventId(), a.inferenceId(), NOW.plusSeconds(1)));
    assertThat(status(q.jobId())).isEqualTo("SUCCEEDED");
    assertThat(count(q.jobId())).isEqualTo(1);
    noOwner(q.jobId());
    var row = jdbc.queryForMap("SELECT * FROM trader.inference_results WHERE job_id=?", q.jobId());
    assertThat(row.get("assignment_number")).isEqualTo(1);
    assertThat(row.get("accepted_owner_token")).isEqualTo(a.ref().ownershipToken());
    assertThat(
            jdbc.queryForObject(
                "SELECT max_assignments FROM trader.inference_jobs WHERE job_id=?",
                Integer.class,
                q.jobId()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                    "SELECT terminal_at FROM trader.inference_jobs WHERE job_id=?",
                    Timestamp.class,
                    q.jobId())
                .toInstant())
        .isEqualTo(receipt.acceptedAt());
    assertThat(resultCount(q.jobId())).isEqualTo(1);
  }

  static Stream<Arguments> invalidOwners() {
    return Stream.of(
            "null",
            "malformed",
            "duplicate",
            "nestedDuplicate",
            "trailing",
            "format",
            "closed",
            "version",
            "duplicateTicker",
            "nul",
            "high",
            "low",
            "rawHigh")
        .flatMap(
            kind -> Stream.of("live", "wrong", "accepted").map(owner -> Arguments.of(kind, owner)));
  }

  static String badInput(String p, String kind) {
    return switch (kind) {
      case "null" -> null;
      case "malformed" -> "{";
      case "duplicate" -> p.replace("\"model\":", "\"model\":{},\"model\":");
      case "nestedDuplicate" ->
          p.replace("\"backend\":\"fake\"", "\"backend\":\"fake\",\"backend\":\"fake\"");
      case "trailing" -> p + " {}";
      case "format" -> change(p, "/generated_at", "not-a-timestamp");
      case "closed" -> p.replace("\"model\":{", "\"model\":{\"unexpected\":true,");
      case "version" -> change(p, "/schema_version", "2.0.0");
      case "duplicateTicker" -> {
        var doc = (ObjectNode) JSON.readTree(p);
        var tickers = (ArrayNode) doc.path("ticker_results");
        tickers.add(tickers.get(0).deepCopy());
        yield doc.toString();
      }
      case "nul" -> incompatible(p, "\\u0000");
      case "high" -> incompatible(p, "\\ud800");
      case "low" -> incompatible(p, "\\udc00");
      case "rawHigh" -> incompatible(p, String.valueOf((char) 0xd800));
      default -> throw new AssertionError(kind);
    };
  }

  @ParameterizedTest
  @MethodSource("invalidOwners")
  void invalidInputRespectsAuthorityAndNeverCastsHistoricalFailures(String kind, String owner) {
    var q = queue("invalid", 1);
    var a = claim();
    String p = payload(a);
    if (owner.equals("accepted")) {
      assertThat(service.complete(a.ref(), p)).isInstanceOf(Accepted.class);
    }
    String before = snapshot(q.jobId());
    String results = resultSnapshot(q.jobId());
    repository.observe(
        (point, id) -> {
          if (point.equals("payloadComparison") || point.equals("beforeInsert")) {
            throw new AssertionError("Invalid input must not reach JSONB conversion");
          }
        });
    var ref = owner.equals("wrong") ? new AssignmentRef(q.jobId(), 1, UUID.randomUUID()) : a.ref();
    var outcome = service.complete(ref, badInput(p, kind));
    if (owner.equals("live")) {
      assertThat(outcome)
          .isInstanceOfSatisfying(
              InvalidResult.class,
              invalid -> {
                assertThat(invalid.code()).isEqualTo(FailureCode.INVALID_RESULT);
                assertThat(invalid.violations()).isNotEmpty();
              });
      assertThat(status(q.jobId())).isEqualTo("FAILED");
      assertThat(resultCount(q.jobId())).isZero();
      noOwner(q.jobId());
    } else {
      assertThat(outcome)
          .isEqualTo(
              owner.equals("accepted")
                  ? new CompletionConflict()
                  : new Rejected(RejectionReason.OWNER_MISMATCH));
      assertThat(snapshot(q.jobId())).isEqualTo(before);
      assertThat(resultSnapshot(q.jobId())).isEqualTo(results);
    }
  }

  @ParameterizedTest
  @MethodSource(
      "com.newsswing.core.inference.jobs.InferenceResultContractValidatorTest#goodUnicode")
  void validUnicodeAndLiteralBackslashesPersistAndReplay(String replacement) {
    var q = queue("unicode", 1);
    var a = claim();
    String p = incompatible(payload(a), replacement);
    var accepted = (Accepted) service.complete(a.ref(), p);
    assertThat(service.complete(a.ref(), p)).isEqualTo(new AlreadyAccepted(accepted.receipt()));
    assertThat(resultCount(q.jobId())).isEqualTo(1);
  }

  static Stream<Arguments> mismatches() {
    return Stream.of(
        Arguments.of("/inference_id", UUID.randomUUID().toString()),
        Arguments.of("/news_event_id", UUID.randomUUID().toString()),
        Arguments.of("/news_event_sha256", "b".repeat(64)),
        Arguments.of("/model/backend", "ollama"),
        Arguments.of("/ticker_results/0/ticker", "OTHER"));
  }

  @ParameterizedTest
  @MethodSource("mismatches")
  void provenanceFailureIsTerminal(String pointer, String value) {
    var q = queue("provenance", 1);
    var a = claim();
    var outcome = (InvalidResult) service.complete(a.ref(), change(payload(a), pointer, value));
    assertThat(outcome.code()).isEqualTo(FailureCode.PROVENANCE_MISMATCH);
    assertThat(outcome.violations()).anyMatch(v -> v.keyword().equals("provenance"));
    assertThat(status(q.jobId())).isEqualTo("FAILED");
    assertThat(resultCount(q.jobId())).isZero();
    noOwner(q.jobId());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "extra", "reordered"})
  void exactMultiTickerCoverageIncludesAbstention(String mode) {
    var e = event("multi", 1, NOW.plusSeconds(600));
    var q =
        (NewsIngestionResult.Queued)
            ingestion.ingest(
                new UnhashedNewsEvent(
                    e.schemaVersion(),
                    e.eventId(),
                    e.correlationId(),
                    e.causationId(),
                    e.source(),
                    e.canonicalUrl(),
                    e.sourceCreatedAt(),
                    e.sourceUpdatedAt(),
                    e.receivedAt(),
                    e.deadlineAt(),
                    e.headline(),
                    e.content(),
                    List.of("ACME", "OTHER")));
    var a = claim();
    var doc = (ObjectNode) JSON.readTree(payload(a));
    var tickers = (ArrayNode) doc.path("ticker_results");
    switch (mode) {
      case "missing" -> tickers.remove(1);
      case "extra" -> {
        var extra = (ObjectNode) tickers.get(0).deepCopy();
        extra.put("ticker", "EXTRA");
        tickers.add(extra);
      }
      default -> {
        var first = tickers.remove(0);
        tickers.add(first);
      }
    }
    var outcome = service.complete(a.ref(), doc.toString());
    assertThat(outcome)
        .isInstanceOf(mode.equals("reordered") ? Accepted.class : InvalidResult.class);
    assertThat(resultCount(q.jobId())).isEqualTo(mode.equals("reordered") ? 1 : 0);
  }

  @ParameterizedTest
  @CsvSource({"-1, true", "0, false", "1, false"})
  void leaseDecisionBoundary(long micros, boolean accepted) {
    var q = queue("lease", 1);
    var a = claim();
    String before = snapshot(q.jobId());
    time.set(a.leaseExpiresAt().plusNanos(micros * 1000));
    var outcome = service.complete(a.ref(), payload(a));
    if (accepted) {
      assertThat(outcome).isInstanceOf(Accepted.class);
    } else {
      assertThat(outcome).isEqualTo(new Rejected(RejectionReason.LEASE_EXPIRED));
      assertThat(service.complete(a.ref(), badInput(payload(a), "nul"))).isEqualTo(outcome);
      assertThat(snapshot(q.jobId())).isEqualTo(before);
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 0, 1})
  void newsDecisionBoundaryWinsOverInvalidInput(long micros) {
    var q = (NewsIngestionResult.Queued) ingestion.ingest(event("news", 1, NOW.plusSeconds(30)));
    var a = claim();
    time.set(NOW.plusSeconds(30).plusNanos(micros * 1000));
    String before = snapshot(q.jobId());
    var outcome = service.complete(a.ref(), payload(a));
    if (micros < 0) {
      assertThat(outcome).isInstanceOf(Accepted.class);
    } else {
      assertThat(outcome).isEqualTo(new Rejected(RejectionReason.NEWS_EXPIRED));
      assertThat(service.complete(a.ref(), badInput(payload(a), "high"))).isEqualTo(outcome);
      assertThat(snapshot(q.jobId())).isEqualTo(before);
    }
  }

  @Test
  void fencesSupersededAndReplacedOwnersIncludingInvalidInput() {
    var q = queue("superseded", 1);
    var a = claim();
    queue("superseded", 2);
    String before = snapshot(q.jobId());
    assertThat(service.complete(a.ref(), "{")).isEqualTo(new Rejected(RejectionReason.NOT_RUNNING));
    assertThat(snapshot(q.jobId())).isEqualTo(before);
    var second = claim();
    time.set(second.leaseExpiresAt());
    service.recoverBatch();
    var replacement = claim();
    String current = snapshot(replacement.ref().jobId());
    assertThat(service.complete(second.ref(), badInput(payload(second), "nul")))
        .isEqualTo(new Rejected(RejectionReason.OWNER_MISMATCH));
    assertThat(snapshot(replacement.ref().jobId())).isEqualTo(current);
  }

  @Test
  void lostAcknowledgementReplaysOriginalReceiptAfterExpiryAndSupersession() {
    var q = queue("lost-ack", 1);
    var a = claim();
    String p = payload(a);
    var receipt = ((Accepted) service.complete(a.ref(), p)).receipt(); // acknowledgement discarded
    String before = snapshot(q.jobId());
    String stored = resultSnapshot(q.jobId());
    queue("lost-ack", 2);
    time.set(NOW.plusSeconds(601));
    String reordered = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.readTree(p));
    assertThat(service.complete(a.ref(), reordered)).isEqualTo(new AlreadyAccepted(receipt));
    assertThat(service.complete(a.ref(), change(p, "/model/name", "different")))
        .isEqualTo(new CompletionConflict());
    assertThat(service.complete(new AssignmentRef(q.jobId(), 1, UUID.randomUUID()), "{"))
        .isEqualTo(new Rejected(RejectionReason.OWNER_MISMATCH));
    assertThat(snapshot(q.jobId())).isEqualTo(before);
    assertThat(resultSnapshot(q.jobId())).isEqualTo(stored);
    assertThat(service.findCurrentEligibleResult(q.newsItemId())).isEmpty();
  }

  @Test
  void malformedCommandsAndAmbientTransactionsHavePriority() {
    var q = queue("commands", 1);
    var a = claim();
    var refs =
        Arrays.asList(
            null,
            new AssignmentRef(null, 1, a.ref().ownershipToken()),
            new AssignmentRef(q.jobId(), 0, a.ref().ownershipToken()),
            new AssignmentRef(q.jobId(), 1, null));
    String before = snapshot(q.jobId());
    for (var ref : refs) {
      assertThat(service.complete(ref, "{"))
          .isEqualTo(new Rejected(RejectionReason.INVALID_COMMAND));
    }
    assertThat(service.complete(new AssignmentRef(UUID.randomUUID(), 1, UUID.randomUUID()), "{"))
        .isEqualTo(new Rejected(RejectionReason.NOT_FOUND));
    assertThatThrownBy(() -> service.findCurrentEligibleResult(null))
        .isInstanceOf(IllegalArgumentException.class);
    new TransactionTemplate(manager)
        .executeWithoutResult(
            ignored -> {
              assertThatThrownBy(() -> service.complete(null, null))
                  .isInstanceOf(IllegalTransactionStateException.class);
              assertThatThrownBy(() -> service.findCurrentEligibleResult(null))
                  .isInstanceOf(IllegalTransactionStateException.class);
            });
    assertThat(snapshot(q.jobId())).isEqualTo(before);
  }

  @Test
  void arrayOrderAndDecimalStringValuesRemainSignificantForReplay() {
    var e = event("values", 1, NOW.plusSeconds(600));
    ingestion.ingest(
        new UnhashedNewsEvent(
            e.schemaVersion(),
            e.eventId(),
            e.correlationId(),
            e.causationId(),
            e.source(),
            e.canonicalUrl(),
            e.sourceCreatedAt(),
            e.sourceUpdatedAt(),
            e.receivedAt(),
            e.deadlineAt(),
            e.headline(),
            e.content(),
            List.of("ACME", "OTHER")));
    var a = claim();
    var doc = (ObjectNode) JSON.readTree(payload(a));
    var tickers = (ArrayNode) doc.path("ticker_results");
    var ticker = (ObjectNode) tickers.get(0);
    ticker.put("abstain", false);
    ticker.put("event_type", "earnings");
    ticker.put("direction", "positive");
    ticker.put("materiality", "high");
    ticker.put("relevance", "direct");
    ticker.putArray("reason_codes").add("earnings_above_consensus");
    var fact = ticker.putArray("numeric_facts").addObject();
    fact.put("metric", "eps");
    fact.put("actual", "1.00");
    fact.put("unit", "currency");
    fact.put("currency", "USD");
    String p = doc.toString();
    var receipt = ((Accepted) service.complete(a.ref(), p)).receipt();
    var reordered = (ObjectNode) JSON.readTree(p);
    var reorderedTickers = (ArrayNode) reordered.path("ticker_results");
    reorderedTickers.add(reorderedTickers.remove(0));
    assertThat(service.complete(a.ref(), reordered.toString())).isEqualTo(new CompletionConflict());
    assertThat(
            service.complete(a.ref(), change(p, "/ticker_results/0/numeric_facts/0/actual", "1.0")))
        .isEqualTo(new CompletionConflict());
    // Reverse every object property order, without changing strings or arrays.
    var reversed = reverseObjects(JSON.readTree(p));
    assertThat(service.complete(a.ref(), reversed.toPrettyString()))
        .isEqualTo(new AlreadyAccepted(receipt));
  }

  private static tools.jackson.databind.JsonNode reverseObjects(
      tools.jackson.databind.JsonNode node) {
    if (node.isObject()) {
      var result = JSON.createObjectNode();
      var entries = new ArrayList<>(node.properties());
      Collections.reverse(entries);
      entries.forEach(entry -> result.set(entry.getKey(), reverseObjects(entry.getValue())));
      return result;
    }
    if (node.isArray()) {
      var result = JSON.createArrayNode();
      node.forEach(value -> result.add(reverseObjects(value)));
      return result;
    }
    return node;
  }

  @Test
  void rejectedNewerAdmissionAtCapacityPreservesCompletionAuthority() {
    var q = queue("capacity-owner", 1);
    var a = claim();
    for (int i = 0; i < 4; i++) {
      queue("full-" + i, 1);
    }
    String before = snapshot(q.jobId());
    assertThatThrownBy(() -> queue("capacity-owner", 2))
        .isInstanceOf(com.newsswing.core.news.ingestion.NewsIngestionException.class);
    assertThat(snapshot(q.jobId())).isEqualTo(before);
    assertThat(service.complete(a.ref(), payload(a))).isInstanceOf(Accepted.class);
    assertThat(pending()).isEqualTo(4);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void detectsBrokenSuccessResultPairingAsInfrastructure(boolean missingResult) {
    var q = queue("broken-pair", 1);
    var a = claim();
    if (missingResult) {
      jdbc.update(
          """
          UPDATE trader.inference_jobs SET status='SUCCEEDED', terminal_at=?,
          ownership_token=NULL, assignment_inference_id=NULL, assigned_at=NULL, lease_expires_at=NULL
          WHERE job_id=?
          """,
          Timestamp.from(NOW),
          q.jobId());
    } else {
      com.newsswing.support.InferenceJobFixtures.insertResult(
          jdbc, q.jobId(), a.inferenceId(), a.ref().ownershipToken(), NOW);
    }
    String before = snapshot(q.jobId());
    String stored = resultSnapshot(q.jobId());
    assertThatThrownBy(() -> service.complete(a.ref(), payload(a)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(snapshot(q.jobId())).isEqualTo(before);
    assertThat(resultSnapshot(q.jobId())).isEqualTo(stored);
  }

  @Test
  void obsoleteRevisionPrecedesPreparedValidationAndExpiry() {
    var q = queue("obsolete-check", 1);
    var a = claim();
    queue("obsolete-check", 2);
    // Model an obsolete RUNNING row awaiting cleanup; ownership is otherwise authentic.
    jdbc.update(
        """
        UPDATE trader.inference_jobs SET status='RUNNING', ownership_token=?, assignment_inference_id=?,
        assigned_at=?, lease_expires_at=?, terminal_at=NULL, superseded_at=NULL, superseded_by_event_id=NULL
        WHERE job_id=?
        """,
        a.ref().ownershipToken(),
        a.inferenceId(),
        Timestamp.from(NOW),
        Timestamp.from(a.leaseExpiresAt()),
        q.jobId());
    time.set(NOW.plusSeconds(601));
    String before = snapshot(q.jobId());
    assertThat(service.complete(a.ref(), badInput(payload(a), "nul")))
        .isEqualTo(new Rejected(RejectionReason.REVISION_OBSOLETE));
    assertThat(snapshot(q.jobId())).isEqualTo(before);
  }

  static Stream<Arguments> rollbackCases() {
    return Stream.of("insert", "update", "zeroUpdate", "commit")
        .flatMap(
            stage ->
                Stream.of(false, true)
                    .filter(invalid -> !invalid || !stage.equals("insert"))
                    .map(invalid -> Arguments.of(stage, invalid)));
  }

  @ParameterizedTest
  @MethodSource("rollbackCases")
  void infrastructureFailureRollsBackBothResultAndJob(String stage, boolean invalid) {
    var q = queue("rollback", 1);
    var a = claim();
    String before = snapshot(q.jobId());
    String table = stage.equals("insert") ? "inference_results" : "inference_jobs";
    String event = stage.equals("insert") ? "INSERT" : "UPDATE";
    jdbc.execute(
        """
        CREATE FUNCTION trader.m3a3_fail() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
        """
            + (stage.equals("zeroUpdate")
                ? "RETURN NULL;"
                : "RAISE EXCEPTION 'forced infrastructure failure' USING ERRCODE='23514';")
            + " END; $$");
    jdbc.execute(
        stage.equals("commit")
            ? "CREATE CONSTRAINT TRIGGER m3a3_fail AFTER UPDATE ON trader.inference_jobs DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION trader.m3a3_fail()"
            : "CREATE TRIGGER m3a3_fail BEFORE "
                + event
                + " ON trader."
                + table
                + " FOR EACH ROW EXECUTE FUNCTION trader.m3a3_fail()");
    try {
      assertThatThrownBy(() -> service.complete(a.ref(), invalid ? "{" : payload(a)))
          .isInstanceOf(RuntimeException.class);
      assertThat(snapshot(q.jobId())).isEqualTo(before);
      assertThat(resultCount(q.jobId())).isZero();
    } finally {
      jdbc.execute("DROP TRIGGER m3a3_fail ON trader." + table);
      jdbc.execute("DROP FUNCTION trader.m3a3_fail()");
    }
    assertThat(service.complete(a.ref(), payload(a))).isInstanceOf(Accepted.class);
  }

  @Test
  void actualUniqueConstraintFailureIsInfrastructureAndRollsBack() {
    var first = queue("unique-one", 1);
    var one = claim();
    service.complete(one.ref(), payload(one));
    var second = queue("unique-two", 1);
    var two = claim();
    jdbc.update(
        "UPDATE trader.inference_jobs SET assignment_inference_id=? WHERE job_id=?",
        one.inferenceId(),
        second.jobId());
    String p = change(payload(two), "/inference_id", one.inferenceId().toString());
    String before = snapshot(second.jobId());
    assertThatThrownBy(() -> service.complete(two.ref(), p))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(snapshot(second.jobId())).isEqualTo(before);
    assertThat(resultCount(second.jobId())).isZero();
    assertThat(resultCount(first.jobId())).isEqualTo(1);
  }
}

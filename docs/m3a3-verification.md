# M3A3 verification report

Implemented Java-only strict inference-result preparation, atomic completion, historical replay,
and current eligible-result reads. Verification date: 2026-10-28.

Verification baseline and pre-publication HEAD: `d914217030aeebf4066e6ef455ec7efb7806b577`.
Preflight confirmed the requested HEAD and a clean working tree/index.
This report records verification before staging and publication. The Git state below is historical;
at verification, changes were uncommitted and unstaged, and nothing had been pushed.

## Verification gates

| Gate | Actual result |
| --- | --- |
| `make doctor` | Passed |
| Initial planned targeted run | 243 Java cases passed; zero failures/errors/skips |
| Final planned targeted run | 248 Java cases passed; zero failures/errors/skips |
| `make format` | Passed; Python files unchanged |
| `make lint` | Passed: Java compile/Spotless, Compose validation, Ruff, mypy |
| `make test` Java | 369 passed; zero failures/errors/skips |
| `make test` Python | 23 passed |
| Separate `make migration-smoke` | 1 passed; zero failures/errors/skips |
| `git diff --check` | Passed |
| Protected-path comparison with baseline | Passed; no differences |
| Verification index | Empty |

The 369 Java cases retain all 237 baseline cases and add 132:
23 strict-validator cases, 77 completion acceptance cases, 19 completion concurrency cases,
4 completion restart cases, 8 eligible-result cases, and 1 real HTTP compatibility case.
The targeted total consists of those 132 additions plus 116 existing lifecycle, contract,
restart, and HTTP cases. Migration smoke repeats a case already included in the full total.

All Java changes were formatted before the full test run. Subsequent changes only finish
checkpoint documentation and this report. No test failures required implementation repair.
No full suite was run during planning.

## Implemented semantics

- The public Java boundary adds complete(AssignmentRef, resultJson) and
  findCurrentEligibleResult(newsItemId). Both reject ambient transactions.
- Completion operates on the original immutable String. Strict one-document parsing rejects
  duplicate decoded property names and trailing documents/garbage. A recursive decoded-string
  check rejects NUL and unpaired surrogates in property names and values, while preserving
  valid supplementary characters and literal backslash sequences.
- One cached SharedContractValidator.CompiledContract validates authoritative Result v1,
  mandatory formats, closed objects, conditional rules, and x-unique-by.
  Private prepared records contain only immutable strings, scalar provenance, and copied collections.
- Completion locks item -> NEWS_INFERENCE admission mutex -> job in READ COMMITTED, using
  existing local two-second lock/five-second statement timeouts.
- Historical acceptance is resolved before current freshness. Accepting assignment/token must
  match. Any prepared parsing/schema/representability failure returns CompletionConflict before
  JSONB conversion. Otherwise PostgreSQL JSONB equality determines AlreadyAccepted versus conflict.
- Without historical acceptance, precedence is missing job, non-RUNNING, assignment/token
  mismatch, obsolete revision, expired news, expired lease, invalid input, provenance mismatch,
  then acceptance. Equality at a deadline is expired.
- Provenance binds inference/event identity, relational content hash, stored input/event schema
  versions, result version, stored backend policy, and exact ticker coverage. It is prepared
  under locks before sampling the existing database lifecycle time source.
  Worker generated_at/model/extraction claims confer no authority.
- Invalid live-owner output terminally fails with INVALID_RESULT or PROVENANCE_MISMATCH,
  clearing ownership and storing no result. Rejected owners cannot fail another assignment.
- Successful insertion and guarded RUNNING -> SUCCEEDED transition share one transaction.
  Accepting assignment/token/time are persisted separately from the unchanged v1 payload.
  Ownership and last_failure_code are cleared; assignment count and policy remain intact.
  TransactionTemplate delivers outcomes only after commit.
- Database, conversion, constraint, timeout, guarded-update, and commit failures remain
  infrastructure exceptions and roll back. No database exceptions are converted into invalid output.
- Eligible reads own a read-only REPEATABLE READ transaction, explicitly establish a snapshot,
  sample lifecycle time once, and join only latest revision + SUCCEEDED + result with a future
  news deadline. They take no row locks, expose no ownership token, and never fall back or recover.
- Inconsistent accepted-result/SUCCEEDED pairing is an infrastructure invariant failure.
  Durable acceptance and replay do not claim exactly-once execution.

## Acceptance evidence

| Area | Evidence |
| --- | --- |
| Acceptance | All three stored backend policies, nondefault assignment policy, server time, multi-ticker abstention, exact identities, cleared ownership, preserved assignment count |
| Strict preparation | Malformed/null/blank/root-invalid input, duplicate root/nested keys, trailing document/garbage, formats, closed objects, schema version, shared authoritative result corpus |
| Unicode | Escaped NUL, escaped/raw isolated high/low surrogates and invalid pairs; valid escaped/raw supplementary characters, accented text, literal backslash-u sequences |
| Authority matrix | 13 invalid-input variants across live, wrong, and historical owners; repository probes forbid insertion/comparison casts on invalid preparation |
| Provenance | Independent inference ID, event ID, hash, backend, ticker mismatch; missing/extra/duplicate ticker handling; stored version binding and existing schema constraints |
| Precedence | Malformed assignment/ambient transaction precedence; obsolete revision before validation/expiry; news before lease; microsecond before/at/after boundaries |
| Replay equality | Object reversal/pretty whitespace accepted; array reorder and decimal string 1.00 versus 1.0 conflict; original receipt and durable rows unchanged |
| Concurrency | Identical/conflicting completions; both orders versus renewal, recoverable/permanent failure, recovery/reassignment, and ingestion |
| Commit visibility | Before-commit latch keeps receipt unavailable and result/success invisible; valid decision can commit after expiry |
| Recovery/rollback race | Recovery skips the item locked by tentative acceptance; rolled-back insertion permits waiting renewal |
| Infrastructure rollback | Insert failure, actual unique constraint failure, terminal-update failure, zero-row guard, deferred commit failure, validation-failure persistence failure, lock timeout |
| Admission | Completion before/after newer admission; capacity-rejected admission preserves completion authority |
| Restart | Four restart/expiry/supersession combinations fully close contexts and pools against unchanged PostgreSQL; policy changes cannot alter historical receipt |
| Eligibility | Missing/nonterminal/failed/current-success cases, strict expiry, newer pending/failed/successful revisions, no historical fallback |
| Snapshot/nonlocking read | Admission/acceptance committed after snapshot cannot mix into that read; repeatable-read/read-only connection verified; item/mutex/job locks do not block reads |
| Compatibility | Real Java completion and invalid-result failure retain exact HTTP field/cache behavior; result route remains absent; all existing health, ingestion, lifecycle, capacity, restart, and migration tests pass |

Tests use isolated PostgreSQL 18.6 Testcontainers, explicit test configurations, controlled
microsecond-aligned lifecycle time, latches/barriers, bounded futures, and bounded executor cleanup.
No sleeps or retried business assertions were introduced. Failure triggers exist only in isolated
test databases and are removed through finally cleanup.

## Concrete lost-acknowledgement example

The test lostAcknowledgementReplaysOriginalReceiptAfterExpiryAndSupersession uses item lost-ack,
revision r1, ticker ACME, assignment number 1, and generated token T1/inference ID I1:

| Step | Controlled UTC time | Result |
| --- | --- | --- |
| Claim r1 | 2026-01-15 14:30:30.123456 | RUNNING, assignment 1/T1/I1; lease ends 14:31:30.123456 |
| Complete payload P | 14:30:30.123456 | Accepted receipt R with I1 and this database acceptance time; SUCCEEDED and one immutable result commit |
| Lose acknowledgement | After commit | Test models the caller not receiving the committed acknowledgement |
| Admit r2, then advance time | r1 deadline 14:40:30.123456; retry time 14:40:31.123456 | Historical r1 stays SUCCEEDED; latest pointer is r2 |
| Retry P with assignment 1/T1, pretty whitespace | 14:40:31.123456 | AlreadyAccepted(R), retaining the original acceptance time despite expiry and supersession |
| Retry changed model.name with assignment 1/T1 | Same time | CompletionConflict; original payload P, receipt R, and job row remain unchanged |

The current eligible-result query returns empty after the newer revision admission. Historical
replay acknowledges the original acceptance independently of current eligibility.

## Scope, deviations, and migrations

No scope deviations and no new migration. V3 already provides the immutable result table,
accepting credentials/time, relational provenance constraints, and required states.

V1-V3, authoritative contracts/corpus, Python, dependency pins, infrastructure, Makefile,
and existing HTTP production representations remain unchanged.
No new direct dependency, worker/result endpoint, worker process, scheduler, model integration,
Redis, broker, signal, risk, portfolio, or order behavior was added.

Test utilities were extracted into CompletionTestSupport, and existing restart test helpers
became package-visible for reuse. These are test-only support changes.

## Changed files and Git status at verification

13 modified tracked files, all unstaged:

```text
AGENTS.md
README.md
apps/core-java/src/main/java/com/newsswing/core/inference/jobs/InferenceJobLifecycleConfiguration.java
apps/core-java/src/main/java/com/newsswing/core/inference/jobs/InferenceJobLifecycleRepository.java
apps/core-java/src/main/java/com/newsswing/core/inference/jobs/InferenceJobLifecycleService.java
apps/core-java/src/main/java/com/newsswing/core/inference/jobs/JdbcInferenceJobLifecycleService.java
apps/core-java/src/test/java/com/newsswing/core/inference/jobs/InferenceJobRestartRecoveryAcceptanceTest.java
apps/core-java/src/test/java/com/newsswing/core/inference/jobs/LifecycleTestSupport.java
apps/core-java/src/test/java/com/newsswing/core/news/api/NewsHttpApiIntegrationTest.java
apps/core-java/src/test/java/com/newsswing/support/InferenceJobFixtures.java
docs/adr/0010-durable-inference-job-lifecycle.md
docs/architecture.md
docs/m3a-design.md
```

8 new untracked files:

```text
apps/core-java/src/main/java/com/newsswing/core/inference/jobs/InferenceResultContractValidator.java
apps/core-java/src/test/java/com/newsswing/core/inference/jobs/CompletionTestSupport.java
apps/core-java/src/test/java/com/newsswing/core/inference/jobs/CurrentEligibleResultAcceptanceTest.java
apps/core-java/src/test/java/com/newsswing/core/inference/jobs/InferenceJobCompletionAcceptanceTest.java
apps/core-java/src/test/java/com/newsswing/core/inference/jobs/InferenceJobCompletionConcurrencyTest.java
apps/core-java/src/test/java/com/newsswing/core/inference/jobs/InferenceJobCompletionRestartAcceptanceTest.java
apps/core-java/src/test/java/com/newsswing/core/inference/jobs/InferenceResultContractValidatorTest.java
docs/m3a3-verification.md
```

At verification, HEAD was unchanged; index empty; 21 total changed/new files; no commit or push.

## Reproduction commands

Run from WSL Ubuntu at the repository root:

```bash
make doctor
./mvnw -f apps/core-java \
  -Dtest=InferenceResultContractValidatorTest,InferenceJobCompletionAcceptanceTest,InferenceJobCompletionConcurrencyTest,InferenceJobCompletionRestartAcceptanceTest,CurrentEligibleResultAcceptanceTest,InferenceJobLifecycleAcceptanceTest,InferenceJobLifecycleConcurrencyTest,InferenceJobRestartRecoveryAcceptanceTest,SharedContractCorpusTest,NewsHttpApiIntegrationTest test
make format
make lint
make test
make migration-smoke
git diff --check
git diff --exit-code d914217030aeebf4066e6ef455ec7efb7806b577 -- \
  apps/core-java/src/main/resources/db/migration \
  contracts tests/fixtures/contracts apps/ai-worker infra \
  apps/core-java/pom.xml Makefile
git diff --cached --quiet
git rev-parse HEAD
git status --short
```

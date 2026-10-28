# M3B1 verification

## Baseline and scope

Implemented against HEAD `21a61f8c72185be8a115be6eac8783899ebe01d6`.
Linux Git confirmed a clean working tree and index before implementation.
The verified baseline was 369 Java and 23 Python tests.

M3B1 adds Java worker HTTP transport only. Controllers call the existing lifecycle boundary
without transactions, SQL, recovery loops, ownership decisions, or result validation.
The complete [protocol](m3b-worker-protocol.md) specifies all envelopes, statuses and codes.
The [M3B design](m3b-design.md) records future M3B2 work and the required blocked-I/O deadline
clarification. No Python worker or Python deadline behavior has been implemented.

## Implemented protocol

All routes are POST under `/api/v1/worker/`, on the existing enforced 127.0.0.1 listener.

| Route | Command | Successful outcomes (HTTP 200) |
| --- | --- | --- |
| claim | empty object | CLAIMED with Assignment; NO_WORK with Retry-After: 2 |
| renew | assignment reference | RENEWED with lease_expires_at |
| fail | assignment reference and RECOVERABLE/PERMANENT kind | RETRY_WAITING; FAILED with failure_code |
| complete | assignment reference and result_json string | ACCEPTED; ALREADY_ACCEPTED with original receipt |

References contain job_id, positive integer assignment_number, and ownership_token.
Claim adds the stored news_event_json string, inference_id, expected_backend,
result_schema_version, and lease_expires_at. Receipt fields are job_id, news_event_id,
inference_id, and accepted_at. Dedicated transport projections keep service carriers separate
from wire documents.

Error mappings are exact:

- 400: MALFORMED_JSON, UNKNOWN_FIELD, INVALID_COMMAND.
- 404: ASSIGNMENT_NOT_FOUND; RESOURCE_NOT_FOUND for unmapped resources.
- 409: NOT_RUNNING, OWNER_MISMATCH, REVISION_OBSOLETE, NEWS_EXPIRED, LEASE_EXPIRED,
  COMPLETION_CONFLICT.
- 422: INVALID_RESULT, PROVENANCE_MISMATCH.
- 413: PAYLOAD_TOO_LARGE.
- 415: UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_CONTENT_ENCODING.
- 405: METHOD_NOT_ALLOWED; 406: NOT_ACCEPTABLE.
- 503: INFRASTRUCTURE_FAILURE, including RecoveryInfrastructureException.
- 500: INTERNAL_ERROR.

Successes and RFC 9457 errors are no-store. Worker responses contain no Location header.
Details are fixed text; validation diagnostics and storage/parser causes are not exposed.

## Acceptance evidence

| Area | Evidence |
| --- | --- |
| Strict parsing | 49 parser cases: closed roots/references, missing/null/wrong types, UUID representation, integer coercion/overflow, decoded duplicate keys, escaped equivalent names, trailing documents/garbage, malformed UTF-8 |
| Direct delegation | 25 controller cases: every service rejection across renew/fail/complete, exact one-call forwarding, unchanged decoded inner string, sanitized storage/transaction/internal exceptions |
| Real transport | 85 real HTTP/PostgreSQL cases, isolated PostgreSQL 18.6 and ephemeral loopback servlet listener |
| Durable transitions | HTTP admission -> claim -> renewal -> completion; SUCCEEDED and one result; recoverable/permanent failure; assignment exhaustion; lease recovery and fresh owner fencing |
| NoWork | Empty response has Retry-After 2; a recovery budget exhausted by two cleanup jobs leaves eligible RETRY_WAIT while returning NO_WORK |
| Completion replay | Original receipt after expiry and newer admission; changed payload conflicts; one immutable result remains |
| Inner result preservation | Exact decoded String equality assertions; malformed/duplicate/nested-duplicate/trailing/NUL/surrogate/unknown-property/provenance-invalid inner documents tested across live, wrong and historical owners |
| Validation precedence | Live invalid output fails terminally with no result; wrong owner leaves RUNNING unchanged; historical invalid retry conflicts without replacing result |
| Valid Unicode | Supplementary/accented characters and literal backslash-u sequences persist through HTTP/JSONB and replay |
| Raw-byte limits | 8192-byte control and 1048576-byte completion boundaries; declared and chunked bodies; limit+1 rejection; multibyte UTF-8 counted as bytes |
| Route normalization | Encoded and semicolon route aliases receive identical request-size protection |
| Privacy | Captured application output, exact public job fields, news reads, duplicate responses, error body fields, query sentinels, SQL sentinels, ownership tokens, and absent Location headers checked |
| Infrastructure | Real result-insert failure rolls back result/success and returns sanitized 503; recovery exception preserves earlier committed cleanup but not the failing transaction |
| Compatibility | Existing ingestion/business HTTP, health, loopback guards, lifecycle, completion, concurrency, restart, contracts, and migration cases pass |

Tests use controlled lifecycle time and isolated fixtures, without arbitrary sleeps or retried
business assertions. Test database triggers are removed in finally cleanup.
The initial new NoWork fixture incorrectly expired its retrying job too; its deadlines were
corrected to model the intended bounded-recovery scenario. Assertions were retained.

## Commands and actual results

Git status and HEAD entries below describe the historical implementation-verification snapshot,
before staging, committing, or publishing M3B1. They are not the current checkout status.

Run from WSL Ubuntu in the repository root:

```bash
make doctor
make format
./mvnw -f apps/core-java \
  -Dtest=WorkerEnvelopeParserTest,WorkerApiControllerTest,WorkerHttpApiIntegrationTest,NewsHttpApiIntegrationTest,HealthHttpAcceptanceTest,LoopbackOnlyServerInvariantTest test
make lint
make test
make migration-smoke
git diff --check
git diff --exit-code 21a61f8c72185be8a115be6eac8783899ebe01d6 -- \
  apps/core-java/src/main/resources/db/migration \
  apps/core-java/src/main/java/com/newsswing/core/inference/jobs \
  apps/core-java/src/main/java/com/newsswing/core/news \
  apps/core-java/src/main/java/com/newsswing/core/bootstrap \
  apps/core-java/src/main/resources/application.yaml \
  contracts tests/fixtures/contracts apps/ai-worker infra \
  apps/core-java/pom.xml Makefile
git diff --cached --quiet
git rev-parse HEAD
git status --short --untracked-files=all
```

| Gate | Actual result |
| --- | --- |
| make doctor | Passed |
| make format | Passed; Python files unchanged |
| Focused tests | 217 passed; zero failures/errors/skips |
| make lint | Passed: Java formatting/compile, Compose config, Ruff, mypy |
| make test Java | 528 passed; zero failures/errors/skips |
| make test Python | 23 passed |
| Separate make migration-smoke | 1 passed; zero failures/errors/skips |
| git diff --check | Passed |
| Protected paths vs baseline | No differences |
| Index | Empty |
| HEAD | Unchanged |

The Java total retains 369 baseline tests and adds 159: 49 parser, 25 controller, and
85 HTTP/PostgreSQL cases. Separate migration smoke repeats one test already in the full total.
Final verification reran focused/full gates after adding container-normalized route protection;
no code changed after those final gates.

## Changed files and deviations

Seven new production files in
`apps/core-java/src/main/java/com/newsswing/core/inference/api/`:
WorkerApiController.java, WorkerApiException.java, WorkerApiExceptionHandler.java,
WorkerApiModels.java, WorkerEnvelopeParser.java, WorkerProblems.java, WorkerRequestFilter.java.

Four new test files:

- `apps/core-java/src/test/java/com/newsswing/core/inference/api/WorkerEnvelopeParserTest.java`
- `apps/core-java/src/test/java/com/newsswing/core/inference/api/WorkerApiControllerTest.java`
- `apps/core-java/src/test/java/com/newsswing/core/inference/api/WorkerHttpApiIntegrationTest.java`
- `apps/core-java/src/test/java/com/newsswing/core/inference/jobs/WorkerLifecycleTestConfiguration.java`

Three new documents:
`docs/m3b-worker-protocol.md`, `docs/m3b-design.md`, `docs/m3b1-verification.md`.

Four modified checkpoint documents:
`AGENTS.md`, `README.md`, `docs/architecture.md`,
`docs/adr/0010-durable-inference-job-lifecycle.md`.

There are no scope or protocol deviations. A small worker-only problem factory avoids changing
existing news/health error infrastructure; explicit controller tests were added to the focused
set, and normalized-path body-limit checks close a transport-filter bypass.
V1–V3, domain contracts/corpus, lifecycle/domain implementations, existing business HTTP code,
health/listener configuration, Python, dependencies/lockfiles, infrastructure and Makefile
are unchanged. No dependency justification or lockfile update is necessary.

Historical pre-commit Git snapshot: 18 changed/new files (4 modified, 14 untracked), all unstaged; index empty;
HEAD was unchanged at the baseline; no commit or push had occurred at that snapshot. M3B2 had not begun.
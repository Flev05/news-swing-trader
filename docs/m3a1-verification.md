# M3A1 verification report

This report captures verification before final staging and commit; the Git state below is historical.

Implemented only the schema and ingestion/read compatibility checkpoint. Metadata 3A identifies schema support, not the completed M3A lifecycle. The full approved design remains in [the M3A design reference](m3a-design.md).

## Gates and exact counts

| Gate | Result |
| --- | --- |
| make doctor | Passed |
| make format | Passed |
| make lint | Passed |
| make test: Java | 173 passed; 0 failures, 0 errors, 0 skipped |
| make test: Python | 23 passed |
| make migration-smoke | 1 passed; 0 failures, 0 errors, 0 skipped; repeats the smoke test included in the Java total |
| git diff --check | Passed |

The 173 Java cases include 72 new cases: policy binding (12), lifecycle schema constraints (35), populated V2 upgrade (1), ingestion compatibility (17), and HTTP status compatibility (7). The existing 101 Java cases remain passing.

The initial targeted run executed 112 cases with two failing assertions: zero-maximum and over-maximum fixtures violated both counter and state-eligibility constraints. The cases were isolated without weakening the migration. The subsequent complete suite passed.

## Acceptance coverage

- Populated committed V2 migration retains original item/revision/job data, pending and superseded states, out-of-order history, pointers, timestamps, and original Flyway checksums; backfills policy and terminal metadata; validates and reruns without further migration.
- Job constraints reject invalid counters, exhausted pending admission, unsupported backend/status, incomplete ownership, empty lease intervals, misplaced ownership/terminal/supersession fields, missing failure reasons, permanent retries, and premature exhaustion failure.
- Result checks reject missing/mismatched JSON identity, wrong relational event/assignment/backend/hash, duplicate acceptance, update and deletion. Successful result fixtures validate against the authoritative v1 schema.
- Ingestion replaces PENDING/RUNNING/RETRY_WAIT/SUCCEEDED/FAILED/EXPIRED predecessors with exact pending credit; revokes only nonterminal ownership; preserves immutable revision history, policy snapshots, and terminal predecessors.
- Capacity rejection and injected job-insert failure preserve predecessor metadata and latest pointer. Stale provider duplicates and immutable conflicts retain precedence in the new states.
- Real HTTP tests read all seven statuses and both exact/provider duplicates with exact existing public fields and no internal ownership metadata.
- All existing contract, canonicalization, ingestion concurrency/rollback, HTTP, loopback, health and durable restart regressions pass. Restart snapshots now also include result rows.

## Scope and deviations

No scope deviations. No claim, renewal, failure, recovery, result acceptance, eligible-result query, worker endpoint, scheduler, placeholder lifecycle service, or Python changes. No new dependencies. V1/V2, authoritative contracts/corpus, Python files, build/dependency pins and Makefile are unchanged. The V3 migration must remain unchanged after commit; later corrections require another migration.

## Changed files

18 modified tracked files and 11 new untracked files, including this report. Paths below are rooted in the WSL checkout.

- [AGENTS.md](/home/xeemt/code/news-swing-trader/AGENTS.md)
- [README.md](/home/xeemt/code/news-swing-trader/README.md)
- [apps/core-java/src/main/java/com/newsswing/core/inference/jobs/InferenceJobPolicy.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/main/java/com/newsswing/core/inference/jobs/InferenceJobPolicy.java)
- [apps/core-java/src/main/java/com/newsswing/core/inference/jobs/JobStatus.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/main/java/com/newsswing/core/inference/jobs/JobStatus.java)
- [apps/core-java/src/main/java/com/newsswing/core/news/api/NewsStatusQueryService.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/main/java/com/newsswing/core/news/api/NewsStatusQueryService.java)
- [apps/core-java/src/main/java/com/newsswing/core/news/api/NewsStatusRepository.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/main/java/com/newsswing/core/news/api/NewsStatusRepository.java)
- [apps/core-java/src/main/java/com/newsswing/core/news/ingestion/NewsIngestionConfiguration.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/main/java/com/newsswing/core/news/ingestion/NewsIngestionConfiguration.java)
- [apps/core-java/src/main/java/com/newsswing/core/news/ingestion/NewsIngestionCoordinator.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/main/java/com/newsswing/core/news/ingestion/NewsIngestionCoordinator.java)
- [apps/core-java/src/main/java/com/newsswing/core/news/ingestion/NewsIngestionRepository.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/main/java/com/newsswing/core/news/ingestion/NewsIngestionRepository.java)
- [apps/core-java/src/main/java/com/newsswing/core/news/ingestion/NewsIngestionResult.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/main/java/com/newsswing/core/news/ingestion/NewsIngestionResult.java)
- [apps/core-java/src/main/resources/application.yaml](/home/xeemt/code/news-swing-trader/apps/core-java/src/main/resources/application.yaml)
- [apps/core-java/src/main/resources/db/migration/V3__durable_inference_job_lifecycle.sql](/home/xeemt/code/news-swing-trader/apps/core-java/src/main/resources/db/migration/V3__durable_inference_job_lifecycle.sql)
- [apps/core-java/src/test/java/com/newsswing/acceptance/HttpAcceptanceSupport.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/test/java/com/newsswing/acceptance/HttpAcceptanceSupport.java)
- [apps/core-java/src/test/java/com/newsswing/acceptance/NewsRestartRecoveryAcceptanceTest.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/test/java/com/newsswing/acceptance/NewsRestartRecoveryAcceptanceTest.java)
- [apps/core-java/src/test/java/com/newsswing/core/MigrationSmokeTest.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/test/java/com/newsswing/core/MigrationSmokeTest.java)
- [apps/core-java/src/test/java/com/newsswing/core/V1ToM2aMigrationTest.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/test/java/com/newsswing/core/V1ToM2aMigrationTest.java)
- [apps/core-java/src/test/java/com/newsswing/core/V2ToM3aMigrationTest.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/test/java/com/newsswing/core/V2ToM3aMigrationTest.java)
- [apps/core-java/src/test/java/com/newsswing/core/inference/jobs/InferenceJobPolicyTest.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/test/java/com/newsswing/core/inference/jobs/InferenceJobPolicyTest.java)
- [apps/core-java/src/test/java/com/newsswing/core/inference/jobs/LifecycleSchemaConstraintTest.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/test/java/com/newsswing/core/inference/jobs/LifecycleSchemaConstraintTest.java)
- [apps/core-java/src/test/java/com/newsswing/core/news/api/NewsHttpApiIntegrationTest.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/test/java/com/newsswing/core/news/api/NewsHttpApiIntegrationTest.java)
- [apps/core-java/src/test/java/com/newsswing/core/news/ingestion/NewsIngestionAcceptanceTest.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/test/java/com/newsswing/core/news/ingestion/NewsIngestionAcceptanceTest.java)
- [apps/core-java/src/test/java/com/newsswing/core/news/ingestion/NewsIngestionCoordinatorTest.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/test/java/com/newsswing/core/news/ingestion/NewsIngestionCoordinatorTest.java)
- [apps/core-java/src/test/java/com/newsswing/core/news/ingestion/NewsIngestionValidationTest.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/test/java/com/newsswing/core/news/ingestion/NewsIngestionValidationTest.java)
- [apps/core-java/src/test/java/com/newsswing/core/news/ingestion/NewsLifecycleCompatibilityTest.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/test/java/com/newsswing/core/news/ingestion/NewsLifecycleCompatibilityTest.java)
- [apps/core-java/src/test/java/com/newsswing/support/InferenceJobFixtures.java](/home/xeemt/code/news-swing-trader/apps/core-java/src/test/java/com/newsswing/support/InferenceJobFixtures.java)
- [docs/adr/0010-durable-inference-job-lifecycle.md](/home/xeemt/code/news-swing-trader/docs/adr/0010-durable-inference-job-lifecycle.md)
- [docs/architecture.md](/home/xeemt/code/news-swing-trader/docs/architecture.md)
- [docs/m3a-design.md](/home/xeemt/code/news-swing-trader/docs/m3a-design.md)
- [docs/m3a1-verification.md](/home/xeemt/code/news-swing-trader/docs/m3a1-verification.md)

## Pre-commit Git state

- Branch: `main`.
- HEAD: `f0985b23cd64778037c8ca5c281f76d905c8942b` (unchanged from the reviewed baseline).
- Index empty; all changes unstaged/untracked. No commits or pushes. M3A2 has not been started.

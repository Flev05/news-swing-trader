# M3A2 verification report

Implemented only Java job claiming, lease renewal, failure, and bounded recovery.
This report captures verification before staging and publication; the Git state below is historical.
The verification baseline and pre-publication HEAD are `857c43d06dec5086845333f632dadae92f7a487e`.
The preflight working tree/index were clean. At verification, changes were uncommitted and nothing had been pushed.

## Verification gates

| Execution | Result |
| --- | --- |
| `make doctor` | Passed |
| Targeted lifecycle/configuration/restart plus HTTP tests | 111 Java cases passed; 0 failures, errors, or skips |
| `make format` | Passed; Python files unchanged |
| `make lint` | Passed: Java compile/Spotless, Compose configuration, Ruff, mypy |
| `make test` — Java | 237 passed; 0 failures, 0 errors, 0 skipped |
| `make test` — Python | 23 passed |
| Final fixture regression run | 74 passed; 0 failures, errors, or skips |
| Separate `make migration-smoke` execution | 1 passed; 0 failures, errors, or skips |
| Final Java `spotless:check` | Passed |
| `git diff --check` | Passed |
| Protected-path comparison against baseline | Passed; no changes |

The 237 Java cases include the existing 173 plus 64 new cases: lifecycle acceptance 26,
concurrency 21, configuration 13, restart 3, and HTTP lifecycle compatibility 1.
The targeted 111 consist of the four planned lifecycle classes (63 cases) plus the HTTP class
(48 cases). The separate migration-smoke execution repeats the single smoke case already
included in the full Java total; it is not added to 237.

The initial targeted run exposed 16 test-observer synchronization failures: direct field assignment
updated Spring's repository proxy rather than its target. Observer registration now uses a method.
The corrected 111-case run passed. Final review then isolated the HTTP lifecycle scenario from
historical status fixtures and bounded the capacity fixture's executor cleanup. A compile error in
that test-only cleanup edit was corrected; all 26 acceptance cases were retained. The final 74-case
acceptance/HTTP run passed. Production code was unchanged after the successful full suite.
The successful full repository gates were not repeated; only affected tests and final formatting
verification were rerun.

Reproduction commands, from WSL Ubuntu at the repository root:

```bash
make doctor
./mvnw -f apps/core-java \
  -Dtest=InferenceJobLifecyclePropertiesTest,InferenceJobLifecycleAcceptanceTest,InferenceJobLifecycleConcurrencyTest,InferenceJobRestartRecoveryAcceptanceTest,NewsHttpApiIntegrationTest test
make format
make lint
make test
./mvnw -f apps/core-java spotless:apply \
  -Dtest=InferenceJobLifecycleAcceptanceTest,NewsHttpApiIntegrationTest test
make migration-smoke
./mvnw -f apps/core-java spotless:check
git diff --check
git diff --exit-code 857c43d06dec5086845333f632dadae92f7a487e -- \
  apps/core-java/src/main/resources/db/migration \
  contracts apps/ai-worker apps/core-java/pom.xml Makefile
git status --short
```

## Acceptance and concurrency evidence

- Concurrent claims return different jobs/ownership; a locked head item is skipped. A one-candidate
  budget can return NoWork even with another available job. Recovery also skips locked items.
- Post-commit delivery uses a transaction-synchronization latch: the caller's future remains incomplete
  and another connection still sees PENDING until commit. Immediate-update and deferred-commit failure
  injection preserve assignment count zero. Renewal, failure, and requeue storage failures roll back.
- Controlled microsecond-aligned time covers renewal immediately before, at, and after lease expiry,
  news-deadline equality, clipping, and backward time without lease shortening.
- Malformed, missing, mismatched, superseded, expired, terminal, and replaced owners cannot mutate
  jobs. Full row snapshots verify rejection leaves authority unchanged.
- Worker interruption clears ownership, waits/requeues without consuming an assignment, and creates a
  fresh inference ID/token on the next claim. Both explicit failure and lease expiry exhaust the
  stored three-assignment policy; permanent failure terminates immediately.
- Recovery cleanup precedes retries. Creation-time/job-ID claim ordering, due-lease ordering, a
  one-candidate budget, two-slot same-pass requeue, changed candidates, and capacity deferral are covered.
- A later failed recovery transaction preserves earlier commits and reports only committed progress.
  A retry-discovery failure carries progress with no failed job ID. Claiming stops on recovery failure.
- Full/zero capacity leaves RETRY_WAIT ownership empty and assignment count unchanged. Expired PENDING
  work continues to count while its item is locked. Two concurrent recovery calls and ingestion
  contend for the admission mutex with one free slot and finish at exactly capacity.
  Replacing a requeued PENDING predecessor credits exactly one slot.
- Eight parameterized supersession cases force both lock orders against claim, renewal, failure,
  and recovery. Rejected newer admission at capacity preserves a real assignment's ownership.
- Four post-wait decision tests hold the admission mutex, verify no early job lock with NOWAIT,
  advance time, and release the mutex. Two further tests use renewal/failure's blocking item lock.
  Claim/recovery item locks are tested as SKIP LOCKED, never as waiting locks.
- Restart cases fully close contexts and Hikari pools against one unchanged Testcontainer, then restart
  before, at, or after lease expiry. Startup does not sweep. Live ownership remains usable; expired
  ownership recovers explicitly. Old policy survives changed configuration and later exhaustion.
- Real HTTP reads and duplicates after RUNNING, RETRY_WAIT, PENDING, and FAILED transitions retain
  exact existing field sets and cache behavior. Normal health contexts retain the production lifecycle
  source and the ingestion Clock. Existing migration, ingestion, API, health, and restart tests pass.

Tests use isolated PostgreSQL 18.6, explicitly imported test configurations, barriers/latches,
bounded futures and executor shutdown, and controlled lifecycle time. No sleeps were introduced.
The database lock-timeout test waits for PostgreSQL's timeout rather than sleeping.

## Implemented semantics and boundary

Every lifecycle entry point rejects ambient transactions. Mutations lock one item, then the
NEWS_INFERENCE admission mutex, then the job, under READ COMMITTED. Production time comes from
`clock_timestamp()` on the transaction-bound connection after locking/preparation; ingestion's
Clock is unchanged. Only claims consume assignments.

Recovery cleanup and retry phases share a bounded candidate budget and commit each candidate
separately. Newly interrupted work can requeue in another transaction if budget remains.
NoWork describes only that bounded invocation. The candidate budget bounds work; per-lock two-second
and per-statement five-second timeouts do not impose an overall call deadline or fixed latency.
Earlier recovery commits survive subsequent recovery or claim failure.

No scope deviations or schema changes. V1–V3, authoritative contracts, Python, dependency pins,
Makefile, and existing HTTP representations are unchanged. No completion/result acceptance or
placeholder methods, eligible-result queries, worker endpoints, scheduler, startup sweep, automatic
retry framework, model integration, or M3A3 behavior was added.

## Concrete job example

The `interruptedAssignmentRecoversReassignsAndFencesOldOwner` acceptance case uses provider
`fixture_wire`, news ID `interruption`, revision `r1`, and deadline
`2026-01-15T14:40:30.123456Z`:

| Decision time (UTC) | Durable behavior |
| --- | --- |
| 14:30:30.123456 | Claim PENDING → RUNNING; assignment 1, fresh token T1/inference I1, lease until 14:31:30.123456 |
| 14:31:30.123456 | Lease is expired at equality |
| 14:31:30.123456 | Recovery transaction 1: RUNNING → RETRY_WAIT, LEASE_EXPIRED, ownership cleared; count remains 1 |
| 14:31:30.123456 | Recovery transaction 2: capacity permits RETRY_WAIT → PENDING; no lease, count remains 1 |
| 14:31:30.123456 | Claim assignment 2 with different token T2/inference I2; lease until 14:32:30.123456 |
| 14:31:30.123456 | Renew/fail using assignment 1/T1 return OWNER_MISMATCH; assignment 2 is unchanged |

The recovery summary is `processed=2, retryWaiting=1, requeued=1`; every other counter is zero.

## Changed files

Nine modified tracked files:

```text
AGENTS.md
README.md
apps/core-java/src/main/java/com/newsswing/core/news/ingestion/NewsIngestionProperties.java
apps/core-java/src/main/resources/application.yaml
apps/core-java/src/test/java/com/newsswing/acceptance/HealthHttpAcceptanceTest.java
apps/core-java/src/test/java/com/newsswing/core/news/api/NewsHttpApiIntegrationTest.java
docs/adr/0010-durable-inference-job-lifecycle.md
docs/architecture.md
docs/m3a-design.md
```

Fifteen new, untracked files:

```text
apps/core-java/src/main/java/com/newsswing/core/inference/jobs/InferenceJobLifecycleConfiguration.java
apps/core-java/src/main/java/com/newsswing/core/inference/jobs/InferenceJobLifecycleProperties.java
apps/core-java/src/main/java/com/newsswing/core/inference/jobs/InferenceJobLifecycleRepository.java
apps/core-java/src/main/java/com/newsswing/core/inference/jobs/InferenceJobLifecycleService.java
apps/core-java/src/main/java/com/newsswing/core/inference/jobs/JdbcInferenceJobLifecycleService.java
apps/core-java/src/main/java/com/newsswing/core/inference/jobs/LifecycleTimeSource.java
apps/core-java/src/main/java/com/newsswing/core/inference/jobs/PostgresLifecycleTimeSource.java
apps/core-java/src/main/java/com/newsswing/core/inference/jobs/RecoveryInfrastructureException.java
apps/core-java/src/test/java/com/newsswing/core/inference/jobs/InferenceJobLifecycleAcceptanceTest.java
apps/core-java/src/test/java/com/newsswing/core/inference/jobs/InferenceJobLifecycleConcurrencyTest.java
apps/core-java/src/test/java/com/newsswing/core/inference/jobs/InferenceJobLifecyclePropertiesTest.java
apps/core-java/src/test/java/com/newsswing/core/inference/jobs/InferenceJobRestartRecoveryAcceptanceTest.java
apps/core-java/src/test/java/com/newsswing/core/inference/jobs/LifecycleHttpTestConfiguration.java
apps/core-java/src/test/java/com/newsswing/core/inference/jobs/LifecycleTestSupport.java
docs/m3a2-verification.md
```

At verification, the index was empty and all 24 changes were unstaged/untracked. HEAD was unchanged; no commit or push had occurred.

Start review with the service interface, `JdbcInferenceJobLifecycleService` (transaction boundaries),
`InferenceJobLifecycleRepository` (SQL/locking), and
`InferenceJobLifecycleConcurrencyTest.supersessionRacesRespectBothLockOrders` (representative race test).

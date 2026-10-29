# News Swing Trader

Production-oriented scaffolding for a future news-driven US equity swing-trading system. The
repository is currently at **M3B2**: a local Python fake worker completes the HTTP vertical slice.
Strict loopback worker HTTP routes adapt the Java lifecycle.
M3A3's validated atomic completion, historical replay, and current eligible-result reads extend
M3A2 claiming, lease renewal, failure, and bounded recovery.
These build on M3A1 durable inference-job schema support and ingestion/read compatibility, and the
M2B2B foundation of authoritative v1 contracts, immutable PostgreSQL
news revisions, transactional ingestion admission, a loopback-only local HTTP business API,
Actuator health, and durable restart recovery acceptance tests.

It has no public result HTTP endpoint, real inference,
signal generation, broker integration, model download, external AI access, risk decisions, or
order intents.

The contract definitions and their encoded semantic invariants are documented in
`contracts/README.md`.

## Environment

Open the Ubuntu WSL distribution and use a checkout on its Linux filesystem.
This example assumes the checkout is under `~/code/`; adjust the directory if needed:

```bash
wsl.exe -d Ubuntu
cd "$HOME/code/news-swing-trader"
```

The preflight verified Java 21.0.12, Maven 3.9.12, Docker Engine 29.5.3, Docker Compose 5.1.4, and a cached `postgres:18.6` image. Python 3.12 is provisioned locally by `make bootstrap`; Ubuntu's system Python 3.14 is left unchanged.

## Quick start

```bash
make bootstrap
make doctor
make format
make lint
make test
make migration-smoke
```

`make bootstrap` downloads a checksum-verified, repository-local `uv` binary, installs an isolated Python 3.12.14 runtime under ignored `.tools/`, creates the worker virtual environment, and writes `apps/ai-worker/uv.lock`.

The Java integration and acceptance tests require a working Docker runtime and use isolated
PostgreSQL Testcontainers; the Compose database is not required for tests. Start the local database when inspecting it manually:

```bash
make db-up
make db-logs
make db-down
```

The local Compose stack contains PostgreSQL only. The Java application can be started against it with:

```bash
./mvnw -f apps/core-java spring-boot:run
```

## Tooling versions

Direct dependency and tool versions are pinned only after they resolve and pass repository checks on this host:

- Java: 21.0.12; Maven Wrapper distribution: 3.9.12.
- Spring Boot: 4.1.1; Java formatting plugin: Spotless 3.9.0.
- Java JSON Schema validator: NetworkNT 3.0.7.
- Python runtime: 3.12.14; uv: 0.12.9.
- Python JSON Schema validator: jsonschema 4.26.0 with non-GPL format checks.
- Python type stubs: types-jsonschema 4.26.0.20260518.
- Python development dependencies: Ruff 0.16.5, mypy 2.3.1, pytest 9.1.1.

The direct `spring-boot-starter-actuator` dependency supplies the built-in health endpoint.
No metrics exporter or additional test dependency is added. The Spring Boot BOM manages Actuator,
Flyway, PostgreSQL JDBC, and Testcontainers versions; their resolved versions are visible through `./mvnw -f apps/core-java dependency:tree`.

## Existing HTTP and health boundary

- The servlet API binds only to `127.0.0.1`; external address or separate management-listener
  configuration fails startup.
- The API accepts strict News Event v1 input, computes the content hash server-side, and returns
  durable admission and current job status from PostgreSQL.
- Business success and RFC 9457 problem responses are not cacheable. Unmapped resources return
  a sanitized 404 problem rather than falling through to the generic 500 handler.
- Actuator shares the same application server and port. Only health is exposed; discovery and JMX
  exposure are disabled, and health details/components are never returned.
- Liveness includes only `livenessState`; readiness includes `readinessState` and `db`. No separate
  management listener, authentication layer, or general observability API is added.

| Loopback URL | Healthy database | Database unavailable |
| --- | --- | --- |
| `/actuator/health` | 200 UP | 503 DOWN |
| `/actuator/health/liveness` | 200 UP | 200 UP |
| `/actuator/health/readiness` | 200 UP | 503 DOWN |

The default base URL is `http://127.0.0.1:8080`; `TRADER_HTTP_PORT` changes the application port.
The existing `management.server.port=-1` opt-out disables management HTTP. Any management address
or other management-port setting is rejected at startup.

The health acceptance tests deny new connections to a dedicated Testcontainer database, terminate
its existing client sessions with a bounded PostgreSQL call, and restore access in resource cleanup.
Only readiness recovery after restoration may poll, using an overall deadline and bounded HTTP
requests. Tests never stop the Compose database or use sleeps.

The restart acceptance test closes two complete application contexts sequentially against one
unchanged PostgreSQL instance and migrated schema. It exercises ingestion and durable resource
reads over real loopback HTTP, then checks stale duplicate resolution, duplicate precedence at
capacity, capacity rejection, and explicitly ordered durable snapshots. Fixed clocks are registered
through test configuration outside the application scan. No application-state assertion is retried.
V1 and V2 remain unchanged; V3 advances `platform_metadata.milestone` to `3A`, identifying
the schema checkpoint rather than completed lifecycle operations.

Later milestones add local model inference, deterministic decisioning, DRY_RUN
intents, and further reliability controls. They are deliberately absent from
this checkpoint.

## Worker HTTP boundary and M3B2 execution

The [worker protocol](docs/m3b-worker-protocol.md) adds POST claim, renew, fail, and complete
under `/api/v1/worker/`. Controllers delegate to the existing lifecycle service; Java owns
all database mutations and transaction boundaries. Strict closed transport envelopes keep
assignment credentials outside domain payloads and pass the decoded `result_json` string
unchanged to the lifecycle validator. Raw limits are 8 KiB for control requests and 1 MiB
for completion, including chunked bodies. Responses are no-store and errors are sanitized.
Public news/job representations and health behavior are unchanged. No recovery route,
scheduler, or automatic worker launch is added. M3B1 introduced this Java-only transport;
M3B2 now runs the local Python fake worker through it, producing deterministic abstention results.
Real-model inference remains future work.

The [M3B design](docs/m3b-design.md) records the approved M3B2 processing, retry, heartbeat,
shutdown, and absolute-deadline requirements. The [worker guide](apps/ai-worker/README.md) documents
the new module CLI and manual ambiguous-claim recovery. The [M3B2 verification report](docs/m3b2-verification.md)
records process acceptance, repository gates, and an executed disposable HTTP/PostgreSQL demo.
The historical [M3B1 verification report](docs/m3b1-verification.md) records HTTP/PostgreSQL
acceptance and repository gates at that checkpoint.

## M3A schema and ingestion compatibility

The complete approved M3A lifecycle design is retained in [docs/m3a-design.md](docs/m3a-design.md)
and [ADR 0010](docs/adr/0010-durable-inference-job-lifecycle.md). M3A1 schema/compatibility, M3A2 claim/renew/fail/recover, and M3A3 completion/replay/eligibility are implemented.

V3 supports PENDING, RUNNING, RETRY_WAIT, SUCCEEDED, FAILED, SUPERSEDED, and EXPIRED, with
assignment/lease constraints, immutable result storage, provenance checks, and queue indexes.
Existing V2 revisions, admissions, job identities, and latest pointers are preserved.

Ingestion snapshots `trader.inference.jobs.max-assignments` (positive; default 3 total assignments)
and `trader.inference.jobs.expected-backend` (fake, llama.cpp, or ollama; default fake).
These fields are immutable policy metadata; M3B2 implements fake execution only. The real-model
backend names remain reserved for future milestones. Replacing a pending predecessor credits
one pending slot; other states credit none. Successful admission supersedes nonterminal predecessors
and revokes ownership atomically; terminal jobs and historical results remain unchanged.

Existing HTTP representations show all statuses without exposing ownership or assignment metadata.
M3A3 supplied Java result acceptance and eligible-result reads; that checkpoint added no result
or worker HTTP endpoints, scheduler, or Python changes. Schema-only tests construct lifecycle
states explicitly
with SQL. Java validates the complete Inference Result v1 contract and atomically pairs an
accepted result with SUCCEEDED.

Once committed, V3 must not be edited. Subsequent corrections require a new migration.

## M3A2 Java lifecycle

M3A2 introduced `claimNext()`, `renew(AssignmentRef)`,
`fail(AssignmentRef, FailureKind)`, and `recoverBatch()`. These Java calls reject ambient transactions;
that checkpoint added no worker HTTP routes. No transaction remains open for inference.

PostgreSQL `clock_timestamp()`, sampled on the transaction connection after locks, governs decisions.
Ingestion retains its independent request Clock. Ownership requires the current revision, RUNNING state,
matching assignment number/token, and lease/news deadlines strictly after decision time. Equality expires.
Claims consume one stored assignment and generate fresh token/inference IDs; delivery follows commit.
Renewal never shortens a lease or extends beyond the news deadline.

- `trader.inference.jobs.lease-duration`: positive whole milliseconds; default `60s`.
- `trader.inference.jobs.recovery-batch-size`: 1-1000; default 100; also bounds claim candidates.
- Existing maximum assignments/backend remain immutable per-job snapshots; the default is three total assignments.
- All mutations lock one item, then the NEWS_INFERENCE admission mutex, then the job.
  Claim/recovery skip locked items; renewal/failure wait for their item.
- Recovery first cleans obsolete/expired work and due leases, then requeues RETRY_WAIT work if capacity permits.
  Requeue uses exact PENDING occupancy under the admission mutex, including expired rows awaiting cleanup.
  Waiting consumes no lease or assignment. No terminal work is retried.

One recovery call attempts at most the batch count of candidate transactions, with each committed
independently. A due lease can become RETRY_WAIT and then PENDING in the same call if budget remains;
these consume two slots. No candidate-list refill occurs. `claimNext()` first invokes one recovery pass,
then inspects at most the batch count of PENDING candidates. `NoWork` means no assignment in that bounded
call, not proof of queue emptiness.

Local lock/statement timeouts are two/five seconds. Candidate budgets bound work; these timeouts
do not establish an overall call deadline or a fixed end-to-end latency guarantee.
A `RecoveryInfrastructureException` exposes committed progress and the failed job ID (absent for
discovery failures); earlier commits survive, the failing transaction rolls back, and processing stops.
Claim failures after a successful recovery pass also leave that recovery progress durable.
There is no automatic retry framework, scheduler, startup sweep, or mutation triggered by reads.
Restart recovery must be invoked explicitly through Java.

See [the M3A2 verification report](docs/m3a2-verification.md) for acceptance coverage and gates.

## M3A3 completion and eligible results

The Java-only lifecycle boundary adds `complete(AssignmentRef, resultJson)` and
`findCurrentEligibleResult(newsItemId)`. M3A3 added no result or worker HTTP route.

Completion prepares one immutable JSON string before opening a transaction. Strict parsing rejects
duplicate keys and trailing documents. Decoded string values and property names reject NUL and
unpaired surrogates, preserving valid supplementary characters and literal backslash sequences.
The cached authoritative schema enforces formats, closed objects, and unique tickers.

After item -> admission mutex -> job locking, replay precedes current ownership/freshness.
Matching accepting credentials and equal PostgreSQL JSONB return the original receipt; changed
or invalid payloads conflict without mutation. Prepared invalid replay never reaches a JSONB cast.
Current ownership and news/lease deadlines precede validation rejection. A valid live owner's
invalid contract or provenance terminally fails the job without a result. Valid acceptance inserts
the immutable result and transitions RUNNING to SUCCEEDED atomically, recording database time and
clearing ownership. Outcomes are delivered after commit; infrastructure failures roll back.

Eligible-result reads own a nonlocking read-only REPEATABLE READ transaction, explicitly establish
a snapshot, and sample lifecycle database time once. Only the latest revision's SUCCEEDED result
with a strictly future news deadline is returned; historical results are never a fallback.
The return type exposes no ownership token. Ingestion's Clock is unchanged.

See [the M3A3 verification report](docs/m3a3-verification.md) for test counts, races, rollback,
restart evidence, protected paths, and a concrete lost-acknowledgement example.

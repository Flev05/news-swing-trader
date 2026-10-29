# M3B2 verification — 2026-10-29

The initial implementation preflight below is a historical snapshot. The review follow-up began
with the existing M3B2 changes still uncommitted; it did not start from a clean working tree.
Current review verification and the pre-commit Git snapshot are recorded separately below.

Initial implementation preflight: HEAD was `452575c65d194d72d598a8fddfa7333029958124`; both index and
working tree were clean. The supplied 528 Java / 23 Python baseline was not rerun for preflight.
Read repository instructions, README, approved M3B design/protocol, Python scaffold/tooling,
authoritative contracts, Java HTTP adapters, lifecycle interfaces, and acceptance fixtures.

## Changes and scope

- `apps/ai-worker/src/news_swing_ai/worker.py`: single-assignment module CLI, serialized control
  flow, renewals, cooperative processor cancellation, frozen completion reconciliation, signals,
  shutdown grace, and documented exit codes.
- `apps/ai-worker/src/news_swing_ai/transport.py`: direct standard-library loopback HTTP,
  fixed routes, strict JSON, response limits, connection/read timeouts, absolute deadlines,
  socket shutdown before close, request-local deadline identity and joined timer cleanup.
- `apps/ai-worker/src/news_swing_ai/fake_result.py`: time-injected explicit abstention for every
  stored symbol, using actual assigned inference and stored event identity/hash.
- `apps/ai-worker/tests/test_worker.py`: focused deterministic worker/transport tests.
- `apps/core-java/src/test/java/com/newsswing/core/inference/jobs/PythonWorkerProcessAcceptanceTest.java`:
  actual Python processes, isolated PostgreSQL, real Java HTTP, controlled lifecycle time,
  and test-only forwarding gates outside production scanning.
- `tools/m3b2_demo.py`: repeatable disposable demonstration and scoped resource cleanup.
- `README.md`, `apps/ai-worker/README.md`, this report: checkpoint, operation, recovery, evidence.
- `AGENTS.md`, `docs/architecture.md`: implemented M3B2 checkpoint and fake-only execution.
- `docs/m3b-design.md`, `docs/m3b-worker-protocol.md`: implemented status, receipt correlation,
  strict UTC timestamps, and unchanged reconciliation policy.
- `docs/m3a-design.md`, `docs/adr/0001-modular-monolith-and-python-worker.md`,
  `docs/adr/0010-durable-inference-job-lifecycle.md`: current follow-up status alongside the
  original historical checkpoint decisions.
- `docs/m3b1-verification.md`: explicitly identified as a historical Java-only snapshot.

This inventory covers all 17 changed/new files relative to HEAD. The review follow-up modifies
only `worker.py`, `test_worker.py`, and the documentation listed above; other initial M3B2
implementation files are retained unchanged.

No milestone or architecture deviations. No Java production changes, migration edits,
contract edits, dependency/pin changes, infrastructure changes, Makefile edits, database access
from the worker, persistence ledger, or automatic Java restart. The old scaffold CLI/backend
remain unchanged. The Git status below records the pre-commit verification snapshot.

## Fault coverage

| Case | Evidence |
| --- | --- |
| Normal ingestion → claim → complete | Actual CLI: HTTP 202, SUCCEEDED, assignment count 1, exactly one fake result |
| Lost completion acknowledgement | Forwarder discards first reply only after Java commits; second request bytes identical; ACCEPTED then ALREADY_ACCEPTED, one result |
| Ambiguous claim | Forwarder discards committed claim reply; process exits 3 with exactly one claim; durable RUNNING assignment, no result |
| Interruption/reassignment | Kill actual assigned process behind a committed-claim gate, advance lifecycle clock 61 seconds, replacement succeeds on assignment 2 |
| Authority rejection | Gate renewal, advance controlled time past lease, release; exit 4, no processing/completion/failure follow-up |
| SIGTERM during claim | Deliver claim after stop; renew then RECOVERABLE release; exit 0, RETRY_WAIT, no result |
| Long processing | Cooperative processor gates prove heartbeat renewal and final renewal before completion |
| Cancellation | Authority loss and shutdown cancel and join active processor before release/reclaim |
| Uncertain renewal/failure | Three-send budget; identical failure bytes; no completion after unresolved renewal |
| Uncertain completion | Lost, 5xx, and malformed-success acknowledgements use three frozen sends; exhaustion exits without failure/reclaim; shutdown only replays completion |
| Receipt correlation | Each job/event/inference mismatch tested for both ACCEPTED and ALREADY_ACCEPTED, with distinct active IDs; correct replay recovers or three mismatches exhaust the original budget; exactly one generation, identical completion bytes, no premature success, failure, or extra claim |
| Strict UTC timestamps | Reject incomplete, naive, nonconforming and invalid date/time forms; accept complete UTC with optional fractions, including Java nanosecond text; claim rejection stops ambiguously, renewal/receipt rejection exhausts unchanged retry budgets; all three boundaries covered |
| Request bounds | Blocked headers and body interrupted at absolute deadline; shutdown cancellation interrupts blocked I/O |
| Stale timer | Deliberately invoke old cancelled deadline callback while next request is blocked; next connection succeeds |
| Destination/response safety | Reject remote origins, credentials/paths/query/fragments, redirects, compression, oversized/truncated responses; environment proxies ignored |
| Deterministic result | Authoritative v1 schema validation, injected time, assigned IDs/hash, stored ticker order, explicit unknown/abstention output |

Tests use bounded latches/events, process waits, joins and executor cleanup. Only transport deadlines
use short elapsed-time bounds. No business assertion is retried; no arbitrary sleeps create races.
The Java launcher requires the repository-managed uv and Python environment, fails with a
`make bootstrap in WSL Ubuntu` instruction if absent, and invokes `--locked --no-sync --offline`.

## Review follow-up verification — 2026-10-29

The receipt validator compares UUID identities against the active assignment's job reference,
the stored event's event ID, and the server-assigned inference ID. It treats a mismatch as a
malformed/uncertain acknowledgement, preserving the frozen request and existing three-send budget.
Strict timestamp parsing requires `YYYY-MM-DDTHH:MM:SS[.fraction]Z` before calendar/time validation;
it returns UTC-aware datetimes and retains existing fractional-second parsing behavior.

Executed from the repository root in WSL Ubuntu:

```bash
make format
make lint
make test-python
./mvnw -f apps/core-java -Dtest=PythonWorkerProcessAcceptanceTest test
git diff --check
```

- Complete Python suite: **122 passed**, zero failures or skips (72 prior cases plus 50 review
  regression cases: 12 receipt correlation, 22 rejected timestamp forms, 4 accepted UTC/fraction
  forms, 9 invalid-timestamp response-boundary cases, and 3 valid-fraction boundary cases).
- Actual Python-process Java acceptance: **6 passed**, zero failures/errors/skips: normal success,
  lost completion acknowledgement, ambiguous claim, interruption/reassignment, authority loss,
  and shutdown. Repository-managed locked/no-sync/offline tooling was used by the existing launcher.
- Formatting/lint: passed (Spotless, Java compile, Ruff format/check, strict mypy).
- Whitespace: `git diff --check` passed; new Python sources are also formatter-checked, and
  all new text files were checked for trailing whitespace.
- The prior **534-test full Java** verification and separately executed **1-test migration smoke**
  are reused. Neither was rerun for this follow-up; Java production/migrations and the six Java
  acceptance scenario definitions are unchanged.
- No new demo run was needed for these review findings. The earlier executed disposable demo
  transcript below is retained as a historical execution record; current process acceptance
  verifies the corrected worker against isolated PostgreSQL and real Java HTTP.

## Initial implementation gates (historical execution record)

- `make doctor`: passed.
- `make format`: passed; final sources formatted.
- `make lint`: passed (Spotless, Java compile, Ruff format/check, strict mypy).
- `make test`: passed **534 Java tests**, zero failures/errors/skips, and the then-current
  **69 Python tests**. The Java total is the supplied 528 baseline plus six process scenarios.
- Final acknowledgement-validation review added three malformed-success reconciliation cases.
  `make test-python` then passed **72 Python tests** (23 baseline plus 49 new cases), zero skips.
  The six actual-process acceptance cases were rerun successfully after that retry change.
  Explicit response cleanup was also checked with the final Python suite and disposable demo.
- `make migration-smoke`: executed separately, **1 test**, zero failures/errors/skips;
  this execution is separate from its inclusion in the 534-test Java suite.

Full successful Java suites were not repeated for preflight or incidental documentation changes.
Only relevant Python/process checks were repeated after the final Python review changes.

## Executed disposable demonstration (historical execution record)

From the repository root in WSL Ubuntu:

```bash
.tools/uv/0.12.9/uv run --directory apps/ai-worker --locked --no-sync --offline python ../../tools/m3b2_demo.py
```

The script packages Java, creates one uniquely named disposable PostgreSQL container with an
OS-assigned loopback port, starts Java on another OS-assigned loopback port, admits a fresh
synthetic event over HTTP, launches the actual worker, and performs one SQL assertion. The
container uses trust only for this isolated demonstration; no developer connection settings or
Compose resources are read or changed. Its exact observed command/output transcript follows:

```text
COMMAND ./mvnw -q -f apps/core-java -DskipTests package
COMMAND docker run --rm --name m3b2-demo-ddaabf6f8b0b4c269c88993e9ff388b2 -d -e POSTGRES_HOST_AUTH_METHOD=trust -p 127.0.0.1::5432 postgres:18.6
COMMAND java -jar apps/core-java/target/core-java-0.0.1-SNAPSHOT.jar --server.port=0 --spring.datasource.url=jdbc:postgresql://127.0.0.1:61955/postgres --spring.datasource.username=postgres --spring.datasource.password=
COMMAND HTTP POST http://127.0.0.1:44349/api/v1/news-events (synthetic event generated by this script)
HTTP 202 job_id=84857048-4912-4f9d-a8fd-6f0fffca0c22
COMMAND /home/xeemt/code/news-swing-trader/.tools/uv/0.12.9/uv run --directory apps/ai-worker --locked --no-sync --offline python -m news_swing_ai.worker --base-url http://127.0.0.1:44349 --once
worker: succeeded
worker exit=0
COMMAND docker exec 2a2f6d94e22fb8b8b910990a407b5c4e5ad6fa670c6c4094923e92d4f37ceb65 psql -U postgres -d postgres -At -c "SELECT j.status, j.assignment_count, count(r.job_id), min(r.result_payload->'model'->>'name') FROM trader.inference_jobs j LEFT JOIN trader.inference_results r ON r.job_id=j.job_id WHERE j.job_id='84857048-4912-4f9d-a8fd-6f0fffca0c22'::uuid GROUP BY j.status,j.assignment_count;"
SUCCEEDED|1|1|deterministic-abstention
CLEANUP Java process stopped
COMMAND docker rm -fv 2a2f6d94e22fb8b8b910990a407b5c4e5ad6fa670c6c4094923e92d4f37ceb65
CLEANUP disposable container and its anonymous volume removed
CLEANUP temporary logs removed; developer Compose database untouched
```

The cleanup targets only the exact newly returned container ID (including its anonymous volume),
the direct Java process, and the script's temporary directory. No developer database was stopped,
truncated, migrated, or otherwise used. The script's final SQL selects only safe status/count/model
fields; it never emits ownership credentials or full result documents.

To reproduce: provision the existing locked tooling with `make bootstrap` if necessary, ensure
Docker and Java 21 are available (`make doctor`), then run the single command above. Ports, job ID,
and disposable container ID vary. Expect HTTP 202, `worker: succeeded`, exit 0, and
`SUCCEEDED|1|1|deterministic-abstention`, followed by cleanup confirmations.

For a worker against an already running local Java instance, use the module command in the
[worker guide](../apps/ai-worker/README.md). After ambiguous claim delivery, fully close/restart
Java against the same database before waiting for lease expiry and restarting one worker. A
cooldown alone cannot eliminate an in-flight claim; the worker never automates this procedure.

## Pre-commit Git status snapshot

`git diff --check` and trailing-whitespace checks for all seven new files passed.
Protected Java production files, V1–V3, contracts, dependency pins, infrastructure, Makefile,
and the original scaffold CLI/backend have no diff against HEAD.
This is a historical snapshot taken before explicit staging and publication of M3B2.
At this snapshot HEAD was `452575c65d194d72d598a8fddfa7333029958124`; no commit or push
had yet been made. The working tree contained ten modified documentation/instruction files
and seven new M3B2 implementation/test/demo/report files; the index was unchanged.
Publication is intentionally performed after recording this snapshot, so these status entries
do not describe the checkout after the checkpoint commit.

```text
 M AGENTS.md
 M README.md
 M apps/ai-worker/README.md
 M docs/adr/0001-modular-monolith-and-python-worker.md
 M docs/adr/0010-durable-inference-job-lifecycle.md
 M docs/architecture.md
 M docs/m3a-design.md
 M docs/m3b-design.md
 M docs/m3b-worker-protocol.md
 M docs/m3b1-verification.md
?? apps/ai-worker/src/news_swing_ai/fake_result.py
?? apps/ai-worker/src/news_swing_ai/transport.py
?? apps/ai-worker/src/news_swing_ai/worker.py
?? apps/ai-worker/tests/test_worker.py
?? apps/core-java/src/test/java/com/newsswing/core/inference/jobs/PythonWorkerProcessAcceptanceTest.java
?? docs/m3b2-verification.md
?? tools/
```

## Final staged review

The final publication review confirmed M3B2-only fake execution, receipt identity correlation,
strict UTC response timestamps, and updated checkpoint documentation. All 17 intended files
were selected explicitly, including only `tools/m3b2_demo.py` from `tools/`. No secrets, caches,
logs, generated artifacts, or temporary demo data were included. Protected paths and the
original scaffold CLI/backend remained unchanged. The complete staged diff was reviewed and
`git diff --cached --check` passed before commit.

Review-only edits clarified the historical pre-commit snapshot above and added missing final
newlines to the demo script and worker guide. No behavior changed, so the successful 122 Python cases, six rerun
Java process scenarios, prior 534-test full Java verification, separate migration smoke, and
formatting/lint checks were reused.

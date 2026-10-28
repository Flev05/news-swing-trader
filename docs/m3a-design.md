# M3A design reference

Status: approved design for the complete M3A lifecycle. **M3A1 and the M3A2 claim/renew/fail/recover boundary are implemented.**
This document retains decisions for subsequent checkpoints; it does not authorize their implementation.
The reviewed baseline was f0985b23cd64778037c8ca5c281f76d905c8942b.

## Checkpoints and boundaries

M3A1 adds V3 schema support, shared statuses, validated ingestion policy snapshots, ingestion
compatibility, public-read compatibility, migration/constraint/compatibility tests, and documentation.
Metadata `3A` identifies the schema checkpoint, not completed lifecycle functionality.
There are no claim, renewal, failure, recovery, completion, or eligible-result-query services,
placeholder implementations, worker HTTP endpoints, scheduler, or Python changes in M3A1.

M3A2 implements claim/renew/fail/recover below; result acceptance and eligibility remain later checkpoints. M3B may expose it to one
local Python worker over loopback HTTP. Java owns every database mutation. No transaction or lock
may remain open during inference. No external AI, model downloads, Redis, broker, signals, risk,
portfolio, or order behavior belongs to M3A. Keep both authoritative v1 contracts unchanged.
Use existing pinned dependencies; none are added for M3A1.

## State and ownership

| From | Trigger | To | Assignment accounting |
| --- | --- | --- | --- |
| New | Ingestion admission | PENDING | Zero |
| PENDING | Eligible claim | RUNNING | Increment once; fresh token and inference ID |
| RUNNING | Valid renewal | RUNNING | Unchanged |
| RUNNING | Valid eligible completion | SUCCEEDED | Unchanged |
| RUNNING | Invalid result/provenance or permanent worker failure | FAILED | Unchanged |
| RUNNING | Recoverable failure or expired lease, assignments remain | RETRY_WAIT | Unchanged; clear ownership |
| RUNNING | Recoverable failure or expired lease, assignments exhausted | FAILED | Unchanged |
| RETRY_WAIT | Eligible recovery with capacity | PENDING | Unchanged; no lease |
| PENDING, RUNNING, RETRY_WAIT | Successfully admitted newer revision | SUPERSEDED | Unchanged; clear ownership |
| PENDING, RUNNING, RETRY_WAIT | Recovery finds expired news | EXPIRED | Unchanged; clear ownership |

SUCCEEDED, FAILED, SUPERSEDED, and EXPIRED are terminal. Never rewrite a historical success merely
because a newer revision arrives. Do not add terminal requeue, retry backoff, or an attempt-history
framework.

Three total assignments, including the initial assignment, is the default. The positive maximum
and expected local backend are snapshotted at job creation. Ingestion policy properties are
`trader.inference.jobs.max-assignments` and `trader.inference.jobs.expected-backend`.
The backend defaults to `fake`; only `fake`, `llama.cpp`, and `ollama` are accepted.
These are provenance policy values, not backend implementations. Configuration changes affect
new jobs, not existing snapshots.

Waiting for queue capacity consumes neither an assignment nor a lease. Retry only recoverable
worker failure and interrupted assignments. Permanent worker failure and validation failure are
terminal. Bounded failure codes are WORKER_RECOVERABLE, WORKER_PERMANENT, LEASE_EXPIRED,
INVALID_RESULT, and PROVENANCE_MISMATCH.

Before claim, renewal, completion, failure, or reassignment, check the actual job/revision.
Ownership requires RUNNING, the matching assignment number and token, latest revision identity,
and both lease and news deadline strictly after the database decision instant. Equality is expired.
Recovery checks revision obsolescence first, news expiry second, then assignment exhaustion.
Old ownership can never renew, complete, or fail a replacement assignment.

## Clock and transaction discipline for later operations

PostgreSQL is authoritative. A package-private time-source interface will read `clock_timestamp()`
on the transaction-bound JDBC connection. Sample after lock acquisition and validation preparation,
immediately before the guarded decision; transaction-start timestamps and worker timestamps are
unsuitable. Keep the existing Java ingestion Clock semantics unchanged.

Default lease duration is 60 seconds. Validate positive whole-millisecond durations. Initial expiry
is min(now + duration, news deadline). Renewal expiry is min(news deadline,
max(existing expiry, now + duration)); never revive an expired lease or shorten a live lease.
PostgreSQL wall time governs expiry; ownership fencing survives host clock adjustments.

Use READ COMMITTED and this compatible order for every lifecycle mutation:
1. Lock one news_items row.
2. Lock the NEWS_INFERENCE queue-admission row.
3. Lock and reread the relevant inference_jobs row.
4. Insert the result when applicable.

Ingestion already follows item, mutex, then predecessor job. Duplicate read paths need no write locks.
Do not discover claims by locking jobs first. Later claims lock candidate items using
FOR UPDATE OF news_items SKIP LOCKED, then take the mutex/job locks and recheck.
Recovery discovers bounded candidate IDs without locking jobs, then processes each in an independent
transaction in the same order. Never retain multiple item locks across a batch.

Later lifecycle public entry points reject ambient transactions and own their transaction boundaries.
Return Claimed only after commit. Use local lock and statement timeouts of two and five seconds.
Timeout/storage failure rolls back the transaction and surfaces as infrastructure failure, not a
worker failure. Do not hold a transaction over inference, HTTP, or waiting for a worker.

Completion-versus-reassignment linearizes at the guarded completion decision under item/mutex/job
locks using freshly sampled database time. A valid completion decision before expiry prevents
reassignment until commit. Rollback means no acceptance. If expiry or reassignment wins, the old
owner is rejected. No exactly-once execution claim is made.

## Ingestion and exact pending capacity

Count exactly all PENDING rows, including rows awaiting expiry cleanup.
Under the durable admission mutex:

`projectedPending = currentPending + 1 - (previousStatus == PENDING ? 1 : 0)`

The latest queued revision must have a job, but that job may be in any supported status.
Pending replacement releases one pending slot. Running, retry-waiting, or terminal predecessors
release none. Reject admission at capacity before mutating the predecessor or latest pointer.

Successful admission inserts the immutable replacement revision, supersedes any nonterminal
predecessor (clearing its ownership), inserts a PENDING job with current policy snapshots, and
updates the latest pointer in one transaction. Terminal predecessors and accepted results remain
unchanged. Duplicate/event-ID/provider-revision conflict resolution still precedes freshness and
capacity policy. Out-of-order and equal-source-timestamp behavior remain unchanged.

Every future increase of PENDING, including RETRY_WAIT -> PENDING, takes the mutex and requires
currentPending + 1 <= capacity. When full, retain RETRY_WAIT without ownership or extra attempts.
Capacity zero may defer until news expires. Do not evict unrelated jobs or bypass capacity through
direct reassignment.

## Later service interfaces and recovery

InferenceJobLifecycleService will expose operation-specific sealed outcomes:
- claimNext(): Claimed(Assignment) or NoWork.
- renew(AssignmentRef): Renewed(leaseExpiresAt) or Rejected(reason).
- complete(AssignmentRef, resultJson): Accepted(receipt), AlreadyAccepted(receipt),
  CompletionConflict, InvalidResult(code, violations), or Rejected(reason).
- fail(AssignmentRef, RECOVERABLE/PERMANENT): RetryWaiting, Failed(code), or Rejected(reason).
- recoverBatch(): RecoverySummary; infrastructure failures remain exceptions.

AssignmentRef carries job ID, assignment number, and ownership token. Assignment additionally carries
the stored News Event v1 payload, server-assigned inference ID, expected backend, result schema version,
and lease expiry. Rejection reasons: NOT_FOUND, NOT_RUNNING, OWNER_MISMATCH, REVISION_OBSOLETE,
NEWS_EXPIRED, LEASE_EXPIRED. Malformed references use INVALID_COMMAND.

A valid recoverable failure goes to RETRY_WAIT unless exhausted; permanent failure goes to FAILED.
Invalid ownership/freshness requests cannot change another assignment. Recovery rechecks current
state under locks; concurrent changes become skips. It processes obsolete/expired pending work,
due running leases, and retry-waiting work, prioritizing cleanup/due leases before capacity retries,
with deterministic timestamp/job-ID ordering.

Default recovery batch size is 100; configurable from 1 to 1000. claimNext invokes one bounded recovery
pass, then inspects at most the configured count of claim candidates. NoWork means no assignment in
this bounded call, not proof of an empty queue. There is no scheduler, startup sweep, or read-triggered
mutation. Restart recovery is explicitly invoked through Java until M3B exposes the entry point.

## Result validation, idempotency, and eligibility

Reuse the compiled authoritative Inference Result v1 schema, format assertions, closed objects, and
schema-owned unique-ticker invariant. Strict parsing rejects duplicate property names. Validate an
immutable copy outside locks, then check provenance under locks:
- inference_id equals the server-issued assignment inference ID;
- event ID equals the job revision; content hash equals the stored revision hash;
- input and result schema versions are 1.0.0;
- backend is schema-approved and equals the job's expected backend;
- ticker set exactly equals stored affected_symbols, including abstention for unextractable symbols.

Worker model details, generated_at, and extraction are untrusted assertions. generated_at never
determines ownership or expiry. Job/assignment/token/acceptance/persistence metadata stay outside
the v1 payload.

For a valid live current owner, invalid contract/provenance atomically makes the job FAILED and stores
no result. Database failure while recording this rejection rolls back. Invalid/stale owners cannot
terminally fail the current assignment.

For valid completion, insert one immutable accepted result and set SUCCEEDED in one transaction.
Any update/insertion/constraint/commit failure rolls back both. An already accepted assignment and
token with an equal JSONB payload returns AlreadyAccepted with the original receipt. Different
payload returns CompletionConflict; different credentials return ownership rejection. Equality ignores
object-property ordering and whitespace; arrays and string values remain significant. Never overwrite.

Replay resolution precedes freshness checks, so a committed completion followed by a lost response
can be retried after expiry or supersession. This acknowledges historical acceptance, not current
eligibility. Later add a Java-only findCurrentEligibleResult(newsItemId) query that requires SUCCEEDED,
the latest revision pointer, and future news deadline at the database read snapshot. No fallback to
old successful results and no result HTTP endpoint.

## Schema and compatibility

V3 extends jobs with counters, policy snapshots, active assignment metadata, terminal time, and failure
code. Constraints enforce recognized states/backends, counter bounds and state eligibility, complete
ownership only while running, terminal timestamps only for terminal states, and supersession/failure
metadata consistency. Existing superseded terminal time is backfilled from superseded_at.

inference_results stores job/revision/inference identity, hash/schema/backend, JSONB payload, accepting
assignment/token, and acceptance time. Composite foreign keys bind the result to its job's revision,
assignment count and backend, and to the immutable revision's hash. Null-safe payload identity checks
prevent missing/mismatched identities. The update/delete trigger protects immutability.
Full schema validation and SUCCEEDED/result pairing remain future Java transaction responsibilities;
database identity checks alone do not certify a valid Inference Result.

Partial indexes cover pending creation order, running lease expiry, and retry-waiting creation order,
with job ID tie-breakers. Existing V2 rows receive zero assignments, maximum three, fake backend,
and no ownership; original business history remains intact. V1 and V2 are unchanged.
Once V3 is committed, subsequent schema corrections require a new migration.

Existing HTTP fields/routes/status codes/cache behavior remain stable. All seven job statuses may
appear in current_status. No ownership, assignment, policy, or persistence internals are exposed.
Supersession fields are null except for SUPERSEDED. Duplicate responses use the stored current status.

## Acceptance and verification

M3A1: populated committed V2 upgrade; fresh migration; old checksums/history and business data preserved;
meaningful job/result constraints and result immutability; policy defaults/binding/snapshots;
replacement/capacity/rollback across new states; duplicates and exact HTTP field compatibility;
all existing ingestion, contracts, loopback HTTP, health and restart regressions.
Construct lifecycle fixture states explicitly with SQL, using real revisions and schema-valid payloads,
not placeholder production lifecycle services.

Later checkpoints must cover competing claims, renewal just before/at/after expiry, worker interruption
across closed contexts, assignment exhaustion, old-owner fencing, completion replay/conflict,
invalid-result termination and storage rollback, both newer-revision race orders, capacity-safe recovery,
expired news before claim/during inference, and current-result eligibility. Use PostgreSQL 18.6
Testcontainers, latches/barriers and bounded futures, and explicitly imported controllable lifecycle
time sources. Microsecond-aligned boundaries; no sleeps.

Run make doctor, make format, make lint, make test, make migration-smoke, git diff --check,
and git status --short from WSL Ubuntu. Preserve dependency pins and leave changes uncommitted
unless separately asked. M3A1 stops before implementing any later lifecycle operation.

## M3A2 implementation boundary

M3A2 exposes only the four approved Java operations; no completion or result placeholder is added.
V1-V3, contracts, dependency pins, Python, and HTTP representations remain unchanged.

The configured recovery batch count is shared by cleanup and retry phases. Cleanup discovers bounded
IDs without job locks, prioritizing obsolete/news-expired work by creation time/job ID, then due leases
by lease expiry/job ID. Remaining budget discovers RETRY_WAIT IDs by creation time/job ID. Lists are
not refilled. Each candidate commits separately; SKIP LOCKED item contention and changed states count
as skips. Fresh under-lock time/freshness checks override advisory discovery cutoffs.
A lease-expired job may requeue in a second transaction during the same call if budget remains.
The summary counts completed candidate transactions, so a job may count twice. A failure stops the
pass and exposes only earlier committed progress plus an optional failed job ID. Claim invokes one
such pass, then inspects a separate bounded PENDING list in creation-time/job-ID order.

Candidate budgets bound work. Two-second lock and five-second statement timeouts do not establish
an overall call deadline or fixed end-to-end latency. Claim/recovery skip locked items; tests of time
sampled after a wait instead hold the admission mutex or use renewal/failure's blocking item lock.
No scheduler, startup sweep, read-triggered mutation, or general retry framework is introduced.

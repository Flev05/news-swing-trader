# ADR 0010: Durable inference-job schema and ingestion compatibility

## Status

Accepted for M3A; schema and ingestion compatibility implemented in M3A1; Java claim/renew/fail/recover implemented in M3A2; validated completion, replay, and eligible-result reads implemented in M3A3.
The complete approved design remains in [the M3A design reference](../m3a-design.md).

## Decision

Add Flyway V3 without modifying V1/V2. Metadata milestone 3A identifies the schema checkpoint,
not completion of the lifecycle. The schema supports PENDING, RUNNING, RETRY_WAIT, SUCCEEDED,
FAILED, SUPERSEDED and EXPIRED; M3A1 implements only ingestion's creation/supersession behavior.

Snapshot validated positive maximum assignments (default three total assignments) and expected
local backend (default fake) on newly created jobs. Retain existing job policy across restarts and
configuration changes. Backend names are stored policy metadata. M3A implemented no execution adapters; M3B2 now
executes fake abstention only, with real-model adapters reserved for future milestones.

Keep the item -> NEWS_INFERENCE mutex -> job lock order. Pending admission uses
pending + 1 - (previous is PENDING ? 1 : 0). A successfully admitted revision supersedes a
nonterminal predecessor and clears ownership; terminal predecessors remain unchanged.
Capacity rejection and write failure preserve predecessor state and latest pointer.

Store accepted results immutably with relational provenance checks and supporting indexes.
M3A3 implements full result validation, atomic acceptance, historical completion replay, and current-result eligibility in Java.
M3A2 implements Java ownership operations and bounded recovery using the approved design. Database checks do not implement those operations.
Public reads and duplicate responses retain their shapes and expose no assignment metadata.

## Consequences

V2 data and immutable revisions survive upgrade. V3 is frozen once committed; subsequent schema
corrections require a new migration. M3A1 tests construct valid lifecycle states explicitly with SQL.
M3A2 adds only Java claim/renew/fail/recover, database-authoritative time, ownership fencing, and
capacity-safe recovery with separate candidate commits and explicit partial progress. Candidate budgets
bound work; per-lock/per-statement timeouts do not establish an overall call deadline.
M3A3 adds the approved completion/query operations without a new migration. That checkpoint added no result or
worker HTTP endpoints, Python changes, scheduler, dependency additions, or model integration.

## M3B1 transport follow-up

M3B1 exposes the existing lifecycle through strict loopback worker HTTP routes without changing
this lifecycle decision, V1–V3, or domain contracts. Java retains every mutation and transaction.
See [the worker protocol](../m3b-worker-protocol.md) and [M3B design](../m3b-design.md).
At the M3B1 checkpoint Python execution remained unimplemented. M3B2 now adds local fake
execution, completion reconciliation, cooperative shutdown, and absolute-deadline interruption
without changing Java production lifecycle behavior. Real-model inference remains future work.
See [M3B2 verification](../m3b2-verification.md) for current execution evidence.

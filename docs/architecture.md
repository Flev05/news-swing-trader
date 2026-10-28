# M3A2 Architecture

The Java 21 modular monolith owns transactional news ingestion and immutable PostgreSQL revisions.
The Python 3.12 worker remains an independent scaffold; there is no cross-process workflow or worker
execution. PostgreSQL 18.6 is the sole Compose service. ADRs 0007–0010 record persistence, admission,
the loopback HTTP business API, and durable lifecycle schema decisions.

```mermaid
flowchart LR
    Client[Local HTTP client] --> Server[127.0.0.1 application server]
    Server --> API[News Event v1 business API]
    API --> Coordinator[Transactional ingestion coordinator]
    Coordinator --> DB[(PostgreSQL 18.6)]
    API --> Reads[Durable event and job reads]
    Reads --> DB
    Server --> Health[Actuator health only]
    Health --> Live[livenessState]
    Health --> Ready[readinessState and db]
    Ready --> DB
    Contracts[contracts/v1 JSON Schemas] --> API
    Contracts --> Python[Python contract tests and fake backend]
```

## Boundaries

- `contracts/v1` remains the authoritative cross-language boundary. Closed Draft 2020-12 schemas,
  portable semantic invariants, and the shared corpus are unchanged.
- Ingestion resolves immutable revision identity before freshness or capacity policy. Item locks
  serialize revision ordering; the durable NEWS_INFERENCE mutex serializes pending admission.
  Supersession, replacement insertion, and latest-pointer updates share one transaction.
- News admission is immutable (`QUEUED` or `OUT_OF_ORDER`); the schema supports all seven M3A job states.
  M3A1 supplies ingestion creation/supersession; M3A2 adds Java-only claim/renew/fail/recover. Business reads reconstruct state from PostgreSQL, not controller memory.
- Flyway V1/V2 are unchanged; V3 adds lifecycle fields, constraints, indexes and immutable results.
  `platform_metadata.milestone=3A` identifies schema support, not completed lifecycle operations.
  The complete approved behavior is recorded in [the M3A design reference](m3a-design.md).
- Ingestion uses item -> queue mutex -> predecessor job locks and credits a capacity slot only
  for a PENDING predecessor. It revokes nonterminal ownership on successful supersession;
  terminal jobs stay unchanged. Positive assignment limits and approved backend names are
  snapshotted per job. Reads expose current status without ownership or assignment metadata.
- All HTTP routes share one server bound exactly to `127.0.0.1`. External binding and separate
  management-listener configuration fail startup; the existing `-1` management-port opt-out remains.
- Actuator exposes only `/actuator/health` and its liveness/readiness groups. Liveness checks only
  application liveness; readiness additionally checks PostgreSQL. All health details and components
  are hidden. Discovery, other Actuator endpoints, and JMX exposure are disabled.
- Acceptance tests use isolated PostgreSQL Testcontainers and real ephemeral-port loopback HTTP.
  Database access denial is restored through resource cleanup. Only post-restoration readiness may
  poll with a deadline. Restart coverage closes one complete context before starting another against
  the same live database and schema, comparing explicitly ordered durable rows and stable metadata.
- The fake backend is a deterministic test seam. Result acceptance, eligible-result queries, worker endpoints, inference, model
  integration, Redis, broker submission, signals, risk, portfolio behavior, orders, and external AI
  access remain absent.

## Java lifecycle transactions

The public lifecycle service rejects ambient transactions and owns READ COMMITTED boundaries.
Candidate discovery takes no job locks. Each mutation locks one item, then the durable queue mutex,
then the job; claim/recovery use SKIP LOCKED on item rows. Each recovery candidate has its own
transaction, with no item lock retained across candidates. Database decision time is sampled after
locking and preparation on the same connection. The ingestion Clock is unchanged.

Claim returns an assignment only after commit; assignment number and a fresh token fence renew/fail.
Recovery prioritizes revision obsolescence, news expiry, then lease expiry/exhaustion. Recoverable work
waits without ownership, and requeue uses the same exact pending-capacity mutex as ingestion.
A recovery exception records earlier committed progress; no outer transaction rolls those commits back.

Recovery's two phases share one candidate budget. A newly interrupted job can consume a cleanup slot
and a later requeue slot in one invocation. Claim adds its own bounded PENDING candidate list after
one recovery pass. NoWork is local to that call. The budget bounds work, while two-second lock and
five-second statement timeouts apply individually and do not impose an overall call deadline.
No scheduler, startup sweep, automatic retry framework, or read-triggered mutation is installed.

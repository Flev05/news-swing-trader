# ADR 0007: Durable news-ingestion persistence foundation

## Status

Accepted for Milestone 2A.

## Decision

M2A persists immutable News Event v1 revisions in PostgreSQL and offers only
package-private persistence operations:

- create a QUEUED revision, its PENDING inference job, and the item's latest pointer in
  one transaction;
- create an OUT_OF_ORDER revision without a job or latest-pointer update;
- return exact provider-revision duplicates without mutation; and
- reject provider-revision and event-ID conflicts.

The M2A repository is not an ingestion entry point. A later M2B coordinator will own idempotency,
freshness, revision ordering, supersession, capacity, and its choice of persistence operation.

Canonical event text uses NFC normalization, LF line endings, and trimmed outer Unicode
whitespace. The SHA-256 content preimage is news-content-v1 plus a zero byte, fixed-order
length-prefixed UTF-8 headline, language, optional summary, optional body, then sorted symbols.
Absent optional fields use the unsigned length marker 0xffffffff. The separate
provider-revision-v1 fingerprint binds stable provider identity, canonical URL, source times,
and content hash.

queue_admission_locks contains the single durable mutex for the NEWS_INFERENCE queue.
Every future transaction that can increase the number of PENDING jobs—including later retry or
requeue operations—must lock that row before making the increase. Transitions that only decrease
the pending count do not require the lock.

## Consequences

Flyway history is the authoritative schema version. platform_metadata.milestone is explicitly
2A, not a claim that all of Milestone 2 is complete. The v1 contract shapes and shared contract
corpus remain unchanged; M2A owns a separate ingestion canonicalization fixture corpus.

M2A adds no HTTP endpoints, admission policy, stale-event handling, capacity enforcement,
revision-ordering policy, job claiming, leases, worker behavior, model access, Redis, or trading
capabilities.

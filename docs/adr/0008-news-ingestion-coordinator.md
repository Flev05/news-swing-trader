# ADR 0008: News ingestion coordinator

## Status

Accepted for Milestone 2B1.

## Decision

M2B1 adds one public application-level coordinator for News Event v1 ingestion. It captures one
Clock instant, canonicalizes and validates the authoritative contract, resolves idempotency before
stale or capacity policy, and owns the transaction around immutable revision persistence.

The NEWS_INFERENCE queue-admission row is locked before a transaction counts or increases PENDING
jobs. A newer revision atomically supersedes the previous PENDING job, creates its replacement,
and updates the latest pointer. Older revisions are immutable OUT_OF_ORDER records without jobs;
distinct unseen revisions at the same source_updated_at are rejected.

## Consequences

M2B1 adds no HTTP endpoint, worker claiming, retry, inference, model access, Redis, or trading
behavior. The existing V2 durable schema remains unchanged and platform metadata continues to
identify the schema milestone as 2A.

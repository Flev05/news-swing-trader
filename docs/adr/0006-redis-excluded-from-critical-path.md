# ADR 0006: Redis excluded from the critical path

## Status

Accepted for the staged architecture.

## Decision

Redis is not required for durable workflow processing. PostgreSQL will own critical state and future durable job coordination.

## Consequences

Milestone 0 has no Redis dependency or Compose service. A future optional cache must never become required for correctness.

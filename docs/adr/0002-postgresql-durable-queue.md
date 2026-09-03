# ADR 0002: PostgreSQL durable queue

## Status

Accepted for the staged architecture.

## Decision

PostgreSQL will become the durable source of truth and future job queue. Future workers will use transactional row claiming rather than an in-memory queue.

## Consequences

Milestone 0 runs PostgreSQL only and adds a Flyway baseline. No queue table or job claim logic exists yet.

# ADR 0009: Loopback-only news HTTP business API

## Status

Accepted for Milestone M2B2A.

## Decision

M2B2A exposes the M2B1 ingestion coordinator through a small Spring MVC API because the durable
persistence path uses blocking JDBC. The API accepts a strict News Event v1 request without a
client-supplied content hash, returns stable admission representations, and provides point reads
for persisted news revisions and inference jobs. PostgreSQL remains the source of truth for every
read; the controller owns no admission or persistence policy.

The application server must bind exactly to `127.0.0.1`. An environment post-processor rejects
non-loopback or separate management-listener configuration before context refresh, and a final
server-factory customizer applies the loopback address defensively. The port remains configurable.

All business errors use stable RFC 9457 Problem Details without internal exception or database
details. Business success and error responses use `Cache-Control: no-store`. The ingestion request
limit is enforced on raw bytes before JSON binding, while closed request models reject unknown
fields and keep content-hash computation server-side.

## Consequences

The API is local-only and unauthenticated. Existing News Event v1 contracts, the V2 database
schema, and M2B1 transaction and queue-lock semantics remain unchanged. M2B2A adds no Actuator,
health endpoints, separate management server, restart-recovery verification, Redis, worker
execution, inference, external calls, or trading behavior; operations and recovery remain the
separate M2B2B checkpoint.

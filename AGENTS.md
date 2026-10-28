# Repository Rules

## Scope

This repository is a staged bootstrap for a news-driven US equity swing-trading system. The current checkpoint is M3B1: a strict loopback worker HTTP API adapts the existing Java claim, renewal, failure, and completion lifecycle; authoritative v1 contracts, immutable PostgreSQL news revisions, transactional ingestion admission, a loopback-only local HTTP business API, narrowly scoped Actuator health, and durable restart recovery acceptance tests are present.

Metadata 3A identifies the schema checkpoint, not completed lifecycle functionality. The complete M3A design is in docs/m3a-design.md. V1/V2 and, once committed, V3 are immutable migration history; corrections require a new migration.

It has no result HTTP endpoint, Python worker execution, inference/model integration, Redis, broker integration, signals, risk decisions, portfolio behavior, orders, or external AI access. Worker transport is documented in docs/m3b-worker-protocol.md; the future M3B2 design and absolute-deadline clarification are in docs/m3b-design.md.

## Architecture boundaries

- `apps/core-java` is the Java 21 modular-monolith scaffold.
- `apps/ai-worker` is the Python 3.12 local-worker scaffold.
- `contracts` holds the only authoritative cross-language contracts.
- `infra` contains local infrastructure only. PostgreSQL is the sole Compose service.
- Never add live-trading, broker-submission, Alpaca, external-AI, or credential-handling code without an explicitly approved milestone.

## Commands

Run commands from WSL Ubuntu in this repository:

```bash
make bootstrap
make doctor
make format
make lint
make test
make migration-smoke
```

Use `make db-up`, `make db-logs`, and `make db-down` for the local PostgreSQL container.

## Definition of done for a change

- Keep changes within the approved milestone.
- Run the relevant Make targets.
- Keep credentials out of Git and document new direct dependencies.
- Leave changes uncommitted unless explicitly asked to commit.

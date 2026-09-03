# Repository Rules

## Scope

This repository is a staged bootstrap for a news-driven US equity swing-trading system. The current milestone is tooling only: it has no news workflow, schemas, broker integration, orders, positions, model download, or external AI access.

## Architecture boundaries

- `apps/core-java` is the Java 21 modular-monolith scaffold.
- `apps/ai-worker` is the Python 3.12 local-worker scaffold.
- `contracts` will hold the only authoritative cross-language contracts beginning in Milestone 1.
- `infra` contains local infrastructure only. PostgreSQL is the sole Compose service in Milestone 0.
- Never add live-trading, broker-submission, Alpaca, external-AI, or credential-handling code without an explicitly approved milestone.

## Commands

Run commands from WSL Ubuntu in this repository:

```bash
make bootstrap
make doctor
make format
make lint
make test
```

Use `make db-up`, `make db-logs`, and `make db-down` for the local PostgreSQL container.

## Definition of done for a change

- Keep changes within the approved milestone.
- Run the relevant Make targets.
- Keep credentials out of Git and document new direct dependencies.
- Leave changes uncommitted unless explicitly asked to commit.

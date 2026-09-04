# News Swing Trader

Production-oriented scaffolding for a future news-driven US equity swing-trading system. The
repository is currently at **Milestone M2B2A**: authoritative v1 contracts, immutable PostgreSQL
news revisions, transactional ingestion admission, and a loopback-only local HTTP business API.

It has no Actuator or health endpoint, restart recovery test, job claiming, worker execution,
signal generation, broker integration, model download, external AI access, risk decisions, or
order intents.

The contract definitions and their encoded semantic invariants are documented in
`contracts/README.md`.

## Environment

Use the Ubuntu WSL distribution and the Linux filesystem:

```bash
wsl.exe -d Ubuntu
cd /home/xeemt/code/news-swing-trader
```

The preflight verified Java 21.0.12, Maven 3.9.12, Docker Engine 29.5.3, Docker Compose 5.1.4, and a cached `postgres:18.6` image. Python 3.12 is provisioned locally by `make bootstrap`; Ubuntu's system Python 3.14 is left unchanged.

## Quick start

```bash
make bootstrap
make doctor
make format
make lint
make test
make migration-smoke
```

`make bootstrap` downloads a checksum-verified, repository-local `uv` binary, installs an isolated Python 3.12.14 runtime under ignored `.tools/`, creates the worker virtual environment, and writes `apps/ai-worker/uv.lock`.

PostgreSQL is optional for ordinary unit tests because the migration smoke test uses Testcontainers. Start the local database when inspecting it manually:

```bash
make db-up
make db-logs
make db-down
```

The local Compose stack contains PostgreSQL only. The Java application can be started against it with:

```bash
./mvnw -f apps/core-java spring-boot:run
```

## Tooling versions

Direct dependency and tool versions are pinned only after they resolve and pass repository checks on this host:

- Java: 21.0.12; Maven Wrapper distribution: 3.9.12.
- Spring Boot: 4.1.1; Java formatting plugin: Spotless 3.9.0.
- Java JSON Schema validator: NetworkNT 3.0.7.
- Python runtime: 3.12.14; uv: 0.12.9.
- Python JSON Schema validator: jsonschema 4.26.0 with non-GPL format checks.
- Python type stubs: types-jsonschema 4.26.0.20260518.
- Python development dependencies: Ruff 0.16.5, mypy 2.3.1, pytest 9.1.1.

The Spring Boot BOM manages Flyway, PostgreSQL JDBC, and Testcontainers versions; their resolved versions are visible through `./mvnw -f apps/core-java dependency:tree`.

## M2B2A boundary

- The servlet API binds only to `127.0.0.1`; external address or separate management-listener
  configuration fails startup.
- The API accepts strict News Event v1 input, computes the content hash server-side, and returns
  durable admission and current job status from PostgreSQL.
- Business success and RFC 9457 problem responses are not cacheable.
- M2B2A deliberately excludes Actuator, health endpoints, and restart recovery verification; those
  are the M2B2B checkpoint.

Later milestones add operations recovery, local inference, deterministic decisioning, DRY_RUN
intents, reliability controls, and the end-to-end vertical slice. They are deliberately absent from
this checkpoint.

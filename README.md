# News Swing Trader

Production-oriented scaffolding for a future news-driven US equity swing-trading system. This repository is currently at **Milestone 0**: local tooling, a PostgreSQL baseline migration, and a deterministic fake inference placeholder only.

It does not ingest news, define trading contracts, generate signals, connect to Alpaca, download a model, contact any AI API, create orders, or submit broker requests.

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

Direct dependency and tool versions are pinned only after they resolve and pass Milestone 0 checks on this host:

- Java: 21.0.12; Maven Wrapper distribution: 3.9.12.
- Spring Boot: 4.1.1; Java formatting plugin: Spotless 3.9.0.
- Python runtime: 3.12.14; uv: 0.12.9.
- Python development dependencies: Ruff 0.16.5, mypy 2.3.1, pytest 9.1.1.

The Spring Boot BOM manages Flyway, PostgreSQL JDBC, and Testcontainers versions; their resolved versions are visible through `./mvnw -f apps/core-java dependency:tree`.

## Milestone 0 verification

- Java compilation and the Flyway/Testcontainers migration smoke test.
- Python formatting, linting, type checking, and deterministic fake-backend tests.
- Docker Compose syntax and PostgreSQL health checks.

Later milestones add contracts, ingestion, durable jobs, local inference, deterministic decisioning, DRY_RUN intents, reliability controls, and the end-to-end vertical slice. They are deliberately absent from this milestone.

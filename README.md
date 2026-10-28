# News Swing Trader

Production-oriented scaffolding for a future news-driven US equity swing-trading system. The
repository is currently at **Milestone M2B2B**: authoritative v1 contracts, immutable PostgreSQL
news revisions, transactional ingestion admission, a loopback-only local HTTP business API,
Actuator health, and durable restart recovery acceptance tests.

It has no job claiming, worker execution,
signal generation, broker integration, model download, external AI access, risk decisions, or
order intents.

The contract definitions and their encoded semantic invariants are documented in
`contracts/README.md`.

## Environment

Open the Ubuntu WSL distribution and use a checkout on its Linux filesystem.
This example assumes the checkout is under `~/code/`; adjust the directory if needed:

```bash
wsl.exe -d Ubuntu
cd "$HOME/code/news-swing-trader"
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

The Java integration and acceptance tests require a working Docker runtime and use isolated
PostgreSQL Testcontainers; the Compose database is not required for tests. Start the local database when inspecting it manually:

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

The direct `spring-boot-starter-actuator` dependency supplies the built-in health endpoint.
No metrics exporter or additional test dependency is added. The Spring Boot BOM manages Actuator,
Flyway, PostgreSQL JDBC, and Testcontainers versions; their resolved versions are visible through `./mvnw -f apps/core-java dependency:tree`.

## M2B2B boundary

- The servlet API binds only to `127.0.0.1`; external address or separate management-listener
  configuration fails startup.
- The API accepts strict News Event v1 input, computes the content hash server-side, and returns
  durable admission and current job status from PostgreSQL.
- Business success and RFC 9457 problem responses are not cacheable. Unmapped resources return
  a sanitized 404 problem rather than falling through to the generic 500 handler.
- Actuator shares the same application server and port. Only health is exposed; discovery and JMX
  exposure are disabled, and health details/components are never returned.
- Liveness includes only `livenessState`; readiness includes `readinessState` and `db`. No separate
  management listener, authentication layer, or general observability API is added.

| Loopback URL | Healthy database | Database unavailable |
| --- | --- | --- |
| `/actuator/health` | 200 UP | 503 DOWN |
| `/actuator/health/liveness` | 200 UP | 200 UP |
| `/actuator/health/readiness` | 200 UP | 503 DOWN |

The default base URL is `http://127.0.0.1:8080`; `TRADER_HTTP_PORT` changes the application port.
The existing `management.server.port=-1` opt-out disables management HTTP. Any management address
or other management-port setting is rejected at startup.

The health acceptance tests deny new connections to a dedicated Testcontainer database, terminate
its existing client sessions with a bounded PostgreSQL call, and restore access in resource cleanup.
Only readiness recovery after restoration may poll, using an overall deadline and bounded HTTP
requests. Tests never stop the Compose database or use sleeps.

The restart acceptance test closes two complete application contexts sequentially against one
unchanged PostgreSQL instance and migrated schema. It exercises ingestion and durable resource
reads over real loopback HTTP, then checks stale duplicate resolution, duplicate precedence at
capacity, capacity rejection, and explicitly ordered durable snapshots. Fixed clocks are registered
through test configuration outside the application scan. No application-state assertion is retried.
The V2 schema and its `platform_metadata` milestone `2A` remain unchanged.

Later milestones add operations recovery, local inference, deterministic decisioning, DRY_RUN
intents, reliability controls, and the end-to-end vertical slice. They are deliberately absent from
this checkpoint.

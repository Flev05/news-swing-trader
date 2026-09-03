# Milestone 0 Architecture

Milestone 0 establishes the repository boundary only. PostgreSQL is the sole running service in Compose. The Java and Python applications are independent scaffolds; neither consumes nor produces trading-domain data.

```mermaid
flowchart LR
    Dev[Developer in WSL Ubuntu] --> Make[Make targets]
    Make --> Core[Java 21 core scaffold]
    Make --> Worker[Python 3.12 fake placeholder]
    Make --> Db[(PostgreSQL 18.6)]
    Core --> Db
    Worker -. no database workflow in M0 .-> Db
    Future[Future milestones: contracts, ingestion, local inference, decisions, DRY_RUN intents] -. excluded .-> Core
```

## Boundaries

- PostgreSQL is prepared as the future durable source of truth, but Milestone 0 contains only Flyway baseline metadata.
- The fake backend is a deterministic test seam, not an LLM, extraction system, or trading strategy.
- No HTTP/domain API is exposed. Spring Boot runs as a non-web application when started manually.
- `contracts/`, `research/`, and `tests/fixtures/` are reserved for future milestones. Cross-language schemas begin in Milestone 1.
- Broker, market-data, risk, portfolio, execution, queue, order, and model-runtime code are intentionally absent.

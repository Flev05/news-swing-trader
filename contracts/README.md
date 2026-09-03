# Versioned contracts

This directory is the only authoritative cross-language contract source. Milestone 1 uses
JSON Schema Draft 2020-12 and publishes immutable major-version directories. Compatible
clarifications may retain `schema_version` `1.0.0`; an incompatible shape requires a new
directory and version.

## News Event v1

A news event is one immutable source revision. Its shape contains:

- system identity and tracing: `event_id`, `correlation_id`, and optional `causation_id`;
- source identity: provider, source `news_id`, source `revision_id`, and canonical URL;
- UTC source-created, source-updated, received, and deadline timestamps;
- the server-computed SHA-256 `content_hash`;
- headline, normalized content, and unique `affected_symbols`.

The event deliberately has no retry, delivery-attempt, persistence, or job state.

## Inference Result v1

An inference result preserves system-assigned event and local-model provenance, then carries a
non-empty `ticker_results` array. Every ticker result independently records issuer role, event
type, direction, materiality, relevance, novelty, expected horizon, schedule status,
information status, optional structured numeric facts, abstention, and bounded reason codes.

This supports one article assigning different classifications to different tickers. The schema
has no confidence, probability, free-form summary, evidence text, signal, risk, portfolio,
order-intent, or broker fields.

Numeric fact decimal values (`actual`, `prior`, and `consensus`) are canonical decimal
strings. They are never JSON numbers. Currency facts require both `unit: "currency"` and an
ISO-style three-letter currency code.

## Semantic invariants

The schemas are closed with `additionalProperties: false` at every payload object level.
Format assertions are mandatory. Portable conditional schemas enforce abstention and numeric
fact consistency. `ticker_results` declares `x-unique-by: "ticker"`; both runtime validators
enforce this schema-owned invariant because standard JSON Schema cannot express uniqueness by
one object property.

All operational timestamps are RFC 3339 UTC values ending in uppercase `Z`. Inference
backends are restricted to `fake`, `llama.cpp`, and `ollama`.

The deterministic corpus at `tests/fixtures/contracts/v1/corpus.json` is the executable
compatibility suite. Every invalid case names the exact expected keyword and JSON-pointer
location, and both Java and Python assert that it fails only for that intended rule.

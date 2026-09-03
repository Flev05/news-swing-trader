# ADR 0003: Local inference only

## Status

Accepted for the staged architecture.

## Decision

Future inference uses local runtimes only. External AI APIs are prohibited. llama.cpp is the intended production backend and Ollama is optional.

## Consequences

Milestone 0 supplies only a deterministic fake placeholder. It downloads no model and includes no llama.cpp, Ollama, or network inference adapter.

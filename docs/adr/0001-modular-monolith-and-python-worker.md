# ADR 0001: Modular monolith and Python worker

## Status

Accepted for the staged architecture.

## Decision

The future core engine will remain one Java modular monolith. Local inference will run in one separate Python worker process.

## Consequences

The boundary is small and operationally simple. At Milestone 0 this decision created only two independent scaffolds, with no cross-process workflow.
M3B2 now implements the local fake-worker HTTP workflow; real-model inference remains future work.

# ADR 0001: Modular monolith and Python worker

## Status

Accepted for the staged architecture.

## Decision

The future core engine will remain one Java modular monolith. Local inference will run in one separate Python worker process.

## Consequences

The boundary is small and operationally simple. This milestone creates only the two independent scaffolds; no cross-process workflow exists yet.

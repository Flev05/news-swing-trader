# Local fake worker

The Milestone 0 scaffold CLI and backend remain available unchanged:

```bash
.tools/uv/0.12.9/uv run --directory apps/ai-worker --locked --no-sync --offline news-swing-ai-worker "sample input"
```

M3B2 adds one local HTTP worker, with no database access or real inference:

```bash
.tools/uv/0.12.9/uv run --directory apps/ai-worker --locked --no-sync --offline python -m news_swing_ai.worker --base-url http://127.0.0.1:8080
# Process at most one delivered assignment, or return when this claim finds no work:
.tools/uv/0.12.9/uv run --directory apps/ai-worker --locked --no-sync --offline python -m news_swing_ai.worker --base-url http://127.0.0.1:8080 --once
```

Run these commands from the repository root in WSL Ubuntu after `make bootstrap`.
Only an explicit `http://127.0.0.1:PORT` origin is accepted. Environment proxies are ignored.
Each request uses a fresh connection, socket timeouts, a bounded response, and its own absolute
deadline callback. Cancellation shuts down the request's socket before closing it; timer cleanup
joins the callback before returning. Routes and control requests are serialized.

The worker renews before processing, during long processing at the approved interval, and once
more before completion. A cooperative processor thread produces one Inference Result v1 with
the assignment's inference ID and stored event ID/hash. Every affected ticker receives explicit
`unsupported_event_type` abstention and unknown classifications. The backend identifies itself
as `fake / deterministic-abstention / 1`; it makes no financial predictions. The result and
outer completion bytes are frozen for all retries.

Uncertain renew/fail calls have three sends within acknowledged safe authority. Uncertain
completion has three sends within 60 seconds, using exactly the same bytes, without heartbeats
or failure commands. Historical completion reconciliation may outlive local lease expiry.
Before reporting success, the receipt must match the active job, stored event, and assigned
inference identities. Mismatched receipts reconcile within the same frozen-byte completion budget.
Assignment expiry, renewed expiry, and receipt acceptance time require complete UTC timestamps
`YYYY-MM-DDTHH:MM:SS[.fraction]Z`; valid fractional seconds are accepted.
An authority rejection cancels and joins processing before continuous mode can claim again.
A processor that does not stop within the bounded join causes exit, never another claim.

SIGINT/SIGTERM set a stop event and a 20-second grace deadline. Idle operation stops; a delivered
claim is resolved and released through RECOVERABLE failure while authority remains confirmed.
In-flight renewal is resolved before release. Once completion begins, shutdown only reconciles
that completion. At grace expiry blocked socket I/O is interrupted; Java owns durable recovery.

Exit codes: **0** normal success/shutdown/no work in once mode; **2** configuration, protocol,
or result defect; **3** unresolved delivery; **4** explicit authority loss in once mode.
Logs contain only fixed operational text, never transport objects, ownership credentials,
result documents, request bodies, or exception contents.

**Ambiguous claim recovery is manual:** stop the worker, fully close/restart Java against the
same database to eliminate the in-flight claim, allow its durable lease to expire, then restart
one worker. A fixed cooldown without eliminating the in-flight call is insufficient. Do not
launch a second worker to probe the uncertain claim. Claim-driven Java recovery then handles
persisted work. There is no Python request ledger, database, or automatic Java restart.

See [approved design](../../docs/m3b-design.md), [wire protocol](../../docs/m3b-worker-protocol.md),
and [verification and disposable demo](../../docs/m3b2-verification.md).

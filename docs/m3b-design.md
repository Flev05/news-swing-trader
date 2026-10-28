# M3B design

M3B1 implements only the [Java worker HTTP protocol](m3b-worker-protocol.md).
M3B2 below remains approved future work, not an implemented worker or authorization to
change Python during M3B1. V1–V3, domain contracts, dependencies and infrastructure remain
unchanged. Java owns all database mutations.

## Checkpoint boundaries

M3B1: strict Java HTTP adaptation of claim/renew/fail/complete, byte limits, no-store,
sanitized errors, real HTTP/PostgreSQL tests, and protocol/checkpoint documentation.
Controllers introduce no transaction, ownership, recovery, or domain-validation logic.

M3B2: one Python process, one active assignment, local HTTP only, deterministic abstention
results, heartbeats, shutdown, transport reconciliation, and actual-process acceptance tests.
Preserve the scaffold CLI; add `python -m news_swing_ai.worker` with `--base-url` and
`--once`. No Python persistence, direct PostgreSQL, request ledger, scheduler, automatic
launch, real model, external AI/provider/broker, Redis, dashboard, signals, risk, portfolio,
or orders.

## Future Python flow

Use standard-library HTTPConnection directly so environment proxies cannot route requests.
Allow only `http://127.0.0.1:PORT` origins with no userinfo, query, fragment, or non-root path.
Use fixed paths and reject redirects.

Serialize control requests. A cooperative processing thread permits heartbeat renewal.
On claim delivery, verify supported fake/backend schema metadata and renew before processing.
Create each result once with injected UTC time, assigned inference ID, actual stored event
ID/hash, and one abstention per affected symbol in stored order. All classifications are
unknown; numeric facts are empty; reason is unsupported_event_type. Identify model backend
fake, name deterministic-abstention, version 1, prompt_template_version 1.0.0.
Freeze the exact completion request bytes, including generated_at, for every retry.

Renew at most every 10 seconds during processing and obtain a final acknowledged renewal
before completion. Stop heartbeat requests during completion reconciliation. Cancel/join a
lost assignment's processor before claiming again. Java remains the authority on every request;
local clocks are scheduling hints only.

Defaults: connection timeout 2s; claim read timeout 60s and absolute request deadline 65s;
other reads 10s and absolute request deadline 15s; response limit 1 MiB; no-work polling 2s.
Use interruptible pre-send connection backoff 1,2,4,8,10s (then capped at 10s); once mode allows
three attempts. Translate acknowledged expiry into a conservative monotonic deadline with a
2s safety margin. Heartbeat interval is min(10s, remaining safe time / 3); clip operations
to remaining safe time and stop rather than schedule intervals below 100ms.
Claim deadlines are operational limits, not a bound on Java's existing recovery work.

## Absolute deadline clarification for M3B2

An absolute transport deadline must actually interrupt blocked I/O.
Do not assume socket.close() alone guarantees that behavior. Use shutdown before close
where appropriate, or a demonstrably equivalent approach. Cover blocked header and blocked
body reads with bounded tests. Bind each deadline callback to its own request/connection
identity and synchronize cancellation/cleanup so an old callback cannot close a subsequent
request's connection. Test that race explicitly. No Python implementation of this behavior
belongs to M3B1.

## Future transport recovery and shutdown

- Proven connection failure before any request bytes could be sent: bounded backoff.
- Ambiguous claim delivery, malformed/truncated claim response, post-send timeout/reset, or
  claim 5xx: stop the worker without another claim (selected policy).
- Recover ambiguous claim by fully closing/restarting Java against the same database,
  eliminating the in-flight call, then allowing durable lease expiry and restarting one worker.
  Existing claim-driven recovery handles persisted work. A fixed cooldown alone is insufficient.
- Uncertain renewal/503: suspend progress toward completion; retry the same reference at most
  three times with 1s then 2s waits, within the last acknowledged safe lease. If unconfirmed,
  cancel processing and exit without completion/failure.
- Uncertain completion/5xx: at most three sends within 60s, waiting 1s then 2s, using the same
  reference and exact frozen bytes. Accepted and AlreadyAccepted both succeed. Replay may
  continue beyond local expiry to acknowledge a historical commit.
- Explicit authority rejection: stop assignment activity. Continuous mode can resume claiming
  only after cooperative cancellation/join. Once mode exits 4.
- Invalid result or completion conflict: worker defect; exit 2 without another failure command.
- Uncertain failure/503: retry identical reference/kind at most three times, with 1s then 2s
  waits within safe authority. NOT_RUNNING means released/no longer owned, not proof of prior
  failure acceptance. Unresolved failure exits for lease recovery.
- Any unresolved completion exhausts its budget with no failure and no new claim.
  Transport recovery never implements a second stored assignment-attempt budget.

SIGINT/SIGTERM handlers set a stop event; shutdown grace is 20s. Idle stops immediately.
During processing, cooperatively cancel/join and report RECOVERABLE while authority is
confirmed (selected policy). Resolve in-flight renewals before release, within grace.
Resolve a claim within grace and release a delivered assignment; aborting an unresolved
claim yields ambiguous delivery. After completion starts, only replay frozen completion
within remaining grace; never replace it with failure. At grace expiry interrupt I/O and
exit, leaving durable recovery to Java.

Exit codes: 0 normal completion/shutdown/no work in once mode; 2 configuration/protocol/result
defect; 3 unresolved transport; 4 explicit authority loss in once mode.

## Future process acceptance

Start isolated PostgreSQL and the real Java servlet app, then launch the actual Python CLI
through repository-managed uv with locked/no-sync/offline tooling. Ingest synthetic news over
HTTP and verify one immutable result paired with SUCCEEDED. Use a test-only forwarding gate
to drop an acknowledgement only after Java commits; the retry must be byte-identical and
AlreadyAccepted. Gate requests for ownership-loss/shutdown tests; kill an assigned process
and advance controlled lifecycle time to demonstrate recovery on a replacement assignment.
Use bounded latches, deadlines, process waits and cleanup, with isolated fixtures.
Do not induce races with arbitrary sleeps or retry failed business assertions.
Keep test hooks out of production CLI/component scanning.

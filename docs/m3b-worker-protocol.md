# M3B local worker HTTP protocol

Status: Java transport was implemented in M3B1 and is used by the implemented M3B2 local
Python fake worker. Real-model inference remains future work. The authoritative News Event v1
and Inference Result v1 contracts remain unchanged. See [M3B design](m3b-design.md) for the worker
and its recovery policy, and [M3B2 verification](m3b2-verification.md) for execution evidence.

## Listener and delegation

All routes share the enforced `127.0.0.1` application listener (default port 8080).
The existing management listener restrictions and health endpoints are unchanged.
Controllers invoke exactly one existing lifecycle service operation; they do not own
transactions or implement recovery, ownership, freshness, result validation, or database writes.
Claim's existing bounded recovery pass remains inside `claimNext()`.
There is no separate recovery route, scheduler, automatic worker launch, or result GET.

## Transport documents

All names below are exact snake_case JSON properties. Closed envelopes require every listed
property, prohibit null, and enforce exact JSON types without string/number/boolean coercion.
UUIDs use the full 8-4-4-4-12 hexadecimal representation. Assignment numbers are positive
32-bit JSON integers. Failure kinds are case-sensitive.

```text
AssignmentRef = {
  "job_id": UUID string,
  "assignment_number": positive integer,
  "ownership_token": UUID string
}
Assignment = {
  "ref": AssignmentRef,
  "news_event_json": string,
  "inference_id": UUID string,
  "expected_backend": string,
  "result_schema_version": string,
  "lease_expires_at": UTC timestamp
}
Receipt = {
  "job_id": UUID string,
  "news_event_id": UUID string,
  "inference_id": UUID string,
  "accepted_at": UTC timestamp
}
```

`news_event_json` is the stored complete News Event v1 document, including actual identity,
content hash, deadline, and all affected symbols. The worker must use the assigned inference ID.
Transport references and receipts are outside the domain documents. A completion receipt must
match the active reference's job ID, the stored event ID, and the assigned inference ID.
A mismatched receipt is an uncertain acknowledgement: replay the same frozen completion within
the existing budget, without regeneration, failure reporting, success, or another claim based on
that acknowledgement.

UTC timestamps require `YYYY-MM-DDTHH:MM:SS[.fraction]Z`, including the date, uppercase T,
complete time, and terminal uppercase Z. Fractional seconds are optional; numeric offsets,
incomplete timestamps, timezone-naive values, and malformed dates/times are rejected. This applies
to assignment expiry, renewal expiry, and receipt acceptance time without changing their existing
ambiguous-response recovery rules.

All operations use POST with UTF-8 `application/json`. No query parameters are accepted.
`Content-Encoding` must be absent or `identity`; compression is unsupported.

| Route | Exact request | HTTP 200 response |
| --- | --- | --- |
| `/api/v1/worker/claim` | `{}` | `{"outcome":"CLAIMED","assignment":Assignment}` |
| Same, without delivery | `{}` | `{"outcome":"NO_WORK"}`, header `Retry-After: 2` |
| `/api/v1/worker/renew` | `{"assignment":AssignmentRef}` | `{"outcome":"RENEWED","lease_expires_at":"…"}` |
| `/api/v1/worker/fail` | `{"assignment":AssignmentRef,"kind":"RECOVERABLE"}` or kind `PERMANENT` | `{"outcome":"RETRY_WAITING"}` or `{"outcome":"FAILED","failure_code":"…"}` |
| `/api/v1/worker/complete` | `{"assignment":AssignmentRef,"result_json":"…"}` | `{"outcome":"ACCEPTED","receipt":Receipt}` or outcome `ALREADY_ACCEPTED` |

`NO_WORK` means only that this bounded call delivered no assignment. The queue can contain
work that this call did not examine or could not requeue. Claim performs at most one configured
recovery pass and one bounded pending-candidate scan. Recovery defaults to 100 candidates;
cleanup and requeue each consume candidate budget. Per-lock/per-statement timeouts of two/five
seconds are not a request-wide latency guarantee.

Failure codes returned by successful failure reporting are `WORKER_RECOVERABLE` and
`WORKER_PERMANENT`. A recoverable failure at the stored assignment limit returns FAILED.

## Strict parsing and original result preservation

Raw request limits are fixed: 8192 bytes for claim/renew/fail and 1048576 bytes for completion.
The check precedes media/envelope parsing and applies to declared lengths and streamed/chunked
bodies. Exact boundaries are accepted. Container-normalized route aliases receive the same filter
protection. Oversized-request responses close the connection rather than
trying to drain an unbounded body. No transport property changes these limits in M3B1.

The worker parser has independent document/string constraints; ingestion's mapper and body
limit are unchanged. Malformed UTF-8 is rejected rather than replaced. Envelopes reject
duplicate decoded property names, trailing documents/garbage, unknown fields and invalid types.

The outer `result_json` string is decoded once and delivered unchanged to
`complete(AssignmentRef, String)`. It is never bound as an inner JSON tree or reserialized.
An empty, malformed, duplicate-containing, Unicode-invalid, or schema-invalid inner document
is a domain validation input, not an envelope error.

Existing lifecycle precedence is retained: historical accepted credentials resolve replay
before current freshness; current ownership and freshness precede invalid-result failure.
A live owner's invalid result terminally fails with no result; another owner cannot fail it.
Matching historical valid output returns the original receipt under PostgreSQL JSONB equality.
Changed or invalid historical output conflicts without mutation or unsafe JSONB conversion.

## Errors and privacy

Errors use `application/problem+json` with exactly `type`, `title`, `status`, `detail`,
`instance`, and `code`. Types are `urn:news-swing:problem:<lowercase-hyphenated-code>`.
Details are bounded fixed text; no request values, parser messages, validation paths/messages,
SQL, exception causes, failed recovery job IDs, or partial-progress details are returned.
`instance` contains the resource path without its query string.

| Condition | Status | code |
| --- | --- | --- |
| JSON syntax, duplicate envelope/reference key, trailing input, malformed UTF-8 | 400 | MALFORMED_JSON |
| Unknown envelope/reference property | 400 | UNKNOWN_FIELD |
| Missing/empty body, null/nonobject root, missing/null/wrong field type, malformed UUID, invalid assignment number/kind, query parameters | 400 | INVALID_COMMAND |
| Lifecycle INVALID_COMMAND | 400 | INVALID_COMMAND |
| Lifecycle NOT_FOUND | 404 | ASSIGNMENT_NOT_FOUND |
| Other lifecycle rejection | 409 | NOT_RUNNING, OWNER_MISMATCH, REVISION_OBSOLETE, NEWS_EXPIRED, LEASE_EXPIRED |
| Different/invalid historical completion | 409 | COMPLETION_CONFLICT |
| Live-owner invalid result | 422 | INVALID_RESULT or PROVENANCE_MISMATCH |
| Raw request exceeds limit | 413 | PAYLOAD_TOO_LARGE |
| Missing/unsupported media type or non-UTF-8 charset | 415 | UNSUPPORTED_MEDIA_TYPE |
| Unsupported content encoding | 415 | UNSUPPORTED_CONTENT_ENCODING |
| Unsupported response media | 406 | NOT_ACCEPTABLE |
| Unsupported method | 405 | METHOD_NOT_ALLOWED |
| Unmapped resource | 404 | RESOURCE_NOT_FOUND |
| Storage/transaction/recovery infrastructure failure | 503 | INFRASTRUCTURE_FAILURE |
| Unexpected internal failure | 500 | INTERNAL_ERROR |

Every worker response is `Cache-Control: no-store`, including errors. No worker response
contains a Location header. No ownership credential appears in public news/job GETs,
duplicate admission responses, URLs, errors, or application logs. Credentials are delivered
only in the claim response and supplied only in subsequent request bodies. Sensitive transport
objects have redacted string representations. HTTP access/body logging is not enabled.

An infrastructure response does not prove that an ambiguous commit did not occur. A recovery
failure may leave earlier candidate commits intact, exactly as in M3A2. No transport layer
retries or compensation are performed by Java.

## Verification

`WorkerEnvelopeParserTest` checks exact parsing and inner-string preservation.
`WorkerApiControllerTest` verifies delegation, every rejection mapping, and sanitized 500/503.
`WorkerHttpApiIntegrationTest` runs real loopback HTTP against isolated PostgreSQL 18.6,
including transitions, replay after expiry/supersession, invalid-owner precedence, raw-byte
boundaries/chunking, Unicode, privacy, rollback, and partial recovery.

The authoritative domain corpus and existing lifecycle, business HTTP, health, restart,
and migration tests remain part of the repository gates.

## Inspecting accepted results locally

Use the existing `GET /api/v1/inference-jobs/{jobId}` to inspect current_status. A result
endpoint is intentionally absent. With the local database connection, inspect only safe
columns (replace the UUID below with the public job ID):

```sql
SELECT j.job_id, j.status, j.assignment_count,
       r.inference_id, r.accepted_at, r.result_payload
FROM trader.inference_jobs j
LEFT JOIN trader.inference_results r ON r.job_id = j.job_id
WHERE j.job_id = '11111111-1111-1111-1111-111111111111'::uuid;
```

Success has status SUCCEEDED and exactly one immutable result. Do not select ownership_token
or accepted_owner_token into demo output. The implemented fake worker and executed disposable
vertical-slice demo are documented in [M3B2 verification](m3b2-verification.md).

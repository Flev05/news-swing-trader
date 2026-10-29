# M4A1 preparation: standalone local extraction benchmark

Status: **preparation only**. All 40 synthetic references are provisional and pending
human review. No corpus freeze, runtime/model setup, holdout inference or measured
model performance is claimed. M4A2 requires separate approval before executing setup/run.
The operational M3B2 worker remains fake-only. Java's 534-test verification is reused.

## Commands and boundaries

Run from the repository root in WSL with the existing Python 3.12 environment:

```bash
apps/ai-worker/.venv/bin/python -m news_swing_ai.benchmark check
apps/ai-worker/.venv/bin/python -m pytest apps/ai-worker/tests/test_benchmark.py -o addopts= -q
make format
make lint
make test-python
git diff --check
```

`check` validates partitioning, evidence pointers, reference examples, hashes and both
authoritative v1 schemas offline. It neither loads a runtime nor claims label correctness.
`run` starts only a supervised local runtime, requires `--execute-local`, refuses existing
run directories, and checks artifact identity before startup. Pending references require
`--allow-provisional`; such diagnostic runs can never recommend integration.
`evaluate` runs offline against saved outputs and checks that inputs/references/manifest
hashes still match the run. It does not silently rescore edited labels.
Exit codes: 0 successful check/completed run/eligible evaluation; 1 evaluation not eligible;
2 configuration/data error; 3 incomplete/interrupted runtime execution.

There are no new Python dependencies, dependency-pin or lockfile changes. Standard-library
HTTP/subprocess/Decimal/hash facilities and existing jsonschema are sufficient.
llama.cpp and its CUDA libraries are future experiment runtime dependencies only. The
original worker, fake generator, HTTP protocol, Java, migrations and contracts are untouched.
The only shared Python change is the backward-compatible `contracts.payload_errors` helper.

Inputs are `tests/fixtures/extraction/m4a-v1/inputs.json`; references and supporting source
quotes are in `references.json`; split/review/provenance metadata are in `manifest.json`.
The generic prompt lives alongside the benchmark source. A strict allowlist exposes only
headline, content and affected symbols: never case IDs, split/category labels, references,
evidence, review comments, IDs, hashes or provenance. News statements describing an impact
are invented article content, not appended reference annotations.

## Provisional labeling rubric and human review

Current dataset: m4a-v1.1; reference version: 1.1.0; prompt version: 1.1.0.
The m4a-v1 directory names the unfrozen major family, not the current minor revision.

[Complete 40-case review table](m4a-human-review.md). Corpus provenance is original synthetic
Codex-assisted drafting dated 2026-10-29; fictional issuers, amounts and .test URLs, without
copying real news. The split has 8 development cases and 32 holdout cases, including two
context-limit controls; the inference holdout has 30 cases. Do not tune against holdout.

- Event type and issuer role describe the stated event and affected issuer relationship.
- Direction is the explicitly described business/news effect, not a forecast of a price move.
  Mixed means stated positive and negative effects coexist; neutral requires explicit absence
  of change/effect. Missing direction is not assumed neutral.
- Materiality: explicit material/immaterial descriptions map provisionally to high/low;
  explicit moderate impact maps to medium. Uncalibrated significant remains ambiguous.
  These are source-description labels, not independently established financial significance.
- Relevance is direct when a company-specific event/effect is stated. Known identity alone
  cannot establish the missing core impact classifications.
- Information status distinguishes company/filing facts, unconfirmed sourced rumors, and
  explicitly attributed interpretations. A rumor is not automatically an abstention.
- Novelty, horizon and schedule use explicit source statements; otherwise unknown is the
  provisional default. Horizon describes the stated business timeline, never holding-period advice.
- Required core uncertainty or absence of a source-supported allowed extraction reason
  triggers full-ticker abstention under the existing contract. In the benchmark convention,
  unsupported_event_type also covers an extraction scenario that v1 cannot faithfully
  express, even when its broad event_type enum exists. Financing-only M4A-17 now tests
  this explicit representability limitation, without forcing an unrelated reason.
  All nine classifications must then be unknown, with no numeric facts and an allowed
  abstention reason. Missing detail, attribution ambiguity, conflicting accounts, and
  unsupported event types use their corresponding contract reasons.
- Never infer consensus from a comparison with prior values alone.
- Period formatting communicated in prompt 1.1.0: Qn YYYY for explicit quarters, FY plus
  four-digit year for fiscal years, otherwise trimmed source label; omit unstated periods.
  Matching remains exact and does not repair model period strings.
- Shared deal_value/contract_value totals are permitted once per explicitly named participant,
  required on the primary issuer and optional on its named counterparty. Revenue/EPS/margins
  and other issuer-specific metrics never transfer by this rule. An optional source-support
  pool on an abstaining party (M4A-26) is diagnostic only: valid abstention still forbids facts.
- Reason-code arrays are compared as sets; contract validation still rejects duplicates.
- Structured numbers retain actual/prior/consensus roles, period, unit and currency. Expand
  explicit million/billion amounts into base units. Percent means percentage points;
  basis_points means the stated basis-point count. Omit missing values and scalar ranges;
  never derive a midpoint or invent currency. Decimal strings have no exponent/separators,
  unnecessary fractional zeros or negative zero. The last rule is a benchmark normalization
  expectation, not a change to the domain schema's more permissive decimal regex.
- Required facts must be recalled. Optional facts are explicitly source-supported alternatives
  whose omission is not an error. Both pools are separate from model-visible input.

Every field label is `clear`, `acceptable_set`, or `ambiguous`, with source evidence. A
representative value on an ambiguous label exists only to validate a coherent v1 example;
it is never treated as a scored truth. Acceptable sets use membership, without partial
credit. Context controls have no semantic inference score. M4A-20 accepts coherent extraction
or full abstention pending a materiality decision; its materiality label is excluded.

Unresolved review decisions: materiality descriptions versus independently assessed magnitude;
M4A-16 days/weeks bins; M4A-17 representability-abstention convention; M4A-20 extraction versus abstention; M4A-08 optional basis-point `other`
fact; M4A-10 range omission; M4A-21/22 primary-required/shared-counterparty-optional coverage; M4A-26 missing customer
impact; and rumor/opinion extraction in M4A-27/29/30. Review every other case as well.

A human must review the table, detailed references and rubric, record decisions/reviewer/date in manifest.human_review,
resolve questions or explicitly retain ambiguity, and then update dataset version/review status
and freeze metadata. None of those actions has occurred. Do not use an LLM as the sole judge.
Label changes require a rationale and a version/hash change; old results remain tied to old labels.

## Exact scoring definitions

First attempts are the distinct `phase=holdout` records for the 30 eligible holdout IDs.
Development, prime, process-cold, repeat and context-control phases never enter that denominator.
Duplicate first-attempt IDs are rejected. Missing, invalid and failed results remain in all
expected field/fact denominators and earn zero semantic credit.

Core fields, exactly: `event_type`, `direction`, `materiality`, `relevance`,
`information_status`, `abstain`. Auxiliary fields, separately reported: `issuer_role`,
`novelty`, `expected_horizon`, `schedule_status`, `reason_codes`.
For each field the denominator is expected ticker labels with clear or acceptable_set status;
ambiguous labels are excluded and counted. The numerator is exact equality or acceptable-set
membership on a contract-valid completed result with exactly one row for that ticker.
Core macro accuracy is the unweighted mean of defined core-field accuracies. Every core
field must itself have a nonempty denominator and meet its gate. Empty ratios are null/N/A,
never 100%; required gates fail when evidence is unavailable.

Numeric matching is one-to-one at the scalar fact-role level. The key is
`(ticker, metric, period, unit, currency, actual|prior|consensus, Decimal value)`.
Missing period/unit/currency use an empty key component; none is inferred. Exact Decimal
comparison never uses floating point or context rounding. Counters consume each supported
reference atom once. Duplicates beyond those slots are unsupported extras and never inflate
credit. A wrong role, period, unit, currency, ticker or value creates a miss and an extra.
Invalid decimal values count as unsupported predictions. A malformed fact without a value
role also counts as one unsupported prediction.

Required-atom recall = matched required atoms / all expected required atoms; all missing
and invalid-case atoms are misses. Source-supported precision = predictions matched to the
required-or-optional source pools / all predicted atoms. It uses the separately retained strict-parsed extraction evidence, including documents
with extra envelope properties, invalid field values and complete-JSON limit/truncation
failures. Accepted result is empty on any failure; diagnostic data never gains envelope
authority. It audits parseable invalid outputs too, but those outputs earn no required-recall or field credit. Optional omissions do not
enter recall. Unannotated extras are unsupported against the provisional references; human
review must establish whether annotations are exhaustive before interpreting that as factual
fabrication. Decimal normalization and source-supported precision are raw-output diagnostics; only
contract-valid completed results earn required-fact recall or categorical semantic credit.
Raw output is never repaired. Numeric-audit availability is reported per case as available,
partial or unavailable, with reason and scope: recognized numeric_facts or metric/value-role objects anywhere in
the strict-parsed document, including extra envelopes and nonstandard containers. This is
diagnostic recognition, not structural repair or acceptance. Unsupported counts are lower
bounds when an audit is partial/unavailable; JSON and Markdown reports expose that status. Partial JSON
is retained verbatim but not prefix-parsed; incomplete or unavailable audits fail a dedicated
coverage gate rather than masquerading as zero unsupported claims. Malformed outer HTTP
responses may have no extractable output; that is explicitly unavailable, not a successful audit.

Ticker exact coverage requires a contract-valid result with the exact affected-symbol set
and unique tickers. Also report expected/matched/missing/extra/duplicate counts and issuer-role
accuracy. Abstention recall uses clear must-abstain ticker labels; false-abstention and
extraction-coverage denominators use clear non-abstain ticker labels. Ambiguous or acceptable
abstention choices are excluded from these two binary diagnostic pools but remain in field
membership scoring. Rumor promotion uses clear rumor labels and reports its denominator.

JSON validity, derived decoding-schema validity and full assembled v1 validity are separate.
The flattened decoding aid omits unsupported conditionals/uniqueness rules. Syntax/grammar
success is not semantic accuracy or authoritative contract acceptance.

Initial diagnostic gates: 30/30 contract-valid results; exact ticker coverage 100%; each
core field >=90%; required numeric recall >=95%; supported precision 100%, zero unsupported
atoms, lexical normalization >=95%; mandatory abstention recall 100%; false abstention <=10%;
extraction coverage >=90%; zero rumor-to-fact promotions; warm p95 <=30 seconds with no
runtime failures; repeat agreement >=95% with no unsupported repeat facts; both context
controls rejected before generation; three valid cold trials; complete resource evidence.
Numeric agreement is exact value/role agreement, not approximate/tolerance matching.
All gates remain provisional and do not establish trading usefulness or real-news reliability.

Repeated cases: M4A-03/07/23/25/30/38, each repeated twice after its first holdout observation.
Agreement compares clear core decisions to the first observation; missing/failed outputs
are disagreements. Unsupported repeat numeric atoms are audited separately. Three cold
trials record individual startup/first-result durations; report median/range, not n=3 p95.
Warm p50/p95 use nearest rank (p95 at n=30 is sorted observation 29), with actual n reported.

## Future M4A2 deployment profiles and measurement

These are complete deployment profiles, **not a causal experiment on parameter count**.

| Profile | Source | Quantization | Model bytes | Offload |
| --- | --- | --- | ---: | --- |
| qwen3-4b-2507-q5 | Qwen3-4B-Instruct-2507; Unsloth GGUF conversion | Q5_K_M | 2,889,514,080 | all |
| qwen3-8b-q4 | Qwen3-8B; Qwen GGUF publication | Q4_K_M | 5,027,783,488 | 28 layers |

Both model cards declare Apache-2.0. The 4B GGUF is a third-party conversion, not Qwen's own
quantization. Original cards: [4B](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507),
[8B](https://huggingface.co/Qwen/Qwen3-8B). Artifact revisions/checksums below are pinned.
Runtime: llama.cpp b11981, commit da6c30ade8c30930dbf49fe57f9f02d727e99b15, a pinned upstream
prerelease under MIT. [Official release](https://github.com/ggml-org/llama.cpp/releases/tag/b11981)
and [asset checksums](https://github.com/ggml-org/llama.cpp/releases/expanded_assets/b11981).
Its bundled NVIDIA runtime libraries retain their own upstream licensing terms.

Preflight observed i7-9700K (8 cores), four 8 GB DDR4 modules, RTX 2070 (8192 MiB, capability 7.5),
WSL Ubuntu 26.04 with 15.59 GiB RAM and 4 GiB swap. GPU use varied around 1-1.5 GiB; no local
runtime/toolkit was found. Recheck availability in M4A2; no system configuration changes are planned.
The driver's reported CUDA compatibility is not proof that runtime kernels execute successfully.

Settings: one slot/request/model; 8192 total context; 2048 output plus 256-token safety margin;
FP16 K/V; flash attention off; batch/microbatch 128; six CPU threads; fit/context shifting off;
no prompt cache, tools or web UI; no automatic profile/CPU fallback. Greedy temperature 0,
seed 42, repeat penalty 1, temperature-only sampler. Thinking disabled using the embedded template.
The configuration hash includes settings, prompt/domain schema, profile and implementation hashes;
manifest records binary hash/version, device/driver, CPU/WSL memory and exact launch arguments.

KV-cache estimate: 2 * 36 layers * 8 KV heads * 128 head dimension * 2 bytes * 8192 tokens =
1.125 GiB across devices. Estimated GPU/host memory is 4-5.5/4-7 GiB for 4B and
4.8-6.2/6-10 GiB for 8B with partial offload, excluding desktop GPU usage. These are uncertain
planning estimates, not measurements. [4B config](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507/raw/main/config.json),
[8B config](https://huggingface.co/Qwen/Qwen3-8B/raw/main/config.json).

The supervisor calls local /apply-template, /tokenize and /completion with a derived schema,
then strictly decodes and assembles trusted IDs/hash/provenance in Python. No assignment,
ownership or persistence state exists in this experiment. [Pinned runtime API](https://raw.githubusercontent.com/ggml-org/llama.cpp/b11981/tools/server/README.md).
Reject input_tokens + 2048 + 256 >8192 before generation, without shortening any source.
Output exhaustion and reported truncation fail explicitly. No repairs or inference retries.

Runtime ownership: every launched process gets a fresh random --alias, verified through
GET /v1/models before readiness. Each source-bearing POST first re-verifies that alias on
the same live HTTP connection; a closing metadata socket fails instead of reconnecting.
The expected alias is not sent to the peer in metadata queries. A foreign healthy listener
is rejected even while the owned child remains alive. Cleanup targets only the launched
process session, never a process discovered by listening port. This guards local listener
collisions, not a hostile local process able to inspect and spoof command-line metadata.
The pinned runtime documents --alias as the /v1/models model id.

Startup deadline 120 seconds; sample absolute deadline 60 seconds; run deadline 45 minutes
per profile, including three cold launches, a final warm process, prime, development,
holdout, controls and repeats. HTTP cancellation interrupts blocked socket I/O. Timeout stops
the owned process session (TERM, two seconds; KILL, three seconds); remaining observations
are missing/incomplete, not silently rerun. Bind failure never attaches to an existing service.
Process-cold trials use new process-local CUDA caches and uncontrolled OS caches; they do not
claim disk-cold latency. OOM/startup failures remain explicit in the manifest and server log.

Warm end-to-end latency includes exact template/tokenization, generation, trusted assembly and
validation. Persist raw response/token timings and token counts separately. Sample /proc high-water
RSS/system RAM/swap and device-wide nvidia-smi memory at a target 500 ms plus query overhead;
retain timestamps and unavailable observations. GPU deltas use the first observation, which may already include model allocation; they are
approximate and cannot establish per-process VRAM on WSL. Gates require >=512 MiB free VRAM, >=2 GiB WSL available RAM,
no swap growth and no unavailable resource observations. CPU/GPU offload logs remain in the run.

Artifacts/raw runs/local config/caches belong only in ignored .tools/m4a; weights in ignored
models/weights. The CLI enforces the raw-run root. Small fixtures and reviewed summaries remain
trackable. New runs never overwrite existing directories. No external AI SDK/API or remote
inference route is implemented.

## Exact future setup and execution (NOT executed in M4A1)

Separate M4A2 approval is required. Download approximately **8.69 GB** total; reserve at least
**20 GB free disk** for weights, archives, extracted runtime libraries, JIT caches and raw runs.
No sudo, compiler/toolkit, driver changes, daemon installation or model conversion is needed.

```bash
set -euo pipefail
cd /home/xeemt/code/news-swing-trader
M4A_ROOT="$PWD/.tools/m4a"
M4A_RUNTIME="$M4A_ROOT/runtime/b11981"
M4A_RELEASE="https://github.com/ggml-org/llama.cpp/releases/download/b11981"
mkdir -p "$M4A_ROOT/downloads" "$M4A_RUNTIME" "$PWD/models/weights"
m4a_fetch() {
  curl --fail --location --retry 2 --connect-timeout 15 --max-time 1800 \
    --output "$2.part" "$1"
  printf '%s  %s\n' "$3" "$2.part" | sha256sum --check -
  mv -- "$2.part" "$2"
}
m4a_fetch \
  "$M4A_RELEASE/llama-b11981-bin-ubuntu-cuda-12.8-x64.tar.gz" \
  "$M4A_ROOT/downloads/llama.tar.gz" \
  5346ceedee45bda5bbd93cfd530ce6a8e1a2fd7a76369c9b2f508c5ce6cd4e4b
m4a_fetch \
  "$M4A_RELEASE/cudart-llama-b11981-bin-ubuntu-cuda-12.8-x64.tar.gz" \
  "$M4A_ROOT/downloads/cudart.tar.gz" \
  01dfe8b269a37cfda5aab7b5fe3d776c5521bbf277ec021738b991e17848a826
tar -xzf "$M4A_ROOT/downloads/llama.tar.gz" --strip-components=1 -C "$M4A_RUNTIME"
tar -xzf "$M4A_ROOT/downloads/cudart.tar.gz" --strip-components=1 -C "$M4A_RUNTIME"
m4a_fetch \
  "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/a06e946bb6b655725eafa393f4a9745d460374c9/Qwen3-4B-Instruct-2507-Q5_K_M.gguf" \
  "$PWD/models/weights/Qwen3-4B-Instruct-2507-Q5_K_M.gguf" \
  5bde5e9d883622acb02bf77fe7dcbc56a8b9a9ad4be78a72ca23a532658b4ecb
m4a_fetch \
  "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/7c41481f57cb95916b40956ab2f0b139b296d974/Qwen3-8B-Q4_K_M.gguf" \
  "$PWD/models/weights/Qwen3-8B-Q4_K_M.gguf" \
  d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785
"$M4A_RUNTIME/llama-server" --version
"$M4A_RUNTIME/llama-server" --list-devices
```

Stop on incompatibility or missing host libraries; do not install anything automatically.
After separate execution approval and reference review (or explicitly diagnostic provisional use):

```bash
M4A_PYTHON="$PWD/apps/ai-worker/.venv/bin/python"
"$M4A_PYTHON" -m news_swing_ai.benchmark check
"$M4A_PYTHON" -m news_swing_ai.benchmark run \
  --runtime-dir "$M4A_RUNTIME" --profile qwen3-4b-2507-q5 \
  --model "$PWD/models/weights/Qwen3-4B-Instruct-2507-Q5_K_M.gguf" \
  --run-dir "$M4A_ROOT/runs/qwen4b-v1" --execute-local
"$M4A_PYTHON" -m news_swing_ai.benchmark evaluate --run-dir "$M4A_ROOT/runs/qwen4b-v1"
"$M4A_PYTHON" -m news_swing_ai.benchmark run \
  --runtime-dir "$M4A_RUNTIME" --profile qwen3-8b-q4 \
  --model "$PWD/models/weights/Qwen3-8B-Q4_K_M.gguf" \
  --run-dir "$M4A_ROOT/runs/qwen8b-v1" --execute-local
"$M4A_PYTHON" -m news_swing_ai.benchmark evaluate --run-dir "$M4A_ROOT/runs/qwen8b-v1"
```

With current pending references, run intentionally refuses; only a separately approved
provisional diagnostic may append `--allow-provisional`. Evaluation still exits 1 and never
marks integration eligibility. Raw summaries are also ignored; retain only a small reviewed,
clearly attributed summary in docs after M4A2, without checking raw outputs into Git.

## Completion and integration boundary

M4A1 completes with offline tooling, provisional corpus, review table, tests and documentation.
M4A2 may conclude go or no-go without tuning profiles or rewriting references to pass.
A subsequent approved worker-integration milestone requires human-reviewed references, a frozen
version, complete reproducible holdout/resource evidence and at least one passing deployment
profile. It must address cancellation, lease/deadline interaction, context rejection and provenance.
Passing synthetic cases establishes feasibility only and never switches the fake-worker default.

## Preparation review corrections (reference/prompt 1.1.0)

No real model outputs exist and no gate was relaxed. All references remain pending human
review and unfrozen. Previous file identities are recorded in manifest.corrections.

| Case | Explicit correction and rationale |
| --- | --- |
| 08 | Original text lacked consensus. The synthetic source now explicitly states gross-margin consensus 24.00%, and required consensus becomes decimal string 24. The earnings_above_consensus reason is now grounded in that new source statement; original source hash is retained in the correction record. This is authoring, not claimed human validation. |
| 17 | Source unchanged. Remove the unsupported forward_looking_statement representative. All classifications now become unknown with unsupported_event_type abstention, explicitly documenting the missing v1 extraction-reason representation for this financing-only scenario. |
| 21/22 | Source unchanged. Add the shared transaction total as optional support on the named counterparty/customer. Keep primary coverage required, and retain ticker-specific keys for issuer metrics; duplicated claims within one party still earn no extra credit. |
| 26 | Apply the same shared-event support rule to the named customer. Because that row abstains, this annotation supports diagnostic auditing only; it does not allow facts in a valid abstention row. |

Prompt 1.1.0 now communicates the generic materiality, direction/abstention, period and
shared-event conventions without IDs, case-specific values, labels or evidence annotations.
Domain schema_version remains 1.0.0, runtime/model pins and numerical gates are unchanged.

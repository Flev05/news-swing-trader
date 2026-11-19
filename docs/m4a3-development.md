# M4A3 bounded development remediation

Baseline HEAD: 0c56c00c84e9ca4fa0125a11d4e12a61ce09d91b, initially clean.
Only M4A-01/04/11/15/21/22/27/31 are investigated or executed. M4A2 artifacts and
published negative findings are preserved. The inspected original holdout is not reused
for tuning or final validation; no new evaluation cases are authored in this milestone.
References remain pending_human_review and frozen=false. The experiment freeze below
identifies executable variants; it does not claim human-reviewed references.

## Audit before preparation

Saved original 4B development outputs, rendered prompts, generation settings/actual grammar,
source documents and unchanged references were inspected. GGUF metadata was read directly,
without model execution, to inspect the embedded chat template. The historical baseline is
observational, not a newly controlled comparison. All eight saved input-token counts match
runtime tokens_evaluated (1613–1648); all stopped at EOS, without truncation, with stop=[],
ignore_eos=false, no thinking tokens. The GGUF has add_bos_token=false and EOS 151645;
its generation prefix is <|im_start|>assistant plus newline. For a single user turn it
contains no thinking prefix; the generic enable_thinking=false setting is preserved.
Saved token IDs were not recorded in M4A2, so counts/prompt inspection cannot establish
byte-for-byte token-ID equivalence. New runs retain exact rendered text and token IDs.
No tokenizer/template/sign-regex defect was demonstrated. The saved grammar permits signs
and decimals. Flattening deliberately omitted conditionals/unique-by, so it permitted mixed
reason branches, unknown core extraction fields and duplicate ticker rows. Its period rule
is unrestricted string despite the schema pattern: a demonstrated decoding gap. These are
interface limitations, not proof that the model alone caused every failure.

| Development case | Structural failures | Unsupported numeric claims | Normalization | Missing/incorrect required atoms | Classification disagreements in raw output |
| --- | --- | --- | --- | --- | --- |
| 01 | None | 0 | None | 0/3 | Invented novelty=update and horizon=intraday |
| 04 | Duplicate ticker; abstention reason in extraction | 0 | N/A: no numbers | 2/2 missing | Invented long_term horizon; extra reason |
| 11 | None | 3: invented zero prior/consensus; transaction value with wrong period | Decimal lexical form correct; period incorrectly taken from closing timeline | 1/1 incorrect key | None |
| 15 | Abstention reason in extraction | 0 | N/A: no numbers | 0/0 (N/A) | Novelty, horizon and extra reason |
| 21 | Both tickers duplicated | 0 | N/A: no numbers | 1/1 missing | Reasons applied to both parties rather than their roles |
| 22 | None | 0 | N/A: no numbers | 1/1 missing | Contract reason, customer role and relevance |
| 27 | Abstention-only reason in extraction | 0 | N/A: no numbers | 1/1 missing | Event type, novelty and reason; rumor itself retained |
| 31 | Unknown core fields and abstention-only reason with abstain=false | 0 | N/A: no numbers | 0/0 (N/A) | Failed full abstention; retained issuer/event/status/relevance |

Categories overlap. Raw missing/incorrect counts ignore contract acceptance to distinguish
actual omissions from credit lost through structural rejection. Required numeric atoms=9;
raw matched=3, missing/incorrect=6. Unsupported atoms=3, including period/role errors;
this does not mean three independent invented quantities. No decimal lexical normalization
failure appears among the six native numeric role predictions in this development subset.
Possible causes such as schema verbosity or instruction load are hypotheses to test.

## Predeclared variants and trusted assembly

A (prompt/provenance version 2.0.0) keeps the original model-facing ticker_results shape
and original prompt 1.1.0 plus a generic correction. Its decoding schema adds separate
abstention/extraction branches, reason partitions, required non-unknown core classifications,
source-supplied ticker enums/exact row count, and an anchored period pattern. Python still
checks unique tickers and all authoritative v1 rules. Prompt additions address omitted
facts, invented zeros/horizons, closing timelines and event/issuer-specific reasons, without
case IDs, labels, evidence annotations or reference answers.

B (2.1.0) returns symbols keyed by the supplied tickers, each with either abstain_reason or
extract. The extraction branch has source classifications, reasons and scalar facts; optional
novelty/horizon/schedule default to unknown as an explicit IR convention. Abstention assembly
constructs all nine unknown classifications and no facts. These are declared representations,
not repairs of malformed output; extra/mixed fields, missing required keys and duplicate JSON
keys reject the whole native document. Strict decoding and the authoritative contract remain.

Each B fact has metric, role, exact evidence quotation, complete value_text and optional
period_text. Trusted code parses the claimed expression with Decimal, preserves the explicit
sign/currency/unit and thousand/million/billion scale, and strips only unnecessary fractional
zeros. Source amount-span boundaries prevent matching a substring that drops a sign, currency
or scale. Missing roles are never filled, including with zero. A copied measurement period
is retained/trimmed only, not inferred from a closing timeline. Unsupported numeric forms are
rejected, with the original text retained; no floating-point arithmetic or rounded context is used.
Text presence establishes neither attribution nor metric/role/period interpretation. Offline
regressions demonstrate that a wrong metric/role can pass lexical grounding and still fail
unchanged reference scoring. Counterparty/shared-fact semantics remain evaluated.

Native text, strict-parsed IR, all recognized numeric diagnostics, the assembled candidate,
rejection reasons and accepted result are distinct saved objects. B normalizes every recognized
claim even if it is unsupported or located in an invalid extra container. Invalid claims count
as unsupported diagnostic atoms; duplicates remain predictions. No rejected claim is removed
from the all-claims precision denominator. An invalid document earns no categorical or required
numeric credit. Accepted-only numeric statistics are separately labeled. Partial JSON remains
verbatim with audit unavailable; no prefix parsing, repair or generation retry occurs.
Deterministic normalization is part of the new representation, so B's normalized lexical score
is not a raw-model lexical-normalization comparison with M4A2/A.

## Frozen execution protocol

Pinned existing Qwen3-4B-Instruct-2507 Q5_K_M/all-layer profile and llama.cpp b11981 are reused.
Model, runtime archives and extracted runtime identities are checked before launch. No downloads,
packages, dependency changes, model substitution or system configuration changes are permitted.
An exclusive read-only freeze.json records both prompts/messages, per-case native schemas,
adapter/runner/scorer/shared validation identities, reference hashes, runtime/model identities,
settings, thresholds, case order and repeat selection before any generation. An exclusive
execution sentinel forbids resuming/restarting the same experiment. Identity drift rejects execution.

A then B, each: one prime using 01; first attempts 01/04/11/15/21/22/27/31; repeats
01/21/27/31 once each. Maximum 26 generation dispatches, including failures/aborts. Ledger
charges are persisted before HTTP dispatch; ambiguous dispatches consume budget. One 1800-second
aggregate clock begins conservatively just before the first runtime launch, including both
startups; five seconds are reserved for bounded owned-process cleanup. Existing 60-second
sample/120-second startup ceilings, 8192 context, 2048 output plus 256 safety, temperature 0,
seed 42 and all hardware/runtime settings remain fixed. Timeout, ownership failure or OOM
stops both variants immediately. No replacement calls, retries or budget extension.

Both variants are defined before generation, with no post-result changes. Schema/grammar success
prevents only the encoded structural errors; metric/issuer/role/direction/materiality/reasons and
numeric coverage still require semantic evaluation. B can remain structurally valid yet wrong.
New standard-library code and existing jsonschema suffice; contracts and operational fake worker
are untouched. Existing scorer and numerical thresholds are reused without edits: all eight
expected first attempts retain denominators, ten ticker labels/field, nine required role atoms,
one mandatory abstention and one clear rumor. Repeats use four expected observations; missing
or rejected pairs disagree. Cold/context controls are not measured here and cannot be claimed
as passed. A favorable development decision is never integration eligibility or generalization.

Commands (no install/synchronization step):

```bash
apps/ai-worker/.venv/bin/python -m pytest apps/ai-worker/tests/test_development.py apps/ai-worker/tests/test_benchmark.py -o addopts= -q
MAVEN_ARGS=-o UV_OFFLINE=1 UV_NO_SYNC=1 UV_LOCKED=1 make format
MAVEN_ARGS=-o UV_OFFLINE=1 UV_NO_SYNC=1 UV_LOCKED=1 make lint
MAVEN_ARGS=-o UV_OFFLINE=1 UV_NO_SYNC=1 UV_LOCKED=1 make test-python
# RUN is a unique ignored .tools/m4a3/runs/... directory:
apps/ai-worker/.venv/bin/python -m news_swing_ai.benchmark.development freeze --run-dir "$RUN"
apps/ai-worker/.venv/bin/python -m news_swing_ai.benchmark.development run --run-dir "$RUN" --execute-local
apps/ai-worker/.venv/bin/python -m news_swing_ai.benchmark.development evaluate --run-dir "$RUN"
```

## Execution and measured results

Preparation verification: make format, make lint (Ruff/mypy), 182 focused tests
(138 existing + 44 new offline regressions), and 304 total Python tests passed
before generation. Java tests are reused while Java paths remain unchanged.

Immutable experiment freeze recorded before first generation:
`b7bde36218b69fc35d7cfa99764ec92d96d7a27744c3ae1713661691203500d1`.
Run directory: `.tools/m4a3/runs/m4a3-20261029T185327Z`.
Versions A=2.0.0, B=2.1.0; exact prompts, native schemas, code and settings are in freeze.json.



Model SHA-256: `5bde5e9d883622acb02bf77fe7dcbc56a8b9a9ad4be78a72ca23a532658b4ecb`.
Runtime b11981/da6c30ade8c30930dbf49fe57f9f02d727e99b15, launcher SHA-256
`85afeac50859716a737e34a3ebed2ef36d9d6015866f4965aead5e4961e92666`.
Existing archives and all 65 extracted runtime file identities were verified; no artifact
was downloaded or substituted. Freeze covers 16 implementation/prompt/schema/reference files.
- development.py: `0f4a826ac8578f5d6851aed055ec53bcd640e16fe98a6880c2ccd199d6edd7fe`
- remediation.py: `18be855069c16068cb0daa38c2a165812e83e7d8183bcf7604725d8658c535ad`
- remediation-a.txt: `cc2ac9c702e90f8ec00f95b3dd1a0cf04eebc1464a7c95d10914b896339d4e96`
- remediation-b.txt: `2432cc0ef5419a7e33ee05464e303174c42bc22f13bf950a0484c13205b4ddeb`

Reference input/reference/manifest SHA-256 identities are unchanged from the M4A2 report:
`9049225bc7fd7ccc85eea6865a379009472042a27907b6f7bc01ce65c4a07224`,
`afd57d314766b90c52837cf4ade65c3c54ae4597d9cc307cfe9f435c3f66b4ef`,
`38195a8c469cccdd97012ec0c1cfe3ff8934b5fc08a1ef88937682ce572d7efa`.
The frozen per-case message/schema definitions and all raw identity metadata remain locally
available; hashes above are reviewable references to the immutable freeze, not a corpus freeze.

Execution completed, exit 0: 26/26 charged generation dispatches, no missing observations,
no transport timeout/ownership/OOM failure, no retries or replacement calls. Each variant
used exactly one prime, eight first attempts and four repeats. Aggregate clock started
2026-10-29T18:54:27.962090Z; both owned processes were cleaned up after **200.84 seconds**
(1800-second cap, cleanup reserve included). The wrapper measured 206.14 seconds including
identity verification outside model-runtime execution. First-request/sample ceilings stayed
60 seconds; startup ceilings stayed 120 seconds. No post-result code/prompt/schema/metric
change or rerun occurred. Offline evaluation exited 0 to indicate a saved report, with
both go_fresh_validation=false and integration_eligible=false; exit 0 is not eligibility.

### Complete generation ledger

Every row below represents two charged calls; A indices 0–12, B indices 13–25. Rejected
means ADAPTER_REJECTED, not missing. Full timestamps/state/request evidence are in ledger.json.

| A/B call index | Phase | Case | A outcome / seconds | B outcome / seconds |
| --- | --- | --- | --- | --- |
| 0/13 | prime | M4A-01 | accepted / 26.625 | accepted / 30.100 |
| 1/14 | development | M4A-01 | accepted / 2.557 | accepted / 5.218 |
| 2/15 | development | M4A-04 | accepted / 2.550 | accepted / 6.497 |
| 3/16 | development | M4A-11 | accepted / 2.633 | rejected / 5.421 |
| 4/17 | development | M4A-15 | accepted / 2.480 | rejected / 2.851 |
| 5/18 | development | M4A-21 | accepted / 4.062 | rejected / 5.413 |
| 6/19 | development | M4A-22 | accepted / 3.754 | rejected / 4.330 |
| 7/20 | development | M4A-27 | accepted / 2.548 | accepted / 1.174 |
| 8/21 | development | M4A-31 | accepted / 2.563 | accepted / 1.129 |
| 9/22 | repeat | M4A-01 | accepted / 2.606 | accepted / 5.212 |
| 10/23 | repeat | M4A-21 | accepted / 4.098 | rejected / 5.190 |
| 11/24 | repeat | M4A-27 | accepted / 2.567 | accepted / 1.170 |
| 12/25 | repeat | M4A-31 | accepted / 2.534 | accepted / 1.181 |

### First-attempt results, all eight expected cases

Historic = recorded original 4B development observations, not newly rerun or controlled.
Each field has ten scored ticker labels, zero ambiguous exclusions/acceptable sets here.
Invalid results earn no semantic credit. Nine required scalar-role atoms retain their full
denominator even when omitted/rejected. Empty precision/normalization denominators are N/A.

| Metric | Historical 4B development | A | B |
| --- | --- | --- | --- |
| Native JSON validity | 8/8 (100.0%) | 8/8 (100.0%) | 8/8 (100.0%) |
| Native decoding-schema validity | 8/8 (100.0%) | 8/8 (100.0%) | 8/8 (100.0%) |
| Accepted after authoritative validation | 3/8 (37.5%) | 8/8 (100.0%) | 4/8 (50.0%) |
| event_type | 4/10 (40.0%) | 9/10 (90.0%) | 3/10 (30.0%) |
| direction | 4/10 (40.0%) | 9/10 (90.0%) | 3/10 (30.0%) |
| materiality | 4/10 (40.0%) | 9/10 (90.0%) | 3/10 (30.0%) |
| relevance | 3/10 (30.0%) | 9/10 (90.0%) | 3/10 (30.0%) |
| information_status | 4/10 (40.0%) | 9/10 (90.0%) | 3/10 (30.0%) |
| abstain | 4/10 (40.0%) | 9/10 (90.0%) | 3/10 (30.0%) |
| issuer_role | 3/10 (30.0%) | 9/10 (90.0%) | 3/10 (30.0%) |
| novelty | 3/10 (30.0%) | 6/10 (60.0%) | 3/10 (30.0%) |
| expected_horizon | 3/10 (30.0%) | 8/10 (80.0%) | 4/10 (40.0%) |
| schedule_status | 4/10 (40.0%) | 10/10 (100.0%) | 2/10 (20.0%) |
| reason_codes | 3/10 (30.0%) | 6/10 (60.0%) | 3/10 (30.0%) |
| Exact accepted ticker coverage | 3/8 (37.5%) | 8/8 (100.0%) | 4/8 (50.0%) |
| Mandatory abstention recall | 0/1 (0.0%) | 0/1 (0.0%) | 1/1 (100.0%) |
| False abstention rate | 0/9 (0.0%) | 0/9 (0.0%) | 1/9 (11.1%) |
| Extraction coverage | 4/9 (44.4%) | 9/9 (100.0%) | 2/9 (22.2%) |
| Required numeric recall | 3/9 (33.3%) | 0/9 (0.0%) | 4/9 (44.4%) |
| All-claims supported precision | 3/6 (50.0%) | N/A (0/0) | 4/11 (36.4%) |
| Diagnostic decimal normalization | 6/6 (100.0%) | N/A (0/0) | 9/11 (81.8%) |
| Unsupported diagnostic atoms | 3 | 0 | 7 |

A emitted no numeric claims: all nine required atoms are missing; zero unsupported atoms
is not successful extraction. B emitted eleven scalar claims; four match source-supported
reference atoms and seven are unsupported against the complete key. Two claims are malformed
nonnumeric text. All eight native documents in each variant were strict-parsed; numeric
audit is available for all recognized claims, including rejected documents. Values/roles/
periods are matched one-to-one; source presence is never treated as semantic correctness.
B's accepted-only numeric statistics are **4/9 required recall, 4/5 precision, 1 unsupported
atom, 5/5 lexical normalization**. These exclude rejected documents only in this explicitly
separate view; the main 4/11 precision and seven unsupported atoms retain them. B's native
value_text expressions are intentionally source-form strings, so model-native decimal
normalization is N/A; the 9/11 diagnostic normalization score concerns the adapter projection,
including two INVALID_CLAIM placeholders for malformed inputs, not normalized model prose.

Both variants' candidate projections identify all ten expected ticker names without extras
or duplicates. A has eight authoritative-contract-valid candidates and accepts eight; B
has six authoritative-contract-valid candidates but accepts only four because two more
fail source/period grounding. The other two candidates have invalid numeric facts and fail
v1 itself. Rejected candidates are saved, never promoted or selectively cleaned.

| Case | A numeric outcome | B numeric outcome and rejection/semantic detail |
| --- | --- | --- |
| 01 | All three EPS roles omitted | Three roles, currency and period correct; accepted |
| 04 | Both guidance roles omitted | 900 million wrongly assigned consensus; prior 800 million correct; accepted but semantically wrong |
| 11 | Deal value omitted | Correct amount, invented period_text=unknown; rejected, still one unsupported atom |
| 15 | No numeric claim (correct) | Invents a numeric other fact from no-change prose and period none; rejected and audited |
| 21 | Shared total omitted | Correct shared amount on both named parties, but invented unknown periods; rejected, two unsupported atoms. Shared amounts themselves are not fabricated |
| 22 | Contract value omitted | Uses deal_value and unscheduled as a period; customer fact is nonnumeric prose; rejected, two unsupported atoms |
| 27 | Deal value omitted | Incorrect unsupported_event_type abstention; misses required rumor extraction/value |
| 31 | No numeric claim | Correct insufficient_source_detail abstention, no facts |

A still invents an extraction for 31 (neutral/low/none with not_company_specific), rather
than required whole-ticker abstention. It also invents horizons/novelty and adds unsupported
reasons on some accepted rows. B preserves the supported EPS facts but shows exactly why
lexical grounding does not prove role correctness in 04. Neither variant promotes the one
clear development rumor to fact; A extracts it as rumor, B abstains instead. Zero promotions
for B therefore does not establish correct rumor extraction or coverage.

### Timing, resources, repeats and fixed gates

| Measurement | A | B |
| --- | --- | --- |
| Startup seconds | 29.945 | 33.126 |
| Prime request seconds | 26.625 | 30.100 |
| Warm first-attempt p50 seconds, n=8 each | 2.557 | 4.330 |
| Warm first-attempt p95 seconds, n=8 each | 4.062 | 6.497 |
| Device-wide peak used GPU MiB | 6,091 | 6,214 |
| Minimum observed free GPU MiB | 1,916 | 1,793 |
| Process RSS high-water KiB | 3,167,936 | 3,167,944 |
| Minimum WSL available RAM KiB | 14,022,088 | 14,054,996 |
| Observed swap growth KiB | 0 | 0 |
| Repeat clear-core agreement | 30/30 (100.0%) | 18/30 (60.0%) |
| Repeat numeric signature consistency | 4/4 (100.0%) | 3/4 (75.0%) |
| Unsupported repeat atoms | 0 | 2 |
| Complete resource samples / recorded | 168/169 | 197/197 |

Percentiles use nearest rank; at n=8 p95 is the maximum, with little tail evidence. Timings
include template/tokenization, generation, adapters, validation and inexpensive per-call
source/hash checks, so are not pure model throughput. These are primed warm observations;
no three-trial cold measurement or context control was added. Input counts were 1834–1869
for A and 1269–1304 for B; exact per-observation counts and token IDs are retained.
Startup/prime caches and other system activity prevent a causal latency comparison with
the historical run. The variants also combine prompt/schema/adapter changes, preventing
single-factor attribution of improvements or regressions.

Device-wide GPU usage includes unrelated desktop use; process VmHWM is not exact host/GPU
allocation. Samples can miss peaks. A contains one unavailable final resource sample with no
error detail, so its unchanged complete-evidence gate **fails**; it is not dropped or
rerun. This is a resource-evidence limitation, not proof of OOM. B's resource gate passes.
No swap growth was observed; lack of measurements at one A instant prevents a full-coverage
claim. A repeats consistently omit all numeric facts, so 4/4 numeric consistency does not
mean recall. B repeat disagreement includes a rejected 21 output, not just stochastic
changes among accepted results; all four prescribed repeats were observed in each variant.

New actual-grammar evidence: A's anchored period regex is still unsupported because the
pinned converter rejects the escape \S, falling back to unrestricted string. The earlier
anchoring hypothesis did not establish full grammar enforcement. Authoritative/native
Python validation remains active; no converter warning was suppressed and no frozen pattern
was changed. The separate branch restrictions operate, but cannot force correct source
classifications or require optional facts. B's grammar similarly permits arbitrary strings
for copied numeric text; its adapter, not its grammar, rejects nonnumeric expressions.

| Fixed development gate | A | B |
| --- | --- | --- |
| contract | pass | fail |
| tickers | pass | fail |
| core_fields | pass | fail |
| numeric_recall | fail | fail |
| numeric_audit_complete | pass | pass |
| numeric_supported | fail | fail |
| normalization | fail | fail |
| abstention | fail | pass |
| false_abstention | pass | fail |
| extraction | pass | fail |
| rumors | pass | pass |
| latency | pass | pass |
| Repeat agreement and zero unsupported repeat facts | pass | fail |
| Complete resource evidence | fail | pass |

**No-go for a later fresh-validation execution on either current variant.** A improves
structural acceptance but drops all numbers and misses abstention. B demonstrates useful
source-form numeric assembly and correct explicit abstention on 31, but fails coverage,
role/period semantics, accepted-result reliability and repeat gates. These results do not
establish that 4B is intrinsically incapable; prompt/schema/adapter interactions remain
relevant, including the demonstrated converter limitation. No gate or reference was changed
to improve scores, and no additional remediation/run is performed after this freeze.
A later approved development milestone would need to resolve these failures before a fresh,
independently reviewed/frozen validation set is executed. None was generated here. Neither
variant is integration-ready; references remain pending_human_review/frozen=false, and the
operational worker remains fake-only. Development improvements establish no generalization
or trading performance claim.

## Verification and retained files

Before first generation: make format and make lint passed using only installed pinned
Maven/UV tooling in offline/no-sync mode; **182 focused tests** (138 existing + 44 new)
and **304 total Python tests** passed. New regressions cover exact Decimal/sign/scale/unit/
currency/period handling, omitted roles, branching, untrusted metadata/extra containers,
malformed/duplicate claims, evidence-vs-semantics, audit retention on output-limit failure,
pre-dispatch budget charging, fatal global stop, exclusive no-resume sentinel, source
identity tampering and ignored run-root checks. Stub tests do not load weights. Unit
normalization strings are offline test inputs, not additional evaluation-corpus cases.
Java's unrelated 534-test evidence is reused; Java/contract/worker/protocol/infrastructure/
dependency paths remain unchanged. No shared benchmark implementation was edited.

Raw local artifacts are ignored under `.tools/m4a3/`: development-audit.json,
gguf-template-audit.json, historical-integrity.json, active-experiment.json and the unique
run directory above. The latter retains freeze.json (read-only), execution/ledger/records/
samples/summary JSON, command log/exit record, owned-runtime logs and per-process CUDA caches.
Historical artifacts remain under `.tools/m4a/` and models/weights; no files were overwritten.
The five new source/test/prompt files and this compact report are intended to be tracked.
Everything remains uncommitted; no pushes or production integration occurred.

Final post-experiment verification snapshot: all 235 historical artifact file hashes and
all frozen source/schema/reference/runtime identities match; 26 ledger entries are charged
and finished, all restricted to the eight development IDs. All 285 historical/new artifact
files are ignored. Owned model processes and wrapper have exited. Whitespace/newline and
credential-pattern checks on all six intended new files passed; previously tracked paths
(including all protected paths) are byte-unchanged. The index is empty. HEAD remains
`0c56c00c84e9ca4fa0125a11d4e12a61ce09d91b` on main. Git-status snapshot:

```text
?? apps/ai-worker/src/news_swing_ai/benchmark/development.py
?? apps/ai-worker/src/news_swing_ai/benchmark/remediation-a.txt
?? apps/ai-worker/src/news_swing_ai/benchmark/remediation-b.txt
?? apps/ai-worker/src/news_swing_ai/benchmark/remediation.py
?? apps/ai-worker/tests/test_development.py
?? docs/m4a3-development.md
```

# M4A2 provisional local diagnostic experiment

Baseline: 2f8949a26d9c533f2cd37bc6d9cafc3c24eddbe9, initially clean.
The project owner delegated the annotation decision to the assistant: adopt 1.1.0
conventions, with M4A-20 requiring whole-ticker insufficient_source_detail abstention.
The source text and all input bytes are unchanged; classifications are unknown, no
numeric facts are expected. References 1.2.0/dataset m4a-v1.2 remain pending_human_review
and frozen=false. No independent human review or production integration is claimed.
Prompt 1.1.0, scorer, thresholds and deployment profiles are unchanged.

## Reference identities and prospective denominators

- inputs.json: `9049225bc7fd7ccc85eea6865a379009472042a27907b6f7bc01ce65c4a07224`
- references.json: `afd57d314766b90c52837cf4ade65c3c54ae4597d9cc307cfe9f435c3f66b4ef`
- manifest.json: `38195a8c469cccdd97012ec0c1cfe3ff8934b5fc08a1ef88937682ce572d7efa`

| Field | Scored | Ambiguous excluded | Acceptable sets |
| --- | ---: | ---: | ---: |
| event_type | 34 | 0 | 0 |
| direction | 34 | 0 | 0 |
| materiality | 34 | 0 | 0 |
| relevance | 34 | 0 | 0 |
| information_status | 34 | 0 | 0 |
| abstain | 34 | 0 | 0 |
| issuer_role | 34 | 0 | 0 |
| novelty | 34 | 0 | 0 |
| expected_horizon | 34 | 0 | 1 |
| schedule_status | 34 | 0 | 0 |
| reason_codes | 34 | 0 | 0 |

Required scalar-role atoms: 29; mandatory abstention: 11; clear extractable: 23; clear rumor: 1.

## Environment and pinned execution identities

Measured on 2026-10-29 in WSL Ubuntu 26.04, kernel
6.18.33.2-microsoft-standard-WSL2: i7-9700K, 8 cores; WSL RAM 16,736,960,512
bytes (15.59 GiB), swap 4 GiB initially unused. Free disk before downloads was
1,021,477,867,520 bytes, exceeding the required 20 GB. RTX 2070 has 8192 MiB,
compute capability 7.5; Windows driver/KMD 610.88 (WSL nvidia-smi 610.57.01),
reported CUDA UMD compatibility 13.3. The pinned archive bundles CUDA 12.8 libraries.
These are observed settings; no WSL limits, drivers, packages or dependencies changed.

No pinned artifacts existed at preflight. All four downloads used the exact URLs/revisions
in [the setup plan](m4a.md), with byte counts and SHA-256 verified before extraction/loading.
Total downloaded: 8,694,202,483 bytes. All models retain their documented Apache-2.0
licenses; llama.cpp is MIT, bundled NVIDIA libraries retain upstream terms.

| Artifact | Bytes | Verified SHA-256 |
| --- | ---: | --- |
| llama.tar.gz | 182,533,348 | `5346ceedee45bda5bbd93cfd530ce6a8e1a2fd7a76369c9b2f508c5ce6cd4e4b` |
| cudart.tar.gz | 594,371,567 | `01dfe8b269a37cfda5aab7b5fe3d776c5521bbf277ec021738b991e17848a826` |
| Qwen3-4B-Instruct-2507-Q5_K_M.gguf | 2,889,514,080 | `5bde5e9d883622acb02bf77fe7dcbc56a8b9a9ad4be78a72ca23a532658b4ecb` |
| Qwen3-8B-Q4_K_M.gguf | 5,027,783,488 | `d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785` |

Runtime is llama.cpp b11981, commit da6c30ade8c30930dbf49fe57f9f02d727e99b15;
`--version` reports 0.8.0-dev, GNU 13.3.0/Linux x86_64. Extracted launcher SHA-256:
`85afeac50859716a737e34a3ebed2ef36d9d6015866f4965aead5e4961e92666`.
The archive checksums also cover the shared implementation and CUDA libraries; launcher
hash alone does not identify those dependencies. Device enumeration confirmed CUDA0/RTX 2070.
An initial non-inference `--list-devices` setup probe timed out after 15 seconds;
its separately reported bounded follow-up succeeded in 0.465 seconds. Cause is unproven.
This was before measurements; no inference observation was retried.

Profiles are complete deployment configurations, not a causal parameter-count comparison:
4B is Unsloth Qwen3-4B-Instruct-2507 Q5_K_M at revision
`a06e946bb6b655725eafa393f4a9745d460374c9`, all layers offloaded; 8B is Qwen Qwen3-8B
Q4_K_M at revision `7c41481f57cb95916b40956ab2f0b139b296d974`, 28 GPU layers.
Both use context 8192, output 2048 plus safety 256, f16 KV, flash attention off,
fit off, one slot, 6 CPU threads, batch/ubatch 128, no context shift/prompt cache/warmup,
thinking disabled, temperature 0, seed 42, repeat penalty 1, temperature-only sampler.
Startup/sample/run deadlines remain 120/60/2700 seconds. Owned aliases are checked before
readiness and before every source-bearing request. No service daemon was installed.

Prompt 1.1.0 SHA-256: `95ed3b65f2cc4295df8c8077e08b6198e80046b5a81a456769db8fb71bf8b9c4`.
The measurement lock covers prompt, scorer, runtime/CLI/core, authoritative schema and
all three dataset files. It was checked before 4B and between profiles; nothing in that
lock was changed during measurement. All numerical gates remain the published ones.

## Recorded runs and completeness

Executed sequentially, once per profile, using the documented run command with
`--execute-local --allow-provisional`. Unique run directory suffixes are
`m4a2-20261029T172816Z-c0e8f774-qwen4b` and
`m4a2-20261029T172816Z-c0e8f774-qwen8b`, both under `.tools/m4a/runs/`.

| Phase | Scheduled per profile | 4B valid / rejected / missing | 8B valid / rejected / missing |
| --- | ---: | --- | --- |
| Process-cold | 3 | 3 / 0 / 0 | 3 / 0 / 0 |
| Warm prime | 1 | 1 / 0 / 0 | 1 / 0 / 0 |
| Development | 8 | 3 / 5 contract-invalid / 0 | 7 / 1 contract-invalid / 0 |
| Holdout first attempts | 30 | 11 / 19 contract-invalid / 0 | 4 / 1 timeout / 25 |
| Context controls | 2 | 2 expected CONTEXT_LIMIT rejections, no generation | 0 reached / 2 missing |
| Repeats | 12 | 6 / 6 contract-invalid / 0 | 0 / 0 / 12 |

4B completed all 56 records (54 inference attempts plus 2 pre-generation controls), run
exit 0, wall 422.36 seconds. 8B stopped with TIMEOUT at M4A-07, run exit 3, wall 557.95
seconds: 17 records, 16 complete model responses, 1 timed-out attempt, 39 scheduled records
missing. The 60.002-second sample timeout cancelled generation and terminated only the
owned runtime session. Server timing logs show generation continuing to roughly 635 tokens
before cancellation; no completed HTTP body/model text was returned for that attempt.
Numeric audit is therefore unavailable for M4A-07, not zero unsupported claims. Remaining
observations were not retried or skipped into a new run. No profile/settings change occurred.

Existing offline evaluation ran on the completed 4B evidence and explicitly partial 8B
evidence. Both evaluation commands returned 1. Both `diagnostic_gates_pass` and
`eligible_for_integration_review` are false. Pending human review would independently block
eligibility even if every diagnostic gate passed. No guard was changed.

Configuration SHA-256 identities (include model/profile, settings, implementation, schema,
prompt; dataset hashes are recorded separately):

- qwen3-4b-2507-q5: `f62919d08b38ff5bf663863636d86fdf9752cff3dcfe92aabbf10e4fb412071a`
- qwen3-8b-q4: `33923c309cff13f3b2758ee7d0d5f1d11baa9f9b8301db86b31b2b4228ad24cb`

## First-attempt scores

Only the 30 eligible holdout IDs enter the main scores. Invalid and missing cases retain
expected denominators and earn no semantic credit. Each categorical field has 34 ticker
labels; no ambiguous label is scored, and horizon has one acceptable set, scored by membership.
The 8B columns are incomplete-run scores, not estimates of performance on unseen cases.

| Metric | 4B | 8B, partial |
| --- | ---: | ---: |
| JSON syntax | 30/30 (100.0%) | 4/30 (13.3%) |
| Derived decoding schema | 28/30 (93.3%) | 4/30 (13.3%) |
| Assembled authoritative v1 | 11/30 (36.7%) | 4/30 (13.3%) |
| event_type | 13/34 (38.2%) | 4/34 (11.8%) |
| direction | 13/34 (38.2%) | 4/34 (11.8%) |
| materiality | 13/34 (38.2%) | 4/34 (11.8%) |
| relevance | 12/34 (35.3%) | 4/34 (11.8%) |
| information_status | 13/34 (38.2%) | 4/34 (11.8%) |
| abstain | 13/34 (38.2%) | 4/34 (11.8%) |
| issuer_role | 13/34 (38.2%) | 4/34 (11.8%) |
| novelty | 6/34 (17.6%) | 2/34 (5.9%) |
| expected_horizon | 2/34 (5.9%) | 3/34 (8.8%) |
| schedule_status | 12/34 (35.3%) | 4/34 (11.8%) |
| reason_codes | 11/34 (32.4%) | 4/34 (11.8%) |
| Core macro accuracy | 37.7% | 11.8% |
| Exact valid ticker coverage | 11/30 (36.7%) | 4/30 (13.3%) |
| Mandatory abstention recall | 0/11 (0.0%) | 0/11 (0.0%) |
| False abstention rate | 0/23 (0.0%) | 0/23 (0.0%) |
| Extraction coverage | 13/23 (56.5%) | 4/23 (17.4%) |
| Required numeric role-atom recall | 8/29 (27.6%) | 2/29 (6.9%) |
| Source-supported numeric precision | 9/42 (21.4%) | 2/6 (33.3%) |
| Decimal lexical normalization | 33/42 (78.6%) | 5/6 (83.3%) |

Numeric matching consumes atoms one-to-one by ticker, metric, exact period, unit, currency,
actual/prior/consensus role and Decimal value. Required omissions reduce recall; optional
supported omissions do not. Duplicate predictions cannot inflate credit. Invalid outputs
are audited from separate strict-parsed evidence but earn no required recall or field credit.
4B has 33 unsupported atoms, with audit available for 30/30 holdout attempts. 8B has at least
4 unsupported atoms among the 6 audited predictions; audit is available for only 4/30
expected cases (1 timeout plus 25 missing are unavailable). These counts include wrong
role/scale/period/currency and excess duplicates; they do not all establish invented values.
No truncated/partial JSON was repaired. Raw evidence and failures remain saved.

4B raw ticker attribution matched all 34 expected names, with 0 missing/extra names but
2 duplicate rows; valid exact coverage remains only 11/30. 8B matched 4/34 names,
30 missing, 0 extra/duplicate. Missing records are failures, not abstentions. Thus 0/23 false
abstentions does not imply good coverage, especially alongside 0/11 valid mandatory
abstentions in both runs.

4B's clear rumor M4A-30 retained rumor in raw text, but duplicated the ticker and mixed
abstention-only reasons into extraction, so earned no semantic credit. 8B never reached
that holdout case. The evaluator reports zero valid rumor-to-fact promotions against the
one expected rumor label for both profiles; its rumor gate mechanically passes even for
unobserved/invalid results. This is a diagnostic coverage limitation, not evidence of
correct rumor handling. Completion/contract/coverage gates fail independently. Likewise
8B's false-abstention gate cannot establish unobserved behavior.

4B repeats: clear-core agreement 48/96 (50%), numeric-signature consistency 6/12 (50%),
22 unsupported repeat atoms; failed outputs count as disagreements. Six cases were repeated
twice, with no repairs. This metric includes validity failures and does not isolate raw model
variability on otherwise accepted results. 8B reached no repeats: agreement and numeric
consistency are N/A with zero denominators; repeat gate fails, not an automatic pass.

## Latency and resource measurements

| Measurement | 4B | 8B |
| --- | --- | --- |
| Process-cold startup, median [min,max], n=3 | 29.72 [29.59, 31.25] s | 30.06 [29.79, 30.93] s |
| Process-cold launch to first valid result, median [min,max], n=3 | 57.67 [57.38, 61.36] s | 73.07 [71.78, 73.82] s |
| Warm holdout attempt duration p50 / p95 | 3.15 / 5.51 s, n=30 | 18.27 / 60.002 s, n=5, includes timeout |
| Resource samples complete / recorded | 756/756 | 978/978 |
| Device-wide peak used / minimum free GPU MiB | 6018 / 1989 | 6467 / 1540 |
| Peak-minus-first-observed used GPU MiB | 4543 | 4734 |
| Process VmHWM KiB | 3,153,556 | 5,241,844 |
| Minimum WSL available RAM KiB | 13,820,320 | 13,486,656 |
| Absolute peak swap used / growth KiB | 0 / 0 | 0 / 0 |

Cold trials use fresh owned processes and separate process CUDA caches; OS/filesystem caches
were uncontrolled. These are not disk-cold measurements. Cold inference durations were
30.11/28.08/27.67 seconds (4B) and 43.01/44.03/40.85 seconds (8B). Warm-process startup
was 29.48/30.23 seconds, followed by a separate prime excluded from holdout timing.
Warm timing includes template/tokenization, generation, assembly and validation, including
contract-invalid responses. Percentiles use nearest rank. The 8B n=5 includes a right-censored
timeout, so its reported p95 is an attempt-duration statistic, not a reliable completion-latency
population estimate; four completed holdout requests ranged 11.69–19.65 seconds. No latency
is assigned to missing observations. Full raw token/timing metadata are retained per response.

Sampling target was 500 ms plus query time; median within-process observed interval was
0.546/0.549 seconds. Device-wide VRAM includes Windows desktop/unrelated use; WSL does not
provide reliable per-process VRAM here. First observations may already contain allocations,
and deltas cannot isolate model VRAM. VmHWM is process resident high-water, not total host
memory or GPU allocation; available RAM includes reclaimable cache. Samples can miss transient
peaks. All recorded samples contained GPU/RAM evidence and passed the fixed resource gate;
this does not establish unmeasured peaks or full-dataset 8B resource behavior. Initial 4–5.5/
4.8–6.2 GiB GPU and 4–7/6–10 GiB host planning ranges remain estimates, not measured results.

## Gates, errors and provisional recommendation

| Fixed gate | 4B | 8B partial |
| --- | --- | --- |
| contract | fail | fail |
| tickers | fail | fail |
| core_fields | fail | fail |
| numeric_recall | fail | fail |
| numeric_audit_complete | pass | fail |
| numeric_supported | fail | fail |
| normalization | fail | fail |
| abstention | fail | fail |
| false_abstention | pass | pass |
| extraction | fail | fail |
| rumors | pass | pass |
| latency | pass | fail |
| resources | pass | pass |
| variability | fail | fail |
| context_limit | pass | fail |
| cold | pass | pass |
| completed | pass | fail |

The mechanically passing rumor/false-abstention gates have the coverage limitations above.
No aggregate gate passes; no threshold was relaxed. The runtime warned that its grammar
converter cannot enforce the unanchored period pattern. Derived decoding validity and Python
schema/semantic validation remain separate; grammar success was not treated as acceptance.

Representative first-attempt errors:

- 4B M4A-02 loses the minus in -0.25 and invents prior=0 despite the source explicitly
  saying none is supplied. Adding insufficient_source_detail to a non-abstaining row also
  violates v1, but those numeric errors remain audited (2 unsupported atoms).
- 4B M4A-09 returns the correct actual/consensus but copies consensus into an invented prior.
  M4A-25 returns 40/35 and 10/15 instead of expanding the stated millions, and invents priors.
- 4B M4A-20 extracts a low-materiality acquisition instead of the delegated whole-ticker
  abstention. M4A-23/38 abstention rows retain non-unknown classifications; M4A-30 duplicates
  JADE. These are rejected rather than promoted into trusted results.
- 8B M4A-02 preserves the negative EPS but also invents prior=0. M4A-03 expands 1.25/1.20
  billion to 12.5/12 billion (tenfold error), again inventing prior=0. M4A-05/06 omit required
  guidance facts. All four are contract-valid, demonstrating that schema validity is insufficient.
- 8B M4A-07 exceeded the sample deadline while generating; no final text is available to
  diagnose its content. The cause of the long generation is not established from timing logs.

Provisional recommendation: **no worker integration for either fixed profile**. 4B is the
more practical starting profile for a separately approved diagnostic investigation because
it completed the corpus at lower latency/resource use, but its extraction/abstention/numeric
reliability fails badly. 8B's early schema-valid advantage does not overcome unsupported
numbers, missing facts and incomplete coverage after timeout. This comparison cannot isolate
parameter count, quantization, instruction variant or offload effects. No further tuning or
measurement is performed in this experiment. Passing synthetic cases establishes feasibility
only; it does not demonstrate real-news reliability or trading performance.

The original holdout has now been inspected. Preserve these M4A2 results, but do not treat
that set as untouched final validation after remediation. Any later generalization claim
requires fresh evaluation cases, independently reviewed and fixed before their first run.

References remain pending_human_review and frozen=false. Remaining independent human
judgments are the source-described materiality convention, M4A-16 horizon bins, M4A-17 v1
representability abstention, M4A-08 optional basis-point other fact, M4A-10 range omission,
shared participant fact coverage, M4A-26 missing customer impact and rumor/opinion cases.
The delegated M4A-20 choice is recorded, not claimed as independent human review. No labels,
prompt, score, gate or profile changed in response to either run. No benchmark defect requiring
an inference rerun was established; all failed/missing observations are preserved.

## Verification and retained evidence

After the annotation change, offline checks actually passed: **138 focused benchmark tests**,
**260 total Python tests**, benchmark check (40 cases; 483 clear/1 acceptable-set/22 ambiguous
labels across all cases), make format and make lint. Format/lint reused the installed pinned
toolchain with Maven/UV offline; no verification dependencies were installed. The new regression
checks owner delegation, required full abstention, empty numeric references, unchanged input
hash and contract-valid assembly. Shared benchmark executable code, prompt, profiles and
scoring remain unchanged. Java's unrelated 534-test verification is reused, not rerun.
Final whitespace, protected-path, identity-lock and ignored-artifact checks are recorded
alongside final Git status below. Both evaluated summaries retain eligibility=false.

Local artifacts (ignored, repository-relative):

- `.tools/m4a/downloads/{llama,cudart}.tar.gz`, `.tools/m4a/runtime/b11981/`,
  `models/weights/{Qwen3-4B-Instruct-2507-Q5_K_M,Qwen3-8B-Q4_K_M}.gguf`.
- `.tools/m4a/m4a2-20261029T172816Z-c0e8f774-{preflight,artifacts,device-probes,measurement-lock}.json`,
  setup/device-probe logs and profile execution JSON/command logs.
- Each unique run directory contains manifest.json, records.json, samples.json, offline
  summary.json/summary.md, per-process server.log and process-specific CUDA caches.
  The 8B failure evidence is retained in full; directories were never overwritten.

The complete 40-case [review table](m4a-human-review.md), fixture README, manifest/references,
focused tests, current plan and historical-verification pointer are updated; this report is
new and trackable. Raw outputs, weights, binaries, caches and local settings remain ignored.
Java, authoritative contracts, migrations, infrastructure, dependency pins/lockfiles,
operational fake worker and protocol are unchanged. During the diagnostic experiment, no
production integration, commits or pushes occurred. Both profile processes and their wrappers have exited; no owned runtime remains.

Historical pre-publication verification snapshot: `git diff --check` passed; all nine measurement-lock hashes
still match; protected-path allowlist passed; all 235 runtime/model/run files are ignored;
small fixtures and this summary remain trackable. Changed-file credential-pattern and
newline checks passed. At that snapshot, HEAD was
`2f8949a26d9c533f2cd37bc6d9cafc3c24eddbe9` on main; the index was empty.
Historical Git status after diagnostics, before the separately authorized publication:

```text
 M apps/ai-worker/tests/test_benchmark.py
 M docs/m4a-human-review.md
 M docs/m4a-verification.md
 M docs/m4a.md
 M tests/fixtures/extraction/m4a-v1/README.md
 M tests/fixtures/extraction/m4a-v1/manifest.json
 M tests/fixtures/extraction/m4a-v1/references.json
?? docs/m4a2-diagnostic.md
```

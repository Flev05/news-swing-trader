# M4A1 preparation review verification — 2026-10-29

This report records M4A1 preparation verification before publication. During that phase,
no runtime/model artifact downloads, installations, real inference, commits or pushes occurred. All subprocesses in regression tests are canned Python
HTTP stubs in temporary directories, not model runtimes or measured extraction runs.
Official pinned-runtime text documentation was read to verify supported alias metadata;
no runtime binaries or models were fetched. M4A2 remains separately approval-gated.

At the historical pre-commit verification snapshot, HEAD was
`2dc86c2afd435711ff97c8ab04394b63ab4cd524`. The pre-existing uncommitted M4A1 work
was preserved and corrected; the index was empty. Java's documented 534-test and
migration verification were reused, not rerun. Protected Java/contracts/migrations/worker
protocol/operational worker/scaffold/dependency pins/lockfiles remain unchanged.

## Review fixes and evidence

1. Runtime identity: each owned process receives a new random --alias, checked against
   GET /v1/models before readiness. Every source-bearing POST verifies the alias on the
   same live HTTP connection, rejecting a closing socket instead of allowing an implicit
   reconnect. The expected alias is not disclosed to the peer in queries. Metadata reads
   obey startup/sample deadlines; mismatch stops the run without inference retries.
   [Pinned runtime support](https://raw.githubusercontent.com/ggml-org/llama.cpp/b11981/tools/server/README.md)
   documents --alias as the returned /v1/models model id. This guards accidental listener
   collisions; it is not authentication against a hostile local process reading command lines.
   A deterministic test occupies the selected port with a separate healthy process and
   withholds its metadata reply until it observes the owned child alive. The foreign alias
   is rejected, neither process receives a source POST, only the owned child is cleaned up,
   and the foreign listener remains healthy. Further tests cover same-socket reconnect
   rejection, changed alias after readiness and bounded metadata timeout.

2. Numeric auditing: strict-parsed extraction evidence is stored separately from accepted
   trusted results. Rejected results are empty; schema-valid completed results alone can
   earn categorical or required-fact semantic credit. Diagnostic traversal recognizes
   numeric_facts and metric/value-role objects even in extra envelope properties and
   nonstandard containers, without repairing the original document. Invented values survive
   envelope, enum, abstention, Unicode, duplicate-ticker and shape failures in unsupported-fact
   diagnostics. Limit/truncation failures retain raw output and outer runtime response;
   complete JSON is audited, incomplete JSON is explicitly unavailable, never prefix-parsed.
   Per-case availability/reason/observed unsupported counts appear in JSON and Markdown.
   Partial/unavailable audits mark totals incomplete/lower-bound and fail audit coverage;
   an unavailable audit cannot masquerade as a successful zero-unsupported result.

3. References: dataset m4a-v1.1, reference version 1.1.0; all labels pending human review
   and frozen=false. The original synthetic M4A-08 text lacked consensus: its source now
   explicitly supplies gross-margin consensus 24.00%, with required decimal consensus 24;
   its source and file hashes were regenerated. M4A-17 source is unchanged; remove the
   unsupported forward_looking_statement representative and use documented whole-ticker
   unsupported_event_type abstention for a financing-only scenario with no supported v1
   extraction reason. This explicitly identifies a representability gap, not absence of
   financing from the event-type enum. Named counterparties in 21/22 now have optional
   support for the shared deal/contract total. Apply the same rule to 26 for diagnostic
   source support while still forbidding numbers in its valid abstention row. Issuer-specific
   revenue/EPS/margins do not transfer, duplicates cannot inflate credit, and the primary
   issuer's required numeric coverage remains separate from counterparty support.
   Prior file hashes and correction rationales are retained in manifest.corrections.

4. Prompt 1.1.0 communicates generic source-described materiality, direction/abstention,
   consensus versus prior, exact period formatting and shared-event conventions. The prompt
   header, Python provenance/configuration and dataset manifest agree on its identity.
   Prompts still exclude case IDs, reference/evidence/category annotations and case-specific
   answers. Domain schema_version stays 1.0.0; model/runtime pins and numeric gates are unchanged.
   Audit-completeness gating is additional protection, not a relaxed performance gate.

## Actual final verification

Existing M4A1 preparation evidence was 107 focused / 229 total Python tests. Review fixes
add 30 focused regressions, bringing actual final results to:

- **137 focused offline tests passed**, no failures/skips (21.33 seconds).
- **259 complete Python tests passed**, no failures/skips (22.08 seconds): 122 original
  baseline tests plus 137 benchmark tests.
- make format passed (80 Java sources unchanged; Python already formatted).
- make lint passed (Spotless/check/Java compile, Ruff format/check, strict mypy: 18 files).
- Corpus check, protected-path diff, unchanged-index and tracked whitespace checks passed.
- A separate scan verifies final newline/trailing whitespace for every untracked artifact.
- Runtime/model/cache/raw-run paths are ignored; fixtures, review table and this summary
  remain trackable. No repository .tools/m4a or models/weights setup directory exists.

Required Make gates ran without downloads or installations:

```bash
export MAVEN_ARGS=-o UV_OFFLINE=1 UV_NO_SYNC=1 UV_LOCKED=1
make format
make lint
make test-python
```

Focused checks used the existing virtual environment:

```bash
apps/ai-worker/.venv/bin/python -m pytest apps/ai-worker/tests/test_benchmark.py -o addopts= -q
apps/ai-worker/.venv/bin/python -m news_swing_ai.benchmark check
git diff --check
git diff --cached --exit-code
```

## Corpus identity and review status

40 cases: 8 development, 30 inference holdout, 2 context controls. Across all cases there
are **473 clear, 10 acceptable-set, 23 ambiguous** field labels; 22 ambiguous labels belong
to the two unscored context controls. Materiality in case 20 remains ambiguous. Nothing is
human-reviewed or frozen, and no real-model score is reported.

| File | Current SHA-256 |
| --- | --- |
| inputs.json | `9049225bc7fd7ccc85eea6865a379009472042a27907b6f7bc01ce65c4a07224` |
| references.json | `236c4cc634adc45100cd9f57c4f9efa796dea11487d75cccbf0c025dbcf68ed4` |
| manifest.json | `13352e03647f571084b1f63cf700401f031a1225e6f28b8ff548082777d18384` |

M4A-08 original content hash: `026f281acec7ecad5ed3a9c3034ebfdb6acf8625244bc0bf08be83eb1f386593`.
Current content hash: `28bf28dc4d63645232776f5b96a4768162706903fbf18edffbfcb9d17e44fefa`. All other news-event content hashes are unchanged by this review.

| Core field | Scored labels | Ambiguous excluded | Acceptable-set labels |
| --- | ---: | ---: | ---: |
| event_type | 34 | 0 | 1 |
| direction | 34 | 0 | 1 |
| materiality | 33 | 1 | 0 |
| relevance | 34 | 0 | 1 |
| information_status | 34 | 0 | 1 |
| abstain | 34 | 0 | 1 |

Required numeric scalar-role atoms: 29. Mandatory-abstention ticker labels: 10; clearly extractable ticker labels: 23; clear rumor labels: 1. These are denominator inventories, not measured scores.

## Remaining human judgments and future work

[Complete updated 40-case review table](m4a-human-review.md).
[Rubric, explicit correction history, and exact future setup/run commands](m4a.md).
Review all cases. In particular, confirm the new synthetic consensus statement (08), generic
basis-point other fact (08), days/weeks bins (16), representability-abstention convention (17),
uncalibrated significant materiality and acceptable alternatives (20), primary-required versus
counterparty-optional shared-fact coverage (21/22), abstention despite source-supported customer
transaction facts (26), ranges (10) and rumor/opinion handling (27/29/30).

Labels remain model-assisted drafts pending human review; no real-model outputs have been judged. No threshold was adjusted for hypothetical or
observed performance. M4A2 still requires separate approval; its pinned downloads total about
8.69 GB and planned disk reserve remains 20 GB. Operational fake execution remains unchanged.

## Historical pre-commit Git status snapshot

The following snapshot was captured before publication staging, commit and push. It does
not describe Git status after publication. At that time, all changes were uncommitted and
the index was empty. Only README.md and the originally approved in-memory contracts helper
modified tracked files; the remaining 14 artifacts were untracked M4A1 source, prompt, tests,
fixtures and documents.

```text
 M README.md
 M apps/ai-worker/src/news_swing_ai/contracts.py
?? apps/ai-worker/src/news_swing_ai/benchmark/__init__.py
?? apps/ai-worker/src/news_swing_ai/benchmark/__main__.py
?? apps/ai-worker/src/news_swing_ai/benchmark/core.py
?? apps/ai-worker/src/news_swing_ai/benchmark/prompt.txt
?? apps/ai-worker/src/news_swing_ai/benchmark/runtime.py
?? apps/ai-worker/src/news_swing_ai/benchmark/scoring.py
?? apps/ai-worker/tests/test_benchmark.py
?? docs/m4a-human-review.md
?? docs/m4a-verification.md
?? docs/m4a.md
?? tests/fixtures/extraction/m4a-v1/README.md
?? tests/fixtures/extraction/m4a-v1/inputs.json
?? tests/fixtures/extraction/m4a-v1/manifest.json
?? tests/fixtures/extraction/m4a-v1/references.json
```

## Publication review

Reviewed the complete 16-file M4A1 change, including untracked source, tests, fixtures and
documentation, for scope, secrets and accidental artifacts. No executable changes were
needed for publication; only this report's temporal wording was clarified. Reuse the
documented 137-focused/259-total Python, formatting, lint and protected-path verification,
and the unrelated Java verification. Corpus hashes still match the verification above.
References remain pending_human_review and frozen=false; unresolved judgments are unchanged.
This publication scope review is not human review or approval of reference annotations.

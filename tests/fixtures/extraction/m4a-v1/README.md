# M4A-v1 synthetic extraction fixtures

Current dataset m4a-v1.1; references/prompt 1.1.0.
Status: provisional, pending human review; not frozen.
Explicit authoring corrections and previous file hashes are in manifest.corrections and docs/m4a.md. No real news or inference output.

- inputs.json contains only case keys and contract-valid synthetic news events.
- references.json contains provisional field labels, required/optional facts and evidence.
- manifest.json owns the explicit development/holdout/context-control split and provenance.

The model prompt sees only allowlisted source fields, never labels or annotations.
Review the complete table in docs/m4a-human-review.md and the rubric/scoring definitions
in docs/m4a.md before changing review status or using scored results.
The two long inputs test context rejection, not semantic abstention.

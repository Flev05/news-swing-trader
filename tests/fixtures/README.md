# Test fixture origin

The News Event examples in `contracts/v1/valid/news-event/` and
`contracts/v1/invalid/news-event/` are synthetic contract-test scenarios. They use fixture provider
identifiers, illustrative ACME/BETA symbols, `.test` source URLs, and short example headlines and
summaries. The multi-ticker body explicitly describes itself as a fixed fixture. Invalid examples
exercise deliberate contract violations.

These files entered the repository with the shared contract corpus in commit
`3687e8e7bf00473a1ed1e69b5d3711a11b7eab5f`.

`ingestion/v1/canonicalization.json`, added in commit
`40eb3666877636dec835f69e70cfc4fd3d0c73e0`, contains synthetic normalization and hashing vectors,
including Unicode/newline variants and fixed expected hashes. Its text tests canonicalization
rather than reporting real financial events.

This provenance note covers those checked-in News Event and canonicalization fixtures, not future
external news inputs.

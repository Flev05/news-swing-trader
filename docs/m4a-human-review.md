# M4A1 provisional human-review table

Dataset m4a-v1.1; references 1.1.0; prompt 1.1.0. All 40 cases remain **pending human review** and **unfrozen**.
R = required; O = optional source-supported fact. Values below are provisional labels, not model outputs.
Shared event totals may appear once per explicitly named party; issuer-specific metrics may not transfer.
O (audit only) does not authorize numeric facts in an abstaining row.

| Case | Split | Expected extraction / abstention | Abstention | Numeric facts | Ambiguous judgments / questions |
| --- | --- | --- | --- | --- | --- |
| M4A-01 | development | ASTR earnings positive/high; EPS beat. | ASTR=false | R ASTR eps actual=2.14, prior=1.72, consensus=1.98, unit=currency, currency=USD, period=Q3 2026 | Provisional rubric applies. |
| M4A-02 | holdout | BERY earnings negative/high; loss and consensus; no invented prior. | BERY=false | R BERY eps actual=-0.25, consensus=0.1, unit=currency, currency=USD, period=Q3 2026 | Provisional rubric applies. |
| M4A-03 | holdout | CNDR positive/high; expand billions. | CNDR=false | R CNDR revenue actual=1250000000, consensus=1200000000, unit=currency, currency=USD, period=Q3 2026 | Provisional rubric applies. |
| M4A-04 | development | DUNE guidance positive/high; actual revised and prior guidance. | DUNE=false | R DUNE guidance_revenue actual=900000000, prior=800000000, unit=currency, currency=USD, period=FY2027 | Provisional rubric applies. |
| M4A-05 | holdout | ELMR guidance negative/high; omit consensus. | ELMR=false | R ELMR guidance_eps actual=1.5, prior=2, unit=currency, currency=USD, period=FY2027 | Provisional rubric applies. |
| M4A-06 | holdout | FJRD neutral/low reiteration; EUR facts. | FJRD=false | R FJRD guidance_revenue actual=400000000, prior=400000000, unit=currency, currency=EUR, period=FY2027 | Provisional rubric applies. |
| M4A-07 | holdout | GRVE mixed/high; both revenue and EPS. | GRVE=false | R GRVE revenue actual=80000000, consensus=75000000, unit=currency, currency=USD, period=Q3 2026; R GRVE eps actual=0.3, consensus=0.5, unit=currency, currency=USD, period=Q3 2026 | Provisional rubric applies. |
| M4A-08 | holdout | HRTH earnings positive/medium; explicit synthetic margin-consensus comparison now supports the earnings reason. Margin required; revenue/basis-point change optional. | HRTH=false | R HRTH gross_margin actual=24.5, prior=23, consensus=24, unit=percent, period=Q3 2026; O HRTH revenue actual=12000000, unit=currency, currency=USD, period=Q3 2026; O HRTH other actual=150, unit=basis_points, period=Q3 2026 | Review the explicit source augmentation and optional generic basis-point other fact; original source had no consensus comparison. |
| M4A-09 | holdout | IBIS positive/low; zero and negative consensus. | IBIS=false | R IBIS net_income actual=0, consensus=-1000000, unit=currency, currency=USD, period=Q3 2026 | Provisional rubric applies. |
| M4A-10 | holdout | JNPC positive/high guidance; omit unrepresentable scalar ranges. | JNPC=false | None | Confirm omission of ranges rather than midpoint extraction; no contract extension in M4A. |
| M4A-11 | development | KSTR merger_acquisition positive/high; deal_value 250000000 USD. | KSTR=false | R KSTR deal_value actual=250000000, unit=currency, currency=USD | Provisional rubric applies. |
| M4A-12 | holdout | LUMF contract positive/high; contract_value 75000000 USD. | LUMF=false | R LUMF contract_value actual=75000000, unit=currency, currency=USD | Provisional rubric applies. |
| M4A-13 | holdout | MORN regulatory positive/high; No required numeric fact. | MORN=false | None | Provisional rubric applies. |
| M4A-14 | holdout | NACR legal negative/high; No required numeric fact. | NACR=false | None | Provisional rubric applies. |
| M4A-15 | development | OPAL management neutral/low; No required numeric fact. | OPAL=false | None | Provisional rubric applies. |
| M4A-16 | holdout | PINE product positive/medium; No required numeric fact. | PINE=false | None | PINE expected_horizon acceptable ['days', 'weeks']; Review days/weeks acceptable set. |
| M4A-17 | holdout | QRTZ full abstention: financing-only extraction has no supported v1 reason; no invented forward-looking reason. | QRTZ=true | None | Human review must confirm the explicit v1 representability-abstention convention; no contract extension or unsupported reason is inserted. |
| M4A-18 | holdout | RILL contract positive/low; contract_value 1250000 USD. | RILL=false | R RILL contract_value actual=1250000, unit=currency, currency=USD | Provisional rubric applies. |
| M4A-19 | holdout | SABL regulatory negative/medium; No required numeric fact. | SABL=false | None | Provisional rubric applies. |
| M4A-20 | holdout | TERN merger_acquisition positive/medium; No required numeric fact. | TERN=acceptable yes/no pending review | None | TERN materiality ambiguous/excluded; Coherent extraction or whole-ticker abstention accepted pending review; see detailed per-field sets; Resolve materiality before freeze; extraction or coherent full abstention are provisionally acceptable. Materiality is excluded. |
| M4A-21 | development | UMBR negative, VALE positive; shared deal total required on UMBR, optional on VALE. | UMBR=false, VALE=false | R UMBR deal_value actual=300000000, unit=currency, currency=USD; O VALE deal_value actual=300000000, unit=currency, currency=USD | Confirm primary-required/counterparty-optional coverage convention; shared transaction values on either named party are source-supported, while issuer-specific metrics are not transferable. |
| M4A-22 | development | WREN positive/high; XYLM customer neutral/low; shared contract total required on WREN, optional on XYLM. | WREN=false, XYLM=false | R WREN contract_value actual=20000000, unit=currency, currency=USD; O XYLM contract_value actual=20000000, unit=currency, currency=USD | Confirm primary-required/counterparty-optional coverage convention; shared transaction values on either named party are source-supported, while issuer-specific metrics are not transferable. |
| M4A-23 | holdout | YARR earnings positive/high; ZINN abstains; never copy EPS to peer. | YARR=false, ZINN=true | R YARR eps actual=3, consensus=2, unit=currency, currency=USD, period=Q3 2026 | Provisional rubric applies. |
| M4A-24 | holdout | AMBP positive; BRCH competitor negative; explicit effects only. | AMBP=false, BRCH=false | None | Provisional rubric applies. |
| M4A-25 | holdout | COBF positive, DRFT negative; separate revenue comparisons. | COBF=false, DRFT=false | R COBF revenue actual=40000000, consensus=35000000, unit=currency, currency=USD, period=Q3 2026; R DRFT revenue actual=10000000, consensus=15000000, unit=currency, currency=USD, period=Q3 2026 | Provisional rubric applies. |
| M4A-26 | holdout | ECHS positive/medium; FABL abstains despite known customer identity. | ECHS=false, FABL=true | R ECHS contract_value actual=6000000, unit=currency, currency=USD; O (audit only) FABL contract_value actual=6000000, unit=currency, currency=USD | Review whole-ticker abstention for missing customer impact. Shared value is source-supported on FABL for auditing, but valid abstention must omit numeric facts. |
| M4A-27 | development | GARN rumor, positive/high as reported; USD 500m remains an unverified reported fact. | GARN=false | R GARN deal_value actual=500000000, unit=currency, currency=USD | Review whether rumor deal value should be required or optional extraction. |
| M4A-28 | holdout | HBRM abstains for insufficient detail; no deal invented. | HBRM=true | None | Provisional rubric applies. |
| M4A-29 | holdout | IRSM legal negative/high opinion; novelty new based on stated new injunction. | IRSM=false | None | Review whether opinion-only materiality is sufficiently extractable; no confidence claims. |
| M4A-30 | holdout | JADE rumor positive/high; reported contract value USD 30m. | JADE=false | R JADE contract_value actual=30000000, unit=currency, currency=USD | Provisional rubric applies. |
| M4A-31 | development | KEEL full abstention: insufficient_source_detail; no numeric facts. | KEEL=true | None | Provisional rubric applies. |
| M4A-32 | holdout | LRCH full abstention: ambiguous_ticker_attribution; no numeric facts. | LRCH=true | None | Provisional rubric applies. |
| M4A-33 | holdout | MAPL full abstention: conflicting_information; no numeric facts. | MAPL=true | None | Provisional rubric applies. |
| M4A-34 | holdout | NOBL full abstention: unsupported_event_type; no numeric facts. | NOBL=true | None | Provisional rubric applies. |
| M4A-35 | holdout | ORCH full abstention: insufficient_source_detail; no numeric facts. | ORCH=true | None | Provisional rubric applies. |
| M4A-36 | holdout | PBBL full abstention: insufficient_source_detail; no numeric facts. | PBBL=true | None | Provisional rubric applies. |
| M4A-37 | holdout | REED positive/high; EPS 1.2 and consensus 1; ignore spam and metadata request. | REED=false | R REED eps actual=1.2, consensus=1, unit=currency, currency=USD, period=Q3 2026 | Provisional rubric applies. |
| M4A-38 | holdout | SHOL abstains; no fabricated USD 9bn fact. | SHOL=true | None | Provisional rubric applies. |
| M4A-39 | limit holdout | CONTEXT_LIMIT before generation; no assembled result or semantic score. | TIDE=not inferred (context limit) | None | No semantic score; all fields excluded for this context-limit control; This tests context rejection, not abstention quality. |
| M4A-40 | limit holdout | CONTEXT_LIMIT before generation; no assembled result or semantic score. | UPLD=not inferred (context limit) | None | No semantic score; all fields excluded for this context-limit control; This tests context rejection, not abstention quality. |

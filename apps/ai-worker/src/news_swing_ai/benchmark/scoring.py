"""Deterministic reference scoring; no model judge and no output repair."""

from __future__ import annotations

import math
from collections import Counter
from typing import Any

from .core import CORE_FIELDS, OTHER_FIELDS, atom, canonical_decimal, numeric_claims


def ratio(correct: int, total: int) -> dict[str, Any]:
    return {"correct": correct, "total": total, "rate": correct / total if total else None}


def percentile(values: list[float], q: float) -> float | None:
    return sorted(values)[max(0, math.ceil(len(values) * q) - 1)] if values else None


def score(
    records: list[dict[str, Any]], references: dict[str, Any], expected_ids: list[str]
) -> dict[str, Any]:
    # Main first attempts only. Repeats and cold/development observations never inflate n.
    first = {record["case_id"]: record for record in records if record["phase"] == "holdout"}
    if len(first) != sum(record["phase"] == "holdout" for record in records):
        raise ValueError("DUPLICATE_FIRST_ATTEMPT")
    if set(first) - set(expected_ids):
        raise ValueError("UNEXPECTED_CASE")
    fields = {field: [0, 0, 0, 0] for field in CORE_FIELDS + OTHER_FIELDS}
    # correct, scored, ambiguous excluded, acceptable-set scored
    validity: Counter[str] = Counter()
    audit_counts: Counter[str] = Counter()
    audit_cases: list[dict[str, Any]] = []
    required_total = required_matched = supported = predictions = unsupported = 0
    normalized = decimal_total = exact_coverage = must_abstain = abstained_correct = 0
    extractable = false_abstain = extracted = rumor_promotions = runtime_failures = 0
    rumor_total = ticker_expected = ticker_matched = ticker_missing = ticker_extra = (
        ticker_duplicates
    ) = 0
    latencies: list[float] = []
    failures: list[dict[str, Any]] = []
    for case_id in expected_ids:
        record = first.get(case_id, {})
        stages = record.get("stages", {})
        for stage in ("json_valid", "decoding_schema_valid", "contract_valid"):
            validity[stage] += bool(stages.get(stage))
        valid = bool(stages.get("contract_valid")) and record.get("failure") is None
        if not valid:
            failures.append(
                {
                    "case_id": case_id,
                    "failure": record.get(
                        "failure", "CONTRACT_INVALID" if record else "NOT_COMPLETED"
                    ),
                }
            )
        if (
            record.get("failure")
            in {
                "TIMEOUT",
                "RUNTIME_EXIT",
                "OOM",
                "RUNTIME_IDENTITY_MISMATCH",
                "HTTP_ERROR",
                "OUTPUT_LIMIT",
                "TRUNCATED",
                "RUN_DEADLINE",
            }
            or not record
        ):
            runtime_failures += 1
        if "elapsed_seconds" in record:
            latencies.append(record["elapsed_seconds"])
        expected = references[case_id]["tickers"]
        evidence = record.get("parsed_extraction")
        audit = record.get(
            "numeric_audit", {"status": "unavailable", "reason": "NO_PARSED_EVIDENCE"}
        )
        audit_counts[audit["status"]] += 1
        audit_cases.append({"case_id": case_id, **audit})
        raw_results = evidence.get("ticker_results", []) if isinstance(evidence, dict) else []
        rows = raw_results if isinstance(raw_results, list) else []
        names = [
            row.get("ticker")
            for row in rows
            if isinstance(row, dict) and isinstance(row.get("ticker"), str)
        ]
        if valid and len(names) == len(expected) and set(names) == set(expected):
            exact_coverage += 1
        ticker_expected += len(expected)
        ticker_matched += len(set(names) & set(expected))
        ticker_missing += len(set(expected) - set(names))
        ticker_extra += len(set(names) - set(expected))
        ticker_duplicates += len(names) - len(set(names))
        numeric_required: Counter[tuple[str, ...]] = Counter()
        numeric_optional: Counter[tuple[str, ...]] = Counter()
        numeric_predictions: Counter[tuple[str, ...]] = Counter()
        for ticker, label in expected.items():
            candidates = [
                row for row in rows if isinstance(row, dict) and row.get("ticker") == ticker
            ]
            predicted = candidates[0] if valid and len(candidates) == 1 else {}
            for field, spec in label["fields"].items():
                count = fields[field]
                if spec["status"] == "ambiguous":
                    count[2] += 1
                    continue
                count[1] += 1
                count[3] += spec["status"] == "acceptable_set"
                actual = predicted.get(field)
                allowed = spec["values"] if spec["status"] == "acceptable_set" else [spec["value"]]
                if field == "reason_codes":
                    actual = sorted(actual) if isinstance(actual, list) else actual
                    allowed = [sorted(value) for value in allowed]
                count[0] += actual in allowed
            abstain = label["fields"]["abstain"]
            if abstain["status"] == "clear":
                if abstain["value"]:
                    must_abstain += 1
                    abstained_correct += predicted.get("abstain") is True
                else:
                    extractable += 1
                    false_abstain += predicted.get("abstain") is True
                    extracted += predicted.get("abstain") is False
            info = label["fields"]["information_status"]
            if info["status"] == "clear" and info["value"] == "rumor":
                rumor_total += 1
                rumor_promotions += predicted.get("information_status") == "fact"
            for key, destination in (
                ("required_facts", numeric_required),
                ("optional_facts", numeric_optional),
            ):
                for fact in label[key]:
                    for role in ("actual", "prior", "consensus"):
                        if role in fact:
                            destination[atom(fact, ticker, role)] += 1
        # Numeric diagnostics inspect the strict-parsed original, including rejected
        # envelopes/nonstandard containers; none of this data becomes an accepted result.
        before_unsupported, before_predictions = unsupported, predictions
        for claimed_ticker, fact in numeric_claims(evidence) if isinstance(evidence, dict) else []:
            if not isinstance(fact, dict):
                unsupported += 1
                predictions += 1
                continue
            roles = [role for role in ("actual", "prior", "consensus") if role in fact]
            if not roles:
                unsupported += 1
                predictions += 1
            for role in roles:
                predictions += 1
                decimal_total += 1
                try:
                    item = atom(fact, claimed_ticker, role)
                    hash(item)
                    numeric_predictions[item] += 1
                    value = fact[role]
                    normalized += value == canonical_decimal(value)
                except (KeyError, ValueError, TypeError):
                    unsupported += 1
        allowed = numeric_required + numeric_optional
        matched = numeric_predictions & allowed
        supported += sum(matched.values())
        unsupported += sum((numeric_predictions - allowed).values())
        audit_cases[-1].update(
            predicted_atoms=predictions - before_predictions,
            unsupported_atoms=unsupported - before_unsupported,
        )
        required_total += sum(numeric_required.values())
        # No semantic credit from invalid/incomplete results, even if some atoms parse.
        if valid:
            required_matched += sum((numeric_predictions & numeric_required).values())
    result: dict[str, Any] = {
        "n": len(expected_ids),
        "observed": len(first),
        "failures": failures,
        "validity": {
            stage: ratio(validity[stage], len(expected_ids))
            for stage in ("json_valid", "decoding_schema_valid", "contract_valid")
        },
        "fields": {
            name: {
                **ratio(count[0], count[1]),
                "ambiguous_excluded": count[2],
                "acceptable_set_scored": count[3],
            }
            for name, count in fields.items()
        },
        "ticker_exact_coverage": ratio(exact_coverage, len(expected_ids)),
        "ticker_counts": {
            "expected": ticker_expected,
            "matched": ticker_matched,
            "missing": ticker_missing,
            "extra": ticker_extra,
            "duplicates": ticker_duplicates,
        },
        "numeric_audit": {"counts": dict(audit_counts), "cases": audit_cases},
        "numeric": {
            "required_atom_recall": ratio(required_matched, required_total),
            "source_supported_precision": ratio(supported, predictions),
            "unsupported_atoms": unsupported,
            "unsupported_atoms_complete": bool(expected_ids)
            and audit_counts["available"] == len(expected_ids),
            "decimal_normalization": ratio(normalized, decimal_total),
        },
        "mandatory_abstention_recall": ratio(abstained_correct, must_abstain),
        "false_abstention_rate": ratio(false_abstain, extractable),
        "extraction_coverage": ratio(extracted, extractable),
        "rumor_to_fact_promotions": rumor_promotions,
        "rumor_promotion_rate": ratio(rumor_promotions, rumor_total),
        "runtime_failures": runtime_failures,
        "warm_latency": {
            "n": len(latencies),
            "p50_seconds": percentile(latencies, 0.5),
            "p95_seconds": percentile(latencies, 0.95),
        },
    }
    rates = [fields[field][0] / fields[field][1] for field in CORE_FIELDS if fields[field][1]]
    result["core_macro_accuracy"] = sum(rates) / len(rates) if rates else None

    def meets(metric: dict[str, Any], minimum: float) -> bool:
        return metric["rate"] is not None and metric["rate"] >= minimum

    result["gates"] = {
        "contract": meets(result["validity"]["contract_valid"], 1),
        "tickers": meets(result["ticker_exact_coverage"], 1),
        "core_fields": all(meets(result["fields"][field], 0.9) for field in CORE_FIELDS),
        "numeric_recall": meets(result["numeric"]["required_atom_recall"], 0.95),
        "numeric_audit_complete": bool(expected_ids)
        and audit_counts["available"] == len(expected_ids),
        "numeric_supported": meets(result["numeric"]["source_supported_precision"], 1)
        and unsupported == 0,
        "normalization": meets(result["numeric"]["decimal_normalization"], 0.95),
        "abstention": meets(result["mandatory_abstention_recall"], 1),
        "false_abstention": result["false_abstention_rate"]["rate"] is not None
        and result["false_abstention_rate"]["rate"] <= 0.1,
        "extraction": meets(result["extraction_coverage"], 0.9),
        "rumors": rumor_total > 0 and rumor_promotions == 0,
        "latency": len(latencies) == len(expected_ids)
        and bool(latencies)
        and max(0.0, result["warm_latency"]["p95_seconds"]) <= 30
        and runtime_failures == 0,
    }
    return result

"""M4A3 isolated adapters. Native evidence never acquires envelope authority."""

from __future__ import annotations

import copy
import json
import re
from dataclasses import asdict
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any
from uuid import NAMESPACE_URL, uuid5

from jsonschema import Draft202012Validator

from news_swing_ai.contracts import payload_errors
from news_swing_ai.transport import ProtocolError, decode

from .core import CLASSIFICATIONS, SCHEMA, extraction_spec, messages, read

HERE = Path(__file__).parent
VERSIONS = {"A": "2.0.0", "B": "2.1.0"}
AMOUNT = re.compile(
    r"(?<![\w.+-])(?:(?P<currency>[A-Z]{3})[ \t]+)?"
    r"(?P<number>[+-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\.[0-9]+)?)"
    r"(?:[ \t]+(?P<scale>thousand|million|billion))?"
    r"(?:[ \t]*(?P<suffix>%|percent|basis points|shares|units|x))?(?![\w]|\.[0-9])"
)


def object_spec(properties: dict[str, Any], required: list[str] | None = None) -> dict[str, Any]:
    return {
        "type": "object",
        "additionalProperties": False,
        "properties": properties,
        "required": list(properties) if required is None else required,
    }


def spec(variant: str, symbols: list[str]) -> dict[str, Any]:
    domain = read(SCHEMA)["$defs"]
    flat = extraction_spec()["properties"]["ticker_results"]["items"]
    common = copy.deepcopy(flat["properties"])
    common["ticker"] = {"enum": symbols}
    reasons = {k: domain[k]["enum"] for k in ["extractionReasonCode", "abstainReasonCode"]}
    if variant == "A":
        common["numeric_facts"]["items"]["properties"]["period"]["pattern"] = r"^.*\S.*$"
        branches = []
        for abstain in [True, False]:
            props = copy.deepcopy(common)
            props["abstain"] = {"const": abstain}
            for key in CLASSIFICATIONS:
                if abstain:
                    props[key] = {"const": "unknown"}
                elif key in ["event_type", "direction", "materiality", "relevance"]:
                    props[key]["enum"].remove("unknown")
            props["reason_codes"]["items"] = {
                "enum": reasons["abstainReasonCode" if abstain else "extractionReasonCode"]
            }
            if abstain:
                props["numeric_facts"]["maxItems"] = 0
            branches.append(object_spec(props, list(flat["required"])))
        return object_spec(
            {
                "ticker_results": {
                    "type": "array",
                    "minItems": len(symbols),
                    "maxItems": len(symbols),
                    "items": {"anyOf": branches},
                }
            }
        )
    if variant != "B":
        raise ValueError("UNKNOWN_VARIANT")
    fields = {k: copy.deepcopy(common[k]) for k in CLASSIFICATIONS}
    for key in ["event_type", "direction", "materiality", "relevance"]:
        fields[key]["enum"].remove("unknown")
    fields["reason_codes"] = {
        "type": "array",
        "minItems": 1,
        "maxItems": 12,
        "items": {"enum": reasons["extractionReasonCode"]},
    }
    fields["facts"] = {
        "type": "array",
        "maxItems": 60,
        "items": object_spec(
            {
                "metric": domain["numericFact"]["properties"]["metric"],
                "role": {"enum": ["actual", "prior", "consensus"]},
                "evidence": {"type": "string", "minLength": 1, "maxLength": 4096},
                "value_text": {"type": "string", "minLength": 1, "maxLength": 256},
                "period_text": {"type": "string", "minLength": 1, "maxLength": 64},
            },
            ["metric", "role", "evidence", "value_text"],
        ),
    }
    required = [k for k in fields if k not in ["novelty", "expected_horizon", "schedule_status"]]
    branch = {
        "anyOf": [
            object_spec({"abstain_reason": {"enum": reasons["abstainReasonCode"]}}),
            object_spec({"extract": object_spec(fields, required)}),
        ]
    }
    return object_spec({"symbols": object_spec({t: copy.deepcopy(branch) for t in symbols})})


def prompt_messages(variant: str, event: dict[str, Any]) -> list[dict[str, str]]:
    base = messages(event)
    extra = (HERE / f"remediation-{variant.lower()}.txt").read_text()
    if variant == "A":
        base[0]["content"] += "\n" + extra
    else:
        # Vocabulary once, without repeating symbol-specific branches or reference hints.
        schema = spec(variant, event["affected_symbols"])
        vocabulary = next(iter(schema["properties"]["symbols"]["properties"].values()))
        base[0]["content"] = extra + "\nBranch schema:\n" + json.dumps(vocabulary)
    return base


def source_fields(event: dict[str, Any]) -> list[str]:
    return [event["headline"]] + [
        event["content"][k] for k in ["summary", "body"] if k in event["content"]
    ]


def normalize_claim(
    claim: dict[str, Any], event: dict[str, Any]
) -> tuple[dict[str, Any], list[str]]:
    """Normalize claimed text, even on rejection, so unsupported values remain auditable."""
    errors = []
    role = claim.get("role")
    role_key = role if role in ["actual", "prior", "consensus"] else "actual"
    fact = {"metric": claim.get("metric", "other"), role_key: "INVALID_CLAIM"}
    if role_key != role:
        errors.append("INVALID_ROLE")
    text = claim.get("value_text")
    evidence = claim.get("evidence")
    amount = AMOUNT.fullmatch(text) if isinstance(text, str) and len(text) <= 256 else None
    if amount is None:
        errors.append("INVALID_AMOUNT_TEXT")
    else:
        number = Decimal(amount["number"].replace(",", ""))
        shift = {None: 0, "thousand": 3, "million": 6, "billion": 9}[amount["scale"]]
        tup = number.as_tuple()
        value = format(Decimal((tup.sign, tup.digits, int(tup.exponent) + shift)), "f")
        if "." in value:
            value = value.rstrip("0").rstrip(".")
        fact[role_key] = value if number else "0"
        if amount["currency"]:
            fact.update(unit="currency", currency=amount["currency"])
        suffix = amount["suffix"]
        if suffix:
            if amount["currency"]:
                errors.append("CONFLICTING_UNIT")
            fact["unit"] = {
                "%": "percent",
                "percent": "percent",
                "basis points": "basis_points",
                "shares": "shares",
                "units": "units",
                "x": "multiple",
            }[suffix]
    grounded = False
    if isinstance(evidence, str) and evidence and isinstance(text, str):
        for source in source_fields(event):
            start = source.find(evidence)
            while start >= 0:
                # Require the complete original amount expression, including sign/currency/scale.
                grounded |= any(
                    m.group() == text and start <= m.start() and m.end() <= start + len(evidence)
                    for m in AMOUNT.finditer(source)
                )
                start = source.find(evidence, start + 1)
    if not grounded:
        errors.append("UNSUPPORTED_SOURCE_SPAN")
    if "period_text" in claim:
        period = claim["period_text"]
        if not isinstance(period, str):
            errors.append("INVALID_PERIOD")
        else:
            fact["period"] = period.strip()
            if not isinstance(evidence, str) or period not in evidence or not period.strip():
                errors.append("UNSUPPORTED_PERIOD_SPAN")
    return fact, errors


def intermediate_audit(
    native: dict[str, Any], event: dict[str, Any]
) -> tuple[list[dict[str, Any]], list[str]]:
    """Recognize all IR and legacy claims, including invalid/extra containers, without filtering."""
    rows: list[dict[str, Any]] = []
    errors: list[str] = []

    def visit(node: Any, ticker: str = "", in_facts: bool = False) -> None:
        if isinstance(node, list):
            for item in node:
                visit(item, ticker, in_facts)
        elif isinstance(node, dict):
            if "ticker" in node:
                ticker = str(node["ticker"])
            legacy = "metric" in node and any(k in node for k in ["actual", "prior", "consensus"])
            if (in_facts and not legacy) or "value_text" in node:
                fact, rejected = normalize_claim(node, event)
                rows.append({"ticker": ticker, "numeric_facts": [fact]})
                errors.extend(rejected)
            if "numeric_facts" in node:
                facts = node["numeric_facts"]
                for fact in facts if isinstance(facts, list) else [facts]:
                    rows.append({"ticker": ticker, "numeric_facts": [fact]})
                    if isinstance(fact, dict):
                        for child in fact.values():
                            visit(child, ticker)
            elif legacy:
                rows.append({"ticker": ticker, "numeric_facts": [node]})
            for key, child in node.items():
                if key == "symbols" and isinstance(child, dict):
                    for name, value in child.items():
                        visit(value, name)
                elif key != "numeric_facts":
                    visit(child, ticker, key == "facts")
        elif in_facts:
            rows.append(
                {
                    "ticker": ticker,
                    "numeric_facts": [{"metric": "other", "actual": "INVALID_CLAIM"}],
                }
            )
            errors.append("MALFORMED_CLAIM")

    visit(native)
    return rows, errors


def adapt(
    variant: str, raw: str, event: dict[str, Any], provenance: dict[str, Any], trial: str
) -> dict[str, Any]:
    out: dict[str, Any] = {
        "native": None,
        "intermediate_claims": [],
        "assembled_candidate": {},
        "result": {},
        "parsed_extraction": None,
        "rejections": [],
        "numeric_audit": {"status": "unavailable", "reason": "STRICT_JSON_FAILED"},
        "stages": {"json_valid": False, "decoding_schema_valid": False, "contract_valid": False},
    }
    try:
        native = decode(raw.encode())
    except (ProtocolError, UnicodeError, RecursionError):
        out["rejections"].append("INVALID_JSON")
        return out
    out["native"] = native
    out["stages"]["json_valid"] = True
    errors = list(
        Draft202012Validator(spec(variant, event["affected_symbols"])).iter_errors(native)
    )
    out["native_schema_errors"] = [e.message for e in errors]
    if errors:
        out["rejections"].append("NATIVE_SCHEMA_INVALID")
    out["stages"]["decoding_schema_valid"] = not errors
    if variant == "A":
        rows = native.get("ticker_results", [])
        candidate_rows = rows if isinstance(rows, list) else []
        diagnostic = copy.deepcopy(native)
    else:
        audit_rows, rejected = intermediate_audit(native, event)
        out["intermediate_claims"] = audit_rows
        out["rejections"].extend(rejected)
        candidate_rows = []
        symbols = native.get("symbols", {})
        for ticker, node in symbols.items() if isinstance(symbols, dict) else []:
            if not isinstance(node, dict):
                continue
            if "abstain_reason" in node:
                row: dict[str, Any] = {k: "unknown" for k in CLASSIFICATIONS}
                row.update(
                    ticker=ticker,
                    abstain=True,
                    reason_codes=[node["abstain_reason"]],
                    numeric_facts=[],
                )
            else:
                fields = node.get("extract", {})
                if not isinstance(fields, dict):
                    continue
                row = {k: fields.get(k, "unknown") for k in CLASSIFICATIONS}
                row.update(
                    ticker=ticker,
                    abstain=False,
                    reason_codes=fields.get("reason_codes", []),
                    numeric_facts=[],
                )
                claims = fields.get("facts", [])
                if isinstance(claims, list):
                    for claim in claims:
                        if isinstance(claim, dict):
                            fact, _ = normalize_claim(claim, event)
                            row["numeric_facts"].append(fact)
            candidate_rows.append(row)
        # Categorical candidates plus EVERY normalized raw claim, including rejected extras.
        diagnostic = {
            "ticker_results": [{**row, "numeric_facts": []} for row in candidate_rows],
            "audited_claims": audit_rows,
        }
        if not errors and not out["rejections"]:
            # On accepted native shapes these rows contain precisely the same claims.
            diagnostic = {"ticker_results": candidate_rows}
    out["parsed_extraction"] = diagnostic
    out["numeric_audit"] = {
        "status": "available",
        "reason": "STRICT_PARSED_ALL_RECOGNIZED_CLAIMS",
        "scope": "native IR facts/value_text and legacy numeric claims, including extras",
    }
    names = [row.get("ticker") for row in candidate_rows if isinstance(row, dict)]
    if sorted(str(n) for n in names) != sorted(event["affected_symbols"]):
        out["rejections"].append("TICKER_COVERAGE")
    candidate = {
        "schema_version": "1.0.0",
        "inference_id": str(uuid5(NAMESPACE_URL, trial)),
        "news_event_id": event["event_id"],
        "news_event_sha256": event["content_hash"],
        "generated_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "model": copy.deepcopy(provenance),
        "ticker_results": candidate_rows,
    }
    out["assembled_candidate"] = candidate
    out["contract_errors"] = [asdict(v) for v in payload_errors(SCHEMA, candidate)]
    out["candidate_contract_valid"] = not out["contract_errors"]
    if out["contract_errors"]:
        out["rejections"].append("CONTRACT_INVALID")
    out["stages"]["contract_valid"] = not out["rejections"]
    if not out["rejections"]:
        out["result"] = candidate
    return out

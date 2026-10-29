"""Inputs, provenance, constrained specification and authoritative assembly."""

from __future__ import annotations

import copy
import hashlib
import json
import re
import struct
import unicodedata
from decimal import Decimal
from pathlib import Path
from typing import Any
from uuid import NAMESPACE_URL, uuid5

from news_swing_ai.contracts import payload_errors
from news_swing_ai.transport import ProtocolError, decode

ROOT = Path(__file__).resolve().parents[5]
DATA = ROOT / "tests/fixtures/extraction/m4a-v1"
SCHEMA = ROOT / "contracts/v1/inference-result.schema.json"
CORE_FIELDS = (
    "event_type",
    "direction",
    "materiality",
    "relevance",
    "information_status",
    "abstain",
)
OTHER_FIELDS = ("issuer_role", "novelty", "expected_horizon", "schedule_status", "reason_codes")
CLASSIFICATIONS = OTHER_FIELDS[:1] + CORE_FIELDS[:5] + OTHER_FIELDS[1:4]
PROMPT = Path(__file__).with_name("prompt.txt")
PROMPT_VERSION = "1.1.0"


def read(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def canonical_hash(event: dict[str, Any]) -> str:
    def field(value: str | None) -> bytes:
        if value is None:
            return struct.pack(">i", -1)
        value = unicodedata.normalize(
            "NFC", value.replace("\r\n", "\n").replace("\r", "\n")
        ).strip()
        raw = value.encode("utf-8")
        return struct.pack(">i", len(raw)) + raw

    content = event["content"]
    symbols = sorted(event["affected_symbols"])
    raw = b"news-content-v1\0" + b"".join(
        field(value)
        for value in (
            event["headline"],
            content["language"],
            content.get("summary"),
            content.get("body"),
        )
    )
    raw += struct.pack(">i", len(symbols)) + b"".join(field(symbol) for symbol in symbols)
    return hashlib.sha256(raw).hexdigest()


def extraction_spec() -> dict[str, Any]:
    """A decoding aid only: flatten refs and omit unsupported conditional rules."""
    schema = read(SCHEMA)

    def flatten(node: Any) -> Any:
        if isinstance(node, list):
            return [flatten(item) for item in node]
        if not isinstance(node, dict):
            return node
        if "$ref" in node:
            return flatten(schema["$defs"][node["$ref"].rsplit("/", 1)[1]])
        return {
            key: flatten(value)
            for key, value in node.items()
            if key not in {"allOf", "anyOf", "x-unique-by", "description"}
        }

    return {
        "type": "object",
        "additionalProperties": False,
        "required": ["ticker_results"],
        "properties": {"ticker_results": flatten(schema["properties"]["ticker_results"])},
    }


def messages(event: dict[str, Any]) -> list[dict[str, str]]:
    # Deliberate allowlist. No case ID, split, references, categories, or annotations.
    visible = {
        "headline": event["headline"],
        "content": {
            key: event["content"][key]
            for key in ("language", "summary", "body")
            if key in event["content"]
        },
        "affected_symbols": event["affected_symbols"],
    }
    system = (
        PROMPT.read_text(encoding="utf-8")
        + "\nExtraction specification:\n"
        + json.dumps(extraction_spec())
    )
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": json.dumps(visible, ensure_ascii=True)},
    ]


def representable(value: Any) -> None:
    if isinstance(value, str):
        if "\0" in value or any(0xD800 <= ord(char) <= 0xDFFF for char in value):
            raise ValueError("UNREPRESENTABLE_UNICODE")
    elif isinstance(value, dict):
        for key, item in value.items():
            representable(key)
            representable(item)
    elif isinstance(value, list):
        for item in value:
            representable(item)


def assemble(
    raw: bytes, event: dict[str, Any], provenance: dict[str, Any], trial_id: str, generated_at: str
) -> tuple[dict[str, Any], dict[str, bool]]:
    value = decode(raw)
    representable(value)
    stages = {"json_valid": True, "decoding_schema_valid": False, "contract_valid": False}
    if set(value) != {"ticker_results"}:
        return {}, stages
    from jsonschema import Draft202012Validator

    stages["decoding_schema_valid"] = Draft202012Validator(extraction_spec()).is_valid(value)
    result = {
        "schema_version": "1.0.0",
        "inference_id": str(uuid5(NAMESPACE_URL, trial_id)),
        "news_event_id": event["event_id"],
        "news_event_sha256": event["content_hash"],
        "generated_at": generated_at,
        "model": copy.deepcopy(provenance),
        **value,
    }
    stages["contract_valid"] = not payload_errors(SCHEMA, result)
    return result if stages["contract_valid"] else {}, stages


def numeric_claims(document: dict[str, Any]) -> list[tuple[str, Any]]:
    """Audit recognizable structured claims anywhere, without repairing the document.

    Nonstandard containers and extra envelope properties confer no result validity.
    A missing/non-string claimed ticker stays empty, never inferred from the input.
    """
    claims: list[tuple[str, Any]] = []

    def visit(node: Any, claimed_ticker: str = "") -> None:
        if isinstance(node, list):
            for item in node:
                visit(item, claimed_ticker)
        elif isinstance(node, dict):
            if "ticker" in node:
                claimed_ticker = node["ticker"] if isinstance(node["ticker"], str) else ""
            if "numeric_facts" in node:
                facts = node["numeric_facts"]
                for fact in facts if isinstance(facts, list) else [facts]:
                    claims.append((claimed_ticker, fact))
                    if isinstance(fact, dict):
                        for child in fact.values():
                            visit(child, claimed_ticker)
            elif "metric" in node and any(
                role in node for role in ("actual", "prior", "consensus")
            ):
                claims.append((claimed_ticker, node))
            for key, child in node.items():
                if key != "numeric_facts":
                    visit(child, claimed_ticker)

    visit(document)
    return claims


def diagnostic_extraction(raw: bytes) -> tuple[dict[str, Any] | None, dict[str, str]]:
    """Strict parsed evidence only; never an accepted result or prefix/repair parser."""
    scope = "recognized numeric_facts or metric/value-role objects in strict-parsed document"
    try:
        parsed = decode(raw)
        rows = parsed.get("ticker_results")
        standard = isinstance(rows, list) and all(
            isinstance(row, dict) and isinstance(row.get("numeric_facts", []), list) for row in rows
        )
        if standard:
            return parsed, {
                "status": "available",
                "reason": "STRICT_PARSED_STRUCTURED_CLAIMS",
                "scope": scope,
            }
        if numeric_claims(parsed):
            return parsed, {
                "status": "partial",
                "reason": "NON_STANDARD_EXTRACTION_SHAPE",
                "scope": scope,
            }
        return parsed, {"status": "unavailable", "reason": "INVALID_TICKER_RESULTS", "scope": scope}
    except (ProtocolError, RecursionError, UnicodeError):
        return None, {"status": "unavailable", "reason": "STRICT_JSON_FAILED", "scope": scope}


def canonical_decimal(value: str) -> str:
    """Lexical normalization without Decimal context rounding."""
    if not Decimal(value):
        return "0"
    return value.rstrip("0").rstrip(".") if "." in value else value


def atom(fact: dict[str, Any], ticker: str, role: str) -> tuple[str, ...]:
    from decimal import Decimal, InvalidOperation

    value = fact[role]
    if (
        not isinstance(value, str)
        or re.fullmatch(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?", value) is None
    ):
        raise ValueError("INVALID_DECIMAL")
    try:
        number = Decimal(value)
    except InvalidOperation as error:
        raise ValueError("INVALID_DECIMAL") from error
    if not number.is_finite():
        raise ValueError("INVALID_DECIMAL")
    return (
        ticker,
        fact["metric"],
        fact.get("period", ""),
        fact.get("unit", ""),
        fact.get("currency", ""),
        role,
        canonical_decimal(value),
    )


def dataset_identity() -> dict[str, str]:
    return {
        name: digest(DATA / name) for name in ("inputs.json", "references.json", "manifest.json")
    }


def check() -> dict[str, Any]:
    from collections import Counter

    inputs = read(DATA / "inputs.json")
    references = read(DATA / "references.json")
    manifest = read(DATA / "manifest.json")
    assert manifest["review_status"] in {"pending_human_review", "reviewed"}
    assert references["review_status"] == manifest["review_status"]
    assert manifest["prompt_template_version"] == PROMPT_VERSION
    assert references["reference_version"] == manifest["reference_version"]
    if manifest["review_status"] == "reviewed":
        assert manifest["frozen"] is True
        review = manifest["human_review"]
        assert all(
            isinstance(review.get(key), str) and review[key].strip()
            for key in ("reviewer", "date", "decisions")
        )
    else:
        assert manifest["frozen"] is False
    cases = inputs["cases"]
    refs = references["cases"]
    ids = [case["case_id"] for case in cases]
    assert len(ids) == len(set(ids)) == 40
    assert set(ids) == set(refs)
    partitions = manifest["development"] + manifest["holdout"]
    assert len(partitions) == len(set(partitions)) == 40 and set(partitions) == set(ids)
    assert len(manifest["development"]) == 8 and len(manifest["holdout"]) == 32
    assert len(manifest["context_limit"]) == 2
    assert set(manifest["context_limit"]) <= set(manifest["holdout"])
    from jsonschema import Draft202012Validator

    numeric_validator = Draft202012Validator(
        {"$ref": "#/$defs/numericFact", "$defs": read(SCHEMA)["$defs"]}
    )
    counts: Counter[str] = Counter()
    for case in cases:
        assert set(case) == {"case_id", "event"}
        event = case["event"]
        assert not payload_errors(ROOT / "contracts/v1/news-event.schema.json", event)
        assert event["content_hash"] == canonical_hash(event)
        ref = refs[case["case_id"]]
        assert set(ref["tickers"]) == set(event["affected_symbols"])
        for ticker, label in ref["tickers"].items():
            assert set(label["fields"]) == set(CORE_FIELDS + OTHER_FIELDS)
            for field, spec in label["fields"].items():
                assert spec["status"] in {"clear", "acceptable_set", "ambiguous"}
                assert spec["evidence"] in event["headline"] + "\n" + event["content"].get(
                    "body", ""
                )
                if spec["status"] == "acceptable_set":
                    assert len(spec["values"]) >= 2
                counts[spec["status"]] += 1
            seen: set[tuple[str, ...]] = set()
            for necessity in ("required_facts", "optional_facts"):
                for fact in label[necessity]:
                    assert numeric_validator.is_valid(
                        {k: v for k, v in fact.items() if k != "evidence"}
                    )
                    assert fact["evidence"] in event["content"].get("body", "")
                    for role in ("actual", "prior", "consensus"):
                        if role in fact:
                            key = atom(fact, ticker, role)
                            assert key not in seen
                            seen.add(key)
        # Ensure a representative reference can assemble under authoritative v1.
        trial = []
        for ticker, label in ref["tickers"].items():
            fields = {
                field: spec.get("value", spec.get("values", ["unknown"])[0])
                for field, spec in label["fields"].items()
            }
            fields["numeric_facts"] = [
                {k: v for k, v in fact.items() if k != "evidence"}
                for fact in label["required_facts"]
            ]
            trial.append({"ticker": ticker, **fields})
        _, stages = assemble(
            json.dumps({"ticker_results": trial}).encode(),
            event,
            {
                "backend": "llama.cpp",
                "name": "reference-check-only",
                "version": "1",
                "prompt_template_version": PROMPT_VERSION,
            },
            case["case_id"],
            "2026-10-29T00:00:00Z",
        )
        assert stages["contract_valid"], case["case_id"]
    return {
        "cases": 40,
        "development": 8,
        "holdout_inference": 30,
        "context_limit": 2,
        "labels": dict(counts),
        "review_status": manifest["review_status"],
        "identity": dataset_identity(),
    }

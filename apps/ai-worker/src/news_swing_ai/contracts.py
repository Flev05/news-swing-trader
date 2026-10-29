"""Portable validation for the authoritative repository JSON Schemas."""

from __future__ import annotations

import json
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import cast

from jsonschema import Draft202012Validator, FormatChecker


@dataclass(frozen=True, order=True)
class Violation:
    keyword: str
    instance_path: str
    message: str


def validation_errors(schema_path: Path, payload_path: Path) -> tuple[Violation, ...]:
    """Return stable violations; an empty tuple means valid."""

    payload = cast(dict[str, object], json.loads(payload_path.read_text(encoding="utf-8")))
    return payload_errors(schema_path, payload)


def payload_errors(schema_path: Path, payload: dict[str, object]) -> tuple[Violation, ...]:
    """Validate an in-memory document with the same rules as the file API."""

    schema = cast(dict[str, object], json.loads(schema_path.read_text(encoding="utf-8")))
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())

    violations = [
        Violation(
            keyword=str(error.validator),
            instance_path=_json_pointer(error.absolute_path),
            message=error.message,
        )
        for error in validator.iter_errors(payload)
    ]
    violations.extend(_unique_by_violations(schema, payload))
    return tuple(sorted(violations))


def _json_pointer(parts: Iterable[object]) -> str:
    return "".join(f"/{str(part).replace('~', '~0').replace('/', '~1')}" for part in parts)


def _unique_by_violations(schema: dict[str, object], payload: dict[str, object]) -> list[Violation]:
    properties = schema.get("properties")
    if not isinstance(properties, dict):
        return []
    results_schema = properties.get("ticker_results")
    results = payload.get("ticker_results")
    if not isinstance(results_schema, dict) or not isinstance(results, list):
        return []
    unique_by = results_schema.get("x-unique-by")
    if not isinstance(unique_by, str):
        return []

    seen: set[str] = set()
    violations: list[Violation] = []
    for result in results:
        if not isinstance(result, dict):
            continue
        value = result.get(unique_by)
        if isinstance(value, str) and value in seen:
            violations.append(
                Violation(
                    keyword="x-unique-by",
                    instance_path="/ticker_results",
                    message=f"duplicate {unique_by} value '{value}'",
                )
            )
        elif isinstance(value, str):
            seen.add(value)
    return violations

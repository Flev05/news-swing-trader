from __future__ import annotations

import json
from pathlib import Path
from typing import TypedDict, cast

import pytest

from news_swing_ai.contracts import validation_errors


class ContractCase(TypedDict):
    name: str
    schema: str
    fixture: str
    expected_valid: bool
    intended_rule: str | None
    expected_violations: list[ExpectedViolation]


class ExpectedViolation(TypedDict):
    keyword: str
    instance_path: str


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
CORPUS = REPOSITORY_ROOT / "tests/fixtures/contracts/v1/corpus.json"


def contract_cases() -> list[ContractCase]:
    document = cast(dict[str, object], json.loads(CORPUS.read_text(encoding="utf-8")))
    return cast(list[ContractCase], document["cases"])


def object_schemas(value: object) -> list[dict[str, object]]:
    found: list[dict[str, object]] = []
    if isinstance(value, dict):
        node = cast(dict[str, object], value)
        if node.get("type") == "object":
            found.append(node)
        for child in node.values():
            found.extend(object_schemas(child))
    elif isinstance(value, list):
        for child in value:
            found.extend(object_schemas(child))
    return found


@pytest.mark.parametrize(
    "schema_path",
    sorted((REPOSITORY_ROOT / "contracts/v1").glob("*.schema.json")),
    ids=lambda path: path.name,
)
def test_every_contract_object_is_closed(schema_path: Path) -> None:
    schema = json.loads(schema_path.read_text(encoding="utf-8"))

    assert all(node.get("additionalProperties") is False for node in object_schemas(schema))


def test_inference_contract_excludes_later_phase_and_self_assessment_fields() -> None:
    schema_path = REPOSITORY_ROOT / "contracts/v1/inference-result.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    declared_properties = {
        property_name
        for node in object_schemas(schema)
        for properties in [node.get("properties")]
        if isinstance(properties, dict)
        for property_name in properties
    }

    assert declared_properties.isdisjoint(
        {
            "attempt",
            "confidence",
            "evidence",
            "order",
            "probability",
            "retry",
            "risk",
            "signal",
            "summary",
        }
    )


def test_corpus_is_deterministic_and_covers_both_contracts() -> None:
    cases = contract_cases()

    names = [case["name"] for case in cases]
    assert names == sorted(set(names))
    assert {case["schema"] for case in cases} == {
        "contracts/v1/news-event.schema.json",
        "contracts/v1/inference-result.schema.json",
    }
    assert all(case["expected_valid"] is (not case["expected_violations"]) for case in cases)
    assert all(case["expected_valid"] or case["intended_rule"] is not None for case in cases)

    referenced_fixtures = {case["fixture"] for case in cases}
    fixture_root = CORPUS.parent
    fixture_files = {
        str(path.relative_to(REPOSITORY_ROOT))
        for path in fixture_root.rglob("*.json")
        if path != CORPUS
    }
    assert referenced_fixtures == fixture_files


@pytest.mark.parametrize("case", contract_cases(), ids=lambda case: case["name"])
def test_validates_shared_corpus(case: ContractCase) -> None:
    schema = REPOSITORY_ROOT / case["schema"]
    fixture = REPOSITORY_ROOT / case["fixture"]

    assert schema.is_file()
    assert fixture.is_file()

    errors = validation_errors(schema, fixture)
    assert (not errors) is case["expected_valid"], (
        f"{case['name']} expected valid={case['expected_valid']}, errors={errors}"
    )
    actual_rules = {(error.keyword, error.instance_path) for error in errors}
    intended_rules = {
        (violation["keyword"], violation["instance_path"])
        for violation in case["expected_violations"]
    }
    assert actual_rules == intended_rules, (
        f"{case['name']} must fail only for its intended rule and location: "
        f"actual={actual_rules}, intended={intended_rules}"
    )

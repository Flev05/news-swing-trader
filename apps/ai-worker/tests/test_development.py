"""Offline M4A3 regressions: no runtime weights or inference required."""

from __future__ import annotations

import copy
import json
import time
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import pytest
from jsonschema import Draft202012Validator

from news_swing_ai.benchmark import development as dev
from news_swing_ai.benchmark.core import read
from news_swing_ai.benchmark.remediation import adapt, normalize_claim, prompt_messages, spec
from news_swing_ai.benchmark.runtime import Runtime, RuntimeFailure
from news_swing_ai.benchmark.scoring import score

CASES, REFS = dev.inputs()
EVENT = CASES["M4A-01"]["event"]
PROVENANCE = {
    "backend": "llama.cpp",
    "name": "offline-stub",
    "version": "1",
    "prompt_template_version": "1.0.0",
}


def native() -> dict[str, Any]:
    fields = {
        k: v["value"]
        for k, v in REFS["M4A-01"]["tickers"]["ASTR"]["fields"].items()
        if k not in ["abstain"]
    }
    fields["facts"] = [
        {
            "metric": "eps",
            "role": role,
            "evidence": EVENT["content"]["body"],
            "value_text": text,
            "period_text": "Q3 2026",
        }
        for role, text in [("actual", "USD 2.14"), ("prior", "USD 1.72"), ("consensus", "USD 1.98")]
    ]
    return {"symbols": {"ASTR": {"extract": fields}}}


def apply(value: dict[str, Any], variant: str = "B") -> dict[str, Any]:
    return adapt(variant, json.dumps(value), EVENT, PROVENANCE, "offline-trial")


def scored(out: dict[str, Any]) -> dict[str, Any]:
    return score(
        [
            {
                **out,
                "case_id": "M4A-01",
                "phase": "holdout",
                **({"failure": "REJECTED"} if not out["result"] else {}),
            }
        ],
        REFS,
        ["M4A-01"],
    )


@pytest.mark.parametrize(
    ("text", "value", "unit", "currency"),
    [
        ("USD -2.50 million", "-2500000", "currency", "USD"),
        ("EUR 1,234.500 thousand", "1234500", "currency", "EUR"),
        ("USD +1.20 billion", "1200000000", "currency", "USD"),
        ("-0.00", "0", None, None),
        ("24.50%", "24.5", "percent", None),
        ("150 basis points", "150", "basis_points", None),
        ("2x", "2", "multiple", None),
        ("100 shares", "100", "shares", None),
        (
            "USD 123456789012345678901234567890.123456789 billion",
            "123456789012345678901234567890123456789",
            "currency",
            "USD",
        ),
    ],
)
def test_exact_decimal_normalization(
    text: str, value: str, unit: str | None, currency: str | None
) -> None:
    event = copy.deepcopy(EVENT)
    event["content"]["body"] = "Reported " + text + "."
    fact, errors = normalize_claim(
        {"metric": "other", "role": "prior", "evidence": text, "value_text": text}, event
    )
    assert errors == []
    assert fact["prior"] == value
    assert fact.get("unit") == unit and fact.get("currency") == currency
    assert "actual" not in fact and "consensus" not in fact and "period" not in fact


@pytest.mark.parametrize(
    ("source", "claimed"),
    [
        ("USD -2.5 million", "USD 2.5 million"),
        ("USD -2.5 million", "2.5"),
        ("USD 900 million", "USD 900"),
        ("USD 900 million", "900"),
        ("EUR 400 million", "USD 400 million"),
        ("USD 900 million", "USD 901 million"),
    ],
)
def test_source_sign_scale_currency_cannot_be_discarded(source: str, claimed: str) -> None:
    event = copy.deepcopy(EVENT)
    event["content"]["body"] = source + "."
    fact, errors = normalize_claim(
        {"metric": "revenue", "role": "actual", "evidence": source, "value_text": claimed}, event
    )
    assert "UNSUPPORTED_SOURCE_SPAN" in errors
    assert "actual" in fact  # Diagnostic value survives rejection.


def test_b_accepts_scalar_claims_without_missing_value_defaults() -> None:
    out = apply(native())
    assert out["result"] and not out["rejections"]
    assert scored(out)["numeric"]["required_atom_recall"]["rate"] == 1
    for fact in out["result"]["ticker_results"][0]["numeric_facts"]:
        assert sum(k in fact for k in ["actual", "prior", "consensus"]) == 1
    value = native()
    del value["symbols"]["ASTR"]["extract"]["expected_horizon"]
    assert apply(value)["result"]["ticker_results"][0]["expected_horizon"] == "unknown"


@pytest.mark.parametrize("reason", ["insufficient_source_detail", "unsupported_event_type"])
def test_b_abstention_branch_assembles_contract_unknowns(reason: str) -> None:
    out = apply({"symbols": {"ASTR": {"abstain_reason": reason}}})
    row = out["result"]["ticker_results"][0]
    assert row["abstain"] is True and row["numeric_facts"] == []
    assert row["direction"] == row["issuer_role"] == row["information_status"] == "unknown"


def test_mixed_branch_rejection_still_audits_unsupported_claims() -> None:
    value = native()
    value["symbols"]["ASTR"]["abstain_reason"] = "insufficient_source_detail"
    value["symbols"]["ASTR"]["extract"]["facts"][0]["value_text"] = "USD 9999"
    out = apply(value)
    assert not out["result"] and out["native"] == value and out["assembled_candidate"]
    report = scored(out)
    assert report["numeric"]["unsupported_atoms"] == 1
    assert report["numeric"]["required_atom_recall"]["correct"] == 0
    assert all(m["correct"] == 0 for m in report["fields"].values())


@pytest.mark.parametrize(
    "extra",
    [
        {"metric": "eps", "role": "prior", "value_text": "USD 9999", "evidence": "invented"},
        {"ticker": "ASTR", "numeric_facts": [{"metric": "eps", "actual": "9999"}]},
        {"metric": "eps", "role": "made_up", "value_text": "USD 9999"},
    ],
)
def test_extra_envelope_claims_never_disappear(extra: dict[str, Any]) -> None:
    value = native()
    value["extra"] = extra
    out = apply(value)
    assert not out["result"]
    assert scored(out)["numeric"]["unsupported_atoms"] >= 1
    assert out["intermediate_claims"] and out["native_schema_errors"]


@pytest.mark.parametrize(
    "claim", [None, 42, {}, {"metric": "eps", "role": "actual", "value_text": []}]
)
def test_malformed_claims_are_penalized(claim: Any) -> None:
    value = native()
    value["symbols"]["ASTR"]["extract"]["facts"].append(claim)
    out = apply(value)
    assert not out["result"]
    assert scored(out)["numeric"]["unsupported_atoms"] >= 1


def test_source_presence_does_not_establish_role_or_metric() -> None:
    value = native()
    value["symbols"]["ASTR"]["extract"]["facts"][0]["metric"] = "revenue"
    value["symbols"]["ASTR"]["extract"]["facts"][0]["role"] = "prior"
    out = apply(value)
    assert out["result"]  # Well formed and textually grounded, still semantically wrong.
    assert scored(out)["numeric"]["unsupported_atoms"] == 1
    assert scored(out)["numeric"]["required_atom_recall"]["correct"] == 2


def test_period_claim_is_preserved_and_audited_on_rejection() -> None:
    value = native()
    value["symbols"]["ASTR"]["extract"]["facts"][0]["period_text"] = "FY2099"
    out = apply(value)
    assert not out["result"] and "UNSUPPORTED_PERIOD_SPAN" in out["rejections"]
    assert scored(out)["numeric"]["unsupported_atoms"] == 1


def test_duplicate_predictions_cannot_inflate_credit() -> None:
    value = native()
    value["symbols"]["ASTR"]["extract"]["facts"] *= 2
    report = scored(apply(value))
    assert report["numeric"]["required_atom_recall"]["correct"] == 3
    assert report["numeric"]["unsupported_atoms"] == 3


@pytest.mark.parametrize("v", ["A", "B"])
def test_messages_are_input_only_and_schemas_are_valid(v: str) -> None:
    for case in CASES.values():
        schema = spec(v, case["event"]["affected_symbols"])
        Draft202012Validator.check_schema(schema)
        text = json.dumps(prompt_messages(v, case["event"]))
        assert case["case_id"] not in text and "required_facts" not in text
        assert "review_status" not in text and 'evidence": "' not in text
        assert case["event"]["event_id"] not in text


def test_a_rejects_mixed_reasons_and_duplicate_tickers() -> None:
    value = {
        "ticker_results": [
            {
                "ticker": "ASTR",
                **{k: v["value"] for k, v in REFS["M4A-01"]["tickers"]["ASTR"]["fields"].items()},
            }
        ]
    }
    value["ticker_results"][0]["reason_codes"].append("insufficient_source_detail")
    out = apply(value, "A")
    assert not out["result"] and "NATIVE_SCHEMA_INVALID" in out["rejections"]
    value["ticker_results"] *= 2
    assert "TICKER_COVERAGE" in apply(value, "A")["rejections"]


def test_strict_json_is_not_repaired() -> None:
    for raw in ['{"symbols":', '{"symbols":{},"symbols":{}}']:
        out = adapt("B", raw, EVENT, PROVENANCE, "stub")
        assert out["result"] == {} and out["native"] is None
        assert out["numeric_audit"]["status"] == "unavailable"


def test_missing_observations_keep_all_denominators() -> None:
    report = score([], REFS, dev.DEVELOPMENT)
    assert report["n"] == 8 and report["observed"] == 0
    assert report["fields"]["direction"]["total"] == 10
    assert report["numeric"]["required_atom_recall"]["total"] == 9
    assert not all(report["gates"].values())
    assert dev.repeat_score([], REFS)["gate"] is False


def test_fixed_schedule_is_26_calls_and_development_only() -> None:
    plan = dev.schedule()
    assert len(plan) == 26
    for v in ["A", "B"]:
        rows = [x for x in plan if x["variant"] == v]
        assert [x["phase"] for x in rows] == ["prime"] + ["development"] * 8 + ["repeat"] * 4
        assert {x["case_id"] for x in rows} == set(dev.DEVELOPMENT)
        assert [x["case_id"] for x in rows[-4:]] == dev.REPEATS


@pytest.mark.parametrize("failure", ["TIMEOUT", "RUNTIME_IDENTITY_MISMATCH", "OOM"])
def test_dispatch_failure_is_charged_and_retained(
    monkeypatch: pytest.MonkeyPatch, failure: str
) -> None:
    monkeypatch.setattr(dev, "verify", lambda _, **kw: None)
    ledger = [{**x, "charged": False} for x in dev.schedule()]
    plan = {
        "variants": {"B": {"messages": {"M4A-01": []}, "schemas": {"M4A-01": {}}}},
        "max_calls": 26,
        "generation": {},
    }
    entry = ledger[13]
    saved: list[bool] = []

    def request(_port: int, route: str, _body: Any, _deadline: float, **_: Any) -> dict[str, Any]:
        if route == "/apply-template":
            return {"prompt": "stub"}
        if route == "/tokenize":
            return {"tokens": [1, 2]}
        assert entry["charged"] is True and saved[-1] is True
        raise RuntimeFailure(failure)

    monkeypatch.setattr(dev, "request", request)
    runtime = cast(
        Runtime,
        SimpleNamespace(
            run_deadline=time.monotonic() + 100,
            port=1,
            alias="stub",
            output=Path("stub"),
            process=None,
        ),
    )
    out = dev.observe(
        runtime,
        CASES["M4A-01"],
        entry,
        plan,
        ledger,
        lambda: saved.append(entry["charged"]),
        PROVENANCE,
    )
    assert out["failure"] == failure and out["result"] == {}
    assert out["charged"] is True and out["state"] == "finished"
    assert sum(bool(e["charged"]) for e in ledger) == 1


def test_output_limit_keeps_complete_raw_numeric_evidence(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(dev, "verify", lambda _, **kw: None)
    ledger = [{**x, "charged": False} for x in dev.schedule()]
    entry = ledger[13]
    value = native()
    value["symbols"]["ASTR"]["extract"]["facts"][0]["value_text"] = "USD 9999"

    def request(_port: int, route: str, _body: Any, _deadline: float, **_: Any) -> dict[str, Any]:
        return (
            {"prompt": "stub"}
            if route == "/apply-template"
            else {"tokens": [1]}
            if route == "/tokenize"
            else {"content": json.dumps(value), "stop_type": "limit"}
        )

    monkeypatch.setattr(dev, "request", request)
    runtime = cast(
        Runtime,
        SimpleNamespace(
            run_deadline=time.monotonic() + 100,
            port=1,
            alias="stub",
            output=Path("stub"),
            process=None,
        ),
    )
    plan = {
        "variants": {"B": {"messages": {"M4A-01": []}, "schemas": {"M4A-01": {}}}},
        "max_calls": 26,
        "generation": {},
    }
    out = dev.observe(runtime, CASES["M4A-01"], entry, plan, ledger, lambda: None, PROVENANCE)
    assert out["failure"] == "OUTPUT_LIMIT" and not out["result"]
    assert out["raw_output"] == json.dumps(value) and out["native"] == value
    assert scored(out)["numeric"]["unsupported_atoms"] == 1


def test_fatal_stops_both_variants_and_exclusive_sentinel_blocks_resume(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(dev, "verify", lambda _, **kw: None)
    plan = {
        "schedule": dev.schedule(),
        "aggregate_seconds": 1800,
        "cleanup_reserve_seconds": 5,
        "variants": {"A": {"version": "2.0.0"}, "B": {"version": "2.1.0"}},
    }
    dev.write(tmp_path / "freeze.json", plan)
    launched = []

    class StubRuntime:
        def __init__(self, _a: Any, _b: Any, _c: Any, output: Path, end: float):
            self.samples: list[Any] = []
            self.ready_seconds = 0
            self.arguments: list[str] = []
            launched.append((output.name, end))

        def __enter__(self) -> StubRuntime:
            return self

        def __exit__(self, *_: Any) -> None:
            pass

    def observe(
        _r: Any, _c: Any, entry: dict[str, Any], _p: Any, _l: Any, _save: Any, _prov: Any
    ) -> dict[str, Any]:
        entry.update(charged=True, state="finished")
        return {**entry, "failure": "TIMEOUT"}

    monkeypatch.setattr(dev, "require_run_root", lambda _: None)
    monkeypatch.setattr(dev, "Runtime", StubRuntime)
    monkeypatch.setattr(dev, "observe", observe)
    monkeypatch.setattr(time, "monotonic", lambda: 100.0)
    assert dev.execute(tmp_path, True) == 3
    assert launched == [("A", 1895.0)]
    ledger = read(tmp_path / "ledger.json")
    assert sum(x["charged"] for x in ledger) == 1
    assert sum(x["state"] == "missing" for x in ledger) == 25
    with pytest.raises(FileExistsError):
        dev.execute(tmp_path, True)


def test_run_requires_explicit_local_execution(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="execute-local"):
        dev.execute(tmp_path, False)


def test_raw_run_root_guard(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="IGNORED_RUN_ROOT"):
        dev.require_run_root(tmp_path)


def test_identity_tampering_is_rejected(tmp_path: Path) -> None:
    path = tmp_path / "adapter.py"
    path.write_text("changed")
    with pytest.raises(ValueError, match="IDENTITY_CHANGED"):
        dev.verify({"files": {str(path): "0" * 64}, "runtime_files": {}})


def test_legacy_fact_in_ir_container_is_audited_once_with_claimed_ticker() -> None:
    value = native()
    value["symbols"]["ASTR"]["extract"]["facts"].append({"metric": "eps", "prior": "9999"})
    out = apply(value)
    report = scored(out)
    assert not out["result"]
    assert report["numeric"]["source_supported_precision"]["total"] == 4
    assert report["numeric"]["unsupported_atoms"] == 1

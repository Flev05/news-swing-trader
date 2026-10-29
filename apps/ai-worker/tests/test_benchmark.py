"""M4A1 offline preparation tests. Stub subprocesses never load model weights."""

from __future__ import annotations

import copy
import json
import os
import signal
import subprocess
import sys
import threading
import time
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest

from news_swing_ai.benchmark import __main__ as cli
from news_swing_ai.benchmark.core import (
    DATA,
    PROMPT_VERSION,
    ROOT,
    SCHEMA,
    assemble,
    atom,
    canonical_hash,
    check,
    dataset_identity,
    diagnostic_extraction,
    extraction_spec,
    messages,
    read,
)
from news_swing_ai.benchmark.runtime import PROFILES, Runtime, RuntimeFailure, identity, request
from news_swing_ai.benchmark.scoring import percentile, ratio, score
from news_swing_ai.contracts import payload_errors, validation_errors
from news_swing_ai.transport import ProtocolError

CASES = {case["case_id"]: case for case in read(DATA / "inputs.json")["cases"]}
REFERENCES = read(DATA / "references.json")["cases"]
PROVENANCE = {
    "backend": "llama.cpp",
    "name": "offline-test-fixture",
    "version": "1",
    "prompt_template_version": PROMPT_VERSION,
    "artifact_sha256": "a" * 64,
}


def prediction(case_id: str) -> dict[str, Any]:
    rows = []
    for ticker, reference in REFERENCES[case_id]["tickers"].items():
        fields = {
            field: spec.get("value", spec.get("values", [None])[0])
            for field, spec in reference["fields"].items()
        }
        rows.append(
            {
                "ticker": ticker,
                **fields,
                "numeric_facts": [
                    {key: value for key, value in fact.items() if key != "evidence"}
                    for fact in reference["required_facts"]
                ],
            }
        )
    return {"ticker_results": rows}


def record(case_id: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
    raw = json.dumps(payload or prediction(case_id)).encode()
    parsed, audit = diagnostic_extraction(raw)
    result, stages = assemble(
        raw,
        CASES[case_id]["event"],
        PROVENANCE,
        case_id,
        "2026-10-29T00:00:00Z",
    )
    return {
        "case_id": case_id,
        "phase": "holdout",
        "result": result,
        "parsed_extraction": parsed,
        "numeric_audit": audit,
        "stages": stages,
        "elapsed_seconds": 1.0,
    }


def test_corpus_is_provisional_complete_and_explicitly_partitioned() -> None:
    report = check()
    manifest = read(DATA / "manifest.json")
    assert report["cases"] == 40
    assert report["holdout_inference"] == 30
    assert manifest["frozen"] is False
    assert report["review_status"] == "pending_human_review"
    assert set(manifest["repeat_subset"]) <= set(manifest["holdout"])
    table = (ROOT / "docs/m4a-human-review.md").read_text()
    assert all(f"| {case_id} |" in table for case_id in CASES)


@pytest.mark.parametrize("case_id", list(CASES))
def test_prompt_contains_only_source_and_generic_specification(case_id: str) -> None:
    event = copy.deepcopy(CASES[case_id]["event"])
    event["reference"] = "SECRET_REFERENCE_ANNOTATION"
    event["category"] = "SECRET_CATEGORY_HINT"
    expected_content = copy.deepcopy(event["content"])
    event["content"]["supporting_annotation"] = "SECRET_NESTED_HINT"
    built = messages(event)
    user = json.loads(built[1]["content"])
    assert set(user) == {"headline", "content", "affected_symbols"}
    assert "SECRET_" not in json.dumps(built)
    assert case_id not in json.dumps(built)
    assert "review_summary" not in json.dumps(built)
    assert user["content"] == expected_content  # no summarization or truncation


def test_canonical_hash_matches_existing_java_vectors() -> None:
    for vector in read(ROOT / "tests/fixtures/ingestion/v1/canonicalization.json")["vectors"]:
        event = {
            "headline": vector["headline"],
            "content": {
                "language": vector["language"],
                "summary": vector["summary"],
                "body": vector["body"],
            },
            "affected_symbols": vector["affected_symbols"],
        }
        assert canonical_hash(event) == vector["expected_content_hash"]


@pytest.mark.parametrize(
    "fixture", ["single-ticker-earnings", "per-ticker-abstention", "multi-ticker-roles"]
)
def test_in_memory_validation_preserves_file_api(fixture: str) -> None:
    path = ROOT / f"tests/fixtures/contracts/v1/valid/inference-result/{fixture}.json"
    assert payload_errors(SCHEMA, read(path)) == validation_errors(SCHEMA, path) == ()


@pytest.mark.parametrize(
    "raw", [b'{"ticker_results":[],"ticker_results":[]}', b"{} {}", b'{"x":NaN}', b"[]", b"\xff"]
)
def test_raw_output_is_not_repaired(raw: bytes) -> None:
    with pytest.raises(ProtocolError):
        assemble(raw, CASES["M4A-03"]["event"], PROVENANCE, "trial", "2026-10-29T00:00:00Z")


@pytest.mark.parametrize(
    "field",
    [
        "inference_id",
        "news_event_id",
        "news_event_sha256",
        "generated_at",
        "model",
        "ownership_token",
        "status",
    ],
)
def test_model_cannot_inject_envelope_or_ownership(field: str) -> None:
    payload = prediction("M4A-03")
    payload[field] = "attacker-controlled"
    result, stages = assemble(
        json.dumps(payload).encode(),
        CASES["M4A-03"]["event"],
        PROVENANCE,
        "trial",
        "2026-10-29T00:00:00Z",
    )
    assert result == {} and stages["json_valid"] and not stages["contract_valid"]


def test_assembly_owns_identity_hash_and_provenance() -> None:
    item = record("M4A-03")
    assert item["stages"]["contract_valid"]
    assert item["result"]["news_event_id"] == CASES["M4A-03"]["event"]["event_id"]
    assert item["result"]["news_event_sha256"] == canonical_hash(CASES["M4A-03"]["event"])
    assert item["result"]["model"] == PROVENANCE


@pytest.mark.parametrize("value", ["\0", "\ud800", "\udfff"])
def test_representability_rejected(value: str) -> None:
    payload = prediction("M4A-03")
    payload["ticker_results"][0]["numeric_facts"][0]["period"] = value
    with pytest.raises(ValueError, match="UNREPRESENTABLE"):
        assemble(
            json.dumps(payload).encode(),
            CASES["M4A-03"]["event"],
            PROVENANCE,
            "trial",
            "2026-10-29T00:00:00Z",
        )


def test_decoding_validity_does_not_imply_contract_or_semantic_accuracy() -> None:
    payload = prediction("M4A-03")
    payload["ticker_results"][0]["abstain"] = True
    item = record("M4A-03", payload)
    assert item["stages"]["decoding_schema_valid"] and not item["stages"]["contract_valid"]
    payload = prediction("M4A-03")
    payload["ticker_results"][0]["direction"] = "negative"
    report = score([record("M4A-03", payload)], REFERENCES, ["M4A-03"])
    assert report["validity"]["contract_valid"]["rate"] == 1
    assert report["fields"]["direction"]["rate"] == 0


def test_acceptable_sets_use_membership_and_ambiguous_fields_are_excluded() -> None:
    payload = prediction("M4A-16")
    payload["ticker_results"][0]["expected_horizon"] = "days"
    report = score([record("M4A-16", payload), record("M4A-20")], REFERENCES, ["M4A-16", "M4A-20"])
    assert report["fields"]["expected_horizon"]["correct"] == 2
    assert report["fields"]["expected_horizon"]["acceptable_set_scored"] == 1
    assert report["fields"]["materiality"]["total"] == 1
    assert report["fields"]["materiality"]["ambiguous_excluded"] == 1


def test_empty_denominators_are_na_and_do_not_pass() -> None:
    assert ratio(0, 0)["rate"] is None
    report = score([], REFERENCES, [])
    assert report["numeric"]["required_atom_recall"]["rate"] is None
    assert report["core_macro_accuracy"] is None
    assert report["gates"]["numeric_recall"] is False
    assert report["gates"]["core_fields"] is False


def test_missing_and_invalid_results_remain_in_denominators() -> None:
    report = score([], REFERENCES, ["M4A-03"])
    assert report["fields"]["event_type"] == {
        "correct": 0,
        "total": 1,
        "rate": 0.0,
        "ambiguous_excluded": 0,
        "acceptable_set_scored": 0,
    }
    assert report["numeric"]["required_atom_recall"]["total"] == 2
    assert report["numeric"]["required_atom_recall"]["correct"] == 0


@pytest.mark.parametrize(
    "key,value",
    [
        ("ticker", "WRONG"),
        ("metric", "eps"),
        ("period", "Q4 2026"),
        ("unit", "other"),
        ("currency", "EUR"),
        ("actual", "1250000001"),
    ],
)
def test_numeric_matching_requires_every_identity_component(key: str, value: str) -> None:
    payload = prediction("M4A-03")
    row = payload["ticker_results"][0]
    if key == "ticker":
        row[key] = value
    else:
        row["numeric_facts"][0][key] = value
    report = score([record("M4A-03", payload)], REFERENCES, ["M4A-03"])
    assert report["numeric"]["required_atom_recall"]["rate"] < 1
    assert report["numeric"]["unsupported_atoms"] >= 1


def test_numeric_roles_cannot_be_swapped() -> None:
    payload = prediction("M4A-03")
    fact = payload["ticker_results"][0]["numeric_facts"][0]
    fact["actual"], fact["consensus"] = fact["consensus"], fact["actual"]
    report = score([record("M4A-03", payload)], REFERENCES, ["M4A-03"])
    assert report["numeric"]["required_atom_recall"]["correct"] == 0
    assert report["numeric"]["unsupported_atoms"] == 2


def test_optional_facts_are_supported_but_never_required() -> None:
    original = record("M4A-08")
    first = score([original], REFERENCES, ["M4A-08"])
    assert first["numeric"]["required_atom_recall"]["rate"] == 1
    payload = prediction("M4A-08")
    payload["ticker_results"][0]["numeric_facts"].extend(
        {k: v for k, v in fact.items() if k != "evidence"}
        for fact in REFERENCES["M4A-08"]["tickers"]["HRTH"]["optional_facts"]
    )
    second = score([record("M4A-08", payload)], REFERENCES, ["M4A-08"])
    assert second["numeric"]["required_atom_recall"] == first["numeric"]["required_atom_recall"]
    assert second["numeric"]["source_supported_precision"]["rate"] == 1


def test_duplicate_predictions_do_not_inflate_credit() -> None:
    payload = prediction("M4A-03")
    facts = payload["ticker_results"][0]["numeric_facts"]
    facts.append(copy.deepcopy(facts[0]))
    report = score([record("M4A-03", payload)], REFERENCES, ["M4A-03"])
    assert report["numeric"]["required_atom_recall"]["correct"] == 2
    assert report["numeric"]["source_supported_precision"]["rate"] == 0.5
    assert report["numeric"]["unsupported_atoms"] == 2


def test_decimal_comparison_is_exact_without_context_rounding() -> None:
    fact = {"metric": "revenue", "actual": "123456789012345678901234567890.123456789"}
    other = {**fact, "actual": "123456789012345678901234567890.123456788"}
    assert atom(fact, "TEST", "actual") != atom(other, "TEST", "actual")
    payload = prediction("M4A-03")
    payload["ticker_results"][0]["numeric_facts"][0]["actual"] += ".00"
    report = score([record("M4A-03", payload)], REFERENCES, ["M4A-03"])
    assert report["numeric"]["required_atom_recall"]["rate"] == 1
    assert report["numeric"]["decimal_normalization"]["rate"] == 0.5


@pytest.mark.parametrize("value", [1.2, "1e9", "+1", "01", "1,000"])
def test_non_decimal_strings_never_gain_numeric_credit(value: Any) -> None:
    payload = prediction("M4A-03")
    payload["ticker_results"][0]["numeric_facts"][0]["actual"] = value
    report = score([record("M4A-03", payload)], REFERENCES, ["M4A-03"])
    assert report["numeric"]["unsupported_atoms"] >= 1
    assert report["numeric"]["required_atom_recall"]["correct"] == 0  # invalid result


def test_duplicate_first_attempts_are_not_silently_overwritten() -> None:
    with pytest.raises(ValueError, match="DUPLICATE_FIRST_ATTEMPT"):
        score([record("M4A-03"), record("M4A-03")], REFERENCES, ["M4A-03"])


def test_repeats_do_not_enter_first_attempt_scores() -> None:
    repeated = {**record("M4A-03"), "phase": "repeat"}
    assert score([record("M4A-03"), repeated], REFERENCES, ["M4A-03"])["n"] == 1


def test_percentiles_and_unavailable_resources() -> None:
    assert percentile([], 0.95) is None
    assert percentile(list(map(float, range(1, 31))), 0.95) == 29
    assert cli.resources([])["gate"] is False
    assert cli.variability([], REFERENCES, ["M4A-03"])["gate"] is False


STUB = r"""
import argparse,json,os,sys,threading,time
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
root=Path(__file__).parent
if '--version' in sys.argv: print('version b11981 da6c30a'); sys.exit(0)
if '--list-devices' in sys.argv: print('CUDA0: NVIDIA GeForce RTX 2070'); sys.exit(0)
p=argparse.ArgumentParser(); p.add_argument('--port',type=int)
p.add_argument('--alias'); args,_=p.parse_known_args()
if json.loads((root/'control.json').read_text()).get('mode')=='exit': sys.exit(7)
if json.loads((root/'control.json').read_text()).get('mode')=='linger':
 (root/'alive.json').write_text(json.dumps({'pid':os.getpid()}))
 threading.Event().wait(30); sys.exit(0)
class Handler(BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 def log_message(self,*args): pass
 def do_GET(self):
  control=json.loads((root/'control.json').read_text()); mode=control.get('mode')
  if self.path=='/v1/models':
   if mode=='metadata_slow': threading.Event().wait(10)
   if 'wait_for' in control:
    marker=Path(control['wait_for']); deadline=time.monotonic()+2
    while not marker.exists() and time.monotonic()<deadline: threading.Event().wait(.01)
    pid=json.loads(marker.read_text())['pid']; os.kill(pid,0)
    (root/'observed-live-child.json').write_text(json.dumps({'pid':pid}))
   if mode=='metadata_close': self.close_connection=True
   self.reply({'object':'list','data':[{'id':'foreign' if mode=='wrong_alias' else args.alias}]})
   return
  self.reply({'status':'loading' if mode=='not_ready' else 'ok'},503 if mode=='not_ready' else 200)
 def reply(self,value,status=200):
  raw=json.dumps(value).encode(); self.send_response(status)
  self.send_header('Content-Type','application/json')
  self.send_header('Content-Length',str(len(raw)))
  if self.close_connection: self.send_header('Connection','close')
  self.end_headers(); self.wfile.write(raw)
 def do_POST(self):
  body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
  with (root/'requests.jsonl').open('a') as f:
   f.write(json.dumps({'route':self.path,'body':body})+'\n')
  control=json.loads((root/'control.json').read_text()); mode=control.get('mode','valid')
  if self.path=='/apply-template': self.reply({'prompt':json.dumps(body['messages'])}); return
  if self.path=='/tokenize':
   count=control.get('tokens',9000 if len(body['content'])>30000 else 100)
   self.reply({'tokens':list(range(count))}); return
  if mode=='slow': threading.Event().wait(10); return
  if mode=='bad_outer':
   self.send_response(200); self.end_headers(); self.wfile.write(b'{')
   self.close_connection=True; return
  if mode=='redirect': self.reply({},302); return
  content=control.get('raw',json.dumps(control['payload']))
  self.reply({'content':content,'tokens_predicted':30,'truncated':mode=='truncated',
   'stop_type':'limit' if mode=='limit' else 'eos','timings':{'predicted_n':30}})
server=ThreadingHTTPServer(('127.0.0.1',args.port),Handler)
(root/'listening.json').write_text(json.dumps({'port':server.server_port}))
server.serve_forever()
"""


@pytest.fixture
def stub(tmp_path: Path) -> Path:
    binary = tmp_path / "llama-server"
    binary.write_text(f"#!{sys.executable}\n" + STUB)
    binary.chmod(0o755)
    (tmp_path / "control.json").write_text(json.dumps({"payload": prediction("M4A-03")}))
    return tmp_path


def stub_control(stub: Path, **settings: Any) -> None:
    path = stub / "control.json"
    path.write_text(json.dumps({**json.loads(path.read_text()), **settings}))


def launch(stub: Path, **settings: Any) -> Runtime:
    profile = replace(
        PROFILES["qwen3-4b-2507-q5"], **{"startup_timeout": 2, "sample_timeout": 0.4, **settings}
    )
    return Runtime(
        stub, stub / "no-weights-used", profile, stub / "logs", time.monotonic() + 5, monitor=False
    )


def test_stub_process_exercises_runtime_and_trusted_assembly(stub: Path) -> None:
    with launch(stub) as runtime:
        item = runtime.infer(CASES["M4A-03"], "holdout", PROVENANCE, "stub-trial")
        assert item.get("failure") is None
        assert item["stages"]["contract_valid"]
        assert runtime.process is not None
        process = runtime.process
    assert process.poll() is not None
    requests = [json.loads(line) for line in (stub / "requests.jsonl").read_text().splitlines()]
    assert [item["route"] for item in requests] == ["/apply-template", "/tokenize", "/completion"]
    completion = requests[-1]["body"]
    assert completion["cache_prompt"] is False and completion["temperature"] == 0
    assert completion["json_schema"] == extraction_spec()


@pytest.mark.parametrize(
    "mode,expected",
    [
        ("limit", "OUTPUT_LIMIT"),
        ("truncated", "TRUNCATED"),
        ("slow", "TIMEOUT"),
        ("bad_outer", "HTTP_ERROR"),
        ("redirect", "HTTP_ERROR"),
    ],
)
def test_stub_runtime_failures_are_explicit_and_not_retried(
    stub: Path, mode: str, expected: str
) -> None:
    stub_control(stub, mode=mode)
    started = time.monotonic()
    with launch(stub) as runtime:
        item = runtime.infer(CASES["M4A-03"], "holdout", PROVENANCE, "stub-trial")
    assert item["failure"] == expected
    assert time.monotonic() - started < 4
    requests = (stub / "requests.jsonl").read_text().splitlines()
    assert sum(json.loads(line)["route"] == "/completion" for line in requests) == 1


@pytest.mark.parametrize("count,expected", [(5888, None), (5889, "CONTEXT_LIMIT")])
def test_context_boundary_rejects_without_completion(
    stub: Path, count: int, expected: str | None
) -> None:
    stub_control(stub, tokens=count)
    with launch(stub) as runtime:
        item = runtime.infer(CASES["M4A-03"], "holdout", PROVENANCE, "trial")
    assert item.get("failure") == expected
    assert ('"route": "/completion"' in (stub / "requests.jsonl").read_text()) == (expected is None)


def test_bad_model_json_has_no_repair(stub: Path) -> None:
    stub_control(stub, raw='{"ticker_results":[],"ticker_results":[]}')
    with launch(stub) as runtime:
        item = runtime.infer(CASES["M4A-03"], "holdout", PROVENANCE, "trial")
    assert item["failure"] == "INVALID_JSON"


def test_startup_exit_cleans_up(stub: Path) -> None:
    stub_control(stub, mode="exit")
    runtime = launch(stub)
    with pytest.raises(RuntimeFailure, match="STARTUP_EXIT"):
        with runtime:
            pytest.fail("early-exit stub should not become ready")
    assert runtime.process is not None and runtime.process.poll() == 7


def test_artifact_mismatch_fails_before_any_process_launch(tmp_path: Path) -> None:
    model = tmp_path / "dummy.txt"
    model.write_text("This is an offline artifact mismatch fixture, not a model.")
    with pytest.raises(ValueError, match="ARTIFACT_MISMATCH"):
        identity(tmp_path / "absent-runtime", model, PROFILES["qwen3-4b-2507-q5"])


def test_cli_run_requires_explicit_execution_and_review(capsys: pytest.CaptureFixture[str]) -> None:
    arguments = [
        "run",
        "--runtime-dir",
        "/absent",
        "--model",
        "/absent",
        "--profile",
        "qwen3-4b-2507-q5",
        "--run-dir",
        "/absent",
    ]
    assert cli.main(arguments) == 2
    assert "--execute-local" in capsys.readouterr().err
    assert cli.main(arguments + ["--execute-local"]) == 2
    assert "pending human review" in capsys.readouterr().err


def test_trackable_fixture_and_ignored_artifact_boundaries() -> None:
    tracked = subprocess.run(
        [
            "git",
            "check-ignore",
            "tests/fixtures/extraction/m4a-v1/inputs.json",
            "docs/m4a-human-review.md",
        ],
        cwd=ROOT,
        capture_output=True,
    )
    assert tracked.returncode == 1
    for path in (
        ".tools/m4a/runs/anything/summary.json",
        ".tools/m4a/runtime/llama-server",
        "models/weights/example.gguf",
    ):
        assert (
            subprocess.run(["git", "check-ignore", path], cwd=ROOT, capture_output=True).returncode
            == 0
        )


def test_evaluate_provisional_offline_fixture_never_recommends_integration(tmp_path: Path) -> None:
    manifest = read(DATA / "manifest.json")
    ids = [cid for cid in manifest["holdout"] if cid not in manifest["context_limit"]]
    records = [record(cid) for cid in ids]
    records += [{**record(cid), "phase": "development"} for cid in manifest["development"]]
    records += [
        {**record(cid), "phase": "repeat"} for cid in manifest["repeat_subset"] for _ in range(2)
    ]
    records += [
        {"case_id": cid, "phase": "limit", "failure": "CONTEXT_LIMIT"}
        for cid in manifest["context_limit"]
    ]
    cli.write(tmp_path / "records.json", records)
    cli.write(
        tmp_path / "manifest.json",
        {
            "dataset": dataset_identity(),
            "profile": "offline-stub-fixture",
            "completed": True,
            "cold": [{"valid": True, "startup_seconds": 1.0, "first_result_seconds": 2.0}] * 3,
        },
    )
    cli.write(
        tmp_path / "samples.json",
        [
            {
                "gpu_free_mib": 1000,
                "gpu_used_mib": 7000,
                "available_kib": 3000000,
                "rss_hwm_kib": 100,
                "swap_used_kib": 0,
            }
        ],
    )
    report, code = cli.evaluate(tmp_path)
    assert code == 1
    assert report["diagnostic_gates_pass"] is True
    assert report["eligible_for_integration_review"] is False
    assert "pending_human_review" in (tmp_path / "summary.md").read_text()
    changed = read(tmp_path / "manifest.json")
    changed["dataset"]["references.json"] = "wrong"
    cli.write(tmp_path / "manifest.json", changed)
    with pytest.raises(ValueError, match="REFERENCE_CHANGED"):
        cli.evaluate(tmp_path)


def test_full_run_command_with_stub_processes_only(
    stub: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(cli, "ROOT", tmp_path)
    monkeypatch.setattr(
        cli,
        "identity",
        lambda *_: {
            "configuration_sha256": "b" * 64,
            "dataset": dataset_identity(),
            "test_only": "canned subprocess fixture; no weights or model inference",
        },
    )

    def factory(*args: Any, **kwargs: Any) -> Runtime:
        return Runtime(*args, **kwargs, monitor=False)

    monkeypatch.setattr(cli, "Runtime", factory)
    monkeypatch.setitem(
        PROFILES,
        "qwen3-4b-2507-q5",
        replace(PROFILES["qwen3-4b-2507-q5"], startup_timeout=2, sample_timeout=2, run_timeout=30),
    )
    output = tmp_path / ".tools/m4a/runs/offline-command-test"
    arguments = [
        "run",
        "--runtime-dir",
        str(stub),
        "--model",
        str(stub / "not-a-model"),
        "--profile",
        "qwen3-4b-2507-q5",
        "--run-dir",
        str(output),
        "--execute-local",
        "--allow-provisional",
    ]
    assert cli.main(arguments) == 0
    records = read(output / "records.json")
    assert sum(item["phase"] == "holdout" for item in records) == 30
    assert sum(item["phase"] == "repeat" for item in records) == 12
    assert sum(item.get("failure") == "CONTEXT_LIMIT" for item in records) == 2
    assert cli.main(["evaluate", "--run-dir", str(output)]) == 1
    assert read(output / "summary.json")["eligible_for_integration_review"] is False
    assert read(output / "manifest.json")["completed"] is True


def test_reviewed_status_requires_actual_review_record_and_freeze(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from news_swing_ai.benchmark import core

    original = core.read

    def edited(path: Path) -> Any:
        value = original(path)
        if path.name in {"manifest.json", "references.json"}:
            value["review_status"] = "reviewed"
        return value

    monkeypatch.setattr(core, "read", edited)
    with pytest.raises(AssertionError):
        core.check()


def test_acceptable_set_nonmember_is_wrong_not_excluded() -> None:
    payload = prediction("M4A-16")
    payload["ticker_results"][0]["expected_horizon"] = "months"
    field = score([record("M4A-16", payload)], REFERENCES, ["M4A-16"])["fields"]["expected_horizon"]
    assert field["correct"] == 0 and field["total"] == 1
    assert field["acceptable_set_scored"] == 1 and field["ambiguous_excluded"] == 0


def test_all_abstention_cannot_pass_extractable_cases() -> None:
    payload = prediction("M4A-36")
    payload["ticker_results"][0]["ticker"] = "CNDR"
    report = score([record("M4A-03", payload)], REFERENCES, ["M4A-03"])
    assert report["false_abstention_rate"]["rate"] == 1
    assert report["extraction_coverage"]["rate"] == 0
    assert report["gates"]["extraction"] is False
    assert report["gates"]["false_abstention"] is False


def test_rumor_promotion_and_empty_rumor_denominator() -> None:
    payload = prediction("M4A-30")
    payload["ticker_results"][0]["information_status"] = "fact"
    report = score([record("M4A-30", payload)], REFERENCES, ["M4A-30"])
    assert report["rumor_promotion_rate"]["rate"] == 1
    assert report["gates"]["rumors"] is False
    empty = score([record("M4A-03")], REFERENCES, ["M4A-03"])
    assert empty["rumor_promotion_rate"]["rate"] is None
    assert empty["gates"]["rumors"] is False


def test_duplicate_tickers_are_contract_invalid_and_gain_no_field_credit() -> None:
    payload = prediction("M4A-03")
    payload["ticker_results"] *= 2
    report = score([record("M4A-03", payload)], REFERENCES, ["M4A-03"])
    assert report["validity"]["contract_valid"]["rate"] == 0
    assert report["fields"]["event_type"]["correct"] == 0
    assert report["ticker_counts"]["duplicates"] == 1


def test_runtime_startup_deadline_cleans_up(stub: Path) -> None:
    stub_control(stub, mode="not_ready")
    runtime = launch(stub, startup_timeout=0.3)
    started = time.monotonic()
    with pytest.raises(RuntimeFailure, match="STARTUP_TIMEOUT"):
        with runtime:
            pytest.fail("not-ready stub must not pass health")
    assert time.monotonic() - started < 3
    assert runtime.process is not None and runtime.process.poll() is not None


def test_expired_run_deadline_does_not_send_inference(stub: Path) -> None:
    with launch(stub) as runtime:
        runtime.run_deadline = time.monotonic() - 1
        item = runtime.infer(CASES["M4A-03"], "holdout", PROVENANCE, "trial")
    assert item["failure"] == "RUN_DEADLINE"
    assert not (stub / "requests.jsonl").exists()


def test_runtime_version_mismatch_stops_before_device_probe(stub: Path) -> None:
    from news_swing_ai.benchmark.core import digest

    model = stub / "dummy-artifact.txt"
    model.write_text("offline text fixture, not model weights")
    binary = stub / "llama-server"
    binary.write_text(binary.read_text().replace("version b11981 da6c30a", "wrong-version"))
    profile = replace(PROFILES["qwen3-4b-2507-q5"], size=model.stat().st_size, sha256=digest(model))
    with pytest.raises(ValueError, match="RUNTIME_VERSION_MISMATCH"):
        identity(stub, model, profile)


def test_repeat_numeric_omissions_are_reported_as_variability() -> None:
    first = record("M4A-03")
    payload = prediction("M4A-03")
    payload["ticker_results"][0]["numeric_facts"] = []
    repeated = {**record("M4A-03", payload), "phase": "repeat"}
    report = cli.variability([first, repeated, repeated], REFERENCES, ["M4A-03"])
    assert report["rate"] == 1  # unchanged categorical decisions
    assert report["numeric_consistency"]["rate"] == 0
    assert report["unsupported_repeat_atoms"] == 0


def test_foreign_healthy_listener_rejected_while_owned_child_is_alive(
    stub: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    foreign = tmp_path / "foreign"
    foreign.mkdir()
    binary = foreign / "llama-server"
    binary.write_text(f"#!{sys.executable}\n" + STUB)
    binary.chmod(0o755)
    (foreign / "control.json").write_text(
        json.dumps({"payload": {}, "wait_for": str(stub / "alive.json")})
    )
    listener = subprocess.Popen(
        [str(binary), "--port", "0", "--alias", "unrelated-runtime"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )
    owned = launch(stub)
    stub_control(stub, mode="linger")
    try:
        deadline = time.monotonic() + 3
        marker = foreign / "listening.json"
        while not marker.exists() and time.monotonic() < deadline:
            assert listener.poll() is None
            threading.Event().wait(0.01)
        port = read(marker)["port"]
        monkeypatch.setattr(Runtime, "choose_port", staticmethod(lambda: port))
        with pytest.raises(RuntimeFailure, match="RUNTIME_IDENTITY_MISMATCH"):
            with owned:
                pytest.fail("healthy foreign process must never become ready")
        assert owned.process is not None and owned.process.poll() is not None
        # The foreign metadata reply was withheld until it observed the owned child alive.
        assert read(foreign / "observed-live-child.json")["pid"] == owned.process.pid
        assert listener.poll() is None
        assert request(port, "/health", None, time.monotonic() + 1)["status"] == "ok"
        assert not (foreign / "requests.jsonl").exists()  # no source or inference POST
        assert not (stub / "requests.jsonl").exists()
    finally:
        os.killpg(listener.pid, signal.SIGTERM)
        listener.wait(timeout=3)


def test_metadata_socket_close_never_reconnects_and_sends_source(stub: Path) -> None:
    stub_control(stub, mode="metadata_close")
    with launch(stub) as runtime:
        item = runtime.infer(CASES["M4A-03"], "holdout", PROVENANCE, "trial")
    assert item["failure"] == "RUNTIME_IDENTITY_MISMATCH"
    assert not (stub / "requests.jsonl").exists()


def test_alias_is_rechecked_before_source_after_readiness(stub: Path) -> None:
    with launch(stub) as runtime:
        stub_control(stub, mode="wrong_alias")
        item = runtime.infer(CASES["M4A-03"], "holdout", PROVENANCE, "trial")
    assert item["failure"] == "RUNTIME_IDENTITY_MISMATCH"
    assert not (stub / "requests.jsonl").exists()


def test_launch_aliases_are_unique() -> None:
    directory = Path("/unused")
    profile = PROFILES["qwen3-4b-2507-q5"]
    first = Runtime(directory, directory, profile, directory, 0, monitor=False)
    second = Runtime(directory, directory, profile, directory, 0, monitor=False)
    assert first.alias != second.alias


@pytest.mark.parametrize(
    "defect", ["extra_envelope", "bad_field", "bad_abstention", "unicode", "duplicate_ticker"]
)
def test_invalid_results_retain_invented_numeric_claims_for_audit(stub: Path, defect: str) -> None:
    payload = prediction("M4A-03")
    payload["ticker_results"][0]["numeric_facts"][0]["actual"] = "999999999"
    if defect == "extra_envelope":
        payload["ownership_token"] = "model-must-not-own-this"
    elif defect == "bad_field":
        payload["ticker_results"][0]["direction"] = "invented_enum"
    elif defect == "bad_abstention":
        payload["ticker_results"][0]["abstain"] = True
    elif defect == "unicode":
        payload["ticker_results"][0]["numeric_facts"][0]["period"] = "\0"
    else:
        payload["ticker_results"] *= 2
    stub_control(stub, raw=json.dumps(payload))
    with launch(stub) as runtime:
        item = runtime.infer(CASES["M4A-03"], "holdout", PROVENANCE, "trial")
    assert item["failure"] in {"CONTRACT_INVALID", "INVALID_RESPONSE"}
    assert item["result"] == {}
    assert item["parsed_extraction"] == payload
    assert item["numeric_audit"]["status"] == "available"
    report = score([item], REFERENCES, ["M4A-03"])
    assert report["numeric"]["unsupported_atoms"] >= 1
    assert report["numeric"]["required_atom_recall"]["correct"] == 0
    assert all(metric["correct"] == 0 for metric in report["fields"].values())


@pytest.mark.parametrize("mode,expected", [("limit", "OUTPUT_LIMIT"), ("truncated", "TRUNCATED")])
def test_complete_json_limit_failures_are_audited_not_accepted(
    stub: Path, mode: str, expected: str
) -> None:
    payload = prediction("M4A-03")
    payload["ticker_results"][0]["numeric_facts"][0]["actual"] = "999999999"
    raw = json.dumps(payload)
    stub_control(stub, mode=mode, raw=raw)
    with launch(stub) as runtime:
        item = runtime.infer(CASES["M4A-03"], "holdout", PROVENANCE, "trial")
    assert item["failure"] == expected
    assert item["raw_output"] == raw and item["runtime_response"]["content"] == raw
    assert item["result"] == {} and item["parsed_extraction"] == payload
    assert item["numeric_audit"]["status"] == "available"
    report = score([item], REFERENCES, ["M4A-03"])
    assert report["numeric"]["unsupported_atoms"] == 1
    assert report["numeric"]["required_atom_recall"]["correct"] == 0
    assert report["validity"]["contract_valid"]["correct"] == 0


@pytest.mark.parametrize("mode,expected", [("limit", "OUTPUT_LIMIT"), ("truncated", "TRUNCATED")])
def test_partial_json_limit_failures_keep_raw_and_declare_audit_unavailable(
    stub: Path, mode: str, expected: str
) -> None:
    raw = '{"ticker_results":[{"ticker":"CNDR","numeric_facts":[{"actual":"999999999"'
    stub_control(stub, mode=mode, raw=raw)
    with launch(stub) as runtime:
        item = runtime.infer(CASES["M4A-03"], "holdout", PROVENANCE, "trial")
    assert item["failure"] == expected and item["raw_output"] == raw
    assert item["parsed_extraction"] is None and item["result"] == {}
    assert item["numeric_audit"]["status"] == "unavailable"
    assert item["numeric_audit"]["reason"] == "STRICT_JSON_FAILED"
    report = score([item], REFERENCES, ["M4A-03"])
    assert report["numeric_audit"]["counts"]["unavailable"] == 1
    assert report["numeric"]["source_supported_precision"]["rate"] is None
    assert report["gates"]["numeric_audit_complete"] is False


def test_partial_structured_rows_audit_available_claims_but_fail_coverage() -> None:
    payload = prediction("M4A-03")
    payload["ticker_results"][0]["numeric_facts"][0]["actual"] = "999999999"
    payload["ticker_results"].append("unstructured invalid row")
    item = record("M4A-03", payload)
    assert item["result"] == {}
    assert item["numeric_audit"]["status"] == "partial"
    report = score([item], REFERENCES, ["M4A-03"])
    assert report["numeric"]["unsupported_atoms"] == 1
    assert report["gates"]["numeric_audit_complete"] is False


def test_margin_consensus_reason_has_explicit_source_and_required_fact() -> None:
    reference = REFERENCES["M4A-08"]["tickers"]["HRTH"]
    fact = reference["required_facts"][0]
    assert fact["consensus"] == "24"
    assert "against consensus 24.00%" in CASES["M4A-08"]["event"]["content"]["body"]
    assert reference["fields"]["reason_codes"]["value"] == ["earnings_above_consensus"]


def test_financing_representability_uses_explicit_abstention_not_invented_reason() -> None:
    reference = REFERENCES["M4A-17"]
    label = reference["tickers"]["QRTZ"]
    assert label["fields"]["abstain"]["value"] is True
    assert label["fields"]["reason_codes"]["value"] == ["unsupported_event_type"]
    assert "No source-supported extraction reason" in reference["representability"]["blocked_by"]
    assert record("M4A-17")["stages"]["contract_valid"]
    assert all(
        spec["value"] == "unknown"
        for name, spec in label["fields"].items()
        if name not in {"abstain", "reason_codes"}
    )


@pytest.mark.parametrize(
    "case_id,primary,counterparty", [("M4A-21", "UMBR", "VALE"), ("M4A-22", "WREN", "XYLM")]
)
def test_shared_transaction_values_on_named_parties_are_not_fabrication(
    case_id: str, primary: str, counterparty: str
) -> None:
    payload = prediction(case_id)
    rows = {row["ticker"]: row for row in payload["ticker_results"]}
    rows[counterparty]["numeric_facts"] = copy.deepcopy(rows[primary]["numeric_facts"])
    report = score([record(case_id, payload)], REFERENCES, [case_id])
    assert report["numeric"]["source_supported_precision"]["rate"] == 1
    assert report["numeric"]["required_atom_recall"]["rate"] == 1
    assert report["numeric"]["unsupported_atoms"] == 0
    # Repeating the same total twice on one participant still cannot inflate credit.
    rows[counterparty]["numeric_facts"] *= 2
    duplicate = score([record(case_id, payload)], REFERENCES, [case_id])
    assert duplicate["numeric"]["unsupported_atoms"] == 1


@pytest.mark.parametrize(
    "case_id,primary,counterparty", [("M4A-21", "UMBR", "VALE"), ("M4A-22", "WREN", "XYLM")]
)
def test_shared_support_does_not_turn_transaction_total_into_issuer_revenue(
    case_id: str, primary: str, counterparty: str
) -> None:
    payload = prediction(case_id)
    rows = {row["ticker"]: row for row in payload["ticker_results"]}
    row_fact = copy.deepcopy(rows[primary]["numeric_facts"][0])
    row_fact["metric"] = "revenue"
    rows[counterparty]["numeric_facts"] = [row_fact]
    report = score([record(case_id, payload)], REFERENCES, [case_id])
    assert report["numeric"]["unsupported_atoms"] == 1


def test_named_issuer_specific_earnings_cannot_transfer_to_other_participant() -> None:
    payload = prediction("M4A-25")
    rows = {row["ticker"]: row for row in payload["ticker_results"]}
    rows["DRFT"]["numeric_facts"].extend(copy.deepcopy(rows["COBF"]["numeric_facts"]))
    report = score([record("M4A-25", payload)], REFERENCES, ["M4A-25"])
    assert report["numeric"]["unsupported_atoms"] == 2


def test_prompt_version_and_generic_rubric_are_consistent_and_answer_free() -> None:
    from news_swing_ai.benchmark.core import PROMPT

    text = PROMPT.read_text()
    manifest = read(DATA / "manifest.json")
    assert text.startswith(f"M4A extraction prompt {PROMPT_VERSION}\n")
    assert (
        manifest["prompt_template_version"]
        == PROVENANCE["prompt_template_version"]
        == PROMPT_VERSION
    )
    for convention in (
        "maps to high",
        "maps to medium",
        "maps to low",
        "Qn YYYY",
        "no period is stated",
        "shared transaction totals",
        "prior value alone is not consensus",
    ):
        assert convention in text
    for hint in ("M4A-08", "M4A-17", "UMBR", "VALE", "24.00%", "300000000"):
        assert hint not in text


def test_ownership_metadata_deadline_is_bounded_and_cleans_up(stub: Path) -> None:
    stub_control(stub, mode="metadata_slow")
    runtime = launch(stub, startup_timeout=0.35)
    started = time.monotonic()
    with pytest.raises(RuntimeFailure, match="TIMEOUT"):
        with runtime:
            pytest.fail("unverified metadata must not become ready")
    assert time.monotonic() - started < 3
    assert runtime.process is not None and runtime.process.poll() is not None
    assert not (stub / "requests.jsonl").exists()


@pytest.mark.parametrize("shape", ["non_array", "extra_object", "nested_in_fact"])
def test_nonstandard_parseable_shapes_cannot_hide_numeric_claims(shape: str) -> None:
    payload = prediction("M4A-03")
    invented = {
        "metric": "revenue",
        "actual": "999999999",
        "period": "Q3 2026",
        "unit": "currency",
        "currency": "USD",
    }
    if shape == "non_array":
        payload["ticker_results"] = payload["ticker_results"][0]
        payload["ticker_results"]["numeric_facts"][0]["actual"] = "999999999"
    elif shape == "extra_object":
        payload["rogue"] = {"ticker": "CNDR", "numeric_facts": [invented]}
    else:
        payload["ticker_results"][0]["numeric_facts"][0]["rogue"] = {
            "ticker": "CNDR",
            "numeric_facts": [invented],
        }
    item = record("M4A-03", payload)
    assert item["result"] == {}
    report = score([item], REFERENCES, ["M4A-03"])
    assert report["numeric"]["unsupported_atoms"] >= 1
    assert report["numeric"]["required_atom_recall"]["correct"] == 0


@pytest.mark.parametrize(
    "case_id,primary,counterparty", [("M4A-21", "UMBR", "VALE"), ("M4A-22", "WREN", "XYLM")]
)
def test_counterparty_only_total_is_supported_but_does_not_fill_primary_coverage(
    case_id: str, primary: str, counterparty: str
) -> None:
    payload = prediction(case_id)
    rows = {row["ticker"]: row for row in payload["ticker_results"]}
    rows[counterparty]["numeric_facts"] = rows[primary]["numeric_facts"]
    rows[primary]["numeric_facts"] = []
    report = score([record(case_id, payload)], REFERENCES, [case_id])
    assert report["numeric"]["source_supported_precision"]["rate"] == 1
    assert report["numeric"]["unsupported_atoms"] == 0
    assert report["numeric"]["required_atom_recall"]["correct"] == 0


def test_shared_value_under_abstaining_party_is_audited_but_not_accepted() -> None:
    payload = prediction("M4A-26")
    rows = {row["ticker"]: row for row in payload["ticker_results"]}
    rows["FABL"]["numeric_facts"] = copy.deepcopy(rows["ECHS"]["numeric_facts"])
    item = record("M4A-26", payload)
    assert item["result"] == {} and not item["stages"]["contract_valid"]
    report = score([item], REFERENCES, ["M4A-26"])
    assert report["numeric"]["unsupported_atoms"] == 0
    assert report["numeric"]["required_atom_recall"]["correct"] == 0


def test_corrections_record_old_hashes_new_source_and_pending_status() -> None:
    manifest = read(DATA / "manifest.json")
    assert manifest["dataset_version"] == "m4a-v1.1"
    assert manifest["reference_version"] == "1.1.0"
    assert manifest["review_status"] == "pending_human_review" and not manifest["frozen"]
    correction = manifest["corrections"][-1]
    identities = dataset_identity()
    assert all(
        value != identities[name] for name, value in correction["previous_file_sha256"].items()
    )
    changed = correction["cases"]["M4A-08"]
    assert changed["new_content_hash"] == canonical_hash(CASES["M4A-08"]["event"])
    assert changed["new_content_hash"] != changed["old_content_hash"]

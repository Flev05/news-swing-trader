"""Bounded M4A3 development experiment. No holdout execution or artifact downloads."""

from __future__ import annotations

import argparse
import json
import os
import signal
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from .__main__ import resources
from .core import CORE_FIELDS, DATA, ROOT, SCHEMA, atom, dataset_identity, digest, read
from .remediation import HERE, VERSIONS, adapt, prompt_messages, spec
from .runtime import GENERATION, PROFILES, Runtime, RuntimeFailure, identity, request
from .scoring import ratio, score

DEVELOPMENT = ["M4A-01", "M4A-04", "M4A-11", "M4A-15", "M4A-21", "M4A-22", "M4A-27", "M4A-31"]
REPEATS = ["M4A-01", "M4A-21", "M4A-27", "M4A-31"]
HISTORICAL = ROOT / ".tools/m4a/runs/m4a2-20261029T172816Z-c0e8f774-qwen4b"
RUNTIME = ROOT / ".tools/m4a/runtime/b11981"
MODEL = ROOT / "models/weights/Qwen3-4B-Instruct-2507-Q5_K_M.gguf"
PROFILE = PROFILES["qwen3-4b-2507-q5"]
FATAL = {
    "TIMEOUT",
    "RUN_DEADLINE",
    "HTTP_ERROR",
    "RUNTIME_EXIT",
    "OOM",
    "RUNTIME_IDENTITY_MISMATCH",
    "STARTUP_TIMEOUT",
    "STARTUP_EXIT",
    "INTERRUPTED",
}


def write(path: Path, value: Any, *, exclusive: bool = False) -> None:
    data = json.dumps(value, indent=2, ensure_ascii=True) + "\n"
    target = path if exclusive else path.with_suffix(path.suffix + ".tmp")
    with target.open("x" if exclusive else "w") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    if not exclusive:
        target.replace(path)


def schedule() -> list[dict[str, Any]]:
    return [
        {"index": index, "variant": variant, "phase": phase, "case_id": cid}
        for index, (variant, phase, cid) in enumerate(
            (v, p, c)
            for v in ["A", "B"]
            for p, ids in [
                ("prime", DEVELOPMENT[:1]),
                ("development", DEVELOPMENT),
                ("repeat", REPEATS),
            ]
            for c in ids
        )
    ]


def inputs() -> tuple[dict[str, Any], dict[str, Any]]:
    manifest = read(DATA / "manifest.json")
    if manifest["development"] != DEVELOPMENT:
        raise ValueError("DEVELOPMENT_SPLIT_CHANGED")
    cases = {
        c["case_id"]: c for c in read(DATA / "inputs.json")["cases"] if c["case_id"] in DEVELOPMENT
    }
    refs = {
        cid: label
        for cid, label in read(DATA / "references.json")["cases"].items()
        if cid in DEVELOPMENT
    }
    return cases, refs


def implementation_files() -> list[Path]:
    return sorted(
        [
            *HERE.glob("*.py"),
            *HERE.glob("*.txt"),
            SCHEMA,
            DATA / "inputs.json",
            DATA / "references.json",
            DATA / "manifest.json",
            HERE.parent / "contracts.py",
            HERE.parent / "transport.py",
        ]
    )


def require_run_root(directory: Path) -> None:
    if not directory.resolve().is_relative_to((ROOT / ".tools/m4a3/runs").resolve()):
        raise ValueError("IGNORED_RUN_ROOT_REQUIRED")


def freeze(directory: Path) -> dict[str, Any]:
    directory = directory.resolve()
    require_run_root(directory)
    cases, _ = inputs()
    manifest = read(DATA / "manifest.json")
    if manifest["review_status"] != "pending_human_review" or manifest["frozen"]:
        raise ValueError("REFERENCE_STATUS_CHANGED")
    metadata = identity(RUNTIME, MODEL, PROFILE)
    # Verify existing archives; never download or substitute any runtime/model.
    for name, expected in {
        "llama.tar.gz": "5346ceedee45bda5bbd93cfd530ce6a8e1a2fd7a76369c9b2f508c5ce6cd4e4b",
        "cudart.tar.gz": "01dfe8b269a37cfda5aab7b5fe3d776c5521bbf277ec021738b991e17848a826",
    }.items():
        if digest(ROOT / ".tools/m4a/downloads" / name) != expected:
            raise ValueError("RUNTIME_ARCHIVE_MISMATCH")
    historical = read(HISTORICAL / "manifest.json")
    if historical["dataset"] != dataset_identity():
        raise ValueError("HISTORICAL_REFERENCE_MISMATCH")
    variants: dict[str, Any] = {
        v: {
            "version": VERSIONS[v],
            "schemas": {cid: spec(v, c["event"]["affected_symbols"]) for cid, c in cases.items()},
            "messages": {cid: prompt_messages(v, c["event"]) for cid, c in cases.items()},
        }
        for v in ["A", "B"]
    }
    from jsonschema import Draft202012Validator

    for item in variants.values():
        for schema in item["schemas"].values():
            Draft202012Validator.check_schema(schema)
    plan = {
        "version": "m4a3-1.0.0",
        "reference_status": manifest["review_status"],
        "reference_frozen": False,
        "experiment_freeze_is_not_reference_review": True,
        "created_at": datetime.now(UTC).isoformat(),
        "dataset": dataset_identity(),
        "files": {str(p.relative_to(ROOT)): digest(p) for p in implementation_files()},
        "runtime_files": {
            str(p.relative_to(ROOT)): digest(p) for p in sorted(RUNTIME.iterdir()) if p.is_file()
        },
        "model_sha256": PROFILE.sha256,
        "metadata": metadata,
        "variants": variants,
        "schedule": schedule(),
        "generation": GENERATION,
        "max_calls": 26,
        "aggregate_seconds": 1800,
        "cleanup_reserve_seconds": 5,
        "sample_seconds": PROFILE.sample_timeout,
        "startup_seconds": PROFILE.startup_timeout,
        "historical_records_sha256": digest(HISTORICAL / "records.json"),
        "historical_manifest_sha256": digest(HISTORICAL / "manifest.json"),
        "metrics": {
            "semantic": "unchanged scoring.score over eight development IDs",
            "raw_numeric": "all recognized native claims, normalized even if rejected",
            "repeat": "four preselected repeats, expected clear core labels and numeric signatures",
            "thresholds": {
                "contract": 1,
                "tickers": 1,
                "each_core": 0.9,
                "numeric_recall": 0.95,
                "numeric_supported": 1,
                "normalization": 0.95,
                "abstention": 1,
                "false_abstention_max": 0.1,
                "extraction": 0.9,
                "repeat": 0.95,
                "warm_p95_max": 30,
                "unsupported_max": 0,
            },
            "cold_and_context_gates": "not measured in M4A3; never claim full benchmark pass",
        },
    }
    directory.mkdir(parents=True, exist_ok=False)
    write(directory / "freeze.json", plan, exclusive=True)
    (directory / "freeze.json").chmod(0o444)
    return {"directory": str(directory), "freeze_sha256": digest(directory / "freeze.json")}


def verify(plan: dict[str, Any], *, runtime_files: bool = True) -> None:
    for section in ["files", "runtime_files"] if runtime_files else ["files"]:
        if any(digest(ROOT / path) != expected for path, expected in plan[section].items()):
            raise ValueError("EXPERIMENT_IDENTITY_CHANGED")
    if digest(HISTORICAL / "records.json") != plan["historical_records_sha256"]:
        raise ValueError("HISTORICAL_RECORDS_CHANGED")
    if digest(HISTORICAL / "manifest.json") != plan["historical_manifest_sha256"]:
        raise ValueError("HISTORICAL_MANIFEST_CHANGED")
    if (
        plan["schedule"] != schedule()
        or plan["max_calls"] != 26
        or plan["aggregate_seconds"] != 1800
    ):
        raise ValueError("INVALID_FROZEN_PLAN")


def observe(
    runtime: Runtime,
    case: dict[str, Any],
    entry: dict[str, Any],
    plan: dict[str, Any],
    ledger: list[dict[str, Any]],
    save_ledger: Any,
    provenance: dict[str, Any],
) -> dict[str, Any]:
    start = time.monotonic()
    deadline = min(runtime.run_deadline, start + PROFILE.sample_timeout)
    v, cid = entry["variant"], entry["case_id"]
    record: dict[str, Any] = {
        **entry,
        "result": {},
        "parsed_extraction": None,
        "stages": {"json_valid": False, "decoding_schema_valid": False, "contract_valid": False},
        "numeric_audit": {"status": "unavailable", "reason": "NO_OUTPUT"},
    }
    try:
        verify(plan, runtime_files=False)
        if start >= runtime.run_deadline:
            raise RuntimeFailure("RUN_DEADLINE")
        frozen = plan["variants"][v]
        record["rendered_prompt"] = request(
            runtime.port,
            "/apply-template",
            {"messages": frozen["messages"][cid]},
            deadline,
            expected_alias=runtime.alias,
        )["prompt"]
        tokens = request(
            runtime.port,
            "/tokenize",
            {"content": record["rendered_prompt"], "add_special": True, "parse_special": True},
            deadline,
            expected_alias=runtime.alias,
        )["tokens"]
        if not isinstance(tokens, list) or not all(type(t) is int for t in tokens):
            raise RuntimeFailure("TOKENIZATION_ERROR")
        record["input_tokens"], record["input_token_ids"] = len(tokens), tokens
        if len(tokens) + PROFILE.output_tokens + PROFILE.safety_tokens > PROFILE.context:
            raise RuntimeFailure("CONTEXT_LIMIT")
        # Charge before metadata/HTTP dispatch; failed or ambiguous dispatch is never replaced.
        if sum(bool(e.get("charged")) for e in ledger) >= plan["max_calls"]:
            raise RuntimeFailure("BUDGET_EXHAUSTED")
        entry.update(charged=True, state="dispatching", dispatched_at=datetime.now(UTC).isoformat())
        save_ledger()
        body = {
            "prompt": tokens,
            "n_predict": PROFILE.output_tokens,
            "json_schema": frozen["schemas"][cid],
            **plan["generation"],
        }
        record["completion_request"] = body
        response = request(
            runtime.port, "/completion", body, deadline, expected_alias=runtime.alias
        )
        record["runtime_response"] = response
        raw = response.get("content")
        record["raw_output"] = raw
        if not isinstance(raw, str):
            raise RuntimeFailure("INVALID_OUTPUT_TYPE")
        record.update(
            adapt(v, raw, case["event"], provenance, f"m4a3/{runtime.output}/{entry['index']}")
        )
        if response.get("truncated"):
            raise RuntimeFailure("TRUNCATED")
        if (
            response.get("stop_type") == "limit"
            or response.get("stopped_limit")
            or response.get("tokens_predicted", 0) >= PROFILE.output_tokens
        ):
            raise RuntimeFailure("OUTPUT_LIMIT")
        if time.monotonic() >= deadline:
            raise RuntimeFailure("TIMEOUT")
        if record.get("rejections"):
            record["failure"] = "ADAPTER_REJECTED"
    except KeyboardInterrupt:
        record["failure"] = "INTERRUPTED"
    except RuntimeFailure as error:
        failure = str(error)
        if (
            failure == "HTTP_ERROR"
            and runtime.process is not None
            and runtime.process.poll() is not None
        ):
            failure = runtime.exit_failure()
        record["failure"] = failure
    except (KeyError, ValueError, TypeError, UnicodeError, RecursionError) as error:
        record["failure"], record["error"] = "INVALID_RESPONSE_OR_IDENTITY", str(error)
    finally:
        record["elapsed_seconds"] = time.monotonic() - start
        if record.get("failure"):
            record["result"] = {}
            record["stages"]["contract_valid"] = False
        entry.update(
            state="finished",
            failure=record.get("failure"),
            elapsed_seconds=record["elapsed_seconds"],
        )
        record["charged"] = bool(entry.get("charged"))
        record["state"] = entry["state"]
        save_ledger()
    return record


def execute(directory: Path, authorized: bool) -> int:
    if not authorized:
        raise ValueError("--execute-local required")
    require_run_root(directory)
    plan = read(directory / "freeze.json")
    verify(plan)
    cases, _ = inputs()
    # Exclusive sentinel forbids restarts/resume/replacement calls, including after interruption.
    write(
        directory / "execution.json",
        {"state": "reserved", "freeze_sha256": digest(directory / "freeze.json")},
        exclusive=True,
    )
    ledger = [{**e, "charged": False, "state": "missing"} for e in plan["schedule"]]
    records: list[dict[str, Any]] = []
    metadata: dict[str, Any] = {
        "freeze_sha256": digest(directory / "freeze.json"),
        "processes": [],
        "completed": False,
    }
    samples: dict[str, Any] = {}

    def save_ledger() -> None:
        write(directory / "ledger.json", ledger)

    save_ledger()
    first_launch = time.monotonic()
    metadata.update(first_launch_utc=datetime.now(UTC).isoformat(), aggregate_seconds=1800)
    end = first_launch + plan["aggregate_seconds"] - plan["cleanup_reserve_seconds"]
    provenance = {
        "backend": "llama.cpp",
        "name": PROFILE.repository,
        "version": PROFILE.revision,
        "artifact_sha256": PROFILE.sha256,
        "configuration_sha256": digest(directory / "freeze.json"),
        "prompt_template_version": "2.0.0",
    }
    try:
        for variant in ["A", "B"]:
            if time.monotonic() >= end:
                raise RuntimeFailure("RUN_DEADLINE")
            provenance["prompt_template_version"] = plan["variants"][variant]["version"]
            runtime = Runtime(RUNTIME, MODEL, PROFILE, directory / variant, end)
            try:
                with runtime:
                    for entry in ledger:
                        if entry["variant"] != variant:
                            continue
                        record = observe(
                            runtime,
                            cases[entry["case_id"]],
                            entry,
                            plan,
                            ledger,
                            save_ledger,
                            provenance,
                        )
                        records.append(record)
                        write(directory / "records.json", records)
                        failure = record.get("failure")
                        if failure in FATAL or failure in {
                            "INVALID_RESPONSE_OR_IDENTITY",
                            "BUDGET_EXHAUSTED",
                        }:
                            raise RuntimeFailure(failure)
            finally:
                samples[variant] = runtime.samples
                metadata["processes"].append(
                    {
                        "variant": variant,
                        "arguments": getattr(runtime, "arguments", []),
                        "ready_seconds": runtime.ready_seconds,
                    }
                )
        metadata["completed"] = True
    except (RuntimeFailure, KeyboardInterrupt, OSError) as error:
        metadata["failure"] = str(error) or "INTERRUPTED"
    finally:
        metadata.update(
            wall_seconds=time.monotonic() - first_launch,
            charged_calls=sum(bool(e["charged"]) for e in ledger),
        )
        write(directory / "records.json", records)
        write(directory / "samples.json", samples)
        write(directory / "execution.json", metadata)
        save_ledger()
        verify(plan)
    return 0 if metadata["completed"] else 3


def repeat_score(records: list[dict[str, Any]], refs: dict[str, Any]) -> dict[str, Any]:
    total = correct = numeric_correct = 0
    for cid in REPEATS:
        first = next(
            (r for r in records if r["case_id"] == cid and r["phase"] == "development"), {}
        )
        repeat = next((r for r in records if r["case_id"] == cid and r["phase"] == "repeat"), {})
        maps = [
            {row["ticker"]: row for row in r.get("result", {}).get("ticker_results", [])}
            for r in [first, repeat]
        ]
        for ticker, reference in refs[cid]["tickers"].items():
            for field in CORE_FIELDS:
                if reference["fields"][field]["status"] == "clear":
                    total += 1
                    correct += (
                        bool(first.get("result"))
                        and bool(repeat.get("result"))
                        and maps[0].get(ticker, {}).get(field) == maps[1].get(ticker, {}).get(field)
                    )
        signatures = []
        for r in [first, repeat]:
            if not r.get("result"):
                continue
            signatures.append(
                sorted(
                    atom(f, row["ticker"], role)
                    for row in r["result"]["ticker_results"]
                    for f in row.get("numeric_facts", [])
                    for role in ["actual", "prior", "consensus"]
                    if role in f
                )
            )
        numeric_correct += len(signatures) == 2 and signatures[0] == signatures[1]
    repeats = [{**r, "phase": "holdout"} for r in records if r["phase"] == "repeat"]
    numeric = score(repeats, refs, REPEATS)["numeric"]
    agreement = ratio(correct, total)
    return {
        "expected_repeats": 4,
        "observed_repeats": len(repeats),
        "core_agreement": agreement,
        "numeric_consistency": ratio(numeric_correct, 4),
        "numeric_claims": numeric,
        "gate": len(repeats) == 4
        and agreement["rate"] is not None
        and agreement["rate"] >= 0.95
        and numeric["unsupported_atoms"] == 0
        and numeric["unsupported_atoms_complete"],
    }


def evaluate(directory: Path) -> dict[str, Any]:
    plan = read(directory / "freeze.json")
    if read(directory / "execution.json")["freeze_sha256"] != digest(directory / "freeze.json"):
        raise ValueError("FREEZE_IDENTITY_CHANGED")
    verify(plan)
    _, refs = inputs()
    records = read(directory / "records.json")
    samples = read(directory / "samples.json")
    result: dict[str, Any] = {
        "scope": "development only",
        "integration_eligible": False,
        "variants": {},
    }
    for variant in ["A", "B"]:
        observed = [r for r in records if r["variant"] == variant]
        attempts = [{**r, "phase": "holdout"} for r in observed if r["phase"] == "development"]
        scored = score(attempts, refs, DEVELOPMENT)
        # Independent semantic credit from accepted assembly, diagnostic precision from ALL claims.
        accepted = [{**r, "parsed_extraction": r.get("result", {})} for r in attempts]
        accepted_score = score(accepted, refs, DEVELOPMENT)
        scored["accepted_numeric"] = accepted_score["numeric"]
        scored["repeat"] = repeat_score(observed, refs)
        scored["resources"] = resources(samples.get(variant, []))
        scored["native_rejections"] = [
            {
                "case_id": r["case_id"],
                "reasons": r.get("rejections", []),
                "failure": r.get("failure"),
            }
            for r in observed
            if r.get("failure")
        ]
        scored["go_fresh_validation"] = (
            all(scored["gates"].values())
            and scored["repeat"]["gate"]
            and scored["resources"]["gate"]
            and len(attempts) == 8
            and all(r.get("result") for r in attempts)
        )
        result["variants"][variant] = scored
    baseline = [
        r
        for r in read(HISTORICAL / "records.json")
        if r["phase"] == "development" and r["case_id"] in DEVELOPMENT
    ]
    result["historical_development_baseline"] = score(
        [{**r, "phase": "holdout"} for r in baseline], refs, DEVELOPMENT
    )
    write(directory / "summary.json", result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["freeze", "run", "evaluate"])
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--execute-local", action="store_true")
    args = parser.parse_args()

    def interrupt(_signum: int, _frame: Any) -> None:
        raise KeyboardInterrupt()

    signal.signal(signal.SIGTERM, interrupt)
    if args.command == "freeze":
        print(json.dumps(freeze(args.run_dir)))
        return 0
    if args.command == "run":
        return execute(args.run_dir, args.execute_local)
    summary = evaluate(args.run_dir)
    print(json.dumps({v: x["go_fresh_validation"] for v, x in summary["variants"].items()}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

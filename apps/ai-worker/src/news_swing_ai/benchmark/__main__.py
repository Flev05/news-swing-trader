"""Explicit check/run/evaluate CLI. Importing it starts no runtime."""

from __future__ import annotations

import argparse
import json
import signal
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path
from statistics import median
from typing import Any

from .core import CORE_FIELDS, DATA, PROMPT_VERSION, ROOT, atom, check, dataset_identity, read
from .runtime import PROFILES, Runtime, RuntimeFailure, identity
from .scoring import ratio, score


def write(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")


def resources(samples: list[dict[str, Any]]) -> dict[str, Any]:
    complete = [
        sample for sample in samples if "gpu_free_mib" in sample and "available_kib" in sample
    ]
    if not complete:
        return {"n": len(samples), "gate": False, "unavailable": True}
    return {
        "n": len(samples),
        "complete_samples": len(complete),
        "interval_seconds": 0.5,
        "gpu_first_observed_used_mib": complete[0]["gpu_used_mib"],
        "gpu_peak_used_mib": max(sample["gpu_used_mib"] for sample in complete),
        "gpu_peak_minus_first_observation_mib": max(sample["gpu_used_mib"] for sample in complete)
        - complete[0]["gpu_used_mib"],
        "gpu_min_free_mib": min(sample["gpu_free_mib"] for sample in complete),
        "process_peak_rss_kib": max(sample["rss_hwm_kib"] for sample in complete),
        "wsl_min_available_kib": min(sample["available_kib"] for sample in complete),
        "swap_growth_kib": max(sample["swap_used_kib"] for sample in complete)
        - complete[0]["swap_used_kib"],
        "gate": len(complete) == len(samples)
        and min(sample["gpu_free_mib"] for sample in complete) >= 512
        and min(sample["available_kib"] for sample in complete) >= 2 * 1024 * 1024
        and max(sample["swap_used_kib"] for sample in complete) == complete[0]["swap_used_kib"],
    }


def variability(
    records: list[dict[str, Any]], references: dict[str, Any], subset: list[str]
) -> dict[str, Any]:
    correct = total = unsupported = numeric_equal = numeric_total = 0
    complete = True
    for case_id in subset:
        first = next(
            (item for item in records if item["case_id"] == case_id and item["phase"] == "holdout"),
            {},
        )
        repeats = [
            item for item in records if item["case_id"] == case_id and item["phase"] == "repeat"
        ]
        complete &= len(repeats) == 2
        for repeat in repeats:
            maps = [
                {
                    row["ticker"]: row
                    for row in item.get("result", {}).get("ticker_results", [])
                    if isinstance(row, dict) and isinstance(row.get("ticker"), str)
                }
                for item in (first, repeat)
            ]
            for ticker, reference in references[case_id]["tickers"].items():
                for field in CORE_FIELDS:
                    if reference["fields"][field]["status"] == "clear":
                        total += 1
                        correct += (
                            first.get("failure") is None
                            and repeat.get("failure") is None
                            and field in maps[0].get(ticker, {})
                            and field in maps[1].get(ticker, {})
                            and maps[0][ticker][field] == maps[1][ticker][field]
                        )
            numeric_total += 1
            signatures = []
            for item in (first, repeat):
                signature: Counter[tuple[str, ...]] = Counter()
                if item.get("failure") is None and item.get("stages", {}).get("contract_valid"):
                    for row in item["result"]["ticker_results"]:
                        for fact in row.get("numeric_facts", []):
                            for role in ("actual", "prior", "consensus"):
                                if role in fact:
                                    signature[atom(fact, row["ticker"], role)] += 1
                    signatures.append(signature)
            numeric_equal += len(signatures) == 2 and signatures[0] == signatures[1]
            diagnostic = score([{**repeat, "phase": "holdout"}], references, [case_id])
            unsupported += diagnostic["numeric"]["unsupported_atoms"]
    metric = ratio(correct, total)
    return {
        **metric,
        "cases": len(subset),
        "unsupported_repeat_atoms": unsupported,
        "numeric_consistency": ratio(numeric_equal, numeric_total),
        "complete": complete,
        "gate": complete
        and metric["rate"] is not None
        and metric["rate"] >= 0.95
        and unsupported == 0,
    }


def run(args: argparse.Namespace) -> int:
    check()
    manifest = read(DATA / "manifest.json")
    if not args.execute_local:
        raise ValueError("run requires --execute-local; M4A2 execution must be separately approved")
    if manifest["review_status"] != "reviewed" and not args.allow_provisional:
        raise ValueError(
            "References pending human review; --allow-provisional permits diagnostics only"
        )
    output = args.run_dir.resolve()
    if not output.is_relative_to((ROOT / ".tools/m4a/runs").resolve()):
        raise ValueError("Raw runs must be under ignored .tools/m4a/runs/")
    profile = PROFILES[args.profile]
    metadata = identity(args.runtime_dir.resolve(), args.model.resolve(), profile)
    output.mkdir(parents=True, exist_ok=False)
    metadata.update(
        profile=args.profile,
        review_status=manifest["review_status"],
        completed=False,
        decoding_mode="schema_constrained_first_attempt",
        measurements="process-cold; system caches not cleared",
    )
    write(output / "manifest.json", metadata)
    cases = {item["case_id"]: item for item in read(DATA / "inputs.json")["cases"]}
    provenance = {
        "backend": "llama.cpp",
        "name": profile.repository,
        "version": profile.revision,
        "artifact_sha256": profile.sha256,
        "configuration_sha256": metadata["configuration_sha256"],
        "prompt_template_version": PROMPT_VERSION,
    }
    records: list[dict[str, Any]] = []
    samples: list[dict[str, Any]] = []
    cold: list[dict[str, Any]] = []
    processes: list[dict[str, Any]] = []
    end = time.monotonic() + profile.run_timeout

    def save() -> None:
        write(output / "records.json", records)
        write(output / "samples.json", samples)
        metadata.update(cold=cold, processes=processes)
        write(output / "manifest.json", metadata)

    def observe(runtime: Runtime, case_id: str, phase: str) -> dict[str, Any]:
        record = runtime.infer(
            cases[case_id], phase, provenance, f"{output.name}/{phase}/{case_id}/{len(records)}"
        )
        record["process_elapsed_seconds"] = time.monotonic() - runtime.started
        records.append(record)
        save()
        if record.get("failure") in {
            "TIMEOUT",
            "RUN_DEADLINE",
            "HTTP_ERROR",
            "RUNTIME_EXIT",
            "OOM",
            "RUNTIME_IDENTITY_MISMATCH",
        }:
            raise RuntimeFailure(record["failure"])
        return record

    try:
        for index in range(3):
            runtime = Runtime(
                args.runtime_dir.resolve(),
                args.model.resolve(),
                profile,
                output / f"cold-{index}",
                end,
            )
            try:
                with runtime:
                    record = observe(runtime, manifest["development"][0], "cold")
                    cold.append(
                        {
                            "startup_seconds": runtime.ready_seconds,
                            "first_result_seconds": record["process_elapsed_seconds"],
                            "valid": record["stages"]["contract_valid"],
                            "cache_state": "new process-local CUDA cache; OS cache uncontrolled",
                        }
                    )
            finally:
                samples.extend(runtime.samples)
                processes.append(
                    {
                        "arguments": getattr(runtime, "arguments", []),
                        "ready_seconds": runtime.ready_seconds,
                    }
                )
        runtime = Runtime(
            args.runtime_dir.resolve(), args.model.resolve(), profile, output / "warm", end
        )
        try:
            with runtime:
                observe(runtime, manifest["development"][0], "prime")
                for phase, identifiers in (
                    ("development", manifest["development"]),
                    (
                        "holdout",
                        [
                            cid
                            for cid in manifest["holdout"]
                            if cid not in manifest["context_limit"]
                        ],
                    ),
                    ("limit", manifest["context_limit"]),
                    ("repeat", manifest["repeat_subset"] * 2),
                ):
                    for case_id in identifiers:
                        observe(runtime, case_id, phase)
        finally:
            samples.extend(runtime.samples)
            processes.append(
                {
                    "arguments": getattr(runtime, "arguments", []),
                    "ready_seconds": runtime.ready_seconds,
                }
            )
        metadata["completed"] = True
    except (RuntimeFailure, KeyboardInterrupt, OSError) as error:
        metadata["failure"] = (
            str(error) if isinstance(error, RuntimeFailure) else "INTERRUPTED_OR_PROCESS_ERROR"
        )
        return 3
    finally:
        save()
    print(f"Completed local diagnostic run: {output}")
    return 0


def evaluate(directory: Path) -> tuple[dict[str, Any], int]:
    metadata = read(directory / "manifest.json")
    if metadata["dataset"] != dataset_identity():
        raise ValueError("DATASET_OR_REFERENCE_CHANGED; do not silently rescore changed references")
    records = read(directory / "records.json")
    manifest = read(DATA / "manifest.json")
    references = read(DATA / "references.json")["cases"]
    scored = [cid for cid in manifest["holdout"] if cid not in manifest["context_limit"]]
    result = score(records, references, scored)
    result["development"] = score(
        [{**item, "phase": "holdout"} for item in records if item["phase"] == "development"],
        references,
        manifest["development"],
    )
    result["variability"] = variability(records, references, manifest["repeat_subset"])
    result["resources"] = resources(read(directory / "samples.json"))
    limits = [item for item in records if item["phase"] == "limit"]
    result["context_limit"] = {
        "n": len(limits),
        "gate": len(limits) == 2
        and {item["case_id"] for item in limits} == set(manifest["context_limit"])
        and all(
            item.get("failure") == "CONTEXT_LIMIT" and "runtime_response" not in item
            for item in limits
        ),
    }
    cold = metadata.get("cold", [])
    cold_timings = {}
    for metric in ("startup_seconds", "first_result_seconds"):
        values = [item[metric] for item in cold if isinstance(item.get(metric), (float, int))]
        cold_timings[metric] = {
            "n": len(values),
            "median_seconds": median(values) if values else None,
            "min_seconds": min(values) if values else None,
            "max_seconds": max(values) if values else None,
        }
    result["cold"] = {
        "n": len(cold),
        "samples": cold,
        "timings": cold_timings,
        "gate": len(cold) == 3
        and all(item["valid"] for item in cold)
        and all(metric["n"] == 3 for metric in cold_timings.values()),
    }

    result["gates"].update(
        resources=result["resources"]["gate"],
        variability=result["variability"]["gate"],
        context_limit=result["context_limit"]["gate"],
        cold=result["cold"]["gate"],
        completed=metadata["completed"],
    )
    result["review_status"] = manifest["review_status"]
    result["diagnostic_gates_pass"] = all(result["gates"].values())
    result["eligible_for_integration_review"] = (
        result["diagnostic_gates_pass"] and manifest["review_status"] == "reviewed"
    )
    result["profile"] = metadata["profile"]
    write(directory / "summary.json", result)
    lines = [
        "# M4A diagnostic summary",
        "",
        f"Profile: {metadata['profile']}. References: {manifest['review_status']}.",
        "Complete deployment profile comparison only; no causal parameter-count conclusion.",
        f"Holdout n={result['n']}; observed={result['observed']}; "
        f"failures={len(result['failures'])}.",
        f"Eligible for integration review: {result['eligible_for_integration_review']}.",
        "",
        "| Metric | Correct | Denominator | Rate |",
        "| --- | --- | --- | --- |",
    ]
    for name, metric in {**result["validity"], **result["fields"], **result["numeric"]}.items():
        if isinstance(metric, dict) and "rate" in metric:
            lines.append(
                f"| {name} | {metric['correct']} | {metric['total']} | "
                f"{'N/A' if metric['rate'] is None else format(metric['rate'], '.3f')} |"
            )
    lines += [
        "",
        "Ambiguous/acceptable-set counts, numeric extras, timing samples and gates: summary.json.",
        "No repairs or inference retries were performed.",
    ]
    lines += [
        "",
        f"Numeric audit availability: {result['numeric_audit']['counts']}.",
        f"Unsupported-atom count complete: {result['numeric']['unsupported_atoms_complete']}.",
        "Diagnostics never confer semantic credit on invalid results.",
        "",
        "| Case | Numeric audit | Reason | Observed unsupported atoms |",
        "| --- | --- | --- | --- |",
    ]
    for audit in result["numeric_audit"]["cases"]:
        status = audit["status"]
        observed = (
            audit["unsupported_atoms"]
            if status == "available"
            else (f">={audit['unsupported_atoms']} (partial)" if status == "partial" else "N/A")
        )
        lines.append(f"| {audit['case_id']} | {status} | {audit['reason']} | {observed} |")
    (directory / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return result, 0 if result["eligible_for_integration_review"] else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Standalone M4A benchmark; no operational worker changes"
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check")
    runner = commands.add_parser("run")
    runner.add_argument("--runtime-dir", type=Path, required=True)
    runner.add_argument("--model", type=Path, required=True)
    runner.add_argument("--profile", choices=PROFILES, required=True)
    runner.add_argument("--run-dir", type=Path, required=True)
    runner.add_argument("--execute-local", action="store_true")
    runner.add_argument("--allow-provisional", action="store_true")
    evaluator = commands.add_parser("evaluate")
    evaluator.add_argument("--run-dir", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "check":
            print(json.dumps(check(), indent=2))
            return 0
        if args.command == "run":

            def interrupt(_signum: int, _frame: Any) -> None:
                raise KeyboardInterrupt()

            previous = signal.signal(signal.SIGTERM, interrupt)
            try:
                return run(args)
            finally:
                signal.signal(signal.SIGTERM, previous)
        result, code = evaluate(args.run_dir)
        print(
            json.dumps(
                {
                    "eligible_for_integration_review": result["eligible_for_integration_review"],
                    "review_status": result["review_status"],
                }
            )
        )
        return code
    except (
        ValueError,
        AssertionError,
        OSError,
        KeyError,
        TypeError,
        subprocess.SubprocessError,
    ) as error:
        print(f"benchmark configuration/data error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

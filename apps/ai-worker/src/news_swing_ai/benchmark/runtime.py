"""Supervised, local-only runtime interaction. No download/install capability."""

from __future__ import annotations

import hashlib
import http.client
import json
import os
import platform
import signal
import socket
import subprocess
import threading
import time
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from uuid import uuid4

from news_swing_ai.transport import ProtocolError, decode

from .core import (
    PROMPT,
    PROMPT_VERSION,
    SCHEMA,
    assemble,
    dataset_identity,
    diagnostic_extraction,
    digest,
    extraction_spec,
    messages,
)

RUNTIME_VERSION = "b11981"
RUNTIME_COMMIT = "da6c30ade8c30930dbf49fe57f9f02d727e99b15"


@dataclass(frozen=True)
class Profile:
    name: str
    repository: str
    revision: str
    filename: str
    sha256: str
    size: int
    gpu_layers: str
    context: int = 8192
    output_tokens: int = 2048
    safety_tokens: int = 256
    sample_timeout: float = 60
    startup_timeout: float = 120
    run_timeout: float = 2700


PROFILES = {
    "qwen3-4b-2507-q5": Profile(
        "qwen3-4b-2507-q5",
        "unsloth/Qwen3-4B-Instruct-2507-GGUF",
        "a06e946bb6b655725eafa393f4a9745d460374c9",
        "Qwen3-4B-Instruct-2507-Q5_K_M.gguf",
        "5bde5e9d883622acb02bf77fe7dcbc56a8b9a9ad4be78a72ca23a532658b4ecb",
        2889514080,
        "all",
    ),
    "qwen3-8b-q4": Profile(
        "qwen3-8b-q4",
        "Qwen/Qwen3-8B-GGUF",
        "7c41481f57cb95916b40956ab2f0b139b296d974",
        "Qwen3-8B-Q4_K_M.gguf",
        "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785",
        5027783488,
        "28",
    ),
}
GENERATION = {
    "temperature": 0,
    "seed": 42,
    "repeat_penalty": 1,
    "samplers": ["temperature"],
    "cache_prompt": False,
    "stream": False,
}


class RuntimeFailure(Exception):
    pass


def alias_matches(document: dict[str, Any], expected: str) -> bool:
    models = document.get("data")
    return (
        document.get("object") == "list"
        and isinstance(models, list)
        and len(models) == 1
        and isinstance(models[0], dict)
        and models[0].get("id") == expected
    )


def request(
    port: int,
    route: str,
    body: dict[str, Any] | None,
    deadline: float,
    *,
    expected_alias: str | None = None,
) -> dict[str, Any]:
    """Direct loopback HTTP, bounded bytes and absolute socket cancellation."""
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise RuntimeFailure("TIMEOUT")
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=min(2, remaining))
    expired = threading.Event()
    guard = threading.Lock()
    active: socket.socket | None = None

    def interrupt() -> None:
        nonlocal active
        with guard:
            expired.set()
            if active is not None:
                try:
                    active.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                active.close()

    timer = threading.Timer(remaining, interrupt)
    timer.start()
    try:
        connection.connect()
        with guard:
            active = connection.sock
            if expired.is_set():
                raise RuntimeFailure("TIMEOUT")
            assert active is not None
            active.settimeout(remaining)

        def receive() -> dict[str, Any]:
            response = connection.getresponse()
            raw = response.read(1048577)
            if expired.is_set():
                raise RuntimeFailure("TIMEOUT")
            if (
                response.status != 200
                or len(raw) > 1048576
                or response.length not in (None, 0)
                or response.getheader("Content-Encoding", "identity") != "identity"
            ):
                raise RuntimeFailure("HTTP_ERROR")
            return decode(raw)

        if expected_alias is not None:
            # Do not reveal the expected nonce to the peer. Verify the same socket that
            # will receive source data, and forbid HTTPConnection's automatic reconnect.
            connection.request("GET", "/v1/models")
            metadata = receive()
            if not alias_matches(metadata, expected_alias) or connection.sock is not active:
                raise RuntimeFailure("RUNTIME_IDENTITY_MISMATCH")
        connection.request(
            "GET" if body is None else "POST",
            route,
            None if body is None else json.dumps(body),
            {"Content-Type": "application/json"},
        )
        return receive()

    except (OSError, http.client.HTTPException, ValueError, ProtocolError) as error:
        raise RuntimeFailure("TIMEOUT" if expired.is_set() else "HTTP_ERROR") from error
    finally:
        timer.cancel()
        timer.join()
        connection.close()


class Monitor:
    """Sampled device-wide VRAM; /proc process high-water and system memory."""

    def __init__(self, pid: int):
        self.pid = pid
        self.stop = threading.Event()
        self.samples: list[dict[str, Any]] = []
        self.thread = threading.Thread(target=self.loop, daemon=True)
        self.thread.start()

    def loop(self) -> None:
        while not self.stop.is_set():
            item: dict[str, Any] = {"monotonic": time.monotonic()}
            try:
                status = Path(f"/proc/{self.pid}/status").read_text()
                memory = Path("/proc/meminfo").read_text()

                def kb(text: str, key: str) -> int:
                    return int(
                        next(
                            line.split()[1]
                            for line in text.splitlines()
                            if line.startswith(key + ":")
                        )
                    )

                item.update(
                    rss_hwm_kib=kb(status, "VmHWM"),
                    available_kib=kb(memory, "MemAvailable"),
                    swap_used_kib=kb(memory, "SwapTotal") - kb(memory, "SwapFree"),
                )
                smi = subprocess.run(
                    [
                        "/usr/lib/wsl/lib/nvidia-smi",
                        "--query-gpu=memory.used,memory.free",
                        "--format=csv,noheader,nounits",
                    ],
                    capture_output=True,
                    text=True,
                    timeout=2,
                    check=True,
                )
                used, free = (int(value.strip()) for value in smi.stdout.strip().split(","))
                item.update(gpu_used_mib=used, gpu_free_mib=free)
            except (OSError, ValueError, StopIteration, subprocess.SubprocessError):
                item["measurement_unavailable"] = True
            self.samples.append(item)
            self.stop.wait(0.5)

    def close(self) -> list[dict[str, Any]]:
        self.stop.set()
        self.thread.join(3)
        return self.samples


class Runtime:
    def __init__(
        self,
        directory: Path,
        model: Path,
        profile: Profile,
        output: Path,
        run_deadline: float,
        *,
        monitor: bool = True,
    ):
        self.directory, self.model, self.profile, self.output = directory, model, profile, output
        self.run_deadline = run_deadline
        self.monitor_enabled = monitor
        self.process: subprocess.Popen[bytes] | None = None
        self.monitor: Monitor | None = None
        self.samples: list[dict[str, Any]] = []
        self.log: Any = None
        self.port = 0
        self.started = 0.0
        self.ready_seconds = 0.0
        self.alias = "m4a-" + uuid4().hex

    def exit_failure(self, startup: bool = False) -> str:
        log_path = self.output / "server.log"
        text = log_path.read_text(errors="replace").lower() if log_path.exists() else ""
        if any(marker in text for marker in ("out of memory", "cudamalloc failed")):
            return "OOM"
        return "STARTUP_EXIT" if startup else "RUNTIME_EXIT"

    @staticmethod
    def choose_port() -> int:
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            return int(reservation.getsockname()[1])

    def __enter__(self) -> Runtime:
        self.port = self.choose_port()
        self.output.mkdir(parents=True, exist_ok=True)
        # Do not inherit runtime configuration, remote hub tokens, proxies or loader settings.
        env = {
            key: value
            for key, value in os.environ.items()
            if key in {"PATH", "HOME", "LANG", "LC_ALL", "SYSTEMROOT"}
        }
        env["CUDA_CACHE_PATH"] = str(self.output / "cuda-cache")
        self.log = (self.output / "server.log").open("wb")
        args = [
            str(self.directory / "llama-server"),
            "--alias",
            self.alias,
            "--model",
            str(self.model),
            "--host",
            "127.0.0.1",
            "--port",
            str(self.port),
            "--ctx-size",
            str(self.profile.context),
            "--n-predict",
            str(self.profile.output_tokens),
            "--gpu-layers",
            self.profile.gpu_layers,
            "--fit",
            "off",
            "--parallel",
            "1",
            "--threads",
            "6",
            "--batch-size",
            "128",
            "--ubatch-size",
            "128",
            "--cache-type-k",
            "f16",
            "--cache-type-v",
            "f16",
            "--flash-attn",
            "off",
            "--no-context-shift",
            "--no-cache-prompt",
            "--cache-ram",
            "0",
            "--no-warmup",
            "--no-webui",
            "--no-agent",
            "--chat-template-kwargs",
            '{"enable_thinking":false}',
        ]
        self.started = time.monotonic()
        self.arguments = args
        try:
            self.process = subprocess.Popen(
                args, stdout=self.log, stderr=self.log, env=env, start_new_session=True
            )
            if self.monitor_enabled:
                self.monitor = Monitor(self.process.pid)
            deadline = min(self.run_deadline, self.started + self.profile.startup_timeout)
            while time.monotonic() < deadline:
                if self.process.poll() is not None:
                    raise RuntimeFailure(self.exit_failure(startup=True))
                try:
                    healthy = (
                        request(
                            self.port, "/health", None, min(deadline, time.monotonic() + 0.5)
                        ).get("status")
                        == "ok"
                    )
                except RuntimeFailure:
                    healthy = False
                if healthy:
                    metadata = request(
                        self.port, "/v1/models", None, min(deadline, time.monotonic() + 2)
                    )
                    if not alias_matches(metadata, self.alias):
                        raise RuntimeFailure("RUNTIME_IDENTITY_MISMATCH")
                    if self.process.poll() is not None:
                        raise RuntimeFailure(self.exit_failure(startup=True))
                    self.ready_seconds = time.monotonic() - self.started
                    return self
                time.sleep(min(0.05, max(0.0, deadline - time.monotonic())))
            raise RuntimeFailure("STARTUP_TIMEOUT")
        except BaseException:
            self.close()
            raise

    def close(self) -> None:
        if self.process is not None:
            # Own session only, including children after an early parent exit.
            try:
                os.killpg(self.process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                pass
            try:
                os.killpg(self.process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            self.process.wait(timeout=3)
        if self.monitor is not None:
            self.samples = self.monitor.close()
        if self.log is not None:
            self.log.close()

    def __exit__(self, *_: Any) -> None:
        self.close()

    def infer(
        self, case: dict[str, Any], phase: str, provenance: dict[str, Any], trial: str
    ) -> dict[str, Any]:
        started = time.monotonic()
        deadline = min(self.run_deadline, started + self.profile.sample_timeout)
        record: dict[str, Any] = {
            "case_id": case["case_id"],
            "phase": phase,
            "trial": trial,
            "stages": {
                "json_valid": False,
                "decoding_schema_valid": False,
                "contract_valid": False,
            },
            "result": {},
            "parsed_extraction": None,
            "numeric_audit": {
                "status": "unavailable",
                "reason": "NO_OUTPUT",
                "scope": "ticker_results[*].numeric_facts",
            },
        }
        try:
            if started >= self.run_deadline:
                raise RuntimeFailure("RUN_DEADLINE")
            rendered = request(
                self.port,
                "/apply-template",
                {"messages": messages(case["event"])},
                deadline,
                expected_alias=self.alias,
            )["prompt"]
            tokens = request(
                self.port,
                "/tokenize",
                {"content": rendered, "add_special": True, "parse_special": True},
                deadline,
                expected_alias=self.alias,
            )["tokens"]
            if not isinstance(tokens, list) or not all(type(token) is int for token in tokens):
                raise RuntimeFailure("TOKENIZATION_ERROR")
            record["input_tokens"] = len(tokens)
            if (
                len(tokens) + self.profile.output_tokens + self.profile.safety_tokens
                > self.profile.context
            ):
                raise RuntimeFailure("CONTEXT_LIMIT")
            response = request(
                self.port,
                "/completion",
                {
                    "prompt": tokens,
                    "n_predict": self.profile.output_tokens,
                    "json_schema": extraction_spec(),
                    **GENERATION,
                },
                deadline,
                expected_alias=self.alias,
            )
            record["runtime_response"] = response
            raw = response["content"]
            record["raw_output"] = raw
            if not isinstance(raw, str):
                record["numeric_audit"]["reason"] = "NON_STRING_OUTPUT"
                raise RuntimeFailure("INVALID_OUTPUT_TYPE")
            parsed, audit = diagnostic_extraction(raw.encode("utf-8"))
            record["parsed_extraction"], record["numeric_audit"] = parsed, audit
            record["stages"]["json_valid"] = parsed is not None
            if response.get("truncated"):
                raise RuntimeFailure("TRUNCATED")
            if (
                response.get("stop_type") == "limit"
                or response.get("stopped_limit")
                or response.get("tokens_predicted", 0) >= self.profile.output_tokens
            ):
                raise RuntimeFailure("OUTPUT_LIMIT")
            if parsed is None:
                raise RuntimeFailure("INVALID_JSON")
            record["result"], record["stages"] = assemble(
                raw.encode("utf-8"),
                case["event"],
                provenance,
                trial,
                datetime.now(UTC).isoformat().replace("+00:00", "Z"),
            )
            if not record["stages"]["contract_valid"]:
                raise RuntimeFailure("CONTRACT_INVALID")
            if time.monotonic() >= deadline:
                raise RuntimeFailure("TIMEOUT")
        except RuntimeFailure as error:
            failure = str(error)
            if (
                failure == "HTTP_ERROR"
                and self.process is not None
                and self.process.poll() is not None
            ):
                failure = self.exit_failure()
            record["failure"] = failure
        except ProtocolError:
            record["failure"] = "INVALID_JSON"
        except (KeyError, TypeError, ValueError, UnicodeError, AttributeError, RecursionError):
            record["failure"] = "INVALID_RESPONSE"
        if "failure" in record:
            record["result"] = {}
        record["elapsed_seconds"] = time.monotonic() - started
        return record


def identity(directory: Path, model: Path, profile: Profile) -> dict[str, Any]:
    if model.stat().st_size != profile.size or digest(model) != profile.sha256:
        raise ValueError("MODEL_ARTIFACT_MISMATCH")
    binary = directory / "llama-server"
    version = subprocess.run(
        [str(binary), "--version"], capture_output=True, text=True, timeout=5, check=True
    )
    text = version.stdout + version.stderr
    if "11981" not in text or "da6c30a" not in text:
        raise ValueError("RUNTIME_VERSION_MISMATCH")
    devices = subprocess.run(
        [str(binary), "--list-devices"], capture_output=True, text=True, timeout=5, check=True
    )
    if "RTX 2070" not in devices.stdout + devices.stderr:
        raise ValueError("CUDA_DEVICE_UNAVAILABLE")
    config = {
        "profile": asdict(profile),
        "generation": GENERATION,
        "runtime": RUNTIME_COMMIT,
        "prompt_sha256": digest(PROMPT),
        "prompt_template_version": PROMPT_VERSION,
        "schema_sha256": digest(SCHEMA),
        "implementation_sha256": {
            name: digest(Path(__file__).with_name(name))
            for name in ("core.py", "runtime.py", "scoring.py", "__main__.py")
        },
        "runtime_settings": {
            "threads": 6,
            "batch": 128,
            "ubatch": 128,
            "kv": "f16",
            "flash_attn": "off",
            "fit": "off",
            "parallel": 1,
            "context_shift": False,
            "warmup": False,
            "prompt_cache": False,
        },
    }
    return {
        "configuration": config,
        "configuration_sha256": hashlib.sha256(
            json.dumps(config, sort_keys=True).encode()
        ).hexdigest(),
        "binary_sha256": digest(binary),
        "runtime_version_output": text,
        "device_output": devices.stdout + devices.stderr,
        "dataset": dataset_identity(),
        "hardware": {
            "platform": platform.platform(),
            "cpu": Path("/proc/cpuinfo").read_text(),
            "memory": Path("/proc/meminfo").read_text(),
            "gpu_driver": subprocess.run(
                ["/usr/lib/wsl/lib/nvidia-smi"],
                capture_output=True,
                text=True,
                timeout=5,
                check=True,
            ).stdout,
        },
        "created_at": datetime.now(UTC).isoformat(),
    }

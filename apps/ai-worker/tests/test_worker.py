from __future__ import annotations

import json
import socket
import threading
import time
from datetime import UTC, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator, FormatChecker

from news_swing_ai.fake_result import generate
from news_swing_ai.transport import DeliveryError, ProtocolError, Transport, decode
from news_swing_ai.worker import Worker

NOW = datetime(2026, 10, 29, tzinfo=UTC)
ID = "11111111-1111-1111-1111-111111111111"


def assignment() -> dict[str, Any]:
    return {
        "ref": {"job_id": ID, "assignment_number": 1, "ownership_token": ID},
        "inference_id": ID,
        "expected_backend": "fake",
        "result_schema_version": "1.0.0",
        "lease_expires_at": (NOW + timedelta(seconds=60)).isoformat().replace("+00:00", "Z"),
        "news_event_json": json.dumps(
            {
                "event_id": ID,
                "content_hash": "a" * 64,
                "affected_symbols": ["BETA", "ACME"],
            }
        ),
    }


def test_generation_uses_assignment_and_explicit_abstention() -> None:
    value = assignment()
    raw = generate(value, lambda: NOW)
    assert raw == generate(value, lambda: NOW)
    result = json.loads(raw)
    schema = Path(__file__).resolve().parents[3] / "contracts/v1/inference-result.schema.json"
    Draft202012Validator(json.loads(schema.read_text()), format_checker=FormatChecker()).validate(
        result
    )
    assert result["inference_id"] == ID
    assert result["news_event_id"] == ID
    assert result["news_event_sha256"] == "a" * 64
    assert [r["ticker"] for r in result["ticker_results"]] == ["BETA", "ACME"]
    assert all(r["abstain"] and r["numeric_facts"] == [] for r in result["ticker_results"])


@pytest.mark.parametrize(
    "url",
    [
        "https://127.0.0.1:80",
        "http://localhost:80",
        "http://127.0.0.2:80",
        "http://127.0.0.1",
        "http://127.0.0.1:80/x",
        "http://127.0.0.1:80?x",
        "http://user@127.0.0.1:80",
        "http://127.0.0.1:80#x",
        "http://127.0.0.1:0",
        "http://127.0.0.1:080",
    ],
)
def test_remote_and_non_origin_configuration_rejected(url: str) -> None:
    with pytest.raises(ProtocolError):
        Transport(url)


@pytest.mark.parametrize("raw", [b'{"x":1,"x":2}', b"[]", b"{} {}", b'{"x":"\xff"}'])
def test_strict_json(raw: bytes) -> None:
    with pytest.raises(ProtocolError):
        decode(raw)


class ScriptedTransport(Transport):
    def __init__(self, script: list[Any]):
        super().__init__("http://127.0.0.1:1")
        self.script = script
        self.calls: list[tuple[str, bytes]] = []

    def request(
        self, operation: str, body: bytes, deadline: float | None = None
    ) -> tuple[int, dict[str, Any]]:
        self.calls.append((operation, body))
        reply = self.script.pop(0)
        if isinstance(reply, Exception):
            raise reply
        return reply  # type: ignore[no-any-return]


def claimed() -> tuple[int, dict[str, Any]]:
    return 200, {"outcome": "CLAIMED", "assignment": assignment()}


def renewed() -> tuple[int, dict[str, Any]]:
    return 200, {"outcome": "RENEWED", "lease_expires_at": assignment()["lease_expires_at"]}


def worker(script: list[Any]) -> tuple[Worker, ScriptedTransport]:
    transport = ScriptedTransport(script)
    instance = Worker(transport, True, now=lambda: NOW)
    instance.wait = lambda *args, **kwargs: None  # type: ignore[method-assign]
    return instance, transport


def test_completion_replays_identical_bytes_without_renew_or_failure() -> None:
    instance, transport = worker(
        [
            claimed(),
            renewed(),
            renewed(),
            DeliveryError(True),
            (503, {}),
            (
                200,
                {
                    "outcome": "ALREADY_ACCEPTED",
                    "receipt": {
                        "job_id": ID,
                        "news_event_id": ID,
                        "inference_id": ID,
                        "accepted_at": "2026-10-29T00:00:00Z",
                    },
                },
            ),
        ]
    )
    assert instance.run() == 0
    assert [op for op, _ in transport.calls] == ["claim", "renew", "renew"] + ["complete"] * 3
    assert transport.calls[-1][1] == transport.calls[-2][1] == transport.calls[-3][1]


@pytest.mark.parametrize(
    "reply",
    [
        DeliveryError(True),
        ProtocolError(),
        (503, {}),
        (200, {"outcome": "WRONG"}),
    ],
)
def test_ambiguous_claim_stops_without_reclaim(reply: Any) -> None:
    instance, transport = worker([reply])
    assert instance.run() == 3
    assert len(transport.calls) == 1


def test_presend_claim_retry_has_once_budget() -> None:
    instance, transport = worker([DeliveryError(False)] * 3)
    assert instance.run() == 3
    assert len(transport.calls) == 3


@pytest.mark.parametrize(
    "code",
    [
        "NOT_RUNNING",
        "OWNER_MISMATCH",
        "REVISION_OBSOLETE",
        "NEWS_EXPIRED",
        "LEASE_EXPIRED",
    ],
)
def test_authority_rejection_stops_assignment(code: str) -> None:
    instance, transport = worker([claimed(), (409, {"code": code})])
    assert instance.run() == 4
    assert [op for op, _ in transport.calls] == ["claim", "renew"]


@pytest.mark.parametrize(
    "reply",
    [
        (409, {"code": "COMPLETION_CONFLICT"}),
        (422, {"code": "INVALID_RESULT"}),
        (422, {"code": "PROVENANCE_MISMATCH"}),
    ],
)
def test_completion_defect_never_reports_failure(reply: Any) -> None:
    instance, transport = worker([claimed(), renewed(), renewed(), reply])
    assert instance.run() == 2
    assert [op for op, _ in transport.calls] == ["claim", "renew", "renew", "complete"]


def test_uncertain_renewal_exhaustion_never_processes() -> None:
    instance, transport = worker([claimed()] + [(503, {})] * 3)
    instance.processor = lambda *_: pytest.fail("processing without confirmed authority")
    assert instance.run() == 3
    assert [op for op, _ in transport.calls] == ["claim", "renew", "renew", "renew"]


def test_shutdown_during_processing_cancels_before_release() -> None:
    instance, transport = worker([claimed(), renewed(), (200, {"outcome": "RETRY_WAITING"})])
    cancelled = threading.Event()

    def processor(value: dict[str, Any], cancel: threading.Event, now: Any) -> str:
        instance.shutdown()
        assert cancel.wait(2)
        cancelled.set()
        raise InterruptedError()

    instance.processor = processor
    try:
        assert instance.run() == 0
        assert cancelled.is_set()
        assert [op for op, _ in transport.calls] == ["claim", "renew", "fail"]
    finally:
        transport.close()


@pytest.mark.parametrize("headers", [False, True])
def test_absolute_deadline_interrupts_blocked_headers_and_body(headers: bool) -> None:
    ready = threading.Event()
    release = threading.Event()

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            self.rfile.read(int(self.headers["Content-Length"]))
            if headers:
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", "100")
                self.end_headers()
                self.wfile.flush()
            ready.set()
            release.wait(3)

        def log_message(self, *args: Any) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01})
    thread.start()
    transport = Transport(f"http://127.0.0.1:{server.server_port}")
    try:
        started = time.monotonic()
        with pytest.raises(DeliveryError) as error:
            transport.request("claim", b"{}", started + 0.25)
        assert ready.is_set()
        assert error.value.sent
        assert time.monotonic() - started < 1.5
    finally:
        release.set()
        server.shutdown()
        server.server_close()
        thread.join(2)


def test_connection_failure_is_provably_presend() -> None:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    with pytest.raises(DeliveryError) as error:
        Transport(f"http://127.0.0.1:{port}").request("claim", b"{}")
    assert not error.value.sent


def test_authority_loss_cancels_and_joins_active_processing() -> None:
    instance, transport = worker([claimed(), renewed(), (409, {"code": "OWNER_MISMATCH"})])
    started = threading.Event()
    finished = threading.Event()

    def processor(value: dict[str, Any], cancel: threading.Event, now: Any) -> str:
        started.set()
        assert cancel.wait(2)
        finished.set()
        raise InterruptedError()

    instance.processor = processor
    instance.wake.wait = lambda timeout=None: started.wait(2)  # type: ignore[method-assign]
    assert instance.run() == 4
    assert finished.is_set()
    assert [op for op, _ in transport.calls] == ["claim", "renew", "renew"]


def test_long_processing_renews_before_final_renewal() -> None:
    receipt = {
        "job_id": ID,
        "news_event_id": ID,
        "inference_id": ID,
        "accepted_at": "2026-10-29T00:00:00Z",
    }
    instance, transport = worker(
        [
            claimed(),
            renewed(),
            renewed(),
            renewed(),
            (200, {"outcome": "ACCEPTED", "receipt": receipt}),
        ]
    )
    entered = threading.Event()
    release = threading.Event()
    finished = threading.Event()

    def processor(value: dict[str, Any], cancel: threading.Event, now: Any) -> str:
        entered.set()
        assert release.wait(2)
        result = generate(value, now)
        finished.set()
        return result

    waits = 0

    def wake(timeout: float | None = None) -> bool:
        nonlocal waits
        waits += 1
        assert entered.wait(2)
        if waits == 2:
            release.set()
            assert finished.wait(2)
        return False

    instance.processor = processor
    instance.wake.wait = wake  # type: ignore[method-assign]
    assert instance.run() == 0
    assert [op for op, _ in transport.calls] == ["claim", "renew", "renew", "renew", "complete"]


def test_uncertain_completion_exhaustion_never_releases_or_reclaims() -> None:
    instance, transport = worker([claimed(), renewed(), renewed()] + [(503, {})] * 3)
    instance.once = False
    assert instance.run() == 3
    assert [op for op, _ in transport.calls] == ["claim", "renew", "renew"] + ["complete"] * 3


def test_idle_shutdown_does_not_claim() -> None:
    instance, transport = worker([])
    try:
        instance.shutdown()
        assert instance.run() == 0
        assert transport.calls == []
    finally:
        transport.close()


def test_failure_retries_identical_command() -> None:
    instance, transport = worker(
        [
            claimed(),
            renewed(),
            DeliveryError(True),
            (503, {}),
            (200, {"outcome": "RETRY_WAITING"}),
        ]
    )

    def processor(value: dict[str, Any], cancel: threading.Event, now: Any) -> str:
        instance.shutdown()
        assert cancel.wait(2)
        raise InterruptedError()

    instance.processor = processor
    try:
        assert instance.run() == 0
        assert [op for op, _ in transport.calls] == ["claim", "renew"] + ["fail"] * 3
        assert transport.calls[-1][1] == transport.calls[-2][1] == transport.calls[-3][1]
    finally:
        transport.close()


def test_late_deadline_callback_cannot_interrupt_next_connection(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    callbacks: list[Any] = []
    arrived = threading.Event()
    release = threading.Event()
    requests = 0

    class Timer:
        def __init__(self, interval: float, callback: Any):
            callbacks.append(callback)

        def start(self) -> None:
            pass

        def cancel(self) -> None:
            pass

        def join(self) -> None:
            pass

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            nonlocal requests
            self.rfile.read(int(self.headers["Content-Length"]))
            requests += 1
            if requests == 2:
                arrived.set()
                assert release.wait(2)
            raw = b'{"outcome":"NO_WORK"}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, *args: Any) -> None:
            pass

    monkeypatch.setattr("news_swing_ai.transport.threading.Timer", Timer)
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    serving = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01})
    serving.start()
    transport = Transport(f"http://127.0.0.1:{server.server_port}")
    replies: list[Any] = []
    second = threading.Thread(target=lambda: replies.append(transport.request("claim", b"{}")))
    try:
        assert transport.request("claim", b"{}")[0] == 200
        second.start()
        assert arrived.wait(2)
        callbacks[0]()  # Deliberately invoke a cancelled timer while request two is blocked.
        release.set()
        second.join(2)
        assert not second.is_alive()
        assert replies == [(200, {"outcome": "NO_WORK"})]
    finally:
        release.set()
        second.join(2)
        server.shutdown()
        server.server_close()
        serving.join(2)


@pytest.mark.parametrize("kind", ["redirect", "oversized", "truncated", "encoding"])
def test_transport_rejects_unsafe_responses_and_ignores_proxy(
    kind: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("http_proxy", "http://127.0.0.1:1")
    monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:1")
    monkeypatch.setenv("ALL_PROXY", "http://127.0.0.1:1")
    monkeypatch.setenv("NO_PROXY", "")

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            self.rfile.read(int(self.headers["Content-Length"]))
            self.send_response(302 if kind == "redirect" else 200)
            self.send_header("Content-Type", "application/json")
            raw = b"{}"
            if kind == "redirect":
                self.send_header("Location", "http://example.test/")
            elif kind == "oversized":
                raw = b" " * 1048577
            elif kind == "encoding":
                self.send_header("Content-Encoding", "gzip")
            self.send_header("Content-Length", str(len(raw) + (10 if kind == "truncated" else 0)))
            self.end_headers()
            try:
                self.wfile.write(raw)
            except (ConnectionError, OSError):
                pass

        def log_message(self, *args: Any) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01})
    thread.start()
    try:
        with pytest.raises((ProtocolError, DeliveryError)):
            Transport(f"http://127.0.0.1:{server.server_port}").request("claim", b"{}")
    finally:
        server.shutdown()
        server.server_close()
        thread.join(2)


def test_stop_during_completion_only_replays_frozen_completion() -> None:
    instance, transport = worker([claimed(), renewed(), renewed()])
    original = transport.request
    completions = 0
    receipt = {
        "job_id": ID,
        "news_event_id": ID,
        "inference_id": ID,
        "accepted_at": "2026-10-29T00:00:00Z",
    }

    def request(
        operation: str, body: bytes, deadline: float | None = None
    ) -> tuple[int, dict[str, Any]]:
        nonlocal completions
        if operation != "complete":
            return original(operation, body, deadline)
        transport.calls.append((operation, body))
        completions += 1
        if completions == 1:
            instance.shutdown()
            raise DeliveryError(True)
        return 200, {"outcome": "ALREADY_ACCEPTED", "receipt": receipt}

    transport.request = request  # type: ignore[method-assign]
    try:
        assert instance.run() == 0
        assert [op for op, _ in transport.calls] == [
            "claim",
            "renew",
            "renew",
            "complete",
            "complete",
        ]
        assert transport.calls[-1][1] == transport.calls[-2][1]
    finally:
        transport.close()


def test_shutdown_grace_interrupts_blocked_io() -> None:
    arrived = threading.Event()
    release = threading.Event()

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            self.rfile.read(int(self.headers["Content-Length"]))
            arrived.set()
            release.wait(3)

        def log_message(self, *args: Any) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    serving = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01})
    serving.start()
    transport = Transport(f"http://127.0.0.1:{server.server_port}")
    errors: list[DeliveryError] = []

    def blocked() -> None:
        try:
            transport.request("claim", b"{}")
        except DeliveryError as error:
            errors.append(error)

    requesting = threading.Thread(target=blocked)
    try:
        requesting.start()
        assert arrived.wait(2)
        transport.limit_shutdown(0)
        requesting.join(2)
        assert not requesting.is_alive()
        assert len(errors) == 1 and errors[0].sent
        with pytest.raises(DeliveryError):
            transport.request("claim", b"{}")
    finally:
        release.set()
        transport.close()
        requesting.join(2)
        server.shutdown()
        server.server_close()
        serving.join(2)


@pytest.mark.parametrize(
    "document",
    [
        {"outcome": "ACCEPTED"},
        {"outcome": "UNKNOWN"},
        {"outcome": "ACCEPTED", "receipt": {}},
    ],
)
def test_malformed_completion_acknowledgement_reconciles_without_failure(
    document: dict[str, Any],
) -> None:
    instance, transport = worker([claimed(), renewed(), renewed()] + [(200, document)] * 3)
    assert instance.run() == 3
    assert [op for op, _ in transport.calls] == ["claim", "renew", "renew"] + ["complete"] * 3
    assert transport.calls[-1][1] == transport.calls[-2][1] == transport.calls[-3][1]


@pytest.mark.parametrize("field", ["job_id", "news_event_id", "inference_id"])
@pytest.mark.parametrize("outcome", ["ACCEPTED", "ALREADY_ACCEPTED"])
@pytest.mark.parametrize("recover", [False, True], ids=["exhausted", "correct-replay"])
def test_completion_receipt_must_identify_active_assignment(
    field: str, outcome: str, recover: bool, capsys: pytest.CaptureFixture[str]
) -> None:
    active = assignment()
    active["ref"]["job_id"] = "22222222-2222-2222-2222-222222222222"
    active["inference_id"] = "33333333-3333-3333-3333-333333333333"
    correct = {
        "job_id": active["ref"]["job_id"],
        "news_event_id": json.loads(active["news_event_json"])["event_id"],
        "inference_id": active["inference_id"],
        "accepted_at": "2026-10-29T00:00:00.123456Z",
    }
    wrong = dict(correct)
    wrong[field] = "44444444-4444-4444-4444-444444444444"
    mismatched = (200, {"outcome": outcome, "receipt": wrong})
    replies = (
        [mismatched, (200, {"outcome": "ALREADY_ACCEPTED", "receipt": correct})]
        if recover
        else [mismatched] * 3
    )
    instance, transport = worker(
        [
            (200, {"outcome": "CLAIMED", "assignment": active}),
            renewed(),
            renewed(),
            *replies,
        ]
    )
    # Exhaustion must stop even in continuous mode; a correct replay may succeed in once mode.
    instance.once = recover
    generated = 0
    waits: list[float] = []

    def processor(value: dict[str, Any], cancel: threading.Event, now: Any) -> str:
        nonlocal generated
        generated += 1
        return generate(value, now)

    def wait(seconds: float, *, reconcile: bool = False) -> None:
        assert reconcile
        waits.append(seconds)

    original = transport.request

    def request(
        operation: str, body: bytes, deadline: float | None = None
    ) -> tuple[int, dict[str, Any]]:
        # No mismatched acknowledgement may produce success before the next send.
        assert capsys.readouterr().out == ""
        return original(operation, body, deadline)

    instance.processor = processor
    instance.wait = wait  # type: ignore[method-assign]
    transport.request = request  # type: ignore[method-assign]
    assert instance.run() == (0 if recover else 3)
    assert capsys.readouterr().out == ("worker: succeeded\n" if recover else "")
    assert generated == 1
    assert waits == ([1] if recover else [1, 2])
    assert [op for op, _ in transport.calls] == ["claim", "renew", "renew"] + ["complete"] * len(
        replies
    )
    frozen = [body for op, body in transport.calls if op == "complete"]
    assert all(body == frozen[0] for body in frozen)


@pytest.mark.parametrize(
    "value",
    [
        None,
        123,
        "2026-01-15Z",
        "2026-01-15",
        "2026-01-15T14:30Z",
        "2026-01-15T14:30:30",
        "2026-01-15 14:30:30Z",
        "2026-01-15t14:30:30Z",
        "20260115T143030Z",
        "2026-01-15T14:30:30z",
        "2026-01-15T14:30:30+00:00",
        "2026-01-15T14:30:30+01:00Z",
        "2026-01-15T14:30:30,123Z",
        "2026-01-15T14:30:30.Z",
        "2026-01-15T14:30:30Z\n",
        " 2026-01-15T14:30:30Z",
        "2026-02-30T14:30:30Z",
        "2026-13-15T14:30:30Z",
        "2026-01-15T24:30:30Z",
        "2026-01-15T14:60:30Z",
        "2026-01-15T14:30:60Z",
        "２０２６-01-15T14:30:30Z",
    ],
)
def test_timestamp_rejects_non_protocol_forms(value: Any) -> None:
    from news_swing_ai.worker import timestamp

    with pytest.raises(ProtocolError):
        timestamp(value)


@pytest.mark.parametrize(
    "value,microseconds",
    [
        ("2026-10-29T00:01:00Z", 0),
        ("2026-10-29T00:01:00.1Z", 100000),
        ("2026-10-29T00:01:00.123456Z", 123456),
        ("2026-10-29T00:01:00.123456789Z", 123456),
    ],
)
def test_timestamp_accepts_complete_utc_and_fractional_seconds(
    value: str,
    microseconds: int,
) -> None:
    from news_swing_ai.worker import timestamp

    result = timestamp(value)
    assert result.tzinfo == UTC
    assert result == datetime(2026, 10, 29, 0, 1, 0, microseconds, tzinfo=UTC)


@pytest.mark.parametrize("stage", ["claim", "renew", "complete"])
@pytest.mark.parametrize(
    "value",
    [
        "2026-10-29Z",
        "2026-10-29T00:01:00",
        "2026-02-30T00:01:00Z",
    ],
)
def test_invalid_timestamp_preserves_ambiguous_response_recovery(
    stage: str,
    value: str,
    capsys: pytest.CaptureFixture[str],
) -> None:
    delivered = claimed()
    renewal = renewed()
    receipt = {
        "job_id": ID,
        "news_event_id": ID,
        "inference_id": ID,
        "accepted_at": value,
    }
    if stage == "claim":
        delivered[1]["assignment"]["lease_expires_at"] = value
        replies: list[Any] = [delivered]
        operations = ["claim"]
    elif stage == "renew":
        renewal[1]["lease_expires_at"] = value
        replies = [delivered] + [renewal] * 3
        operations = ["claim"] + ["renew"] * 3
    else:
        replies = [delivered, renewal, renewal] + [
            (200, {"outcome": "ACCEPTED", "receipt": receipt})
        ] * 3
        operations = ["claim", "renew", "renew"] + ["complete"] * 3
    instance, transport = worker(replies)
    instance.once = False
    assert instance.run() == 3
    assert capsys.readouterr().out == ""
    assert [op for op, _ in transport.calls] == operations
    assert all(body == transport.calls[-1][1] for op, body in transport.calls if op == stage)


@pytest.mark.parametrize("stage", ["claim", "renew", "complete"])
def test_fractional_utc_timestamps_accepted_at_each_response_boundary(stage: str) -> None:
    delivered = claimed()
    renewal = renewed()
    receipt = {
        "job_id": ID,
        "news_event_id": ID,
        "inference_id": ID,
        "accepted_at": "2026-10-29T00:00:00Z",
    }
    fractional = "2026-10-29T00:01:00.123456789Z"
    if stage == "claim":
        delivered[1]["assignment"]["lease_expires_at"] = fractional
    elif stage == "renew":
        renewal[1]["lease_expires_at"] = fractional
    else:
        receipt["accepted_at"] = fractional
    instance, _ = worker(
        [
            delivered,
            renewal,
            renewal,
            (200, {"outcome": "ACCEPTED", "receipt": receipt}),
        ]
    )
    assert instance.run() == 0

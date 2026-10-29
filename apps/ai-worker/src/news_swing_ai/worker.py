"""M3B2 single-assignment local fake worker (the scaffold CLI is unchanged)."""

from __future__ import annotations

import argparse
import re
import signal
import threading
import time
from collections.abc import Callable, Sequence
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

from .fake_result import generate
from .transport import DeliveryError, ProtocolError, Transport, decode, encode


class AuthorityLost(Exception):
    pass


def utcnow() -> datetime:
    return datetime.now(UTC)


def timestamp(value: Any) -> datetime:
    if (
        not isinstance(value, str)
        or re.fullmatch(
            r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?Z", value
        )
        is None
    ):
        raise ProtocolError()
    try:
        return datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError:
        raise ProtocolError() from None


def validate_assignment(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != {
        "ref",
        "news_event_json",
        "inference_id",
        "expected_backend",
        "result_schema_version",
        "lease_expires_at",
    }:
        raise ProtocolError()
    ref = value["ref"]
    if not isinstance(ref, dict) or set(ref) != {"job_id", "assignment_number", "ownership_token"}:
        raise ProtocolError()
    for identifier in (ref["job_id"], ref["ownership_token"], value["inference_id"]):
        if not isinstance(identifier, str) or str(UUID(identifier)) != identifier.lower():
            raise ProtocolError()
    if type(ref["assignment_number"]) is not int or not 1 <= ref["assignment_number"] <= 2147483647:
        raise ProtocolError()
    if not isinstance(value["news_event_json"], str):
        raise ProtocolError()
    timestamp(value["lease_expires_at"])
    return value


Processor = Callable[[dict[str, Any], threading.Event, Callable[[], datetime]], str]


def process(
    assignment: dict[str, Any], cancel: threading.Event, now: Callable[[], datetime]
) -> str:
    if cancel.is_set():
        raise InterruptedError()
    return generate(assignment, now)


class Worker:
    def __init__(
        self,
        transport: Transport,
        once: bool = False,
        *,
        now: Callable[[], datetime] = utcnow,
        monotonic: Callable[[], float] = time.monotonic,
        processor: Processor = process,
    ):
        self.transport = transport
        self.once = once
        self.now = now
        self.monotonic = monotonic
        self.processor = processor
        self.stop = threading.Event()
        self.wake = threading.Event()
        self.grace = float("inf")
        self.safe = 0.0

    def shutdown(self) -> None:
        if not self.stop.is_set():
            self.grace = self.monotonic() + 20
            self.stop.set()
            self.wake.set()
            self.transport.limit_shutdown(20)

    def wait(self, seconds: float, *, reconcile: bool = False) -> None:
        end = min(self.monotonic() + seconds, self.grace)
        if reconcile:
            threading.Event().wait(max(0, end - self.monotonic()))
        else:
            self.stop.wait(max(0, end - self.monotonic()))

    def acknowledge(self, expiry: Any, started: float) -> None:
        # Deduct all request time as well as the safety margin; clocks schedule, never authorize.
        self.safe = started + (timestamp(expiry) - self.now()).total_seconds() - 2
        if self.safe - self.monotonic() < 0.3:
            raise DeliveryError(True)

    def control(
        self, operation: str, body: bytes, *, receipt_identity: dict[str, str] | None = None
    ) -> dict[str, Any]:
        end = min(self.monotonic() + 60, self.grace) if operation == "complete" else self.safe
        for attempt in range(3):
            started = self.monotonic()
            if started >= min(end, self.grace):
                raise DeliveryError(True)
            try:
                status, document = self.transport.request(operation, body, min(end, self.grace))
            except (DeliveryError, ProtocolError):
                status, document = 503, {}
            if status == 200:
                try:
                    allowed = {
                        "renew": {"RENEWED"},
                        "fail": {"RETRY_WAITING", "FAILED"},
                        "complete": {"ACCEPTED", "ALREADY_ACCEPTED"},
                    }
                    if document.get("outcome") not in allowed[operation]:
                        raise ProtocolError()
                    if operation == "renew":
                        if set(document) != {"outcome", "lease_expires_at"}:
                            raise ProtocolError()
                        self.acknowledge(document["lease_expires_at"], started)
                    elif operation == "complete":
                        if set(document) != {"outcome", "receipt"}:
                            raise ProtocolError()
                        receipt = document["receipt"]
                        if not isinstance(receipt, dict) or set(receipt) != {
                            "job_id",
                            "news_event_id",
                            "inference_id",
                            "accepted_at",
                        }:
                            raise ProtocolError()
                        for field in ("job_id", "news_event_id", "inference_id"):
                            if (
                                not isinstance(receipt[field], str)
                                or str(UUID(receipt[field])) != receipt[field].lower()
                            ):
                                raise ProtocolError()
                        if receipt_identity is None or any(
                            UUID(receipt[field]) != UUID(expected)
                            for field, expected in receipt_identity.items()
                        ):
                            raise ProtocolError()
                        timestamp(receipt["accepted_at"])
                    elif document["outcome"] == "FAILED":
                        if set(document) != {"outcome", "failure_code"} or document[
                            "failure_code"
                        ] not in {"WORKER_RECOVERABLE", "WORKER_PERMANENT"}:
                            raise ProtocolError()
                    elif set(document) != {"outcome"}:
                        raise ProtocolError()
                    return document
                except (ProtocolError, KeyError, TypeError, ValueError):
                    status, document = 503, {}
            code = document.get("code")
            if (status == 404 and code == "ASSIGNMENT_NOT_FOUND") or (
                status == 409
                and code
                in {
                    "NOT_RUNNING",
                    "OWNER_MISMATCH",
                    "REVISION_OBSOLETE",
                    "NEWS_EXPIRED",
                    "LEASE_EXPIRED",
                }
            ):
                raise AuthorityLost()
            if status < 500:
                raise ProtocolError()
            if attempt == 2:
                raise DeliveryError(True)
            self.wait(attempt + 1, reconcile=True)
        raise DeliveryError(True)

    def assignment(self, assignment: dict[str, Any], started: float) -> None:
        self.acknowledge(assignment["lease_expires_at"], started)
        command = encode({"assignment": assignment["ref"]})
        self.control("renew", command)
        if self.stop.is_set():
            self.control("fail", encode({"assignment": assignment["ref"], "kind": "RECOVERABLE"}))
            return
        cancel = threading.Event()
        done = threading.Event()
        output: list[str] = []
        failed = threading.Event()

        def compute() -> None:
            try:
                output.append(self.processor(assignment, cancel, self.now))
            except Exception:
                failed.set()
            finally:
                done.set()
                self.wake.set()

        thread = threading.Thread(target=compute, name="fake-processor", daemon=True)
        self.wake.clear()
        thread.start()
        try:
            while not done.is_set():
                remaining = self.safe - self.monotonic()
                interval = min(10, remaining / 3)
                if interval < 0.1:
                    raise DeliveryError(True)
                if self.stop.is_set():
                    break
                self.wake.wait(interval)
                if self.stop.is_set() or done.is_set():
                    break
                self.control("renew", command)
            if self.stop.is_set():
                cancel.set()
                thread.join(max(0, min(2, self.grace - self.monotonic())))
                if thread.is_alive():
                    raise DeliveryError(True)
                self.control(
                    "fail", encode({"assignment": assignment["ref"], "kind": "RECOVERABLE"})
                )
                return
            if failed.is_set():
                self.control("fail", encode({"assignment": assignment["ref"], "kind": "PERMANENT"}))
                raise ProtocolError()
            self.control("renew", command)
            if self.stop.is_set():
                self.control(
                    "fail", encode({"assignment": assignment["ref"], "kind": "RECOVERABLE"})
                )
                return
            # The immutable document and outer request are generated exactly once.
            frozen = encode({"assignment": assignment["ref"], "result_json": output[0]})
            receipt_identity = {
                "job_id": assignment["ref"]["job_id"],
                "news_event_id": decode(assignment["news_event_json"].encode())["event_id"],
                "inference_id": assignment["inference_id"],
            }
            self.control("complete", frozen, receipt_identity=receipt_identity)
            print("worker: succeeded", flush=True)
        finally:
            cancel.set()
            thread.join(max(0, min(2, self.grace - self.monotonic())))
            if thread.is_alive():
                raise DeliveryError(True)

    def run(self) -> int:
        try:
            attempts = 0
            while not self.stop.is_set():
                started = self.monotonic()
                try:
                    status, document = self.transport.request("claim", b"{}", self.grace)
                except DeliveryError as error:
                    if error.sent:
                        return 3
                    attempts += 1
                    if self.stop.is_set():
                        return 0
                    if self.once and attempts >= 3:
                        return 3
                    self.wait((1, 2, 4, 8, 10)[min(attempts - 1, 4)])
                    continue
                except ProtocolError:
                    return 3  # Even an invalid claim response can conceal a committed claim.
                attempts = 0
                if status >= 500:
                    return 3
                if status != 200:
                    return 2
                if document == {"outcome": "NO_WORK"}:
                    if self.once:
                        return 0
                    self.wait(2)
                    continue
                if set(document) != {"outcome", "assignment"} or document["outcome"] != "CLAIMED":
                    return 3
                try:
                    assignment = validate_assignment(document["assignment"])
                except (ProtocolError, ValueError, TypeError):
                    return 3
                if (
                    assignment["expected_backend"] != "fake"
                    or assignment["result_schema_version"] != "1.0.0"
                ):
                    return 2
                try:
                    self.assignment(assignment, started)
                except AuthorityLost:
                    if self.once:
                        return 4
                if self.once:
                    return 0
            return 0
        except AuthorityLost:
            return 4 if self.once else 0
        except DeliveryError:
            return 3
        except (ProtocolError, KeyError, ValueError, TypeError):
            return 2


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the local deterministic abstention worker.")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--once", action="store_true")
    parsed = parser.parse_args(arguments)
    try:
        transport = Transport(parsed.base_url)
    except ProtocolError:
        print("worker: invalid loopback origin", flush=True)
        return 2
    worker = Worker(transport, parsed.once)
    previous = {}
    try:
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous[signum] = signal.signal(signum, lambda *_: worker.shutdown())
        return worker.run()
    finally:
        for signum, handler in previous.items():
            signal.signal(signum, handler)
        transport.close()


if __name__ == "__main__":
    raise SystemExit(main())

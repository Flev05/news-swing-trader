"""Bounded proxy-independent loopback HTTP transport."""

from __future__ import annotations

import http.client
import json
import socket
import threading
import time
from typing import Any
from urllib.parse import urlsplit


class ProtocolError(Exception):
    """Invalid peer document; diagnostics must never include peer text."""


class DeliveryError(Exception):
    def __init__(self, sent: bool):
        self.sent = sent
        super().__init__("uncertain delivery" if sent else "connection unavailable")


def encode(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=True, separators=(",", ":"), allow_nan=False).encode()


def decode(raw: bytes) -> dict[str, Any]:
    def pairs(items: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in items:
            if key in result:
                raise ProtocolError()
            result[key] = value
        return result

    def invalid_constant(value: str) -> Any:
        raise ProtocolError()

    try:
        value = json.loads(
            raw.decode("utf-8"), object_pairs_hook=pairs, parse_constant=invalid_constant
        )
        if not isinstance(value, dict):
            raise ProtocolError()
        return value
    except (ValueError, UnicodeError) as error:
        raise ProtocolError() from error


class Transport:
    def __init__(self, base_url: str):
        try:
            url = urlsplit(base_url)
            if (
                url.scheme != "http"
                or url.hostname != "127.0.0.1"
                or url.username is not None
                or url.password is not None
                or url.path not in ("", "/")
                or url.query
                or url.fragment
                or url.port is None
                or not 1 <= url.port <= 65535
                or base_url.rstrip("/") != f"http://127.0.0.1:{url.port}"
            ):
                raise ValueError()
        except ValueError as error:
            raise ProtocolError() from error
        self.port = url.port
        self._lock = threading.Lock()
        self._interrupt: Any = None
        self._shutdown_timer: threading.Timer | None = None
        self._closing = False

    def limit_shutdown(self, seconds: float) -> None:
        def abort() -> None:
            with self._lock:
                self._closing = True
                if self._interrupt is not None:
                    self._interrupt()

        self._shutdown_timer = threading.Timer(seconds, abort)
        self._shutdown_timer.start()

    def close(self) -> None:
        if self._shutdown_timer is not None:
            self._shutdown_timer.cancel()
            self._shutdown_timer.join()

    def request(
        self, operation: str, body: bytes, deadline: float | None = None
    ) -> tuple[int, dict[str, Any]]:
        if operation not in {"claim", "renew", "fail", "complete"}:
            raise ProtocolError()
        claim = operation == "claim"
        end = min(deadline or float("inf"), time.monotonic() + (65 if claim else 15))
        remaining = end - time.monotonic()
        if remaining <= 0:
            raise DeliveryError(False)
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=min(2, remaining))
        guard = threading.Lock()
        active: socket.socket | None = None
        expired = False

        def interrupt() -> None:
            nonlocal expired
            with guard:
                expired = True
                if active is not None:
                    try:
                        active.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                    active.close()

        with self._lock:
            if self._closing:
                raise DeliveryError(False)
            self._interrupt = interrupt
        timer = threading.Timer(remaining, interrupt)
        sent = False
        response: http.client.HTTPResponse | None = None
        timer.start()
        try:
            connection.connect()
            with guard:
                active = connection.sock
                if expired:
                    raise DeliveryError(False)
                assert active is not None
                active.settimeout(min(60 if claim else 10, max(0.001, end - time.monotonic())))
            sent = True
            connection.request(
                "POST",
                "/api/v1/worker/" + operation,
                body,
                {"Content-Type": "application/json", "Accept": "application/json"},
            )
            response = connection.getresponse()
            if 300 <= response.status < 400 or response.getheader("Location") is not None:
                raise ProtocolError()
            if response.getheader("Content-Encoding", "identity") != "identity":
                raise ProtocolError()
            raw = response.read(1048577)
            if len(raw) > 1048576:
                raise ProtocolError()
            if response.length not in (None, 0):
                raise DeliveryError(True)
            expected = "application/json" if response.status == 200 else "application/problem+json"
            if response.getheader("Content-Type", "").split(";")[0] != expected:
                raise ProtocolError()
            return response.status, decode(raw)
        except (OSError, http.client.HTTPException) as error:
            raise DeliveryError(sent) from error
        finally:
            with self._lock:
                self._interrupt = None
            timer.cancel()
            timer.join()
            if response is not None:
                response.close()
            connection.close()

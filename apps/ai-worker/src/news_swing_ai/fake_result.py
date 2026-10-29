"""Explicit deterministic abstention, derived only from the stored assignment."""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime
from typing import Any

from .transport import ProtocolError, decode, encode


def generate(assignment: dict[str, Any], now: Callable[[], datetime]) -> str:
    if assignment["expected_backend"] != "fake" or assignment["result_schema_version"] != "1.0.0":
        raise ProtocolError()
    event = decode(assignment["news_event_json"].encode())
    fields = (
        "issuer_role",
        "event_type",
        "direction",
        "materiality",
        "relevance",
        "novelty",
        "expected_horizon",
        "schedule_status",
        "information_status",
    )
    return encode(
        {
            "schema_version": "1.0.0",
            "inference_id": assignment["inference_id"],
            "news_event_id": event["event_id"],
            "news_event_sha256": event["content_hash"],
            "generated_at": now().astimezone(UTC).isoformat().replace("+00:00", "Z"),
            "model": {
                "backend": "fake",
                "name": "deterministic-abstention",
                "version": "1",
                "prompt_template_version": "1.0.0",
            },
            "ticker_results": [
                dict(
                    ticker=ticker,
                    **dict.fromkeys(fields, "unknown"),
                    numeric_facts=[],
                    abstain=True,
                    reason_codes=["unsupported_event_type"],
                )
                for ticker in event["affected_symbols"]
            ],
        }
    ).decode()

"""Deterministic placeholder inference boundary for Milestone 0."""

from __future__ import annotations

import hashlib
import json
from typing import Protocol


class InferenceBackend(Protocol):
    """A future local inference backend boundary."""

    def infer(self, prompt: str) -> str:
        """Return a backend response for an opaque prompt."""


class FakeInferenceBackend:
    """Return stable, non-domain JSON for tests without a model runtime."""

    def infer(self, prompt: str) -> str:
        response = {
            "backend": "fake",
            "input_sha256": hashlib.sha256(prompt.encode("utf-8")).hexdigest(),
            "milestone": 0,
        }
        return json.dumps(response, separators=(",", ":"), sort_keys=True)

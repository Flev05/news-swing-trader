"""Command-line entry point for the Milestone 0 fake backend."""

from __future__ import annotations

import argparse
from collections.abc import Sequence

from .backends import FakeInferenceBackend


def main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the deterministic Milestone 0 fake backend.")
    parser.add_argument("prompt", help="Opaque input used only to derive a stable hash.")
    parsed = parser.parse_args(arguments)
    print(FakeInferenceBackend().infer(parsed.prompt))
    return 0

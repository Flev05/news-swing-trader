from news_swing_ai.backends import FakeInferenceBackend


def test_fake_backend_is_byte_identical_for_identical_input() -> None:
    backend = FakeInferenceBackend()

    assert backend.infer("earnings headline") == backend.infer("earnings headline")


def test_fake_backend_changes_only_with_input_hash() -> None:
    backend = FakeInferenceBackend()

    assert backend.infer("first") != backend.infer("second")

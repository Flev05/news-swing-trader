from pytest import CaptureFixture

from news_swing_ai.cli import main


def test_cli_prints_fake_response(capsys: CaptureFixture[str]) -> None:
    assert main(["sample input"]) == 0

    assert '"backend":"fake"' in capsys.readouterr().out

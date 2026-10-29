from __future__ import annotations

from pathlib import Path

import pytest
from typer.testing import CliRunner

import cluetooth_sync.cli as cli


def _discovered_prefixes(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    args: list[str],
    env: dict[str, str],
) -> list[str]:
    key = tmp_path / "key"
    key.write_bytes(b"\x00" * 32)
    seen: list[list[str]] = []
    monkeypatch.setattr(cli, "_build_storage_client", lambda *_: object())
    monkeypatch.setattr(
        cli, "_print_survey", lambda _s, _d, _b, prefixes: seen.append(prefixes)
    )
    result = CliRunner().invoke(
        cli.app,
        [
            "sync",
            "--database-url",
            "postgres://unused",
            "--bucket",
            "bucket",
            "--private-key",
            str(key),
            "--list-only",
            *args,
        ],
        env=env,
    )
    assert result.exit_code == 0, result.output
    return seen[0]


def test_sync_discovers_scans_and_gatt_by_default(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    assert _discovered_prefixes(monkeypatch, tmp_path, [], {}) == ["scans/", "gatt/"]


def test_prefix_option_repeats_and_the_environment_splits_on_spaces(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    assert _discovered_prefixes(
        monkeypatch, tmp_path, ["--prefix", "a/", "--prefix", "b/"], {}
    ) == ["a/", "b/"]
    assert _discovered_prefixes(
        monkeypatch, tmp_path, [], {"CLUETOOTH_PREFIX": "scans/ gatt/"}
    ) == ["scans/", "gatt/"]
    assert _discovered_prefixes(
        monkeypatch, tmp_path, [], {"CLUETOOTH_PREFIX": "scans/"}
    ) == ["scans/"]

from __future__ import annotations

import subprocess
from pathlib import Path

from nacl.public import PrivateKey, SealedBox


def test_production_rust_sealed_box_decrypts_with_pynacl(tmp_path: Path) -> None:
    private_key = PrivateKey(bytes(range(32)))
    repository_root = Path(__file__).resolve().parents[2]
    completed = subprocess.run(
        [
            "cargo",
            "run",
            "--quiet",
            "--locked",
            "--example",
            "prepare_encrypted_payload",
            "--",
            str(tmp_path / "core"),
            bytes(private_key.public_key).hex(),
        ],
        cwd=repository_root / "cluetooth-core",
        check=True,
        capture_output=True,
        text=True,
        timeout=120,
    )
    plaintext_path, ciphertext_path, object_path = completed.stdout.splitlines()

    plaintext = Path(plaintext_path).read_bytes()
    ciphertext = Path(ciphertext_path).read_bytes()
    assert ciphertext != plaintext
    assert len(ciphertext) == len(plaintext) + 48
    assert SealedBox(private_key).decrypt(ciphertext) == plaintext
    assert plaintext.startswith(b"PAR1") and plaintext.endswith(b"PAR1")
    assert object_path.startswith("scans/v2/")
    assert object_path.endswith(".parquet.encrypted")

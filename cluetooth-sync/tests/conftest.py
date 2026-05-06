from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Iterator

import pytest
from testcontainers.postgres import PostgresContainer


REPO_ROOT = Path(__file__).resolve().parents[2]
DB_DIR = REPO_ROOT / "db"
DBMATE_BIN = DB_DIR / "node_modules" / "@dbmate" / "linux-x64" / "bin" / "dbmate"


def _database_url(container: PostgresContainer) -> str:
    host = container.get_container_host_ip()
    port = container.get_exposed_port(5432)
    username = container.username
    password = container.password
    dbname = container.dbname
    return f"postgres://{username}:{password}@{host}:{port}/{dbname}?sslmode=disable"


def _run_migrations(database_url: str) -> None:
    subprocess.run(
        [
            str(DBMATE_BIN),
            "--url",
            database_url,
            "--migrations-dir",
            "db/migrations",
            "up",
        ],
        cwd=DB_DIR,
        check=True,
    )


@pytest.fixture(scope="session")
def database_url() -> Iterator[str]:
    with PostgresContainer(
        "postgis/postgis:18-3.6",
        username="cluetooth_test",
        password="cluetooth_test",
        dbname="cluetooth_test",
    ) as container:
        database_url = _database_url(container)
        _run_migrations(database_url)
        yield database_url

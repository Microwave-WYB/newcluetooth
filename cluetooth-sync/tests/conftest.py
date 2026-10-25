from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Iterator
from urllib.parse import urlsplit, urlunsplit
from uuid import uuid4

import adbc_driver_postgresql.dbapi as pg_dbapi
import pytest
from testcontainers.postgres import PostgresContainer


REPO_ROOT = Path(__file__).resolve().parents[2]
DB_DIR = REPO_ROOT / "db"


def _database_url(container: PostgresContainer) -> str:
    host = container.get_container_host_ip()
    port = container.get_exposed_port(5432)
    username = container.username
    password = container.password
    dbname = container.dbname
    return f"postgres://{username}:{password}@{host}:{port}/{dbname}?sslmode=disable"


def _with_database(database_url: str, dbname: str) -> str:
    parts = urlsplit(database_url)
    return urlunsplit(parts._replace(path=f"/{dbname}"))


def _run_migrations(database_url: str) -> None:
    subprocess.run(
        [
            "dbmate",
            "--url",
            database_url,
            "--migrations-dir",
            "db/migrations",
            "up",
        ],
        cwd=DB_DIR,
        check=True,
    )


def _execute(database_url: str, statement: str) -> None:
    connection = pg_dbapi.connect(database_url, autocommit=True)
    cursor = connection.cursor()
    try:
        cursor.execute(statement)
    finally:
        cursor.close()
        connection.close()


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


@pytest.fixture
def clean_database_url(database_url: str) -> Iterator[str]:
    """A freshly migrated database of its own, for whole-table assertions.

    The shared `database_url` holds rows written by every other test, while the
    backfill verification compares whole-table totals against `scans_old`.
    """
    dbname = f"cluetooth_clean_{uuid4().hex[:12]}"
    _execute(database_url, f'create database "{dbname}"')
    clean_url = _with_database(database_url, dbname)
    _run_migrations(clean_url)
    try:
        yield clean_url
    finally:
        _execute(database_url, f'drop database if exists "{dbname}" with (force)')

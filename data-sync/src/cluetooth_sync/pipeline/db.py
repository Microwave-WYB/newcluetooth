from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from typing import Any

import adbc_driver_postgresql.dbapi as pg_dbapi


@dataclass(frozen=True)
class DatabaseSession:
    connection: Any
    cursor: Any


@contextmanager
def session(
    database_url: str,
    *,
    autocommit: bool = False,
) -> Iterator[DatabaseSession]:
    connection = pg_dbapi.connect(database_url, autocommit=autocommit)
    cursor = connection.cursor()
    try:
        yield DatabaseSession(connection=connection, cursor=cursor)
        if not autocommit:
            connection.commit()
    except Exception:
        if not autocommit:
            connection.rollback()
        raise
    finally:
        cursor.close()
        connection.close()

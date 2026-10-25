import functools
from importlib.resources import files

import cluetooth_sync.queries as query_resources



@functools.cache
def read_query(relative_path: str) -> str:
    return (
        files(query_resources)
        .joinpath(*relative_path.split("/"))
        .read_text(encoding="utf-8")
    )


def quote_pg_identifier(identifier: str) -> str:
    return '"' + identifier.replace('"', '""') + '"'


def stage_query(relative_path: str, **tables: str) -> str:
    """Query with `{{ name }}` placeholders replaced by quoted table names."""
    query = read_query(relative_path)
    for name, table in tables.items():
        query = query.replace("{{ " + name + " }}", quote_pg_identifier(table))
    if "{{ " in query:
        raise ValueError(f"unresolved table placeholder in {relative_path}")
    return query

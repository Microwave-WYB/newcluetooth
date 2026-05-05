import functools
from importlib.resources import files

import cluetooth_sync.queries as query_resources

SCAN_INSERT_STAGE_PLACEHOLDER = "{{ staging_table }}"


@functools.cache
def read_query(relative_path: str) -> str:
    return (
        files(query_resources)
        .joinpath(*relative_path.split("/"))
        .read_text(encoding="utf-8")
    )


def quote_pg_identifier(identifier: str) -> str:
    return '"' + identifier.replace('"', '""') + '"'


def scan_insert_from_stage(staging_table: str) -> str:
    return read_query("scans/insert_from_stage.sql").replace(
        SCAN_INSERT_STAGE_PLACEHOLDER,
        quote_pg_identifier(staging_table),
    )

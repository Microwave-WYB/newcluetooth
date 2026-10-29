from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence, TextIO
from urllib import error, parse, request

import polars as pl

import cluetooth_sync.pipeline.db as db


WIGLE_UPLOAD_URL = "https://api.wigle.net/api/v2/file/upload"
WIGLE_TRANSACTIONS_URL = "https://api.wigle.net/api/v2/file/transactions"
WIGLE_CSV_HEADER = (
    "MAC",
    "SSID",
    "AuthMode",
    "FirstSeen",
    "Channel",
    "Frequency",
    "RSSI",
    "CurrentLatitude",
    "CurrentLongitude",
    "AltitudeMeters",
    "AccuracyMeters",
    "RCOIs",
    "MfgrId",
    "Type",
)
ACTIVE_BATCH_STATUSES = ("created", "exported", "uploading", "uploaded", "completed")


@dataclass(frozen=True)
class WigleBatch:
    id: int
    status: str
    row_count: int
    min_item_id: int | None
    max_item_id: int | None
    filename: str | None
    csv_sha256: str | None
    wigle_status: str | None
    wigle_transids: tuple[str, ...]


@dataclass(frozen=True)
class WigleExportResult:
    row_count: int
    csv_sha256: str
    file_size: int


class WigleUploadError(Exception):
    pass


def build_wigle_auth_header(*, api_key: str | None) -> str:
    if api_key:
        return f"Basic {api_key}"

    raise ValueError("provide WIGLE_API_KEY")


def default_wigle_filename() -> str:
    return f"cluetooth-wigle-{uuid.uuid4().hex}.csv"


def create_wigle_upload_batch(
    database_url: str,
    *,
    batch_size: int,
    filename: str,
) -> WigleBatch | None:
    """Claim up to `batch_size` pending address-minutes (one CSV row each).

    Every minute row of a claimed address-minute is recorded as a member, so the
    address-minute counts as uploaded even if another payload, session or PDU
    kind of it is merged in later.
    """
    client_token = uuid.uuid4().hex
    with db.session(database_url) as session:
        # Creators run one at a time: a group spans several rows, and row locks
        # (skip locked) cannot guard a group.
        session.cursor.execute(_WIGLE_BATCH_LOCK)
        session.cursor.fetch_polars()
        session.cursor.execute(
            """
            insert into wigle_upload_batches (status, filename, client_token)
            values ('created', $1, $2)
            """,
            [filename, client_token],
        )
        session.cursor.execute(
            f"""
            with batch as (
              select id
              from wigle_upload_batches
              where client_token = $2
            ),
            candidate as materialized (
              select m.minute, m.addr
              from sighting_minutes m
              where m.location is not null
                and m.best_rssi is not null
                and not exists ({_WIGLE_NOT_CLAIMED})
              group by m.minute, m.addr
              order by m.minute, m.addr
              limit $1
            )
            insert into wigle_upload_batch_minutes (batch_id, minute_id, minute)
            select batch.id, g.id, g.minute
            from batch
            cross join candidate
            join sighting_minutes g
              on g.minute = candidate.minute
             and g.addr = candidate.addr
            """,
            [batch_size, client_token],
        )
        session.cursor.execute(
            """
            with batch as (
              select id
              from wigle_upload_batches
              where client_token = $1
            ),
            stats as (
              select
                bm.batch_id,
                count(distinct (m.minute, m.addr))::bigint as row_count,
                min(bm.minute_id)::bigint as min_item_id,
                max(bm.minute_id)::bigint as max_item_id
              from wigle_upload_batch_minutes bm
              join batch
                on batch.id = bm.batch_id
              join sighting_minutes m
                on m.id = bm.minute_id
               and m.minute = bm.minute
              group by bm.batch_id
            )
            update wigle_upload_batches b
            set
              row_count = stats.row_count,
              min_item_id = stats.min_item_id,
              max_item_id = stats.max_item_id
            from stats
            where b.id = stats.batch_id
            """,
            [client_token],
        )
        session.cursor.execute(
            """
            delete from wigle_upload_batches
            where client_token = $1
              and row_count = 0
            """,
            [client_token],
        )

    return _get_wigle_upload_batch_by_token(database_url, client_token)


def get_wigle_upload_batch(database_url: str, batch_id: int) -> WigleBatch | None:
    rows = _read_database_dicts(
        database_url,
        """
        select
          id,
          status,
          row_count,
          min_item_id,
          max_item_id,
          filename,
          csv_sha256,
          wigle_status,
          wigle_transids::text as wigle_transids
        from wigle_upload_batches
        where id = $1
        """,
        [batch_id],
    )
    if not rows:
        return None
    return _batch_from_row(rows[0])


def _get_wigle_upload_batch_by_token(
    database_url: str,
    client_token: str,
) -> WigleBatch | None:
    rows = _read_database_dicts(
        database_url,
        """
        select
          id,
          status,
          row_count,
          min_item_id,
          max_item_id,
          filename,
          csv_sha256,
          wigle_status,
          wigle_transids::text as wigle_transids
        from wigle_upload_batches
        where client_token = $1
        """,
        [client_token],
    )
    if not rows:
        return None
    return _batch_from_row(rows[0])


def get_resumable_wigle_upload_batch(database_url: str) -> WigleBatch | None:
    rows = _read_database_dicts(
        database_url,
        """
        select
          id,
          status,
          row_count,
          min_item_id,
          max_item_id,
          filename,
          csv_sha256,
          wigle_status,
          wigle_transids::text as wigle_transids
        from wigle_upload_batches
        where status in ('created', 'exported')
        order by id
        limit 1
        """,
    )
    if not rows:
        return None
    return _batch_from_row(rows[0])


def count_pending_wigle_rows(database_url: str) -> int:
    rows = _read_database_dicts(
        database_url,
        _count_pending_wigle_rows_sql(),
    )
    return int(rows[0]["row_count"])


def list_wigle_upload_batches(database_url: str, *, limit: int) -> list[WigleBatch]:
    rows = _read_database_dicts(
        database_url,
        """
        select
          id,
          status,
          row_count,
          min_item_id,
          max_item_id,
          filename,
          csv_sha256,
          wigle_status,
          wigle_transids::text as wigle_transids
        from wigle_upload_batches
        order by id desc
        limit $1
        """,
        [limit],
    )
    return [_batch_from_row(row) for row in rows]


def write_wigle_csv(
    database_url: str,
    output: TextIO,
    *,
    batch_id: int | None = None,
    limit: int | None = None,
    include_uploaded: bool = False,
    capabilities: str = "Misc [LE]",
    app_release: str = "unknown",
    model: str = "unknown",
    release: str = "unknown",
    device: str = "unknown",
    display: str = "unknown",
    board: str = "unknown",
    brand: str = "unknown",
) -> int:
    _write_preheader_csv(
        output,
        _preheader_row(
            app_release=app_release,
            model=model,
            release=release,
            device=device,
            display=display,
            board=board,
            brand=brand,
        ),
    )

    if batch_id is None:
        # The ADBC driver cannot bind a null parameter, so "no limit" is 0.
        rows_df = _read_database_frame(
            database_url,
            _pending_wigle_rows_sql(),
            [capabilities, limit or 0, include_uploaded],
        )
    else:
        rows_df = _read_database_frame(
            database_url,
            _batch_wigle_rows_sql(),
            [batch_id, capabilities],
        )

    rows_df.write_csv(output, include_header=True, null_value="")
    return rows_df.height


def export_wigle_batch_csv(
    database_url: str,
    *,
    batch_id: int,
    output_path: Path,
    capabilities: str = "Misc [LE]",
    app_release: str = "unknown",
    model: str = "unknown",
    release: str = "unknown",
    device: str = "unknown",
    display: str = "unknown",
    board: str = "unknown",
    brand: str = "unknown",
) -> WigleExportResult:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8", newline="") as output:
        row_count = write_wigle_csv(
            database_url,
            output,
            batch_id=batch_id,
            capabilities=capabilities,
            app_release=app_release,
            model=model,
            release=release,
            device=device,
            display=display,
            board=board,
            brand=brand,
        )

    csv_sha256 = _sha256_path(output_path)
    file_size = output_path.stat().st_size
    with db.session(database_url) as session:
        session.cursor.execute(
            """
            update wigle_upload_batches
            set
              status = 'exported',
              filename = $2,
              csv_sha256 = $3,
              row_count = $4,
              exported_at = coalesce(exported_at, now()),
              error = null
            where id = $1
            """,
            [batch_id, output_path.name, csv_sha256, row_count],
        )

    return WigleExportResult(
        row_count=row_count,
        csv_sha256=csv_sha256,
        file_size=file_size,
    )


def upload_wigle_file(
    *,
    file_path: Path,
    auth_header: str,
    donate: bool = False,
    timeout_seconds: int = 120,
) -> dict[str, Any]:
    boundary = f"----cluetooth-wigle-{uuid.uuid4().hex}"
    body = _multipart_body(file_path=file_path, boundary=boundary, donate=donate)
    req = request.Request(
        WIGLE_UPLOAD_URL,
        data=body,
        headers={
            "Authorization": auth_header,
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Content-Length": str(len(body)),
        },
        method="POST",
    )
    return _request_json(req, timeout_seconds=timeout_seconds)


def fetch_wigle_transactions(
    *,
    auth_header: str,
    page_start: int = 0,
    page_end: int = 100,
    timeout_seconds: int = 120,
) -> dict[str, Any]:
    query = parse.urlencode({"pagestart": page_start, "pageend": page_end})
    req = request.Request(
        f"{WIGLE_TRANSACTIONS_URL}?{query}",
        headers={"Authorization": auth_header},
        method="GET",
    )
    return _request_json(req, timeout_seconds=timeout_seconds)


def mark_wigle_batch_uploading(database_url: str, *, batch_id: int) -> None:
    with db.session(database_url) as session:
        session.cursor.execute(
            """
            update wigle_upload_batches
            set status = 'uploading', error = null
            where id = $1
            """,
            [batch_id],
        )


def mark_wigle_batch_uploaded(
    database_url: str,
    *,
    batch_id: int,
    response: dict[str, Any],
) -> tuple[str, ...]:
    transids = _extract_upload_transids(response)
    with db.session(database_url) as session:
        session.cursor.execute(
            """
            update wigle_upload_batches
            set
              status = 'uploaded',
              wigle_transids = $2::jsonb,
              upload_response = $3::jsonb,
              uploaded_at = now(),
              error = null
            where id = $1
            """,
            [batch_id, json.dumps(list(transids)), json.dumps(response)],
        )
    return transids


def mark_wigle_batch_failed(
    database_url: str,
    *,
    batch_id: int,
    error_message: str,
) -> None:
    with db.session(database_url) as session:
        session.cursor.execute(
            """
            update wigle_upload_batches
            set status = 'failed', error = $2
            where id = $1
            """,
            [batch_id, error_message],
        )


def update_wigle_batch_statuses(
    database_url: str,
    *,
    transactions_response: dict[str, Any],
) -> int:
    results = transactions_response.get("results")
    if not isinstance(results, list):
        return 0

    translogs_by_id: dict[str, dict[str, Any]] = {}
    for result in results:
        if not isinstance(result, dict):
            continue
        transid = result.get("transid")
        if isinstance(transid, str):
            translogs_by_id[transid] = result

    if not translogs_by_id:
        return 0

    updated = 0
    with db.session(database_url) as session:
        batch_rows = _read_database_dicts(
            database_url,
            """
            select id, wigle_transids::text as wigle_transids
            from wigle_upload_batches
            where status in ('uploaded', 'completed')
            """,
        )

        for row in batch_rows:
            batch_id = int(row["id"])
            transids = _parse_transids(row["wigle_transids"])
            matches = [
                translogs_by_id[transid]
                for transid in transids
                if transid in translogs_by_id
            ]
            if not matches:
                continue

            wigle_status = ",".join(
                str(match.get("status", "")).strip()
                for match in matches
                if match.get("status") is not None
            )
            status = (
                "completed"
                if all(_translog_completed(m) for m in matches)
                else "uploaded"
            )
            completed_sql = "now()" if status == "completed" else "completed_at"
            session.cursor.execute(
                f"""
                update wigle_upload_batches
                set
                  status = $2,
                  wigle_status = nullif($3, ''),
                  status_response = $4::jsonb,
                  status_checked_at = now(),
                  completed_at = {completed_sql}
                where id = $1
                """,
                [
                    batch_id,
                    status,
                    wigle_status,
                    json.dumps({"matches": matches}),
                ],
            )
            updated += 1

    return updated


def _preheader_row(
    *,
    app_release: str,
    model: str,
    release: str,
    device: str,
    display: str,
    board: str,
    brand: str,
) -> list[str]:
    return [
        "WigleWifi-1.6",
        f"appRelease={app_release}",
        f"model={model}",
        f"release={release}",
        f"device={device}",
        f"display={display}",
        f"board={board}",
        f"brand={brand}",
        "star=Sol",
        "body=3",
        "subBody=0",
    ]


def _write_preheader_csv(output: TextIO, row: list[str]) -> None:
    schema = [f"column_{index}" for index in range(len(row))]
    pl.DataFrame([row], schema=schema, orient="row").write_csv(
        output,
        include_header=False,
        null_value="",
    )


# One WiGLE row per address per minute, whatever the payloads, sessions,
# uploaders or PDU kinds behind it. `scope` is the set of minute rows the CSV
# covers; the row carries
#   * the position, RSSI, accuracy and time of the best located sample among
#     them, ranked as the minute table ranks its samples (higher score, then
#     smaller accuracy, then earlier);
#   * the most recent non-empty local name of any of them (latest last_seen);
#   * the first manufacturer id of the best row's payload.
_WIGLE_COLLAPSE = """
        names as (
          select
            s.minute,
            s.addr,
            (
              array_agg(f.local_name order by s.last_seen desc nulls last, s.id desc)
                filter (where nullif(f.local_name, '') is not null)
            )[1] as local_name
          from scope s
          join payload_fields f
            on f.payload_id = s.payload_id
          group by s.minute, s.addr
        ),
        ranked as (
          select
            s.*,
            f.manufacturer_ids,
            row_number() over (
              partition by s.minute, s.addr
              order by
                (s.location is null or s.best_rssi is null),
                s.best_score desc nulls last,
                greatest(coalesce(s.best_accuracy, 50), 1),
                s.best_seen,
                s.id
            ) as rank
          from scope s
          join payload_fields f
            on f.payload_id = s.payload_id
        )
        select
          r.addr::text as "MAC",
          coalesce(n.local_name, '') as "SSID",
          {auth_mode}::text as "AuthMode",
          to_char(r.best_seen at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS') as "FirstSeen",
          '0' as "Channel",
          '' as "Frequency",
          r.best_rssi::text as "RSSI",
          st_y(r.location::geometry)::text as "CurrentLatitude",
          st_x(r.location::geometry)::text as "CurrentLongitude",
          '' as "AltitudeMeters",
          coalesce(r.best_accuracy::text, '') as "AccuracyMeters",
          '' as "RCOIs",
          coalesce(r.manufacturer_ids[1]::text, '') as "MfgrId",
          'BLE' as "Type"
        from ranked r
        join names n
          on n.minute = r.minute
         and n.addr = r.addr
        where r.rank = 1
          and r.location is not null
          and r.best_rssi is not null
        order by r.minute, r.addr
"""

# Is any minute row of the address-minute of `m` in an active batch? One member
# claims the whole address-minute, so a row merged in later never re-uploads it.
_WIGLE_NOT_CLAIMED = """
            select 1
            from sighting_minutes g
            join wigle_upload_batch_minutes bm
              on bm.minute_id = g.id
             and bm.minute = g.minute
            join wigle_upload_batches b
              on b.id = bm.batch_id
            where g.minute = m.minute
              and g.addr = m.addr
              and b.status in (
                'created',
                'exported',
                'uploading',
                'uploaded',
                'completed'
              )
"""

# Serializes batch creation (a transaction-level advisory lock).
_WIGLE_BATCH_LOCK = "select true as locked from pg_advisory_xact_lock(4381022502)"


# Pending address-minutes (or all of them, with $3), `$2` of them at most (0 for
# no limit), every minute row of each as the scope.
def _pending_wigle_rows_sql() -> str:
    return f"""
        with groups as (
          select m.minute, m.addr
          from sighting_minutes m
          where m.location is not null
            and m.best_rssi is not null
            and (
              $3::boolean
              or not exists ({_WIGLE_NOT_CLAIMED})
            )
          group by m.minute, m.addr
          order by m.minute, m.addr
          limit nullif($2::bigint, 0)
        ),
        scope as (
          select m.*
          from groups
          join sighting_minutes m
            on m.minute = groups.minute
           and m.addr = groups.addr
        ),
        {_WIGLE_COLLAPSE.format(auth_mode="$1")}
    """


def _count_pending_wigle_rows_sql() -> str:
    return f"""
        select count(*)::bigint as row_count
        from (
          select 1
          from sighting_minutes m
          where m.location is not null
            and m.best_rssi is not null
            and not exists ({_WIGLE_NOT_CLAIMED})
          group by m.minute, m.addr
        ) pending
    """


def _batch_wigle_rows_sql() -> str:
    return f"""
        with scope as (
          select m.*
          from wigle_upload_batch_minutes bm
          join sighting_minutes m
            on m.id = bm.minute_id
           and m.minute = bm.minute
          where bm.batch_id = $1
        ),
        {_WIGLE_COLLAPSE.format(auth_mode="$2")}
    """


def _read_database_frame(
    database_url: str,
    query: str,
    parameters: Sequence[Any] | None = None,
) -> pl.DataFrame:
    execute_options: dict[str, Any] | None = None
    if parameters is not None:
        execute_options = {"parameters": list(parameters)}

    return pl.read_database_uri(
        query,
        database_url,
        engine="adbc",
        execute_options=execute_options,
    )


def _read_database_dicts(
    database_url: str,
    query: str,
    parameters: Sequence[Any] | None = None,
) -> list[dict[str, Any]]:
    return _read_database_frame(database_url, query, parameters).to_dicts()


def _batch_from_row(row: Mapping[str, Any]) -> WigleBatch:
    return WigleBatch(
        id=int(row["id"]),
        status=str(row["status"]),
        row_count=int(row["row_count"]),
        min_item_id=None if row["min_item_id"] is None else int(row["min_item_id"]),
        max_item_id=None if row["max_item_id"] is None else int(row["max_item_id"]),
        filename=None if row["filename"] is None else str(row["filename"]),
        csv_sha256=None if row["csv_sha256"] is None else str(row["csv_sha256"]),
        wigle_status=None if row["wigle_status"] is None else str(row["wigle_status"]),
        wigle_transids=_parse_transids(row["wigle_transids"]),
    )


def _parse_transids(raw: Any) -> tuple[str, ...]:
    if raw is None:
        return ()
    parsed: Any = json.loads(str(raw)) if isinstance(raw, str) else raw
    if not isinstance(parsed, list):
        return ()
    return tuple(value for value in parsed if isinstance(value, str))


def _sha256_path(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _multipart_body(*, file_path: Path, boundary: str, donate: bool) -> bytes:
    parts: list[bytes] = []
    parts.extend(
        [
            f"--{boundary}\r\n".encode("ascii"),
            (
                'Content-Disposition: form-data; name="file"; '
                f'filename="{file_path.name}"\r\n'
            ).encode("utf-8"),
            b"Content-Type: text/csv; charset=utf-8\r\n\r\n",
            file_path.read_bytes(),
            b"\r\n",
        ]
    )

    if donate:
        parts.extend(
            [
                f"--{boundary}\r\n".encode("ascii"),
                b'Content-Disposition: form-data; name="donate"\r\n\r\n',
                b"on\r\n",
            ]
        )

    parts.append(f"--{boundary}--\r\n".encode("ascii"))
    return b"".join(parts)


def _request_json(req: request.Request, *, timeout_seconds: int) -> dict[str, Any]:
    try:
        with request.urlopen(req, timeout=timeout_seconds) as response:
            body = response.read()
    except error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise WigleUploadError(f"WiGLE HTTP {exc.code}: {body}") from exc
    except error.URLError as exc:
        raise WigleUploadError(f"WiGLE request failed: {exc}") from exc

    try:
        decoded = json.loads(body.decode("utf-8"))
    except json.JSONDecodeError as exc:
        text = body.decode("utf-8", errors="replace")
        raise WigleUploadError(f"WiGLE returned non-JSON response: {text}") from exc

    if not isinstance(decoded, dict):
        raise WigleUploadError("WiGLE returned a JSON response that was not an object")

    return decoded


def _extract_upload_transids(response: dict[str, Any]) -> tuple[str, ...]:
    results = response.get("results")
    if not isinstance(results, dict):
        return ()
    raw_transids = results.get("transids")
    if not isinstance(raw_transids, list):
        return ()

    transids: list[str] = []
    for item in raw_transids:
        if not isinstance(item, dict):
            continue
        value = item.get("transId") or item.get("transid")
        if isinstance(value, str):
            transids.append(value)
    return tuple(transids)


def _translog_completed(translog: dict[str, Any]) -> bool:
    percent_done = translog.get("percentDone")
    if isinstance(percent_done, int | float) and percent_done >= 100:
        return True

    status = translog.get("status")
    if not isinstance(status, str):
        return False

    lowered = status.lower()
    return "complete" in lowered or "done" in lowered or "success" in lowered

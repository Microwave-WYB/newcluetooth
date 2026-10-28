"""The minute merge must stay linear in the batch size (hash or merge joins)."""

from __future__ import annotations

import time

import cluetooth_sync.pipeline.db as db
from cluetooth_sync.pipeline.queries import stage_query
from cluetooth_sync.pipeline.sightings import create_batch_table

KEYS = 20_000


def _load_batch(session: db.DatabaseSession) -> str:
    """A realistic batch: KEYS distinct keys (addresses), mixed PDU kinds, some nulls."""
    cursor = session.cursor
    cursor.execute(
        "insert into payloads (sha256, structure_ids) "
        "select sha256(int4send(g)), '{}' from generate_series(1, 50) g "
        "on conflict do nothing"
    )
    cursor.execute(
        "insert into blobs (uri) values ('gs://plan/blob') "
        "on conflict (uri) do update set uri = excluded.uri returning id"
    )
    blob_id = int(cursor.fetch_polars().item(0, "id"))
    cursor.execute(
        "insert into uploaders (uploader) values ('plan') "
        "on conflict (uploader) do update set uploader = excluded.uploader returning id"
    )
    uploader_id = int(cursor.fetch_polars().item(0, "id"))
    batch = create_batch_table(session)
    cursor.execute(
        f"""
        insert into {batch} (
          scanned_at, addr, payload_id, uploader_id, session_id, blob_id, rssi,
          lat, lon, accuracy, is_legacy, primary_phy, secondary_phy, adv_sid,
          data_status, addr_type
        )
        select
          timestamptz '2031-04-01 00:00:00+00'
            + (g % 1000) * interval '1 minute' + (g % 60) * interval '1 second',
          lpad(to_hex(g), 12, '0')::macaddr,
          (select min(id) from payloads where sha256 = sha256(int4send(1 + g % 50))),
          case when g % 3 = 0 then null else {uploader_id} end,
          case when g % 5 = 0 then null else gen_random_uuid() end,
          {blob_id},
          -90 + g % 50,
          32.0 + (g % 100) / 1000.0, -117.0, 5.0,
          case when g % 4 = 0 then null else g % 2 = 0 end,
          case when g % 4 = 0 then null else 1 + (g % 2) * 2 end,
          case when g % 4 = 0 then null else g % 4 end,
          case when g % 7 = 0 then null else g % 16 end,
          case when g % 4 = 0 then null else 0 end,
          case when g % 4 = 0 then null else g % 2 end
        from generate_series(1, {KEYS}) g
        """
    )
    return batch


def test_merge_plan_uses_hash_or_merge_joins_and_runs_fast(
    clean_database_url: str,
) -> None:
    with db.session(clean_database_url) as session:
        batch = _load_batch(session)
        merge = stage_query("sightings/merge_minutes.sql", batch=batch)

        # ADBC wraps result-returning statements in COPY, which cannot take
        # EXPLAIN of an INSERT; a function returning the plan lines can.
        session.cursor.execute(
            "create function pg_temp.plan_of(q text) returns setof text as $$ "
            "begin return query execute 'explain (costs off) ' || q; end; "
            "$$ language plpgsql"
        )
        session.cursor.execute("select pg_temp.plan_of($1) as line", [merge])
        plan = "\n".join(session.cursor.fetch_polars().get_column("line"))
        assert "Nested Loop" not in plan, plan
        assert "Hash Join" in plan or "Merge Join" in plan, plan

        started = time.monotonic()
        session.cursor.execute(merge)
        elapsed = time.monotonic() - started
        session.cursor.execute("select count(*) as n from sighting_minutes")
        assert session.cursor.fetch_polars().item(0, "n") == KEYS
    print(f"merge of {KEYS} keys: {elapsed:.2f}s")
    # The nested-loop plan took ~8 s for this batch; the hash plan is milliseconds
    # to a few hundred. A loose bound keeps the test stable on slow machines.
    assert elapsed < 4, f"merging {KEYS} keys took {elapsed:.1f}s"

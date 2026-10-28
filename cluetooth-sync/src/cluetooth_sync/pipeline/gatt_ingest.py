"""Write one validated gatt/v1 discovery: stage, payload dictionary, tables.

One transaction per object, like scans: the trigger payload, the discovery row,
its attributes and `success = true` commit together.
"""

import polars as pl
from polars._typing import SchemaDict

import cluetooth_sync.pipeline.db as db
from cluetooth_sync.pipeline.gatt_v1 import GattDiscovery
from cluetooth_sync.pipeline.ingest import ensure_blob, run_with_retries, write_stage
from cluetooth_sync.pipeline.queries import read_query, stage_query
from cluetooth_sync.pipeline.sightings import create_raws_table, store_payloads

_DISCOVERY_SCHEMA: SchemaDict = {
    "discovery_uuid": pl.String,
    "session_id": pl.String,
    "app_version": pl.String,
    "device_model": pl.String,
    "android_version": pl.String,
    "addr": pl.String,
    "addr_type": pl.String,
    "trigger_raw": pl.String,
    "trigger_rssi": pl.Int64,
    "trigger_scanned_at": pl.Datetime(time_unit="us", time_zone="UTC"),
    "trigger_is_legacy": pl.Boolean,
    "trigger_primary_phy": pl.Int64,
    "trigger_secondary_phy": pl.Int64,
    "trigger_adv_sid": pl.Int64,
    "started_at": pl.Datetime(time_unit="us", time_zone="UTC"),
    "ended_at": pl.Datetime(time_unit="us", time_zone="UTC"),
    "lat": pl.Float64,
    "lon": pl.Float64,
    "accuracy": pl.Float32,
    "outcome": pl.String,
    "stage_reached": pl.String,
    "error_stage": pl.String,
    "error_status": pl.Int64,
    "error_name": pl.String,
    "disconnect_initiator": pl.String,
    "disconnect_status": pl.Int64,
    "mtu": pl.Int64,
    "phy_tx": pl.Int64,
    "phy_rx": pl.Int64,
    "connect_ms": pl.Int64,
    "mtu_ms": pl.Int64,
    "discover_ms": pl.Int64,
    "read_ms": pl.Int64,
    "profile_hash": pl.String,
    "raw_json": pl.String,
}
_ATTRIBUTE_SCHEMA: SchemaDict = {
    "attribute_id": pl.Int64,
    "parent_attribute_id": pl.Int64,
    "kind": pl.String,
    "uuid": pl.String,
    "is_primary": pl.Boolean,
    "instance": pl.Int64,
    "properties": pl.String,
    "value_hex": pl.String,
    "read_status": pl.String,
}


def ingest_gatt_discovery(
    database_url: str,
    discovery: GattDiscovery,
    *,
    mark_failure: bool = True,
    skip_if_succeeded: bool = False,
) -> int:
    """Insert one discovery; return 1, or 0 if the object was already ingested."""
    return run_with_retries(
        database_url,
        discovery.uri,
        lambda: _ingest_once(database_url, discovery, skip_if_succeeded),
        mark_failure=mark_failure,
    )


def discovery_stage(discovery: GattDiscovery) -> pl.DataFrame:
    trigger = discovery.trigger
    row = {
        "discovery_uuid": discovery.discovery_uuid,
        "session_id": discovery.session_id,
        "app_version": discovery.app_version,
        "device_model": discovery.device_model,
        "android_version": discovery.android_version,
        "addr": discovery.addr,
        "addr_type": discovery.addr_type,
        "trigger_raw": None if trigger is None else trigger.raw_hex,
        "trigger_rssi": None if trigger is None else trigger.rssi,
        "trigger_scanned_at": None if trigger is None else trigger.scanned_at,
        "trigger_is_legacy": None if trigger is None else trigger.is_legacy,
        "trigger_primary_phy": None if trigger is None else trigger.primary_phy,
        "trigger_secondary_phy": None if trigger is None else trigger.secondary_phy,
        "trigger_adv_sid": None if trigger is None else trigger.adv_sid,
        "started_at": discovery.started_at,
        "ended_at": discovery.ended_at,
        "lat": discovery.lat,
        "lon": discovery.lon,
        "accuracy": discovery.accuracy,
        "outcome": discovery.outcome,
        "stage_reached": discovery.stage_reached,
        "error_stage": discovery.error_stage,
        "error_status": discovery.error_status,
        "error_name": discovery.error_name,
        "disconnect_initiator": discovery.disconnect_initiator,
        "disconnect_status": discovery.disconnect_status,
        "mtu": discovery.mtu,
        "phy_tx": discovery.phy_tx,
        "phy_rx": discovery.phy_rx,
        "connect_ms": discovery.connect_ms,
        "mtu_ms": discovery.mtu_ms,
        "discover_ms": discovery.discover_ms,
        "read_ms": discovery.read_ms,
        "profile_hash": discovery.profile_hash,
        "raw_json": discovery.raw_json,
    }
    return pl.DataFrame([row], schema=_DISCOVERY_SCHEMA)


def attribute_stage(discovery: GattDiscovery) -> pl.DataFrame:
    return pl.DataFrame(
        [
            {
                "attribute_id": attribute.attribute_id,
                "parent_attribute_id": attribute.parent_attribute_id,
                "kind": attribute.kind,
                "uuid": attribute.uuid,
                "is_primary": attribute.is_primary,
                "instance": attribute.instance,
                "properties": None
                if attribute.properties is None
                else ",".join(attribute.properties),
                "value_hex": attribute.value_hex,
                "read_status": attribute.read_status,
            }
            for attribute in discovery.attributes
        ],
        schema=_ATTRIBUTE_SCHEMA,
    )


def _ingest_once(
    database_url: str, discovery: GattDiscovery, skip_if_succeeded: bool
) -> int:
    with db.session(database_url) as session:
        session.cursor.execute(read_query("sightings/ingest_lock.sql"))
        session.cursor.fetch_polars()
        blob_id, already_succeeded = ensure_blob(
            session,
            discovery.uri,
            None,
            discovery.app_version,
            discovery.session_id,
            discovery.meta,
        )
        if skip_if_succeeded and already_succeeded:
            # Another sync process committed this object after our discovery.
            print(f"skip {discovery.uri}: already ingested", flush=True)
            return 0

        discovery_table = write_stage(session, discovery_stage(discovery))
        raws = create_raws_table(session)
        session.cursor.execute(
            stage_query(
                "gatt/collect_trigger_raw.sql", raws=raws, stage=discovery_table
            )
        )
        store_payloads(session, raws)
        session.cursor.execute(
            stage_query("gatt/insert_discovery.sql", stage=discovery_table),
            [blob_id],
        )
        discovery_id = int(session.cursor.fetch_polars().item(0, "id"))
        if discovery.attributes:
            attribute_table = write_stage(session, attribute_stage(discovery))
            session.cursor.execute(
                stage_query("gatt/insert_attributes.sql", stage=attribute_table),
                [discovery_id],
            )
        session.cursor.execute(read_query("blobs/mark_succeeded.sql"), [discovery.uri])
    return 1

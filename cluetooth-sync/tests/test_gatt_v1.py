from __future__ import annotations

import copy
import hashlib
import json
from typing import Any

import polars as pl
import pytest
from nacl.public import PrivateKey, SealedBox

from cluetooth_sync.pipeline import (
    gatt_ingest,
    parse_gatt_v1_bytes,
    route_payload_uri,
)
from cluetooth_sync.pipeline.gatt_v1 import normalize_uuid
from cluetooth_sync.pipeline.orchestrate import BlobBytes, _ingest_blob

PRIVATE_KEY = PrivateKey(bytes(range(32)))
TRIGGER_RAW = "02010605094e616d6505ff4c000102"


def _uri(suffix: str) -> str:
    return (
        "gs://test-bucket/gatt/v1/2025/03/24/"
        f"0195c920-7c00-7abc-8def-0123456789{suffix}.json.encrypted"
    )


def _id(uri: str) -> str:
    return uri.rsplit("/", 1)[-1].removesuffix(".json.encrypted")


def _discovery(uri: str, addr: str = "AA:00:00:04:00:01") -> dict[str, Any]:
    """A complete discovery object as the contract in #23 describes it."""
    return {
        "schema": "gatt/v1",
        "id": _id(uri),
        "session_id": "7f0e5b1c-0000-4000-8000-000000000002",
        "app_version": "0.2.0",
        "device": {"model": "Pixel 8", "android": "15"},
        "target": {
            "addr": addr,
            "addr_type": "random",
            "name": "ignored name",
            "trigger_adv": {
                "raw": TRIGGER_RAW,
                "rssi": -61,
                "scanned_at": "2026-10-27T12:00:00.250Z",
                "is_legacy": False,
                "primary_phy": 1,
                "secondary_phy": 2,
                "adv_sid": 1,
            },
        },
        "started_at": "2026-10-27T12:00:01.000Z",
        "ended_at": "2026-10-27T12:00:09.500+00:00",
        "location": {"lat": 32.8801, "lon": -117.234, "accuracy": 4},
        "outcome": "complete",
        "stage_reached": "disconnect",
        "error": None,
        "disconnect": {"initiator": "local", "status": 0},
        "mtu": 247,
        "phy": {"tx": 2, "rx": 2},
        "timings_ms": {"connect": 400, "mtu": 100, "discover": 900, "read": 2000},
        "services": [
            {
                "uuid": "180A",
                "primary": True,
                "instance": 12,
                "characteristics": [
                    {
                        "uuid": "2A29",
                        "instance": 14,
                        "properties": ["read"],
                        "value": {"hex": "4d6f64656c", "status": "ok"},
                        "descriptors": [
                            {"uuid": "2901", "value": {"hex": "00", "status": "ok"}}
                        ],
                    },
                    {
                        "uuid": "2A25",
                        "instance": 16,
                        "properties": ["read", "notify"],
                        "value": {"hex": "", "status": "insufficient_authentication"},
                        "descriptors": [
                            {
                                "uuid": "2902",
                                "value": {"hex": "", "status": "read_not_permitted"},
                            }
                        ],
                    },
                ],
            },
            {
                "uuid": "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
                "primary": True,
                "instance": 20,
                "characteristics": [
                    {
                        "uuid": "6E400003-B5A3-F393-E0A9-E50E24DCCA9E",
                        "instance": 22,
                        "properties": ["notify"],
                        "value": {"status": "not_read"},
                        "descriptors": [],
                    }
                ],
            },
        ],
    }


def _parse(document: dict[str, Any], uri: str):
    return parse_gatt_v1_bytes(json.dumps(document).encode(), route_payload_uri(uri))


def _ingest(database_url: str, uri: str, document: dict[str, Any]) -> int | None:
    ciphertext = SealedBox(PRIVATE_KEY.public_key).encrypt(
        json.dumps(document).encode()
    )
    return _ingest_blob(database_url, bytes(PRIVATE_KEY), BlobBytes(uri, ciphertext))


def _query(database_url: str, sql: str, *parameters: Any) -> list[dict[str, Any]]:
    return pl.read_database_uri(
        sql,
        database_url,
        engine="adbc",
        execute_options={"parameters": list(parameters)},
    ).to_dicts()


def test_complete_discovery_is_stored(database_url: str) -> None:
    uri = _uri("d1")
    assert _ingest(database_url, uri, _discovery(uri)) == 1

    [row] = _query(
        database_url,
        """
        select d.discovery_uuid::text as discovery_uuid, d.session_id::text as session_id,
               d.app_version, d.device_model, d.android_version, d.addr::text as addr,
               d.addr_type, d.trigger_rssi, d.trigger_is_legacy, d.trigger_primary_phy,
               d.trigger_secondary_phy, d.trigger_adv_sid,
               d.trigger_scanned_at::text as trigger_scanned_at,
               d.started_at::text as started_at, d.ended_at::text as ended_at,
               d.lat, d.lon, d.accuracy, st_astext(d.location) as location,
               d.outcome, d.stage_reached, d.error_status, d.disconnect_initiator,
               d.disconnect_status, d.mtu, d.phy_tx, d.phy_rx, d.connect_ms, d.mtu_ms,
               d.discover_ms, d.read_ms, d.profile_hash,
               d.raw ->> 'schema' as raw_schema,
               d.raw -> 'target' ->> 'name' as raw_name,
               encode(payload_raw(d.trigger_payload_id), 'hex') as trigger_raw,
               b.success, b.app_version as blob_app_version, b.meta ->> 'device_model' as blob_model
        from gatt_discoveries d
        join blobs b on b.id = d.blob_id
        where b.uri = $1
        """,
        uri,
    )
    assert row == {
        "discovery_uuid": _id(uri),
        "session_id": "7f0e5b1c-0000-4000-8000-000000000002",
        "app_version": "0.2.0",
        "device_model": "Pixel 8",
        "android_version": "15",
        "addr": "aa:00:00:04:00:01",
        "addr_type": "random",
        "trigger_rssi": -61,
        "trigger_is_legacy": False,
        "trigger_primary_phy": 1,
        "trigger_secondary_phy": 2,
        "trigger_adv_sid": 1,
        "trigger_scanned_at": "2026-10-27 12:00:00.25+00",
        "started_at": "2026-10-27 12:00:01+00",
        "ended_at": "2026-10-27 12:00:09.5+00",
        "lat": 32.8801,
        "lon": -117.234,
        "accuracy": 4.0,
        "location": "POINT(-117.234 32.8801)",
        "outcome": "complete",
        "stage_reached": "disconnect",
        "error_status": None,
        "disconnect_initiator": "local",
        "disconnect_status": 0,
        "mtu": 247,
        "phy_tx": 2,
        "phy_rx": 2,
        "connect_ms": 400,
        "mtu_ms": 100,
        "discover_ms": 900,
        "read_ms": 2000,
        "profile_hash": _parse(_discovery(uri), uri).profile_hash,
        "raw_schema": "gatt/v1",
        "raw_name": "ignored name",
        "trigger_raw": TRIGGER_RAW,
        "success": True,
        "blob_app_version": "0.2.0",
        "blob_model": "Pixel 8",
    }

    attributes = _query(
        database_url,
        """
        select a.attribute_id, a.parent_attribute_id, a.kind, a.uuid, a.is_primary,
               a.instance, a.properties, encode(a.value, 'hex') as value, a.read_status
        from gatt_attributes a
        join gatt_discoveries d on d.id = a.discovery_id
        join blobs b on b.id = d.blob_id
        where b.uri = $1
        order by a.attribute_id
        """,
        uri,
    )
    assert [
        (a["attribute_id"], a["parent_attribute_id"], a["kind"], a["uuid"])
        for a in attributes
    ] == [
        (1, None, "service", "180a"),
        (2, 1, "characteristic", "2a29"),
        (3, 2, "descriptor", "2901"),
        (4, 1, "characteristic", "2a25"),
        (5, 4, "descriptor", "2902"),
        (6, None, "service", "6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
        (7, 6, "characteristic", "6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
    ]
    assert attributes[0] | {"kind": None} == {
        "attribute_id": 1,
        "parent_attribute_id": None,
        "kind": None,
        "uuid": "180a",
        "is_primary": True,
        "instance": 12,
        "properties": None,
        "value": None,
        "read_status": None,
    }
    assert [(a["value"], a["read_status"]) for a in attributes[1:]] == [
        ("4d6f64656c", "ok"),
        ("00", "ok"),
        (None, "insufficient_authentication"),
        (None, "read_not_permitted"),
        (None, None),
        (None, "not_read"),
    ]
    assert attributes[1]["properties"] == ["read"]
    assert attributes[3]["properties"] == ["notify", "read"]
    assert attributes[2]["instance"] is None
    assert attributes[1]["instance"] == 14


def test_partial_discovery_keeps_what_was_collected(database_url: str) -> None:
    uri = _uri("d2")
    document = _discovery(uri)
    document.update(
        outcome="partial",
        stage_reached="read",
        error={"stage": "read", "status": 133, "name": "GATT_ERROR"},
        disconnect={"initiator": "remote", "status": 19},
    )
    document["services"][0]["characteristics"][1]["value"] = {
        "hex": "",
        "status": "timeout",
    }
    document["services"][1]["characteristics"][0]["value"] = {
        "hex": "",
        "status": "error:-3",
    }
    assert _ingest(database_url, uri, document) == 1

    [row] = _query(
        database_url,
        "select d.outcome, d.stage_reached, d.error_stage, d.error_status, d.error_name, "
        "d.disconnect_initiator, d.disconnect_status, d.profile_hash is not null as hashed "
        "from gatt_discoveries d join blobs b on b.id = d.blob_id where b.uri = $1",
        uri,
    )
    assert row == {
        "outcome": "partial",
        "stage_reached": "read",
        "error_stage": "read",
        "error_status": 133,
        "error_name": "GATT_ERROR",
        "disconnect_initiator": "remote",
        "disconnect_status": 19,
        "hashed": True,
    }
    statuses = _query(
        database_url,
        "select a.uuid, a.read_status from gatt_attributes a "
        "join gatt_discoveries d on d.id = a.discovery_id join blobs b on b.id = d.blob_id "
        "where b.uri = $1 and a.read_status is distinct from 'ok' and a.kind <> 'service' "
        "order by a.attribute_id",
        uri,
    )
    assert [(s["uuid"], s["read_status"]) for s in statuses] == [
        ("2a25", "timeout"),
        ("2902", "read_not_permitted"),
        ("6e400003-b5a3-f393-e0a9-e50e24dcca9e", "error:-3"),
    ]


def test_failed_discovery_without_services_or_optional_fields(
    database_url: str,
) -> None:
    uri = _uri("d3")
    document = {
        "schema": "gatt/v1",
        "id": _id(uri),
        "target": {"addr": "AA:00:00:04:00:03"},
        "started_at": "2026-10-27T12:00:01+00:00",
        "outcome": "failed",
        "stage_reached": "connect",
        "error": {"stage": "connect", "status": 133},
        "services": [],
    }
    assert _ingest(database_url, uri, document) == 1

    [row] = _query(
        database_url,
        """
        select d.session_id, d.app_version, d.device_model, d.addr_type,
               d.trigger_payload_id, d.trigger_rssi, d.ended_at, d.lat, d.lon,
               d.accuracy, d.location::text as location, d.error_name, d.disconnect_status,
               d.mtu, d.phy_tx, d.connect_ms, d.profile_hash,
               (select count(*) from gatt_attributes a where a.discovery_id = d.id) as attrs,
               b.app_version as blob_app_version, b.session_id as blob_session_id
        from gatt_discoveries d join blobs b on b.id = d.blob_id where b.uri = $1
        """,
        uri,
    )
    assert all(value is None for key, value in row.items() if key != "attrs")
    assert row["attrs"] == 0


def test_same_structure_with_different_values_has_the_same_profile_hash() -> None:
    uri_a, uri_b = _uri("e1"), _uri("e2")
    first = _discovery(uri_a)
    second = _discovery(uri_b, addr="AA:00:00:04:00:99")
    # Different values, statuses, instance ids, MTU, services' primary flag,
    # property order, UUID spelling and read outcome; same structure.
    second["services"][0]["characteristics"][0]["value"] = {
        "hex": "ffee",
        "status": "ok",
    }
    second["services"][0]["characteristics"][1]["properties"] = ["notify", "read"]
    second["services"][0]["characteristics"][1]["value"] = {
        "hex": "",
        "status": "timeout",
    }
    second["services"][0]["instance"] = 99
    second["services"][1]["primary"] = False
    second["services"][1]["uuid"] = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    second["services"][0]["uuid"] = "0000180a-0000-1000-8000-00805f9b34fb"
    second["mtu"] = 23
    second["outcome"] = "partial"

    hash_a = _parse(first, uri_a).profile_hash
    assert hash_a is not None and len(hash_a) == 64
    assert _parse(second, uri_b).profile_hash == hash_a


@pytest.mark.parametrize(
    "mutate",
    [
        lambda d: d["services"][0]["characteristics"].pop(),
        lambda d: d["services"].reverse(),
        lambda d: d["services"][0]["characteristics"][0]["properties"].append("write"),
        lambda d: d["services"][0]["characteristics"][0]["descriptors"].pop(),
        lambda d: d["services"][0].update(uuid="180F"),
        lambda d: d["services"][1]["characteristics"][0].update(uuid="2A00"),
    ],
)
def test_different_structure_has_a_different_profile_hash(mutate: Any) -> None:
    uri = _uri("e3")
    base = _parse(_discovery(uri), uri).profile_hash
    changed = _discovery(uri)
    mutate(changed)
    assert _parse(changed, uri).profile_hash != base


def test_profile_hash_is_documented_sha256_of_canonical_structure() -> None:
    uri = _uri("e4")
    document = _discovery(uri)
    document["services"] = [
        {
            "uuid": "180A",
            "characteristics": [
                {
                    "uuid": "2A29",
                    "properties": ["read"],
                    "descriptors": [{"uuid": "2901"}],
                }
            ],
        }
    ]
    expected = hashlib.sha256(b'[["180a",[["2a29",["read"],["2901"]]]]]').hexdigest()
    assert _parse(document, uri).profile_hash == expected


def test_profile_hash_is_stored_and_groups_devices(database_url: str) -> None:
    uris = [_uri("e5"), _uri("e6")]
    for uri, addr in zip(uris, ["AA:00:00:04:00:11", "AA:00:00:04:00:12"], strict=True):
        document = _discovery(uri, addr=addr)
        document["services"][0]["characteristics"][0]["value"] = {
            "hex": addr.replace(":", "")[-4:],
            "status": "ok",
        }
        assert _ingest(database_url, uri, document) == 1
    rows = _query(
        database_url,
        "select count(distinct d.profile_hash) as hashes, count(*) as n "
        "from gatt_discoveries d join blobs b on b.id = d.blob_id where b.uri in ($1, $2)",
        *uris,
    )
    assert rows == [{"hashes": 1, "n": 2}]


def test_trigger_payload_is_the_stored_payload_and_not_a_sighting(
    database_url: str,
) -> None:
    uri = _uri("d4")
    raw = "0201060b09756e69712d6761747464"
    document = _discovery(uri)
    document["target"]["trigger_adv"]["raw"] = raw.upper()
    assert _ingest(database_url, uri, document) == 1
    [row] = _query(
        database_url,
        "select p.sightings, encode(payload_raw(p.id), 'hex') as raw, "
        "(select count(*) from sighting_minutes m where m.payload_id = p.id) as minutes "
        "from gatt_discoveries d join blobs b on b.id = d.blob_id "
        "join payloads p on p.id = d.trigger_payload_id where b.uri = $1",
        uri,
    )
    assert row == {"sightings": 0, "raw": raw, "minutes": 0}


def test_ingesting_the_same_object_twice_is_skipped(database_url: str) -> None:
    uri = _uri("d5")
    document = _discovery(uri)
    assert _ingest(database_url, uri, document) == 1
    assert _ingest(database_url, uri, document) == 0
    assert _query(
        database_url,
        "select count(*) as n from gatt_discoveries d join blobs b on b.id = d.blob_id "
        "where b.uri = $1",
        uri,
    ) == [{"n": 1}]


def test_invalid_object_is_recorded_as_failed_and_writes_nothing(
    database_url: str,
) -> None:
    uri = _uri("d6")
    document = _discovery(uri)
    document["outcome"] = "mostly"
    assert _ingest(database_url, uri, document) is None
    [row] = _query(
        database_url,
        "select success, attempts, error from blobs where uri = $1",
        uri,
    )
    assert row["success"] is False and row["attempts"] == 1
    assert "outcome must be one of" in row["error"]
    assert _query(
        database_url,
        "select count(*) as n from gatt_discoveries d join blobs b on b.id = d.blob_id "
        "where b.uri = $1",
        uri,
    ) == [{"n": 0}]


def test_failure_inside_the_transaction_rolls_everything_back(
    database_url: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The database refuses an attribute after the discovery row was inserted."""
    uri = _uri("d7")
    document = _discovery(uri)
    unique_raw = "0201060a09726f6c6c6261636b"
    document["target"]["trigger_adv"]["raw"] = unique_raw
    real_stage = gatt_ingest.attribute_stage

    def bad_stage(discovery: Any) -> pl.DataFrame:
        return real_stage(discovery).with_columns(pl.lit("bogus").alias("kind"))

    monkeypatch.setattr(gatt_ingest, "attribute_stage", bad_stage)
    assert _ingest(database_url, uri, document) is None
    [row] = _query(
        database_url,
        "select b.success, (select count(*) from gatt_discoveries d where d.blob_id = b.id) "
        "as discoveries, (select count(*) from payloads p "
        "where sha256 = sha256(decode($2, 'hex'))) as payloads "
        "from blobs b where b.uri = $1",
        uri,
        unique_raw,
    )
    assert row == {"success": False, "discoveries": 0, "payloads": 0}


def _mutated(path: list[Any], value: Any) -> dict[str, Any]:
    uri = _uri("f1")
    document = _discovery(uri)
    cursor: Any = document
    for key in path[:-1]:
        if cursor[key] is None:
            cursor[key] = {}
        cursor = cursor[key]
    if value is _DELETE:
        del cursor[path[-1]]
    else:
        cursor[path[-1]] = value
    return document


_DELETE = object()
_CHAR = ["services", 0, "characteristics", 0]


@pytest.mark.parametrize(
    ("path", "value", "message"),
    [
        (["schema"], "gatt/v2", "schema must be"),
        (["schema"], _DELETE, "required gatt/v1 key schema"),
        (["id"], "0195c920-7c00-7abc-8def-0123456789ff", "does not match URI ID"),
        (["id"], _DELETE, "required gatt/v1 key id"),
        (["target"], _DELETE, "required gatt/v1 key target"),
        (["target", "addr"], "aa:00:00:04:00:01", "canonical uppercase"),
        (["target", "addr"], _DELETE, "required gatt/v1 key target.addr"),
        (["target", "addr_type"], "weird", "addr_type must be one of"),
        (["target", "trigger_adv", "raw"], "zz", "trigger_adv.raw must be"),
        (["target", "trigger_adv", "raw"], "", "trigger_adv.raw must be"),
        (["target", "trigger_adv", "raw"], "010", "trigger_adv.raw must be"),
        (["target", "trigger_adv", "primary_phy"], 2, "primary_phy must be one of"),
        (["target", "trigger_adv", "adv_sid"], 16, "adv_sid is out of range"),
        (["target", "trigger_adv", "scanned_at"], "yesterday", "not an ISO-8601"),
        (["started_at"], _DELETE, "required gatt/v1 key started_at"),
        (["started_at"], "2026-10-27T12:00:01", "must carry a UTC offset"),
        (["started_at"], 5, "started_at must be a string"),
        (["ended_at"], "2026-10-27T11:00:00Z", "ended_at must not be before"),
        (["outcome"], "mostly", "outcome must be one of"),
        (["outcome"], _DELETE, "required gatt/v1 key outcome"),
        (["stage_reached"], "pairing", "stage_reached must be one of"),
        (["stage_reached"], _DELETE, "required gatt/v1 key stage_reached"),
        (["error", "stage"], "pairing", "error.stage must be one of"),
        (["error"], [], "error must be a JSON object"),
        (["disconnect", "initiator"], "peer", "disconnect.initiator must be one of"),
        (["mtu"], 22, "mtu is out of range"),
        (["mtu"], 518, "mtu is out of range"),
        (["mtu"], "247", "mtu must be an integer"),
        (["mtu"], True, "mtu must be an integer"),
        (["phy", "tx"], 4, "phy.tx is out of range"),
        (["timings_ms", "read"], -1, "timings_ms.read is out of range"),
        (["session_id"], "not-a-uuid", "session_id must be a UUID"),
        (["location", "lat"], 91, "location.lat must be between"),
        (["location", "lon"], _DELETE, "must be present together"),
        (["location", "accuracy"], -1, "accuracy must be nonnegative"),
        (["location", "lat"], "north", "location.lat must be a number"),
        (["services"], _DELETE, "required gatt/v1 key services"),
        (["services"], {}, "services must be a JSON array"),
        (["services", 0, "uuid"], "xyz", "is not a 16-, 32- or 128-bit UUID"),
        (["services", 0, "uuid"], _DELETE, "services[0].uuid is missing"),
        (["services", 0, "primary"], "yes", "primary must be a boolean"),
        (["services", 0, "instance"], -1, "instance is out of range"),
        (_CHAR + ["uuid"], "2A2", "is not a 16-, 32- or 128-bit UUID"),
        (_CHAR + ["properties"], "read", "properties must be a JSON array"),
        (_CHAR + ["properties"], ["Read"], "is not a property name"),
        (_CHAR + ["value", "status"], "fine", "is not a known read status"),
        (_CHAR + ["value", "status"], _DELETE, "value.status is missing"),
        (_CHAR + ["value", "status"], "error:x", "is not a known read status"),
        (_CHAR + ["value", "hex"], "abc", "even-length hex"),
        (_CHAR + ["value", "hex"], "gg", "even-length hex"),
        (_CHAR + ["value", "hex"], "00" * 513, "longer than 512 bytes"),
        (_CHAR + ["value", "hex"], _DELETE, "hex is required when status is ok"),
        (_CHAR + ["descriptors", 0, "value", "status"], "timeout", "must be empty"),
    ],
)
def test_gatt_validation_failures(path: list[Any], value: Any, message: str) -> None:
    uri = _uri("f1")
    with pytest.raises(
        ValueError, match=message.replace("[", r"\[").replace("]", r"\]")
    ):
        _parse(_mutated(path, value), uri)


def test_non_ok_status_with_data_is_rejected() -> None:
    uri = _uri("f2")
    document = _discovery(uri)
    document["services"][0]["characteristics"][1]["value"] = {
        "hex": "aa",
        "status": "timeout",
    }
    with pytest.raises(ValueError, match="hex must be empty when status"):
        _parse(document, uri)


def test_gatt_rejects_malformed_json() -> None:
    uri = _uri("f3")
    route = route_payload_uri(uri)
    for payload in (
        b"",
        b"[]",
        b"not json",
        b"\xff\xfe",
        b'{"schema": "gatt/v1", "schema": "gatt/v1"}',
        b'{"mtu": NaN}',
    ):
        with pytest.raises(ValueError):
            parse_gatt_v1_bytes(payload, route)


def test_gatt_reader_refuses_scan_routes() -> None:
    route = route_payload_uri(
        "gs://test-bucket/scans/v3/2025/03/24/"
        "0195c920-7c00-7abc-8def-0123456789b1.parquet.encrypted"
    )
    with pytest.raises(ValueError, match="requires a gatt/v1 route"):
        parse_gatt_v1_bytes(b"{}", route)


def test_unknown_keys_are_kept_in_the_stored_json(database_url: str) -> None:
    uri = _uri("d9")
    document = _discovery(uri)
    document["future_field"] = {"x": 1}
    document["services"][0]["characteristics"][0]["note"] = "extra"
    assert _ingest(database_url, uri, copy.deepcopy(document)) == 1
    [row] = _query(
        database_url,
        "select d.raw -> 'future_field' ->> 'x' as x from gatt_discoveries d "
        "join blobs b on b.id = d.blob_id where b.uri = $1",
        uri,
    )
    assert row == {"x": "1"}


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("180A", "180a"),
        ("0000180A-0000-1000-8000-00805F9B34FB", "180a"),
        ("0000fe59-0000-1000-8000-00805f9b34fb", "fe59"),
        ("12345678-0000-1000-8000-00805f9b34fb", "12345678"),
        ("12345678", "12345678"),
        (
            "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
        ),
    ],
)
def test_uuid_notation_matches_ad_structures(value: str, expected: str) -> None:
    assert normalize_uuid(value, "uuid") == expected

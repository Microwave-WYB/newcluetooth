"""Validation of one `gatt/v1` JSON object (docs/payload-schema-v3.md).

The object is small, so it is read with the standard JSON parser and checked by
hand. Required keys are `schema`, `id`, `started_at`, `outcome`,
`stage_reached`, `target.addr` and `services`; every other key may be absent
(same as null) but must have the documented type when present. Unknown keys are
allowed and kept in the stored JSON.
"""

import hashlib
import json
import re
from dataclasses import dataclass
from datetime import UTC, datetime
from math import isfinite
from typing import Any

from .payload_route import PayloadRoute, PayloadRouteKind

SCHEMA = "gatt/v1"
OUTCOMES = ("complete", "partial", "failed", "cancelled")
STAGES = ("connect", "mtu", "discover", "read", "disconnect")
ADDR_TYPES = ("public", "random", "anonymous", "unknown")
DISCONNECT_INITIATORS = ("local", "remote", "timeout")
READ_STATUSES = (
    "ok",
    "insufficient_authentication",
    "insufficient_encryption",
    "read_not_permitted",
    "timeout",
    "not_read",
)
# ATT attribute values are at most 512 bytes.
MAX_VALUE_BYTES = 512

_CANONICAL_MAC = re.compile(r"^[0-9A-F]{2}(?::[0-9A-F]{2}){5}$")
_UUID_SHORT = re.compile(r"^(?:[0-9A-Fa-f]{4}|[0-9A-Fa-f]{8})$")
_UUID_128 = re.compile(
    r"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$"
)
_BLUETOOTH_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
_PROPERTY = re.compile(r"^[a-z][a-z0-9_]*$")
_ERROR_STATUS = re.compile(r"^error:-?[0-9]+$")
_HEX = re.compile(r"^(?:[0-9A-Fa-f]{2})*$")


@dataclass(frozen=True)
class GattTrigger:
    raw_hex: str
    rssi: int | None
    scanned_at: datetime | None
    is_legacy: bool | None
    primary_phy: int | None
    secondary_phy: int | None
    adv_sid: int | None


@dataclass(frozen=True)
class GattAttribute:
    attribute_id: int
    parent_attribute_id: int | None
    kind: str
    uuid: str
    is_primary: bool | None
    instance: int | None
    properties: list[str] | None
    value_hex: str | None
    read_status: str | None


@dataclass(frozen=True)
class GattDiscovery:
    uri: str
    discovery_uuid: str
    session_id: str | None
    app_version: str | None
    device_model: str | None
    android_version: str | None
    addr: str
    addr_type: str | None
    trigger: GattTrigger | None
    started_at: datetime
    ended_at: datetime | None
    lat: float | None
    lon: float | None
    accuracy: float | None
    outcome: str
    stage_reached: str
    error_stage: str | None
    error_status: int | None
    error_name: str | None
    disconnect_initiator: str | None
    disconnect_status: int | None
    mtu: int | None
    phy_tx: int | None
    phy_rx: int | None
    connect_ms: int | None
    mtu_ms: int | None
    discover_ms: int | None
    read_ms: int | None
    profile_hash: str | None
    attributes: list[GattAttribute]
    raw_json: str

    @property
    def meta(self) -> dict[str, str]:
        """`blobs.meta`: the same keys as scan v3 footers where they exist."""
        meta = {"payload_schema": SCHEMA, "payload_id": self.discovery_uuid}
        for key, value in (
            ("app_version", self.app_version),
            ("device_model", self.device_model),
            ("android_version", self.android_version),
        ):
            if value is not None:
                meta[key] = value
        return meta


def parse_gatt_v1_bytes(payload: bytes, route: PayloadRoute) -> GattDiscovery:
    if route.kind is not PayloadRouteKind.GATT_V1_JSON:
        raise ValueError("gatt/v1 reader requires a gatt/v1 route")
    text = payload.decode("utf-8")
    document = json.loads(
        text,
        object_pairs_hook=_no_duplicate_keys,
        parse_constant=_reject_constant,
    )
    document, stripped = _strip_nuls(document)
    if stripped:
        # PostgreSQL text and jsonb cannot hold NUL; the archive keeps the original.
        text = json.dumps(document, ensure_ascii=False)
    root = _object(document, "gatt/v1 object")
    if _required(root, "schema", "schema") != SCHEMA:
        raise ValueError(f"gatt/v1 schema must be {SCHEMA!r}")
    discovery_uuid = _string(_required(root, "id", "id"), "id")
    if discovery_uuid != route.payload_id:
        raise ValueError(
            f"gatt/v1 id {discovery_uuid!r} does not match URI ID {route.payload_id!r}"
        )

    device = _optional_object(root, "device")
    target = _object(_required(root, "target", "target"), "target")
    addr = _string(_required(target, "addr", "target.addr"), "target.addr")
    if _CANONICAL_MAC.fullmatch(addr) is None:
        raise ValueError("target.addr must be canonical uppercase XX:XX:XX:XX:XX:XX")
    started_at = _timestamp(_required(root, "started_at", "started_at"), "started_at")
    ended_at = _optional_timestamp(root, "ended_at")
    if ended_at is not None and ended_at < started_at:
        raise ValueError("ended_at must not be before started_at")

    lat, lon, accuracy = _location(root.get("location"))
    error = _optional_object(root, "error")
    disconnect = _optional_object(root, "disconnect")
    phy = _optional_object(root, "phy")
    timings = _optional_object(root, "timings_ms")
    services = _list(_required(root, "services", "services"), "services")
    attributes = _attributes(services)

    return GattDiscovery(
        uri=route.uri,
        discovery_uuid=discovery_uuid,
        session_id=_optional_uuid(root, "session_id"),
        app_version=_optional_string(root, "app_version"),
        device_model=_optional_string(device, "model", "device.model"),
        android_version=_optional_string(device, "android", "device.android"),
        addr=addr,
        addr_type=_optional_choice(target, "addr_type", ADDR_TYPES, "target.addr_type"),
        trigger=_trigger(target.get("trigger_adv")),
        started_at=started_at,
        ended_at=ended_at,
        lat=lat,
        lon=lon,
        accuracy=accuracy,
        outcome=_choice(_required(root, "outcome", "outcome"), OUTCOMES, "outcome"),
        stage_reached=_choice(
            _required(root, "stage_reached", "stage_reached"), STAGES, "stage_reached"
        ),
        error_stage=_optional_choice(error, "stage", STAGES, "error.stage"),
        error_status=_optional_int(error, "status", "error.status"),
        error_name=_optional_string(error, "name", "error.name"),
        disconnect_initiator=_optional_choice(
            disconnect, "initiator", DISCONNECT_INITIATORS, "disconnect.initiator"
        ),
        disconnect_status=_optional_int(disconnect, "status", "disconnect.status"),
        mtu=_optional_int(root, "mtu", "mtu", 23, 517),
        phy_tx=_optional_int(phy, "tx", "phy.tx", 1, 3),
        phy_rx=_optional_int(phy, "rx", "phy.rx", 1, 3),
        connect_ms=_optional_int(timings, "connect", "timings_ms.connect", 0),
        mtu_ms=_optional_int(timings, "mtu", "timings_ms.mtu", 0),
        discover_ms=_optional_int(timings, "discover", "timings_ms.discover", 0),
        read_ms=_optional_int(timings, "read", "timings_ms.read", 0),
        profile_hash=profile_hash(services_structure(attributes)),
        attributes=attributes,
        raw_json=text,
    )


def normalize_uuid(value: str, path: str) -> str:
    """Lowercase; Bluetooth Base UUIDs shortened to 4 (16-bit) or 8 (32-bit) digits."""
    if _UUID_SHORT.fullmatch(value) is not None:
        return value.lower()
    if _UUID_128.fullmatch(value) is None:
        raise ValueError(f"{path} is not a 16-, 32- or 128-bit UUID: {value!r}")
    value = value.lower()
    if value.endswith(_BLUETOOTH_BASE_SUFFIX):
        short = value[:8]
        return short[4:] if short.startswith("0000") else short
    return value


def services_structure(attributes: list[GattAttribute]) -> list[Any]:
    """Services, characteristics and descriptors as nested lists, without values.

    `[[service_uuid, [[characteristic_uuid, [property, ...], [descriptor_uuid, ...]],
    ...]], ...]` in document order, properties sorted. Instance ids and `primary`
    are not part of it.
    """
    structure: list[Any] = []
    for attribute in attributes:
        if attribute.kind == "service":
            structure.append([attribute.uuid, []])
        elif attribute.kind == "characteristic":
            structure[-1][1].append([attribute.uuid, attribute.properties, []])
        else:
            structure[-1][1][-1][2].append(attribute.uuid)
    return structure


def profile_hash(structure: list[Any]) -> str | None:
    """sha256 (hex) of the compact JSON of `structure`; None without services."""
    if not structure:
        return None
    canonical = json.dumps(structure, separators=(",", ":"), ensure_ascii=True)
    return hashlib.sha256(canonical.encode("ascii")).hexdigest()


def _attributes(services: list[Any]) -> list[GattAttribute]:
    attributes: list[GattAttribute] = []

    def add(parent: GattAttribute | None, **fields: Any) -> GattAttribute:
        attribute = GattAttribute(
            attribute_id=len(attributes) + 1,
            parent_attribute_id=None if parent is None else parent.attribute_id,
            **fields,
        )
        attributes.append(attribute)
        return attribute

    for service_index, service_value in enumerate(services):
        path = f"services[{service_index}]"
        service = _object(service_value, path)
        service_attr = add(
            None,
            kind="service",
            uuid=_uuid(_required(service, "uuid", f"{path}.uuid"), f"{path}.uuid"),
            is_primary=_optional_bool(service, "primary", f"{path}.primary"),
            instance=_optional_int(service, "instance", f"{path}.instance", 0),
            properties=None,
            value_hex=None,
            read_status=None,
        )
        characteristics = _optional_list(service, "characteristics", path)
        for char_index, char_value in enumerate(characteristics):
            char_path = f"{path}.characteristics[{char_index}]"
            characteristic = _object(char_value, char_path)
            value_hex, status = _value(
                characteristic.get("value"), f"{char_path}.value"
            )
            char_attr = add(
                service_attr,
                kind="characteristic",
                uuid=_uuid(
                    _required(characteristic, "uuid", f"{char_path}.uuid"),
                    f"{char_path}.uuid",
                ),
                is_primary=None,
                instance=_optional_int(
                    characteristic, "instance", f"{char_path}.instance", 0
                ),
                properties=_properties(characteristic, char_path),
                value_hex=value_hex,
                read_status=status,
            )
            descriptors = _optional_list(characteristic, "descriptors", char_path)
            for desc_index, desc_value in enumerate(descriptors):
                desc_path = f"{char_path}.descriptors[{desc_index}]"
                descriptor = _object(desc_value, desc_path)
                desc_hex, desc_status = _value(
                    descriptor.get("value"), f"{desc_path}.value"
                )
                add(
                    char_attr,
                    kind="descriptor",
                    uuid=_uuid(
                        _required(descriptor, "uuid", f"{desc_path}.uuid"),
                        f"{desc_path}.uuid",
                    ),
                    is_primary=None,
                    instance=None,
                    properties=None,
                    value_hex=desc_hex,
                    read_status=desc_status,
                )
    return attributes


def _properties(characteristic: dict[str, Any], path: str) -> list[str]:
    values = _optional_list(characteristic, "properties", path)
    properties: set[str] = set()
    for index, value in enumerate(values):
        item = _string(value, f"{path}.properties[{index}]")
        if _PROPERTY.fullmatch(item) is None:
            raise ValueError(f"{path}.properties[{index}] is not a property name")
        properties.add(item)
    return sorted(properties)


def _value(value: Any, path: str) -> tuple[str | None, str]:
    """(hex, read_status) of a characteristic or descriptor `value` object."""
    if value is None:
        return None, "not_read"
    obj = _object(value, path)
    status = _string(_required(obj, "status", f"{path}.status"), f"{path}.status")
    if status not in READ_STATUSES and _ERROR_STATUS.fullmatch(status) is None:
        raise ValueError(f"{path}.status is not a known read status: {status!r}")
    hex_value = obj.get("hex")
    if hex_value is not None:
        hex_value = _string(hex_value, f"{path}.hex")
        if _HEX.fullmatch(hex_value) is None:
            raise ValueError(f"{path}.hex is not an even-length hex string")
        if len(hex_value) > 2 * MAX_VALUE_BYTES:
            raise ValueError(f"{path}.hex is longer than {MAX_VALUE_BYTES} bytes")
    if status == "ok":
        if hex_value is None:
            raise ValueError(f"{path}.hex is required when status is ok")
        return hex_value.lower(), status
    if hex_value:
        raise ValueError(f"{path}.hex must be empty when status is {status!r}")
    return None, status


def _trigger(value: Any) -> GattTrigger | None:
    if value is None:
        return None
    obj = _object(value, "target.trigger_adv")
    raw = _string(_required(obj, "raw", "trigger_adv.raw"), "trigger_adv.raw")
    if not raw or _HEX.fullmatch(raw) is None:
        raise ValueError("trigger_adv.raw must be a non-empty even-length hex string")
    rssi = _optional_int(obj, "rssi", "trigger_adv.rssi", -32768, 32767)
    adv_sid = _optional_int(obj, "adv_sid", "trigger_adv.adv_sid", 0, 255)
    if adv_sid is not None and 15 < adv_sid < 255:
        raise ValueError(f"trigger_adv.adv_sid is out of range: {adv_sid}")
    return GattTrigger(
        raw_hex=raw.lower(),
        # Same absent-value rules as scan rows: rssi 127 and adv_sid 255 are null.
        rssi=None if rssi == 127 else rssi,
        scanned_at=_optional_timestamp(obj, "scanned_at", "trigger_adv.scanned_at"),
        is_legacy=_optional_bool(obj, "is_legacy", "trigger_adv.is_legacy"),
        primary_phy=_optional_choice_int(obj, "primary_phy", (1, 3), "trigger_adv"),
        secondary_phy=_optional_int(
            obj, "secondary_phy", "trigger_adv.secondary_phy", 0, 3
        ),
        adv_sid=None if adv_sid == 255 else adv_sid,
    )


def _location(value: Any) -> tuple[float | None, float | None, float | None]:
    if value is None:
        return None, None, None
    obj = _object(value, "location")
    lat = _optional_number(obj, "lat", "location.lat")
    lon = _optional_number(obj, "lon", "location.lon")
    accuracy = _optional_number(obj, "accuracy", "location.accuracy")
    if (lat is None) != (lon is None):
        raise ValueError("location.lat and location.lon must be present together")
    if lat is not None and not -90.0 <= lat <= 90.0:
        raise ValueError("location.lat must be between -90 and 90")
    if lon is not None and not -180.0 <= lon <= 180.0:
        raise ValueError("location.lon must be between -180 and 180")
    if accuracy is not None and lat is None:
        raise ValueError("location.accuracy requires coordinates")
    if accuracy is not None and accuracy < 0.0:
        raise ValueError("location.accuracy must be nonnegative")
    return lat, lon, accuracy


def _strip_nuls(value: Any) -> tuple[Any, bool]:
    """Remove NUL characters from every string value (as the scan path does for
    names); a NUL in an object key is rejected."""
    if isinstance(value, str):
        return value.replace("\x00", ""), "\x00" in value
    if isinstance(value, list):
        items = [_strip_nuls(item) for item in value]
        return [item for item, _ in items], any(changed for _, changed in items)
    if isinstance(value, dict):
        if any("\x00" in key for key in value):
            raise ValueError("JSON object keys must not contain NUL")
        entries = {key: _strip_nuls(item) for key, item in value.items()}
        return (
            {key: item for key, (item, _) in entries.items()},
            any(changed for _, changed in entries.values()),
        )
    return value, False


def _no_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key {key!r}")
        result[key] = value
    return result


def _reject_constant(name: str) -> Any:
    raise ValueError(f"JSON constant {name} is not allowed")


def _required(obj: dict[str, Any], key: str, path: str) -> Any:
    if obj.get(key) is None:
        raise ValueError(f"required gatt/v1 key {path} is missing")
    return obj[key]


def _object(value: Any, path: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{path} must be a JSON object")
    return value


def _optional_object(obj: dict[str, Any], key: str) -> dict[str, Any]:
    value = obj.get(key)
    return {} if value is None else _object(value, key)


def _list(value: Any, path: str) -> list[Any]:
    if not isinstance(value, list):
        raise ValueError(f"{path} must be a JSON array")
    return value


def _optional_list(obj: dict[str, Any], key: str, path: str) -> list[Any]:
    value = obj.get(key)
    return [] if value is None else _list(value, f"{path}.{key}")


def _string(value: Any, path: str) -> str:
    if not isinstance(value, str):
        raise ValueError(f"{path} must be a string")
    return value


def _optional_string(
    obj: dict[str, Any], key: str, path: str | None = None
) -> str | None:
    value = obj.get(key)
    return None if value is None else _string(value, path or key)


def _choice(value: Any, choices: tuple[str, ...], path: str) -> str:
    text = _string(value, path)
    if text not in choices:
        raise ValueError(f"{path} must be one of {', '.join(choices)}; got {text!r}")
    return text


def _optional_choice(
    obj: dict[str, Any], key: str, choices: tuple[str, ...], path: str
) -> str | None:
    value = obj.get(key)
    return None if value is None else _choice(value, choices, path)


def _int(value: Any, path: str, low: int | None, high: int | None) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{path} must be an integer")
    if low is not None and value < low or high is not None and value > high:
        raise ValueError(f"{path} is out of range: {value}")
    return value


def _optional_int(
    obj: dict[str, Any],
    key: str,
    path: str,
    low: int | None = None,
    high: int | None = None,
) -> int | None:
    value = obj.get(key)
    if value is None:
        return None
    # Status codes and timings fit int4; keep them inside PostgreSQL integer.
    return _int(
        value,
        path,
        -(2**31) if low is None else low,
        2**31 - 1 if high is None else high,
    )


def _optional_choice_int(
    obj: dict[str, Any], key: str, choices: tuple[int, ...], path: str
) -> int | None:
    value = obj.get(key)
    if value is None:
        return None
    number = _int(value, f"{path}.{key}", None, None)
    if number not in choices:
        raise ValueError(f"{path}.{key} must be one of {choices}; got {number}")
    return number


def _optional_number(obj: dict[str, Any], key: str, path: str) -> float | None:
    value = obj.get(key)
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise ValueError(f"{path} must be a number")
    number = float(value)
    if not isfinite(number):
        raise ValueError(f"{path} must be finite")
    return number


def _optional_bool(obj: dict[str, Any], key: str, path: str) -> bool | None:
    value = obj.get(key)
    if value is None:
        return None
    if not isinstance(value, bool):
        raise ValueError(f"{path} must be a boolean")
    return value


def _uuid(value: Any, path: str) -> str:
    return normalize_uuid(_string(value, path), path)


def _optional_uuid(obj: dict[str, Any], key: str) -> str | None:
    value = obj.get(key)
    if value is None:
        return None
    text = _string(value, key)
    if _UUID_128.fullmatch(text) is None:
        raise ValueError(f"{key} must be a UUID")
    return text.lower()


def _timestamp(value: Any, path: str) -> datetime:
    text = _string(value, path)
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError as exc:
        raise ValueError(f"{path} is not an ISO-8601 timestamp: {text!r}") from exc
    if parsed.tzinfo is None:
        raise ValueError(f"{path} must carry a UTC offset: {text!r}")
    return parsed.astimezone(UTC)


def _optional_timestamp(
    obj: dict[str, Any], key: str, path: str | None = None
) -> datetime | None:
    value = obj.get(key)
    return None if value is None else _timestamp(value, path or key)

import re
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import Enum
from pathlib import PurePosixPath
from uuid import UUID

from .legacy_adapter import is_legacy_scan_blob
from .storage import parse_gcs_uri


class PayloadRouteKind(Enum):
    LEGACY_JSONL_ZSTD = "legacy-jsonl-zstd"
    PAYLOAD_V2_PARQUET = "payload-v2-parquet"
    PAYLOAD_V3_PARQUET = "payload-v3-parquet"
    GATT_V1_JSON = "gatt-v1-json"


class PayloadRoutingError(ValueError):
    """Base class for an unsupported or malformed scan object URI."""


class UnsupportedPayloadSchemaError(PayloadRoutingError):
    pass


class UnsupportedPayloadPathError(PayloadRoutingError):
    pass


@dataclass(frozen=True)
class PayloadRoute:
    kind: PayloadRouteKind
    uri: str
    payload_id: str | None = None
    utc_date: str | None = None


_DATED_UUID = (
    r"(?P<year>[0-9]{4})/(?P<month>[0-9]{2})/(?P<day>[0-9]{2})/"
    r"(?P<payload_id>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"
)
_V2_PATTERN = re.compile(rf"^scans/v2/{_DATED_UUID}\.parquet\.encrypted$")
_V3_PATTERN = re.compile(rf"^scans/v3/{_DATED_UUID}\.parquet\.encrypted$")
_GATT_V1_PATTERN = re.compile(rf"^gatt/v1/{_DATED_UUID}\.json\.encrypted$")
# Canonical dated-UUID objects: (pattern, path root, expected path, route kind).
_DATED_ROUTES = (
    (
        _V2_PATTERN,
        "scans/v2/",
        "scans/v2/YYYY/MM/DD/<canonical-uuidv7>.parquet.encrypted",
        PayloadRouteKind.PAYLOAD_V2_PARQUET,
    ),
    (
        _V3_PATTERN,
        "scans/v3/",
        "scans/v3/YYYY/MM/DD/<canonical-uuidv7>.parquet.encrypted",
        PayloadRouteKind.PAYLOAD_V3_PARQUET,
    ),
    (
        _GATT_V1_PATTERN,
        "gatt/v1/",
        "gatt/v1/YYYY/MM/DD/<canonical-uuidv7>.json.encrypted",
        PayloadRouteKind.GATT_V1_JSON,
    ),
)
_LEGACY_PATTERN = re.compile(
    r"^scans/[^/]+_"
    r"[0-9]+(?:\.[0-9]+){2}(?:[-+][A-Za-z0-9_.-]+)?"
    r"\.jsonl\.zst\.encrypted$"
)


def route_payload_uri(uri: str) -> PayloadRoute:
    try:
        _, object_name = parse_gcs_uri(uri)
    except ValueError as exc:
        raise UnsupportedPayloadPathError(str(exc)) from exc

    raw_components = object_name.split("/")
    if object_name.startswith("/") or any(
        component in {"", ".", ".."} for component in raw_components
    ):
        raise UnsupportedPayloadPathError(
            f"noncanonical scan object path {object_name!r}"
        )
    components = PurePosixPath(object_name).parts
    if any(component in {"", ".", ".."} for component in components):
        raise UnsupportedPayloadPathError(f"unsafe scan object path {object_name!r}")

    for pattern, root, expected, kind in _DATED_ROUTES:
        match = pattern.fullmatch(object_name)
        if match is not None:
            return _dated_route(kind, uri, match)
        if object_name.startswith(root):
            raise UnsupportedPayloadPathError(
                f"payload path under {root} must match {expected}"
            )
    if len(components) > 1 and (
        components[1].startswith("schema=")
        or re.fullmatch(r"v[0-9A-Za-z_.-]+", components[1]) is not None
    ):
        raise UnsupportedPayloadSchemaError(
            f"unsupported payload schema path in {uri!r}"
        )

    if components[:1] == ("gatt",):
        raise UnsupportedPayloadPathError(f"unsupported GATT object path {uri!r}")

    if _LEGACY_PATTERN.fullmatch(object_name):
        if not is_legacy_scan_blob(uri):
            raise UnsupportedPayloadSchemaError(
                "legacy JSONL payload producer version must match 0.0.1 through 0.0.4"
            )
        return PayloadRoute(kind=PayloadRouteKind.LEGACY_JSONL_ZSTD, uri=uri)

    if object_name.endswith(".jsonl.zst.encrypted"):
        if is_legacy_scan_blob(uri):
            raise UnsupportedPayloadPathError(
                "legacy JSONL payloads must be flat under scans/"
            )
        raise UnsupportedPayloadSchemaError(
            "legacy JSONL payload producer version must match 0.0.1 through 0.0.4"
        )

    raise UnsupportedPayloadPathError(f"unsupported scan payload path {uri!r}")


def _dated_route(
    kind: PayloadRouteKind, uri: str, match: re.Match[str]
) -> PayloadRoute:
    payload_id = match.group("payload_id")
    try:
        parsed_uuid = UUID(payload_id)
    except ValueError as exc:
        raise UnsupportedPayloadPathError("payload ID is not a UUID") from exc
    if parsed_uuid.version != 7 or str(parsed_uuid) != payload_id:
        raise UnsupportedPayloadPathError(
            "payload ID must be a canonical lowercase RFC 4122 UUIDv7"
        )

    try:
        path_date = datetime(
            int(match.group("year")),
            int(match.group("month")),
            int(match.group("day")),
            tzinfo=UTC,
        ).date()
    except ValueError as exc:
        raise UnsupportedPayloadPathError(f"invalid payload UTC date: {exc}") from exc
    try:
        uuid_date = datetime.fromtimestamp(parsed_uuid.time / 1000, UTC).date()
    except (OverflowError, OSError, ValueError) as exc:
        raise UnsupportedPayloadPathError(
            "payload UUID timestamp is outside the supported UTC date range"
        ) from exc
    if path_date != uuid_date:
        raise UnsupportedPayloadPathError(
            f"payload UUID UTC date {uuid_date.isoformat()} does not match path date "
            f"{path_date.isoformat()}"
        )

    return PayloadRoute(
        kind=kind,
        uri=uri,
        payload_id=payload_id,
        utc_date=path_date.isoformat(),
    )

import struct
from typing import cast

from thrift.Thrift import TType
from thrift.protocol import TCompactProtocol
from thrift.transport import TTransport

_PARQUET_MAGIC = b"PAR1"
_FILE_METADATA_KEY_VALUE_FIELD_ID = 5
_KEY_VALUE_KEY_FIELD_ID = 1
_KEY_VALUE_VALUE_FIELD_ID = 2


class _ParquetCompactProtocol(TCompactProtocol.TCompactProtocol):
    def skip(self, ttype: int, max_depth: int = 64) -> None:
        # Thrift's STRING wire type represents both string and binary. Parquet uses
        # binary statistics in skipped fields, so generic skip must not UTF-8 decode.
        if ttype == TType.STRING:
            self.readBinary()
        else:
            super().skip(ttype, max_depth)


def read_parquet_key_value_occurrences(payload: bytes) -> list[tuple[str, str | None]]:
    """Read raw FileMetaData.key_value_metadata occurrences from a Parquet footer."""
    if (
        len(payload) < 12
        or payload[:4] != _PARQUET_MAGIC
        or payload[-4:] != _PARQUET_MAGIC
    ):
        raise ValueError("malformed Parquet file magic/footer")

    metadata_length = struct.unpack("<I", payload[-8:-4])[0]
    metadata_start = len(payload) - 8 - metadata_length
    if metadata_length == 0 or metadata_start < 4:
        raise ValueError("malformed Parquet footer length")

    transport = TTransport.TMemoryBuffer(payload[metadata_start:-8])
    protocol = _ParquetCompactProtocol(transport)
    occurrences: list[tuple[str, str | None]] = []
    try:
        protocol.readStructBegin()
        while True:
            _, field_type, field_id = protocol.readFieldBegin()
            if field_type == TType.STOP:
                break
            if (
                field_id == _FILE_METADATA_KEY_VALUE_FIELD_ID
                and field_type == TType.LIST
            ):
                element_type, count = cast(tuple[int, int], protocol.readListBegin())
                if element_type != TType.STRUCT:
                    raise ValueError("malformed Parquet key-value metadata list")
                for _ in range(count):
                    occurrences.append(_read_key_value(protocol))
                protocol.readListEnd()
            else:
                protocol.skip(field_type)
            protocol.readFieldEnd()
        protocol.readStructEnd()
    except ValueError:
        raise
    except Exception as error:
        raise ValueError("malformed Parquet compact footer") from error
    return occurrences


def _read_key_value(
    protocol: TCompactProtocol.TCompactProtocol,
) -> tuple[str, str | None]:
    key: str | None = None
    value: str | None = None
    protocol.readStructBegin()
    while True:
        _, field_type, field_id = protocol.readFieldBegin()
        if field_type == TType.STOP:
            break
        if field_id == _KEY_VALUE_KEY_FIELD_ID and field_type == TType.STRING:
            key = protocol.readString()
        elif field_id == _KEY_VALUE_VALUE_FIELD_ID and field_type == TType.STRING:
            value = protocol.readString()
        else:
            protocol.skip(field_type)
        protocol.readFieldEnd()
    protocol.readStructEnd()
    if key is None:
        raise ValueError("malformed Parquet key-value metadata entry without key")
    return key, value

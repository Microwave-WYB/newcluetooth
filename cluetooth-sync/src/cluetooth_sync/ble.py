from collections.abc import Sequence
from dataclasses import dataclass


@dataclass(frozen=True)
class AdStruct:
    ad_type: int
    data: bytes


def parse_raw(raw: bytes) -> Sequence[AdStruct]:
    view = memoryview(raw)
    structs: list[AdStruct] = []
    i = 0
    while i < len(raw):
        length = view[i]
        if length == 0:
            break
        ad_type = view[i + 1]
        data = view[i + 2 : i + 1 + length].tobytes()
        structs.append(AdStruct(ad_type=ad_type, data=data))
        i += length + 1
    return structs

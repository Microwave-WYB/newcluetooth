"""Python reference evaluator for the Rust matcher cross-check (tests/matcher.rs).

Generated verbatim from the knowledge base's rule language on branch kb/lab
(commit 31a5c98): `cluetooth-agents/kb/src/kb/rules.py` with the SQL compiler
and the psycopg import removed, and the three constants and `canonical_uuid`
of `wiki_match/adv.py` inlined. Do not edit the copied part; the driver at the
bottom reads {"cases": [{"rule", "hex", "addr", "addr_type"}]} on stdin and
prints one boolean per case.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

BT_BASE_SUFFIX = bytes.fromhex("00001000800000805f9b34fb")
# AD types holding lists of service UUIDs -> UUID width in bytes.
UUID_LIST_TYPES = {0x02: 2, 0x03: 2, 0x04: 4, 0x05: 4, 0x06: 16, 0x07: 16}
# AD types holding service data -> UUID width in bytes.
SERVICE_DATA_TYPES = {0x16: 2, 0x20: 4, 0x21: 16}
NAME_TYPES = (0x09, 0x08)


def canonical_uuid(little_endian: bytes) -> str:
    """16/32-bit UUIDs uppercase hex, 128-bit lowercase dashed.

    A 128-bit UUID on the Bluetooth base UUID folds to its short form.
    """
    be = little_endian[::-1]
    if len(be) == 16 and be[4:] == BT_BASE_SUFFIX:
        head = be[:4]
        be = head[2:] if head[:2] == b"\x00\x00" else head
    h = be.hex()
    if len(be) in (2, 4):
        return h.upper()
    return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}"



LEAVES = ("name", "company", "uuid", "struct", "shape", "addr")
NAME_TYPES = (0x08, 0x09)
# bytes before the data a struct condition looks at: the company field, or the UUID
SD_WIDTH = SERVICE_DATA_TYPES  # 0x16: 2, 0x20: 4, 0x21: 16


def base_offset(ad: int) -> int:
    if ad == 0xFF:
        return 2
    return SD_WIDTH.get(ad, 0)


# Structures ------------------------------------------------------------------


@dataclass(frozen=True)
class Structure:
    ord: int
    ad_type: int
    data: bytes
    name: str | None
    company: int | None
    uuids: tuple[str, ...]


def structures(raw: bytes) -> list[Structure]:
    """AD structures of one advertisement, with the fields rules look at. Stops at
    padding (zero length) or truncation, like the database parser."""
    out: list[Structure] = []
    i = 0
    while i < len(raw):
        n = raw[i]
        if n == 0 or i + 1 + n > len(raw):
            break
        t, d = raw[i + 1], raw[i + 2 : i + 1 + n]
        # UTF-8 with replacement; NUL padding removed (Postgres text cannot hold it)
        name = d.decode("utf-8", errors="replace").replace("\x00", "") if t in NAME_TYPES else None
        company = int.from_bytes(d[:2], "little") if t == 0xFF and len(d) >= 2 else None
        uuids: tuple[str, ...] = ()
        w = UUID_LIST_TYPES.get(t)
        if w:
            uuids = tuple(canonical_uuid(d[k : k + w]) for k in range(0, len(d) - w + 1, w))
        w = SD_WIDTH.get(t)
        if w and len(d) >= w:
            uuids = (canonical_uuid(d[:w]),)
        out.append(Structure(len(out), t, d, name, company, uuids))
        i += 1 + n
    return out


def shape_text(structs: list[Structure]) -> str:
    return ",".join(f"{s.ad_type:02X}/{len(s.data)}" for s in structs)


# Validation --------------------------------------------------------------------

# A restricted regex both Postgres (ARE) and Python read the same way: literals,
# escaped punctuation, \d, classes with ranges, ., groups, |, and quantifiers.
_REGEX_TOKEN = re.compile(
    r"\\[.\-+*?()\[\]{}|^$/\\ _:#d]|\[\^?(?:\\.|[^\]\\])+\]|\{\d+(?:,\d*)?\}|[*+?|()]|\.|[^\\\[\]{}()*+?|.^$]"
)


def regex_ok(pattern: str) -> bool:
    pos = 0
    while pos < len(pattern):
        m = _REGEX_TOKEN.match(pattern, pos)
        if not m:
            return False
        pos = m.end()
    try:
        re.compile(pattern)
    except re.error:
        return False
    return "(?" not in pattern


def _ads(v: Any) -> list[int]:
    return [v] if isinstance(v, int) else list(v)


def validate(node: Any, path: str = "rule") -> list[str]:
    """Problems with a rule tree ([] when it is well formed)."""
    if not isinstance(node, dict):
        return [f"{path}: a node is an object"]
    keys = [k for k in node if k not in ("desc", "note")]
    if len(keys) != 1:
        return [f"{path}: one operator or condition per node, got {keys}"]
    k, v = keys[0], node[keys[0]]
    if any(k in node and not isinstance(node[k], str) for k in ("desc", "note")):
        return [f"{path}: desc and note are text"]
    if k in ("all", "any"):
        if not isinstance(v, list) or not v:
            return [f"{path}.{k}: a non-empty list"]
        return [p for i, c in enumerate(v) for p in validate(c, f"{path}.{k}[{i}]")]
    if k == "not":
        return validate(v, f"{path}.not")
    if k == "name":
        modes = [m for m in ("equals", "prefix", "regex") if m in v]
        if len(modes) != 1 or not isinstance(v[modes[0]], str):
            return [f"{path}.name: exactly one of equals, prefix, regex"]
        if "regex" in v and not regex_ok(v["regex"]):
            return [f"{path}.name: regex outside the shared subset: {v['regex']!r}"]
        if v.get("case", "sensitive") not in ("sensitive", "insensitive"):
            return [f"{path}.name: case is sensitive or insensitive"]
        if not set(_ads(v.get("ad", NAME_TYPES))) <= set(NAME_TYPES):
            return [f"{path}.name: ad is 0x08 and/or 0x09"]
        return []
    if k == "company":
        ok = isinstance(v, list) and v and all(isinstance(x, int) and 0 <= x <= 0xFFFF for x in v)
        return [] if ok else [f"{path}.company: a list of company fields"]
    if k == "uuid":
        if not (isinstance(v.get("in"), list) and v["in"] and isinstance(v.get("ad"), list) and v["ad"]):
            return [f"{path}.uuid: in (UUIDs) and ad (AD types) are both required"]
        bad = [a for a in v["ad"] if a not in UUID_LIST_TYPES and a not in SD_WIDTH]
        return [f"{path}.uuid: {bad} do not carry UUIDs"] if bad else []
    if k == "struct":
        ads = _ads(v.get("ad", []))
        if not ads:
            return [f"{path}.struct: ad is required"]
        if len({base_offset(a) for a in ads}) != 1:
            return [f"{path}.struct: AD types with different data offsets cannot share a condition"]
        if "company" in v and ads != [0xFF]:
            return [f"{path}.struct: company needs ad 0xFF"]
        if "uuid" in v and not all(a in SD_WIDTH for a in ads):
            return [f"{path}.struct: uuid needs a service-data type"]
        b = v.get("bytes")
        if b is not None:
            if not re.fullmatch(r"(?:[0-9a-f]{2})+", b.get("value", "")):
                return [f"{path}.struct.bytes: value is lowercase hex"]
            if "mask" in b and len(b["mask"]) != len(b["value"]):
                return [f"{path}.struct.bytes: mask and value have the same length"]
        return []
    if k == "shape":
        items = v.get("items")
        if not isinstance(items, list) or not items:
            return [f"{path}.shape: items"]
        bad = [x for x in items if not re.fullmatch(r"[0-9A-F]{2}/(\*|\d+|\d+-\d+)", x)]
        return [f"{path}.shape: bad items {bad}"] if bad else []
    if k == "addr":
        if not ("oui" in v or "type" in v):
            return [f"{path}.addr: oui or type"]
        return []
    return [f"{path}: unknown node {k!r}"]


# Python evaluator -------------------------------------------------------------


def _name_ok(spec: dict[str, Any], name: str) -> bool:
    insensitive = spec.get("case") == "insensitive"
    if "regex" in spec:
        return re.fullmatch(spec["regex"], name, re.I if insensitive else 0) is not None
    want = spec.get("equals", spec.get("prefix", ""))
    have, want = (name.lower(), want.lower()) if insensitive else (name, want)
    return have == want if "equals" in spec else have.startswith(want)


def _bytes_ok(data: bytes, b: dict[str, Any]) -> bool:
    value = bytes.fromhex(b["value"])
    mask = bytes.fromhex(b["mask"]) if "mask" in b else b"\xff" * len(value)
    seg = data[b.get("offset", 0) : b.get("offset", 0) + len(value)]
    return len(seg) == len(value) and all(s & m == v & m for s, v, m in zip(seg, value, mask, strict=True))


def _len_ok(n: int, want: Any) -> bool:
    return n == want if isinstance(want, int) else want[0] <= n <= want[1]


def _shape_regex(spec: dict[str, Any]) -> str:
    parts = []
    for item in spec["items"]:
        ad, length = item.split("/")
        if length == "*":
            parts.append(f"{ad}/[0-9]+")
        elif "-" in length:
            lo, hi = (int(x) for x in length.split("-"))
            parts.append(f"{ad}/(" + "|".join(str(n) for n in range(lo, hi + 1)) + ")")
        else:
            parts.append(f"{ad}/{int(length)}")
    return "^" + ",".join(parts) + ("(,.*)?$" if spec.get("rest", True) else "$")


def _addr_ok(spec: dict[str, Any], addr: str | None, addr_type: str | None) -> bool:
    if addr is None:
        return False
    hexed = addr.replace(":", "").replace("-", "").upper()
    if "oui" in spec and hexed[:6] not in {o.upper() for o in spec["oui"]}:
        return False
    return not ("type" in spec and addr_type != spec["type"])


def evaluate(
    node: dict[str, Any],
    structs: list[Structure],
    addr: str | None = None,
    addr_type: str | None = None,
) -> bool:
    """Whether an advertisement (its structures, and address when known) matches."""
    k = next(k for k in node if k not in ("desc", "note"))
    v = node[k]
    if k == "all":
        return all(evaluate(c, structs, addr, addr_type) for c in v)
    if k == "any":
        return any(evaluate(c, structs, addr, addr_type) for c in v)
    if k == "not":
        return not evaluate(v, structs, addr, addr_type)
    if k == "name":
        ads = set(_ads(v.get("ad", NAME_TYPES)))
        return any(s.ad_type in ads and s.name is not None and _name_ok(v, s.name) for s in structs)
    if k == "company":
        return any(s.ad_type == 0xFF and s.company in v for s in structs)
    if k == "uuid":
        ads, want = set(v["ad"]), set(v["in"])
        return any(s.ad_type in ads and want & set(s.uuids) for s in structs)
    if k == "struct":
        for s in structs:
            if s.ad_type not in _ads(v["ad"]):
                continue
            if "company" in v and s.company != v["company"]:
                continue
            if "uuid" in v and (not s.uuids or s.uuids[0] != v["uuid"]):
                continue
            base = base_offset(s.ad_type)
            if len(s.data) < base:
                continue
            data = s.data[base:]
            if "len" in v and not _len_ok(len(data), v["len"]):
                continue
            if "bytes" in v and not _bytes_ok(data, v["bytes"]):
                continue
            return True
        return False
    if k == "shape":
        return re.match(_shape_regex(v), shape_text(structs)) is not None
    if k == "addr":
        return _addr_ok(v, addr, addr_type)
    raise ValueError(f"unknown node {k!r}")


def matches(rule: dict[str, Any], raw: bytes, addr: str | None = None, addr_type: str | None = None) -> bool:
    return evaluate(rule, structures(raw), addr, addr_type)




# Driver --------------------------------------------------------------------------

if __name__ == "__main__":
    import json
    import sys

    cases = json.load(sys.stdin)["cases"]
    json.dump(
        [matches(c["rule"], bytes.fromhex(c["hex"]), c.get("addr"), c.get("addr_type")) for c in cases],
        sys.stdout,
    )

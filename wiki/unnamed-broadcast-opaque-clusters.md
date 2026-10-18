---
qmd:
  metadata:
    title: "Unnamed broadcast population: opaque clusters"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Unnamed broadcast population: opaque clusters

## How to recognize it

- **Local name:** (no local name)
- **Company field:** company field 15833, company field 283
- **AD shape (ordered type/data length):** `FF`, `01,FF`
- **What it is:** Rollup of advertisements without a local name and two opaque `FF`-only clusters.
- **Confidence and limits:** No product identification. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Unnamed broadcast population

A bounded read-only `advs` aggregate over rows whose `local_name` is null or blank found **315,614 exact `(addr,raw)` variants / 237,793 observed addresses / 10,309,357 scans**. This is an operational definition of *unnamed in the rollup*, not proof that every received packet lacked any name field. Common ordered AD-type shapes such as `FF` alone (**106,716 variants / 100,563 addresses / 3,294,994 scans**) are far too generic to identify a product.

One narrower no-name group has a single `FF` field, **62-byte raw payload length**, parsed company value **15833 (`0x3DD9`)**, and **1,634 variants / 4 addresses / 2,089 scans**; no variant exceeds four scans. The [current SIG company-identifier list](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml) does not assign `0x3DD9`, so this is **not vendor evidence**. For comparison, a distinct no-name `01,FF` / 62-byte group with company field **283** has **3,309 variants / 331 addresses / 5,950 scans**; the SIG assigns 283 to Hewlett Packard Enterprise, but that field does **not** identify any HPE end product. Variable payloads, malformed/company-field values and address reuse are all possible; neither group establishes traffic infrastructure, hardware identity or a physical-device count.

Sources and detailed private query notes remain outside Git in `us-osm-hospitals/20261013T225104Z`. No complete device addresses, serial-bearing names, raw advertisement payloads, precise locations, credentials or patient data are published.

**Next bounded batch:** check common SIG-defined service classes such as electronic shelf labels (`1857`), industrial measurement (`185A`) and tire-pressure monitoring (`1860`) only as aggregate service/AD-shape leads; require independent original-product evidence before attributing any unnamed advertisements to a device category. Keep the daily collector-progress heartbeat independent of this BLE research.

---
qmd:
  metadata:
    title: "Unnamed Bluetooth Mesh Proxy (1828) service signal"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Unnamed Bluetooth Mesh Proxy (`1828`) service signal

## How to recognize it

- **Local name:** (no local name)
- **Company field:** company field 0x07A5, company field 0x0211
- **Service / service-data UUID:** `1828`
- **AD shape (ordered type/data length):** `01/1,03/2,16/11`, `01/1,03/2,16/19`, `01/1,03/2,16/11,FF/29`
- **What it is:** Unnamed advertisements carrying the SIG Mesh Proxy service UUID `1828`.
- **Confidence and limits:** Protocol service signal only; not a product class or common mesh network. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Unnamed Mesh Proxy cohort

A further **unnamed Mesh Proxy service-signal cohort** advertises SIG-defined [`1828` Mesh Proxy](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/uuids/service_uuids.yaml): **80 variants / 78 addresses / 619 scans**. Parsed ordered AD prefixes use `01:1,03:2,16:11` (**69 variants / 68 addresses / 601 scans**) or `01:1,03:2,16:19` (**11 / 10 / 18**); `FF` manufacturer data may follow. The dominant stricter prefix `01:1,03:2,16:11,FF:29` accounts for **62 / 61 / 575**. Notation is hexadecimal AD type followed by data-byte length, excluding its Length/Type bytes. Every stored raw payload has length 62 bytes, but the database parser may stop before the end; these are **parsed prefixes, not claims of a complete single on-air packet**. The [SIG company registry](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml) associates shared company fields `0x07A5` with RAB Lighting (**56 / 56 / 240**) and `0x0211` with Telink Semiconductor (**6 / 5 / 335**); a shared field is not manufacturer authentication, and neither subset establishes a lamp or other physical product class. `1828` indicates an advertised protocol service, **not** verified GATT activity, a common Mesh network, physical-device count, or a model. Service-data lengths are compatible with familiar proxy identity formats, but no identity bytes, cryptographic validity, or source product have been verified. The [SIG Mesh Protocol v1.1](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/MshPRT_v1.1/out/en/index-en.html) full chapter could not be directly fetched within the source-size limit, so no stronger format attribution is made.

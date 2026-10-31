---
qmd:
  metadata:
    title: "Unnamed Bluetooth Mesh Proxy (1828) and Provisioning (1827) service signals"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Unnamed Bluetooth Mesh Proxy (`1828`) and Provisioning (`1827`) service signals

## How to recognize it

- **Local name:** (no local name)
- **Company field:** company field 0x07A5, company field 0x0211, company field 0x03F6
- **Service / service-data UUID:** `1828`, `1827`
- **AD shape (ordered type/data length):** `01/1,03/2,16/11`, `01/1,03/2,16/19`, `01/1,03/2,16/11,FF/29`, `01/1,03/2,16/20`
- **What it is:** Unnamed advertisements carrying the SIG Mesh Proxy service UUID `1828`, or the SIG Mesh Provisioning service UUID `1827` (`01/1,03/2,16/20`).
- **Confidence and limits:** Protocol service signals only; not a product class or common mesh network. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Unnamed Mesh Proxy cohort

A further **unnamed Mesh Proxy service-signal cohort** advertises SIG-defined [`1828` Mesh Proxy](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/uuids/service_uuids.yaml): **80 variants / 78 addresses / 619 scans**. Parsed ordered AD prefixes use `01:1,03:2,16:11` (**69 variants / 68 addresses / 601 scans**) or `01:1,03:2,16:19` (**11 / 10 / 18**); `FF` manufacturer data may follow. The dominant stricter prefix `01:1,03:2,16:11,FF:29` accounts for **62 / 61 / 575**. Notation is hexadecimal AD type followed by data-byte length, excluding its Length/Type bytes. Every stored raw payload has length 62 bytes, but the database parser may stop before the end; these are **parsed prefixes, not claims of a complete single on-air packet**. The [SIG company registry](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml) associates shared company fields `0x07A5` with RAB Lighting (**56 / 56 / 240**) and `0x0211` with Telink Semiconductor (**6 / 5 / 335**); a shared field is not manufacturer authentication, and neither subset establishes a lamp or other physical product class. `1828` indicates an advertised protocol service, **not** verified GATT activity, a common Mesh network, physical-device count, or a model. Service-data lengths are compatible with familiar proxy identity formats, but no identity bytes, cryptographic validity, or source product have been verified. The [SIG Mesh Protocol v1.1](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/MshPRT_v1.1/out/en/index-en.html) full chapter could not be directly fetched within the source-size limit, so no stronger format attribution is made.

## Mesh Provisioning (`1827`) cohort

The SIG-defined [`1827` Mesh Provisioning](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/uuids/service_uuids.yaml) service appears in **25 variants / 25 addresses / 288 scans** across the same first-party database on **2026-10-18**; **19 variants** have no local name. It is the sibling role to `1828`: in Mesh terms, provisioning advertisements are what an unprovisioned device broadcasts, whereas proxy advertisements come from an already-provisioned node. Both are protocol roles, **not** product classes.

The dominant unnamed form is `01/1,03/2,16/20` (**17 variants / 17 addresses / 251 scans**), also with a single `FF` field following (**1 / 1 / 15** with company field 0x5590, **1 / 1 / 1** with 0x07A5); the stored raw payload is 62 bytes in every case. A data length of 20 (counting the two UUID bytes) is compatible with a 16-byte device UUID plus 2-byte OOB information, but the bytes were not decoded and the [Mesh Protocol v1.1](https://www.bluetooth.com/specifications/specs/mesh-protocol/) layout was not independently re-read here, so that reading is a **compatibility note, not a verified parse**. The six named variants (**6 / 6 / 21**) mostly add `09` (name) and `19` (appearance) fields; a four-variant subset has company field 0x03F6, which the [SIG registry](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml) lists as Iton Technology Corp. Company field 0x07A5 (RAB Lighting, Inc. in the same registry) again appears, as with `1828`; 0x5590 and 0xE1FD have no entry in the registry as fetched. None of these shared fields is manufacturer authentication, and **no product category, mesh network, or provisioning attempt** is established; observed addresses are not unit counts. Name suffixes are withheld.

A bounded read-only check for further SIG service classes found zero matches for `1857` (Electronic Shelf Label), `185A` (Industrial Measurement Device) and `1860` (Tire Pressure Monitoring System) in `service_uuids`, and only trivially small counts for `1853` Common Audio (1 / 1 / 81) and `1826` Fitness Machine (12 / 12 / 38); these absences are by advertised service UUID only.

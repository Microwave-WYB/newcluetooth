---
qmd:
  metadata:
    title: "ResMed ResMed* first-party name cohort (company field 909, FD56)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# ResMed `ResMed*` first-party name cohort (company field 909, `FD56`)

## How to recognize it

- **Local name:** `ResMed*`
- **Company field:** company field 909
- **Service / service-data UUID:** `FD56`
- **AD shape (ordered type/data length):** `01/1,09/13,1B/6,01/1,03/2,0A/1,FF/3`
- **What it is:** ResMed-assigned company field and member UUID on `ResMed*` names.
- **Confidence and limits:** Company-related candidate; not a specific device class. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Related observations

Case-insensitive `ResMed*` has **36 exact variants / 36 addresses / 1,051 scans** with `01/1,09/13,1B/6,01/1,03/2,0A/1,FF/3`. The [Bluetooth SIG company assignments](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml) allocate auxiliary field 909 to Resmed Ltd; its [member UUID assignments](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/uuids/member_uuids.yaml) allocate `FD56` to the same company. This supports a **company-related BLE candidate**, not authentic hardware or a specific medical-device class/model. Names contain a hidden six-digit suffix. [ResMed's AirMini pairing example](https://ap.resmed.com/knowledge/how-do-i-pair-airmini-to-a-smartphone) is only a naming-style parallel: it does **not** map this exact name/AD layout to AirMini. No CPAP subtype, physical-unit count or hospital use is established.

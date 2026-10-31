---
qmd:
  metadata:
    title: "MIRA-* name with Nordic-like UUID (manufacturer unknown)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# `MIRA-*` name with Nordic-like UUID (manufacturer unknown)

## How to recognize it

- **Local name:** `MIRA-*`
- **Service / service-data UUID:** `6e400054-b5a3-f393-e0a9-e50e24dcca9e`
- **AD shape (ordered type/data length):** `01/1,FF/13,09/11,07/16`
- **What it is:** `MIRA-*` name signal; manufacturer and product class unknown.
- **Confidence and limits:** Unresolved; no hormone-test or hospital inference. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| `MIRA-*` name signal — **manufacturer and product class unknown** | `MIRA-*` (name 11 bytes); `01/1, FF/13, 09/11, 07/16`; advertised UUID `6e400054-b5a3-f393-e0a9-e50e24dcca9e` | **13 / 13 / 431** | [Mira Hormone Monitor original pairing guide](https://help.miracare.com/en-US/troubleshooting-bluetooth-pairing-issues-with-mira-hormone-monitor-848147) documents a Bluetooth-capable product but **not** this name or UUID. This UUID resembles the [Nordic NUS UUID base](https://github.com/nrfconnect/sdk-nrf/blob/main/include/bluetooth/services/nus.h), but is **not** the official `6e400001…` NUS service UUID; its meaning, maker and category are unverified. Other unrelated Bluetooth-capable “Mira” products include [Metrohm MIRA Raman analyzers](https://www.metrohm.com/content/dam/metrohm/shared/documents/operator-trainings/801058023EN.pdf) and [Kohler/Mira Mode shower controls](https://resources.kohler.com/plumbing/mira/pdf/1286337-w2-b-mira-mode-interface-user-guide.pdf); neither is identified by this advertisement either. Do not infer hormone tests, patient data, medical equipment, clinical activity or hospital deployment. |

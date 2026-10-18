---
qmd:
  metadata:
    title: "Otodata level: … tank monitors (company field 945)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Otodata `level: …` tank monitors (company field 945)

## How to recognize it

- **Local name:** `level: <reading withheld>`
- **Company field:** company field 945
- **What it is:** Otodata tank-level monitoring family candidate.
- **Confidence and limits:** Family candidate; not a Level Home lock. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Otodata tank-level monitoring family candidate](https://www.otodatatankmonitors.com/industry/utilities) | `level: <reading withheld>` name format plus Otodata-assigned company field 945; no advertised service UUID, varied `01,FF,09` with optional `12,19` | **7 / 7 / 8** | [Independent field record](https://community.home-assistant.io/t/bluetooth-propane-tank-monitor-esphome-otodata-nee-vo-ferrellgas-ble/430476) corroborates `level:` naming. **Not** a Level Home lock; observed readings, tank contents, TM model and physical-unit count are not disclosed/inferred. |

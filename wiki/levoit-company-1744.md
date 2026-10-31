---
qmd:
  metadata:
    title: "Levoit LAP-* / Core*S* air purifiers (company field 1744)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Levoit `LAP-*` / `Core*S*` air purifiers (company field 1744)

## How to recognize it

- **Local name:** `LAP-V102S*`, `LAP-V201S*`, `Core200S*`, `Core300S*`, `Core400S*`, `LAP-C601S*`
- **Company field:** company field 1744
- **AD shape (ordered type/data length):** `01/1,FF/14,09/<name length>`
- **What it is:** Levoit smart air-purifier model-name leads sharing one structure.
- **Confidence and limits:** Model-name leads; shape and company field alone cannot identify a model. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Levoit smart air-purifier model-name leads

The [Levoit user-manual directory](https://levoit.ae/pages/user-manuals), [Vital 100S manual](https://levoit.com/cdn/shop/files/LAP-V102S-AUSR_Vital_100S-P_Smart_Air_Purifier_User_Manual.pdf), and [Core 300S product page](https://levoit.com/products/core-300s-p-smart-air-purifier) provide product context. The **model-name token**, rather than the shared shape, distinguishes these leads. All target variants have `01/1 → FF/14 → 09/<name length>` and auxiliary company field 1744; no advertised service UUID. Retail `-P` suffixes and shared SDK identity are **not** established by these observations.

| Sanitized name token → candidate model line | Variants / addresses / scans | Name-data length |
|---|---:|---:|
| `LAP-V102S*` → Levoit Vital 100S | **16 / 16 / 1,157** | 13 |
| `LAP-V201S*` → Levoit Vital 200S | **15 / 15 / 262** | 13 |
| `Core200S*` → Levoit Core 200S | **13 / 13 / 214** | 8 or 12 |
| `Core300S*` → Levoit Core 300S | **3 / 3 / 40** | 8 |
| `Core400S*` → [Levoit Core 400S](levoit-core-400s.md) | **3 / 3 / 9** | 8 |
| `LAP-C601S*` → [Levoit Core 600S model line](https://levoit.com/products/core-600s-p-smart-air-purifier) | **4 / 4 / 29** | 13 |

All four `LAP-C601S*` variants share the table's `01/1,FF/14,09/13` skeleton, auxiliary company field 1744 and no advertised service/service-data UUID. The original product page's `LAP-C601S-WUS` / `Core 600S-P` retail suffix is **not** established for these shorter observed model tokens. Repeating the whole-library wildcarded shape/company query: **18 / 18 / 143** beyond the old five groups included the newly recognized **4 / 4 / 29** `LAP-C601S*` variants. After excluding all six listed models, **14 exact variants / 14 addresses / 114 scans remain unassigned**. These are not confirmed Levoit devices, and shape/company field alone cannot identify a model. Two broad `CORE` rows lacking that company field remain excluded.

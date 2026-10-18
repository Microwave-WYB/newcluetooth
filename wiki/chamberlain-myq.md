---
qmd:
  metadata:
    title: "Chamberlain MyQ-* / myQ-F* smart garage"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Chamberlain `MyQ-*` / `myQ-F*` smart garage

## How to recognize it

- **Local name:** `MyQ-*`, `myQ-F*`
- **Company field:** company field 2168
- **Service / service-data UUID:** `26d91a37-c279-4d0f-96a1-532ce41ce0f6`
- **AD shape (ordered type/data length):** `01/1,06/16,09/7,19/2,FF/4`
- **What it is:** Chamberlain myQ smart-garage ecosystem.
- **Confidence and limits:** Ecosystem candidate; `myQ-F*` is a filtered subset of `MyQ-*` (see overlap note on the Phillips Connect page). Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Chamberlain myQ smart-garage ecosystem](https://support.chamberlaingroup.com/s/article/How-to-Connect-the-Smart-Garage-Hub-to-a-Network) | `MyQ-*`; `01/1, 06/16, 09/7, 19/2, FF/4`; service UUID `26d91a37-c279-4d0f-96a1-532ce41ce0f6` | **112 / 112 / 420** | Official setup documents a `myQ-NNN` Bluetooth discovery name; the observed UUID and name do **not** establish a particular MYQ-G0401 gateway or opener SKU. Rechecked after an earlier 73 / 73 / 296 snapshot. |
| [Chamberlain myQ garage-door connectivity family candidate](https://www.myq.com/products/smart-garage-door-opener) | Case-insensitive `myQ-F*` (suffix hidden), Chamberlain-assigned company field 2168; UUID `26d91a37-c279-4d0f-96a1-532ce41ce0f6`; `01/1,06/16,09/7,19/2,FF/4` | **13 / 13 / 52** | [Original setup guidance](https://www.myq.com/setup) gives family context, not gateway vs opener/compatible accessory or exact SKU. Does not show active door movement or access authorization. Earlier narrower count 6 / 6 / 34 is superseded by this case-insensitive recheck. |

See the overlapping-filters note on [Phillips Connect trailer ads](phillips-connect-trailer-ads.md): `myQ-F*` is a name-filtered subset of `MyQ-*`; their counts must not be added or subtracted.

---
qmd:
  metadata:
    title: "Garmin-assigned FRONT* signal (FE1F, company field 135)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Garmin-assigned `FRONT*` signal (`FE1F`, company field 135)

## How to recognize it

- **Local name:** `FRONT*`
- **Company field:** company field 135
- **Service / service-data UUID:** `FE1F`
- **AD shape (ordered type/data length):** `01,03,16,09,FF`, `01,03,FF,16,09`
- **What it is:** Brand-level Garmin signal on `FRONT*` names; product class unknown.
- **Confidence and limits:** Brand-level signal only; not a product class or model. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Garmin-assigned `FRONT*` BLE-service **candidate**](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Assigned_Numbers/out/en/index-en.html) | Case-insensitive `FRONT*` with Garmin-assigned company field 135 and service/service-data UUID `FE1F`; two `01,03,16,09,FF` / `01,03,FF,16,09` forms | **13 / 9 / 44** | Assigned-number reference supports a **brand-level signal**, not product class, physical front light, Frontpoint security or Garmin model; one other same-prefix variant below lacks these fields. Name-column and AD name lengths disagree in part; no repair assumed. |

## Related observations

One other case-insensitive `FRONT*` observation (**1 exact variant / 1 address / 1 scan**) lacks Garmin company field and `FE1F`: `01/1,09/23,0A/1,06/16` with withheld 128-bit UUID. It is **not** part of the 13-variant Garmin-service group; category and manufacturer remain unknown. The complete `FRONT*` cohort is 14 / 10 / 45; exact uppercase `FRONT` alone matches only 1 / 1 / 10 and cannot define the product family.

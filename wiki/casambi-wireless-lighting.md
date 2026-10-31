---
qmd:
  metadata:
    title: "Casambi lighting: PSC-WCM-450*, CBU-PWM4* (company field 963)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Casambi lighting: `PSC-WCM-450*`, `CBU-PWM4*` (company field 963)

## How to recognize it

- **Local name:** `PSC-WCM-450*`, `CBU-PWM4*`
- **Company field:** company field 963
- **Service / service-data UUID:** `FE4D`
- **AD shape (ordered type/data length):** `FF/25,01/1,12/4,09/20`, `FF/25,01/1,12/4,09/13`
- **What it is:** Casambi-based wireless lighting controller and PWM dimmer.
- **Confidence and limits:** Plausible Casambi BLE variant, not a confirmed SKU; the shape is shared across names. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [mwConnect PSC-WCM-450 wireless lighting controller](https://casambi.com/ecosystem/wireless-room-controller/) | `PSC-WCM-450*`; commonly `FF/25, 01/1, 12/4, 09/20` (23 variants); three further name/shape variants include shortened name or `02` AD | **26 / 26 / 477** | Casambi BLE variant is plausible, not a confirmed SKU. The same shape occurs under other names such as `CBU-PWM4*`. |
| [Casambi CBU-PWM4 four-channel LED PWM dimmer](https://casambi.com/ecosystem/cbu-pwm4/) | `CBU-PWM4*` (suffix hidden) plus auxiliary Casambi company field 963; three have `FF/25,01/1,12/4,09/13`, one adds service UUID `FE4D` | **4 / 4 / 11** | Name identifies the model family more reliably than the shape shared with PSC-WCM; `LR` or regional/current variants cannot be inferred. |

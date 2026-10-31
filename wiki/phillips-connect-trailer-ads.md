---
qmd:
  metadata:
    title: "Phillips Connect trailer telematics: PCAM_*, pctgw_*, CFQU* (company field 2175)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Phillips Connect trailer telematics: `PCAM_*`, `pctgw_*`, `CFQU*` (company field 2175)

## How to recognize it

- **Local name:** `PCAM_*`, `PCAM_F*`, `pctgw_*`, `CFQU*`
- **Company field:** company field 2175
- **AD shape (ordered type/data length):** `01/1,FF/8,09/11`, `01/1,FF/13,09/11`, `01/1,FF/13,09/10`
- **What it is:** Phillips Connect trailer cameras, gateways and container telemetry candidates sharing company field 2175.
- **Confidence and limits:** Product-family candidates; the `PCAM_F*` row is a name-filtered subset of `PCAM_*`. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Phillips Connect CargoVision trailer camera candidate](https://www.phillips-connect.com/launches-the-industrys-first-wireless-on-demand-cargo-camera) | `PCAM_*` (name 11 bytes); `01/1, FF/8, 09/11`; company field 2175 auxiliary | **104 / 91 / 668** | Vendor documents Bluetooth-connected cargo cameras but not the `PCAM_` naming rule; product family candidate only. Count was rechecked separately on the same research date because an earlier snapshot had 70 / 57 / 520. |
| [Phillips Connect commercial-trailer telematics family candidate](https://www.phillips-connect.com/product/smart7-nosebox) | `pctgw_*` (suffix hidden), Phillips Connect Technologies-assigned company field 2175; `01/1,FF/13,09/11`, no service UUID | **6 / 6 / 38** | Smart7 Nosebox documents a trailer gateway product category, **not** this name-to-Smart7/Pro mapping. No trailer fleet, component role or hardware version established. |
| [Phillips Connect fleet-hardware `PCAM_F*` **candidate**](https://www.phillips-connect.com/launches-the-industrys-first-wireless-on-demand-cargo-camera) | `PCAM_F*` (suffix hidden, name 11 bytes), Phillips Connect Technologies-assigned company field 2175; `01/1,FF/8,09/11`, no service UUID | **13 / 13 / 52** | Original CargoVision cargo-camera page shows a Bluetooth-connected trailer product, **not** a direct `PCAM_F` model mapping. Do not assert camera type, CargoVision unit, filming state or 13 physical cameras; older 8 / 8 / 37 snapshot superseded. |
| [Phillips Connect-associated freight-container telemetry **candidate**](https://www.phillips-connect.com/our-products) | `CFQU*` (container-like suffix hidden, name 10 bytes) plus Phillips Connect Technologies-assigned company field 2175; `01/1,FF/13,09/10`, no service UUID | **9 / 9 / 192** | [COFC's equipment page](https://www.cofclogistics.com/equipment) calls `CFQU` a **container ID prefix** and mentions GPS-equipped containers, **not** evidence these ads are COFC-owned or mapped to a particular Phillips unit. No Smart7/SolarNet/AssetTrac SKU assigned. |

## Related observations

**Overlapping filters, not new families:** `PCAM_F*` is a name-filtered subset of `PCAM_*`; `myQ-F*` is a name-filtered subset of `MyQ-*` (case-insensitive). Their counts were reported from separate queries/snapshots and must **not** be added to or subtracted from the broader counts to infer distinct hardware, coverage or additional product families. BlazePod, TireLinc and CAREL each appear only once in the table.

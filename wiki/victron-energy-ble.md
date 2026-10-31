---
qmd:
  metadata:
    title: "Victron Energy: Cerbo*, SmartSolar*, Orion XS* (company field 737)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Victron Energy: `Cerbo*`, `SmartSolar*`, `Orion XS*` (company field 737)

## How to recognize it

- **Local name:** `Cerbo*`, `SmartSolar*`, `Orion XS*`
- **Company field:** company field 737
- **Service / service-data UUID:** `cd54c012-880b-425b-b167-81ed6a15e913`, `cd54c00a-880b-425b-b167-81ed6a15e913`, `cd55a060-880b-425b-b167-81ed6a15e913`
- **AD shape (ordered type/data length):** `01/1,FF/22,09/22`, `01/1,FF/24,09/20`
- **What it is:** Victron Cerbo GX gateway, SmartSolar MPPT and Orion XS DC-DC charger families.
- **Confidence and limits:** Family candidates; no voltage/current model token identified. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Victron Cerbo GX energy-management gateway](https://www.victronenergy.com/communication-centres/cerbo-gx) | `Cerbo*`; eight variants explicitly name `Cerbo GX` (8 / 8 / 75), one is model-unresolved (1 / 1 / 2); service UUID `cd54c012-880b-425b-b167-81ed6a15e913` or `cd54c00a-880b-425b-b167-81ed6a15e913` | **9 / 9 / 77** | [Bluetooth enables initial configuration](https://www.victronenergy.com/media/pg/Cerbo_GX/en/accessing-the-gx-device.html), not proof of remote-console or other device links. UUID differences do not identify MK2 or firmware revision. |
| [Victron SmartSolar MPPT charging-controller family](https://www.victronenergy.com/solar-charge-controllers/smartsolar-mppt-75-10-75-15-100-15-100-20) | `SmartSolar*` (suffix hidden); 49 variants use `01/1,FF/22,09/22` with auxiliary Victron field 737; four also/alternatively advertise UUID `cd55a060-880b-425b-b167-81ed6a15e913` | **53 / 11 / 92** | One address alone generated **36 exact raw variants**. No voltage/current model token is identified; neither 53 variants nor 11 addresses is a validated device count. |
| [Victron Orion XS DC-DC battery-charger family](https://www.victronenergy.com/dc-dc-converters/orion-xs-dc-dc-battery-chargers) | `Orion XS*` (suffix hidden), Victron-associated company field 737; mainly `01/1,FF/24,09/20`; 1 variant adds a withheld 128-bit UUID | **93 / 7 / 201** | Four same-name addresses have varying company data (largest one **72** raw variants). [Original Instant Readout guide](https://www.victronenergy.com/media/pg/Orion_XS_12-12-50A_DC-DC_battery_charger/en/operation%2C-configuration-and-monitoring.html) supports a dynamic-advertisement context, **not** an interpretation of changing bytes, voltage/current SKU, or 93 chargers. |

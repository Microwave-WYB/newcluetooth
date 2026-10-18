---
qmd:
  metadata:
    title: "Anker Anker* SOLIX / Prime and AFYDKPN* (FF09)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Anker `Anker*` SOLIX / Prime and `AFYDKPN*` (`FF09`)

## How to recognize it

- **Local name:** `Anker*`, `AFYDKPN*`
- **Company field:** company field 0xFFFF
- **Service / service-data UUID:** `FF09`, `2215`
- **AD shape (ordered type/data length):** `01,FF,03,09,0A`, `01,FF,03,09`, `01/1,03/2,FF/17,09/17`
- **What it is:** Anker SOLIX power stations, Prime power bank and the `AFYDKPN*` smart-charging candidate.
- **Confidence and limits:** SOLIX rows: model-name candidates. `AFYDKPN*`: third-party-code candidate. Company field is uninformative. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## Anker SOLIX portable power stations and Prime power-bank family

Literal `Anker*` has **5 exact `(addr,raw)` variants / 5 addresses / 70 scans**, with four distinct **public SOLIX model tokens**:

| Advertised public token / original manufacturer source | Variants / addresses / scans | Product candidate |
|---|---:|---|
| [`Anker SOLIX C1000`](https://www.anker.com/nz/anker-portable-power-station-warranty-policy) | **1 / 1 / 3** | C1000 portable power station. |
| [`Anker SOLIX C300`](https://support.anker.com/s/article/Anker-SOLIX-C300-Portable-Power-Station-Guide-de-l-utilisateur-A1722-CA) | **1 / 1 / 59** | C300 portable power station; not necessarily this guide's region or revision. |
| [`Anker SOLIX C800 Plus`](https://service.anker.com/article-description/Anker-SOLIX-C800-PLUS-Portable-Power-Station) | **1 / 1 / 1** | C800 Plus portable power station. |
| [`Anker SOLIX F2000`](https://service.anker.com/article-description/Anker-SOLIX-F2000-Portable-Power-Station-PowerHouse-767-USER-MANUAL) | **1 / 1 / 2** | F2000 / PowerHouse 767 portable power station. |
| [`Anker Prime Power Bank`](https://www.anker.com/products/a1340-250w-power-bank) | **1 / 1 / 5** | **Prime power-bank family only**: linked A1340 is an example, **not** an identified model here. |

Four SOLIX ads carry service UUID `FF09` and ordered `01,FF,03,09,0A`; the Prime ad instead carries `2215` and `01,FF,03,09`. All have uninformative company field `0xFFFF`. No exact `(addr,raw)` or address overlap with the separately recorded `AFYDKPN*` smart-charging lead in this snapshot, but different/random addresses cannot establish different physical units. Only the four explicit SOLIX name tokens add model candidates; ads do not authenticate hardware, region, capacity, firmware or active operating state.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Anker smart-charging / Prime-ecosystem `AFYDKPN*` **candidate**](https://github.com/geohot/anker-vibecoded/blob/master/anker_prime.py) | `AFYDKPN*` (device suffix hidden, name 17 bytes), service UUID `FF09`, uninformative company field `0xFFFF`; `01/1,03/2,FF/17,09/17` | **9 / 9 / 138** | **Third-party source code** explicitly recognizes this name prefix as Anker charging hardware. [Original Anker Prime Bluetooth base](https://www.anker.com/products/a1903-anker-prime-150w-charging-base) is category background only, **not** proof of A1903, base vs charger vs power bank, or wattage. |

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.

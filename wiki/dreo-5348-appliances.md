---
qmd:
  metadata:
    title: "Dreo appliances on 5348 (company field 17992): DreoTF*, DreoAC*, DreoCD*, DreoHM*, DreoPF*, DreoSH*"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Dreo appliances on `5348` (company field 17992): `DreoTF*`, `DreoAC*`, `DreoCD*`, `DreoHM*`, `DreoPF*`, `DreoSH*`

## How to recognize it

- **Local name:** `DreoTF*`, `DreoAC*`, `DreoCD05W*`, `DreoCD06W*`, `DreoCD08W*`, `DreoHM*`, `DreoPF*`, `DreoSH*`
- **Company field:** company field 17992
- **Service / service-data UUID:** `5348`
- **AD shape (ordered type/data length):** `01/1,09/11,03/2,19/2,FF/5`, `01/1,09/12,08/12`
- **What it is:** Dreo-named connected tower fans, air conditioners, appliances and heaters sharing one broadcast structure.
- **Confidence and limits:** Category candidates; the shared shape is not an exclusive category clue. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Dreo Bluetooth-assisted smart tower-fan family](https://www.dreo.com/products/dreo-cruiser-pro-t2-tower-fan) | `DreoTF<hidden five-character suffix>`; 36 variants have service UUID `5348` and `01/1, 09/11, 03/2, 19/2, FF/5`; 16 have `01/1, 09/12, 08/12` without advertised UUID | **52 / 52 / 605** | Manufacturer documents Bluetooth setup then Wi-Fi control, but **neither shape proves a particular tower-fan SKU**. |
| [Dreo connected portable-air-conditioner family candidate](https://support.dreo.com/en/support/troubleshooting/network-connection) | `DreoAC*` (suffix hidden), 8 variants with UUID `5348`, auxiliary field 17992 and `01/1,09/11,03/2,19/2,FF/5`; one weaker name-only variant | **9 / 9 / 55** | Dreo documents Bluetooth discovery/Wi-Fi setup and [AC516S](https://support.dreo.com/en/support/product/ac516s/smart?id=1753294451972771841&secondCategoryId=1889193926626164737) as a product, but **not** this BLE name-to-SKU mapping. |
| [Dreo-named connected-appliance **code-family** candidate](https://www.dreo.com/collections/dreo-air-comfort) | Public tokens `DreoCD05W*` (**4 / 4 / 40**), `DreoCD06W*` (**2 / 2 / 73**), `DreoCD08W*` (**2 / 2 / 41**); suffixes hidden; all UUID `5348`, `01/1,09/11,03/2,19/2,FF/5` | **8 / 8 / 154** | Three broadcast codes, **not three established retail models**. Original catalogue only documents Dreo's product range; CD meaning, appliance type and SKU mapping remain unknown. |
| [Dreo-named connected humidifier **candidate**](https://www.dreo.com/products/hm311s-smart-humidifier) | `DreoHM*` (suffix hidden), UUID `5348`, auxiliary field 17992 and `01/1,09/11,03/2,19/2,FF/5` | **7 / 7 / 79** | Dreo offers smart humidifiers and [Bluetooth discovery for setup](https://support.dreo.com/en/support/troubleshooting/network-connection), but no source binds this BLE token/shape to HM311S or even definitively to a humidifier; same shape also appears in DreoAC/CD. |
| [Dreo PF-series connected pedestal/air-circulation fan candidate](https://support.dreo.com/es/support/product/pf513s-613s/smart) | `DreoPF*` (suffix hidden), `01/1,09/11,03/2,19/2,FF/5`; company field 17992 unverified | **11 / 11 / 25** | Original PF513S/613S page confirms fan product line, but no source maps this BLE name to those retail models; category remains a moderate-confidence candidate, not an exact fan SKU. |
| [Dreo-named connected space-heater **candidate**](https://www.dreo.com/blogs/news/discover-the-dreo-wh719s-smart-wall-mounted-heater-your-ideal-wall-mounted-electric-heater-with-thermostat) | `DreoSH*` (5-byte suffix hidden), service UUID `5348`; `01/1,09/11,03/2,19/2,FF/5` | **7 / 7 / 73** | Original WH719S heater page describes Bluetooth-assisted setup, **not** a mapping from `DreoSH` to that model or even conclusive heater identification. Shared Dreo appliance shape is not an exclusive category clue. |

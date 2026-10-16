---
qmd:
  metadata:
    title: "nLight AIR rSDGR outdoor fixture sensor"
    date: "2026-10-14"
    tags: [hospital, lighting, occupancy-sensing]
    confidence: medium
---

# nLight AIR rSDGR outdoor fixture sensor

## Identification and use

The `rSDGR…` and `rSDGRRSX…` prefixes support an nLight AIR outdoor fixture-embedded occupancy/daylight sensor **family candidate**, but no original default BLE advertisement-name or suffix rule was found. RSX is associated with an outdoor area-luminaire family; an **unverified numeric suffix is not by itself a new sensor model** and cannot reveal actual luminaire options. Possible applications include site lighting and parking areas, but the records do not establish such a use at any hospital.

**BLE supports commissioning/discovery; normal nLight AIR wireless control is
900 MHz.** The Bluetooth metadata is not 900 MHz business traffic.
[Acuity's original outdoor-controls article](https://insights.acuitybrands.com/holophane-whats-new-blog/get-an-nlight-enabled-infrastructure-today-2) describes PIR/photocell functions, 900 MHz communication and phone Bluetooth setup for this equipment class; it does not certify the exact historical advertisement, an occupancy event, lamp state or a live control session.

A separate read-only **first-party, unfiltered** `cluetooth` prefix aggregation found `rSDGR*` **7 exact `(addr,raw)` variants / 7 addresses / 12 scans**, of which `rSDGRRSX*` is a **subset** (**6 / 6 / 11**); the remaining non-RSX prefix contributes **1 / 1 / 1**. All have ordered `01/1,09/13,FF/20`, parsed company field 11781 and no advertised service/service-data UUID; this shared layout cannot determine a sensor SKU or fixture option. These counts are **not** added to earlier WiGLE results or evidence of the same physical devices, hospital installation, occupation sensing or 900 MHz control.

Research company ID 11781 (`0x2E05`) was not an assigned SIG company identifier
in the checked list. It must not be promoted to a confirmed manufacturer or a
medical-device attribution. The name/product match is the basis of this lead;
without raw ADV bytes the ID discrepancy remains unresolved. No exact luminaire,
deployment, ownership, or control session is verified.

## Sources

- [Acuity rSDGR product page — original](https://www.acuitybrands.com/products/detail/817178/nlight/rsdgr/nlightr-air-outdoor-fixture-embedded-smart-sensor).
  [Verified Wayback fallback, captured 2026-02-23 18:08:17 UTC](https://web.archive.org/web/20260223180817/https://www.acuitybrands.com/products/detail/817178/nlight/rsdgr/nlightr-air-outdoor-fixture-embedded-smart-sensor).
- [nLight AIR operating architecture — original](https://nlight.acuitybrands.com/nlight-air/how-nlight-air-works).
- [Acuity outdoor nLight-enabled infrastructure overview — original](https://insights.acuitybrands.com/holophane-whats-new-blog/get-an-nlight-enabled-infrastructure-today-2): family-level sensing and wireless roles, not captured control traffic.
- [Bluetooth SIG company identifiers — original](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml).
- Private research: `hospital_iot_candidates_2026-10-14.md`, rSDGR row,
  and `today_all_records_2026-10-14.md`, company-ID caveat.

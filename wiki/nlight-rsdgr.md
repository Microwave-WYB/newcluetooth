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

The `rSDGR…` and `rSDGRRSX…` prefixes match nLight AIR's outdoor,
fixture-embedded occupancy/daylight sensor family. RSX is associated with an
outdoor area-luminaire family, not a reason to treat every suffix as a new sensor
model. Possible applications include site lighting and parking areas, but the
records do not establish such a use at any hospital.

**BLE supports commissioning/discovery; normal nLight AIR wireless control is
900 MHz.** The Bluetooth metadata is not 900 MHz business traffic.

Research company ID 11781 (`0x2E05`) was not an assigned SIG company identifier
in the checked list. It must not be promoted to a confirmed manufacturer or a
medical-device attribution. The name/product match is the basis of this lead;
without raw ADV bytes the ID discrepancy remains unresolved. No exact luminaire,
deployment, ownership, or control session is verified.

## Sources

- [Acuity rSDGR product page — original](https://www.acuitybrands.com/products/detail/817178/nlight/rsdgr/nlightr-air-outdoor-fixture-embedded-smart-sensor).
  [Verified Wayback fallback, captured 2026-02-23 18:08:17 UTC](https://web.archive.org/web/20260223180817/https://www.acuitybrands.com/products/detail/817178/nlight/rsdgr/nlightr-air-outdoor-fixture-embedded-smart-sensor).
- [nLight AIR operating architecture — original](https://nlight.acuitybrands.com/nlight-air/how-nlight-air-works).
- [Bluetooth SIG company identifiers — original](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml).
- Private research: `hospital_iot_candidates_2026-10-14.md`, rSDGR row,
  and `today_all_records_2026-10-14.md`, company-ID caveat.

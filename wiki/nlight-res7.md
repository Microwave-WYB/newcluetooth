---
qmd:
  metadata:
    title: "nLight AIR rES7 fixture-integrated sensor"
    date: "2026-10-14"
    tags: [hospital, lighting, occupancy-sensing]
    confidence: medium
---

# nLight AIR rES7 fixture-integrated sensor

## Identification and use

The `rES7…` name prefix matches Acuity nLight AIR's fixture-integrated
occupancy/daylight sensor family. Suffixes are not evidence of a different model.
This is a lighting-infrastructure lead, not a clinical monitor.

**BLE is for mobile commissioning/discovery; normal nLight AIR wireless
lighting control uses 900 MHz.** A WiGLE Bluetooth record does not capture that
900 MHz control traffic or prove any particular control state.

## Important attribution pitfall

Company ID 3589 (`0x0E05`) is assigned by the SIG to SUREPULSE MEDICAL LIMITED,
but the corresponding research names were rES7-prefixed. The documented
rES7 product-name match favors lighting, **not SurePulse neonatal monitors**.
The conflicting ID remains unresolved: nonstandard reuse, parsing, or source
aggregation cannot be distinguished without raw ADV bytes. Do not “correct”
the original records or treat either identity as authenticated. No exact fixture,
hospital deployment, ownership, or vulnerability is established.

## Sources

- [Acuity nLight rES7 sensor — original product page](https://nlight.acuitybrands.com/products/detail/593899/nlight/res7-sensor/nlight-air-fixture-integrated-wireless-sensor).
- [Acuity: CLX embedded nLight AIR options — original](https://insights.acuitybrands.com/lithonia-lighting-whats-new/clx-is-now-even-better-with-two-embedded-nlight-air-options-2).
- [nLight AIR operating architecture — original](https://nlight.acuitybrands.com/nlight-air/how-nlight-air-works).
- [rES7 manufacturer specification — public third-party PDF mirror](https://www.prulite.com/wp-content/uploads/2023/04/res7.pdf).
- [Bluetooth SIG company identifiers — original](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml).
- Private research: `medical_exploration_report.md`, section 3, and
  `hospital_iot_candidates_2026-10-14.md`, rES7 row. No dated Wayback fallback
  was verified for the original rES7 or architecture URLs.

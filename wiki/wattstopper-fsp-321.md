---
qmd:
  metadata:
    title: "Wattstopper FSP-321 fixture sensor"
    date: "2026-10-14"
    tags: [hospital, lighting, occupancy-sensing]
    confidence: medium
---

# Wattstopper FSP-321 fixture sensor

## Identification and use

The `FSP-321…` prefix matches Legrand/Wattstopper's in-fixture PIR occupancy
and daylight-responsive 0–10 V dimming sensor. The manufacturer describes a
Bluetooth Low Energy configuration interface and universal-voltage product.
The [original Sensor Configuration mobile-app guide](https://legrand.webdamdb.com/directdownload.php?ti=55647189&tok=zvtDY4W5L9kSbTfqCa%2Fx1wRR), p. 5, says a discovered sensor's **default displayed name is its model**, but a user-configured name may replace it. That supports a model-like `FSP-321…` lead; it **does not** specify the observed suffix format, prove these were original ADV name bytes, or authenticate the hardware.

A separate read-only **first-party, unfiltered** `cluetooth` `FSP-321*` cohort contains **16 exact `(addr,raw)` variants / 16 addresses / 365 scans**, split by distinct public model tokens: `FSP-321` (**14 / 14 / 294**) and [`FSP-321B`](https://legrand.webdamdb.com/directdownload.php?ti=55652784&tok=%2FrxZ%2FyqkiJnmv7216rcU%2FQRR) (**2 / 2 / 71**), whose original manufacturer instructions describe a Bluetooth-equipped IP66 in-fixture PIR dimming sensor. The **`B` is part of the model**, not a private suffix; trailing per-device characters remain hidden. Both groups share ordered AD `FF/8,01/1,09/15`, LEGRAND-assigned company field 1414 and no advertised service/service-data UUID. These independent counts **do not** authenticate units or map them to earlier WiGLE entries or hospital installations; shared AD shape alone cannot distinguish the two models, and lack of an advertised service does not mean no GATT service exists.

This is a plausible lighting-sensor model-family match, not confirmation of a
particular lens, configuration, fixture, or measured occupancy. Bluetooth
discovery is not evidence of people being tracked or of a live dimming command.
No verifiable raw ADV or GATT exchange is available for the **historical WiGLE entries**. The separately reported first-party records support only parsed passive AD structure, not authenticated hardware, measured occupancy, a configuration/dimming session, hospital deployment or ownership.
The nLight AIR 900 MHz architecture must not be generalized to this different
manufacturer's product.

## Sources

- [Legrand/Wattstopper FSP-321 — original product page](https://www.legrand.us/lighting-controls-and-systems/occupancy-and-vacancy-sensors/in-fixture-sensors/0-10v-pir-in-fixture-sensor-bl-e-universal-voltage/p/fsp-321).
- [Legrand Sensor Configuration Mobile App User Guide — original](https://legrand.webdamdb.com/directdownload.php?ti=55647189&tok=zvtDY4W5L9kSbTfqCa%2Fx1wRR), p. 5: default model-name display and optional custom naming.
- [FSP-321B installation instructions — original manufacturer PDF](https://legrand.webdamdb.com/directdownload.php?ti=55652784&tok=%2FrxZ%2FyqkiJnmv7216rcU%2FQRR): public `B` model and fixture sensor family; not an observed session.
- Private research: `hospital_iot_candidates_2026-10-14.md`, FSP-321 row.
  No dated Wayback fallback was verified for this product URL.

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

This is a plausible lighting-sensor model-family match, not confirmation of a
particular lens, configuration, fixture, or measured occupancy. Bluetooth
discovery is not evidence of people being tracked or of a live dimming command.
No raw radio frames, hospital deployment, or ownership were verified.
The nLight AIR 900 MHz architecture must not be generalized to this different
manufacturer's product.

## Sources

- [Legrand/Wattstopper FSP-321 — original product page](https://www.legrand.us/lighting-controls-and-systems/occupancy-and-vacancy-sensors/in-fixture-sensors/0-10v-pir-in-fixture-sensor-bl-e-universal-voltage/p/fsp-321).
- [Legrand Sensor Configuration Mobile App User Guide — original](https://legrand.webdamdb.com/directdownload.php?ti=55647189&tok=zvtDY4W5L9kSbTfqCa%2Fx1wRR), p. 5: default model-name display and optional custom naming.
- Private research: `hospital_iot_candidates_2026-10-14.md`, FSP-321 row.
  No dated Wayback fallback was verified for this product URL.

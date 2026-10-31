---
qmd:
  metadata:
    title: "Primex Sync clock/repeater family candidate"
    date: "2026-10-14"
    tags: [hospital, time-synchronization]
    confidence: medium
---

# Primex Sync clock/repeater family candidate

## Identification and use

Primex's [original signal-strength support article](https://support.primexinc.com/hc/en-us/articles/360022473254-Measure-Bluetooth-signal-strength-at-Bluetooth-device-install-location) explicitly says Bluetooth **clocks and repeaters** advertise as `PrimexClock`, whereas a Bluetooth Bridge advertises as `Bridge`. The ordinary page returned 403, but its [manufacturer help-center article API](https://support.primexinc.com/api/v2/help_center/en-us/articles/360022473254.json) provided the full text. The historical name therefore supports a **clock-or-repeater family candidate**, not a role, analog/digital clock model, bridge identity or authenticated hardware.

Primex describes a OneVue Sync Bridge, NTP-derived time and BLE distribution in its [original technology overview](https://primexinc.com/pages/technology-solutions/bluetooth). Its [Bluetooth LED clock listing](https://primexinc.com/products/bluetooth-led-digital-clock-4-digit) explicitly includes **hospitals** among intended settings. Those are product capabilities and markets, **not** proof the saved name represents that digital model, a particular bridge, actual time source, mesh session, synchronization accuracy, hospital installation or ownership. No live control traffic or clock configuration was captured.

## Sources

- [Primex: How the Sync Bluetooth solution works — original](https://support.primexinc.com/hc/en-us/articles/360022649133-How-the-Sync-Bluetooth-solution-works).
  [Verified Wayback fallback, captured 2025-09-07 04:56:13 UTC](https://web.archive.org/web/20250907045613/https://support.primexinc.com/hc/en-us/articles/360022649133-How-the-Sync-Bluetooth-solution-works).
  The replay exposes the system-component headings; it is not independent
  verification of a live clock's operation.
- [Primex direct advertisement-name explanation — original support article and original JSON text](https://support.primexinc.com/api/v2/help_center/en-us/articles/360022473254.json): `PrimexClock` applies to clocks **and** repeaters; `Bridge` applies to Bluetooth bridges.
- [Primex Bluetooth LED digital-clock product](https://primexinc.com/products/bluetooth-led-digital-clock-4-digit) — hospital use-case example, **not** the observed hardware model.
- Private research: `hospital_iot_candidates_2026-10-14.md`, PrimexClock row.

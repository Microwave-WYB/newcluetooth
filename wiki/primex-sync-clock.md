---
qmd:
  metadata:
    title: "Primex Sync clock family"
    date: "2026-10-14"
    tags: [hospital, time-synchronization]
    confidence: medium
---

# Primex Sync clock family

## Identification and use

The name `PrimexClock` is consistent with Primex's OneVue Sync Bluetooth
clock family. Primex describes a system of bridges, clocks, repeaters, and a
Bluetooth mesh network, with OneVue management. The existing research describes
a bridge distributing NTP-derived time to facility clocks.

This identifies a plausible synchronized-clock family, **not** an exact analog
or digital clock model, a particular bridge, or a verified time source in the
observed environment. A discovery name is not proof of synchronization accuracy,
hospital deployment, or ownership; no mesh traffic or clock configuration was
captured.

## Sources

- [Primex: How the Sync Bluetooth solution works — original](https://support.primexinc.com/hc/en-us/articles/360022649133-How-the-Sync-Bluetooth-solution-works).
  [Verified Wayback fallback, captured 2025-09-07 04:56:13 UTC](https://web.archive.org/web/20250907045613/https://support.primexinc.com/hc/en-us/articles/360022649133-How-the-Sync-Bluetooth-solution-works).
  The replay exposes the system-component headings; it is not independent
  verification of a live clock's operation.
- Private research: `hospital_iot_candidates_2026-10-14.md`, PrimexClock row.

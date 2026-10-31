---
qmd:
  metadata:
    title: "Historical connectivity names with unresolved device identity"
    date: "2026-10-16"
    tags: [iot, facilities, conference, wigle]
    confidence: low
---

# Historical connectivity names with unresolved device identity

These counts come **only from an earlier saved WiGLE report** (`hospital_iot_followup_2026-10-15.md` in external run `us-osm-hospitals/20261013T225104Z`). The original accepted-response cache and ledger were not available to this follow-up, so the numbers are **report snapshots**, not newly computed totals or first-party BLE advertisements. API BT/BLE labels, company fields and saved OSM-polygon intersections do not authenticate an individual product, prove actual Bluetooth frames, or establish hospital installation or ownership.

| Sanitized name family | Reported returned entries / distinct API IDs / polygon-intersecting entries | What original manufacturer material supports | What remains unknown |
|---|---:|---|---|
| `NET*` (actual rest withheld) | **8 / 8 / 2** | Seven entries have company field 1704, assigned to GD Midea Air-Conditioning Equipment; [Midea's original MSmartHome AC/dehumidifier instructions](https://www.midea.com/content/dam/midea-aem/us/air-conditioners/window-air-conditioners/maw10s1wbl-t-/MSmartHome-AC-and-Dehum-Instructions.pdf) say **some** models permit local Bluetooth control. | The eighth entry has a different company field (6). No original `NET` broadcast-name mapping to a Midea product was found. Actual brand, device class and model **remain unknown**; do not call all eight air conditioners. |
| `PowerConfBLE` | **2 / 2 / 1** | [AnkerWork PowerConf original support](https://support.ankerwork.com/s/product/a085g000000NlwWAAS/powerconf) and its [conference-device catalogue](https://us.ankerwork.com/collections/microphone) show Bluetooth/USB conference speakerphones. A **PowerConf-family speakerphone candidate** is plausible. | No manufacturer documentation binds this exact broadcast name to model A3301, an audio link, BLE control channel or active use. |
| `LifeSpan-TM-*` (unverified numeric tail withheld) | **2 / 1 / 2** | [LifeSpan's original workstation manuals](https://lifespanfitness.com/pages/operating-guides-for-active-workstations) and [Bluetooth setup background](https://lifespanfitness.com/blogs/news/treadmill-desk-and-bike-desk-personal-settings) describe Bluetooth for **some** treadmill/bike desks. | No original product mapping for the withheld suffix: **product category itself is unresolved**, not a verified treadmill or rehabilitation device. |

A later first-party `cluetooth` **complete-name equality** check returned zero for `PowerConfBLE` and the privately held full `LifeSpan-TM-*` string. This narrow negative does not negate historical WiGLE names or prove that devices of those families were absent under other names. API IDs are not physical-device counts; the saved polygons are geometry, not institutional inventories. No identifiers, suffixes, coordinates, raw radio payloads or private responses are published.

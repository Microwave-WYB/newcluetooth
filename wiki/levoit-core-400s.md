---
qmd:
  metadata:
    title: "Levoit Core 400S air purifier BLE fingerprint"
    date: "2026-10-16"
    tags: [iot, ble, air-purifier]
    confidence: high
---

# Levoit Core 400S smart air purifier

The first-party `cluetooth` database contains **3 exact `(addr, raw)` advertisement variants, 3 observed addresses and 9 scans** whose local name equals `Core400s` case-insensitively (not a broad prefix match). Their ordered AD types are `[1, 255, 9]` (flags, manufacturer data, complete local name), with corresponding **data** lengths `[1, 14, 8]` bytes. No advertised service UUID was observed. Company ID `1744` is an optional observation, **not** an attribution requirement. Addresses and variants are not verified physical-device counts.

[Levoit's Core 400S manual](https://files.vesync.com/desc/LEVOIT_Core400S_USER_MANUAL.pdf) identifies a smart air purifier and instructs users on p. 17 to turn on phone Bluetooth during VeSync setup and use a secure 2.4 GHz Wi-Fi network. The distinctive model-name token plus that documentation supports a **Core 400S model-line candidate**, not a particular regional SKU or authenticated device identity. The manual does not publish the observed BLE name or AD layout.

An earlier saved WiGLE hospital-region snapshot independently reported **1 returned entry / 1 API identifier / 1 polygon-intersecting entry** under this model-like name. It is **not** another first-party variant, cannot be added to the 3 / 3 / 9 figures, and is not linked to the same physical purifier. The original manual establishes a setup requirement, **not** continuous BLE control, a captured setup session, medical-device authorization, clinical efficacy or hospital installation.

**Do not match on shape alone:** the strict `[1:1,255:14,9:8]` shape includes 21 advertisement variants, only 3 of which match `Core400s`; wildcarding the name length increases the shape cohort to 68. No hospital ownership, deployment or location is inferred.

Provenance: read-only `ro` query of first-party simi `cluetooth.adv_shapes`, researched **2026-10-16**; private details in `core400s-first-party-attribution.md` under the external `us-osm-hospitals/20261013T225104Z` research run. Research date is not observation date; no raw addresses, payloads or serials are published.

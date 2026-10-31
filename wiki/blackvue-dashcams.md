---
qmd:
  metadata:
    title: "BlackVue BlackVue* dashcam series names"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# BlackVue `BlackVue*` dashcam series names

## How to recognize it

- **Local name:** `750XPLUS*`, `970XLTE*`, `770X*`, `900X*`, `900XPLUS*`, `970X*`, `750XLTE*`
- **AD shape (ordered type/data length):** `01/1,09/23`, `01/1,09/19`, `01/1,0A/1,09/19`, `01/1,09/20`, `01/1,09/26`
- **What it is:** BlackVue connected-dashcam series name tokens.
- **Confidence and limits:** Series-name leads, no manufacturer ID or service UUID. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

First-party simi `cluetooth` read-only aggregates, researched **2026-10-16**. The principal feature here is a **sanitized model token in the advertised name**, not a claim that a generic BLE shape uniquely identifies a model. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, **not confirmed physical devices**. Hide all device-specific suffixes; names can be spoofed, and a research date is not an observation date. No hospital association is required.

## BlackVue connected dashcams

Name-model tokens distinguish seven candidate series within **19 variants / 19 addresses / 176 scans**; the broader case-insensitive `BlackVue*` prefix has one further **unassigned** variant/address/scan (20 / 20 / 177 total). The [official BlackVue smartphone connection instructions](https://manual.blackvue.com/docs/dr770x/playing-and-managing-videos/using-your-smartphone-android-ios-dr750x-plus-series/) describe Bluetooth Seamless Pairing followed by Wi-Fi; Cloud connectivity is optional, not inferred from a BLE advertisement. These are series-name leads, not proof of camera sub-configuration.

| Name-model token (suffix hidden) | Variants / addresses / scans | Ordered AD types / data lengths in bytes |
|---|---:|---|
| `750XPLUS*` | **4 / 4 / 5** | `01/1, 09/23` |
| `970XLTE*` | **3 / 3 / 124** | `01/1, 09/23` |
| `770X*` | **3 / 3 / 5** | `01/1, 09/19` |
| `900X*` | **3 / 3 / 12** | `01/1, 0A/1, 09/19` |
| `900XPLUS*` | **3 / 3 / 3** | `01/1, 09/23` |
| `970X*` | **2 / 2 / 25** | `01/1, 09/20` |
| `750XLTE*` | **1 / 1 / 2** | `01/1, 09/26` |

AD lengths exclude the length/type bytes. The `900X` and `970X` rows exclude their separately listed `PLUS` and `LTE` tokens respectively. These advertisements do not carry a manufacturer ID or service UUID. Identical simple AD shapes across distinct names are **not** grounds to merge product series. No full MACs, per-device names, serials, payloads or locations are published.

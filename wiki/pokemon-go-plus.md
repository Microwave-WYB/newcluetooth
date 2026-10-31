---
qmd:
  metadata:
    title: "Pokémon GO Plus / Plus+ Pokemon* accessories"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Pokémon GO Plus / Plus+ `Pokemon*` accessories

## How to recognize it

- **Local name:** `Pokemon GO Plus +`, `Pokemon GO Plus`
- **Company field:** company field 1363
- **AD shape (ordered type/data length):** `01,FF,20,09`
- **What it is:** Pokémon GO Plus+ and original GO Plus (or compatible) accessories.
- **Confidence and limits:** Candidates; third-party auto-catchers cannot be ruled out. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

First-party simi `cluetooth` read-only aggregates, researched **2026-10-16**. The principal feature here is a **sanitized model token in the advertised name**, not a claim that a generic BLE shape uniquely identifies a model. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, **not confirmed physical devices**. Hide all device-specific suffixes; names can be spoofed, and a research date is not an observation date. No hospital association is required.

## Pokémon GO physical accessories

The case-insensitive `Pokemon*` cohort has **52 variants / 51 addresses / 1,023 scans**. Two exact product-name tokens account for the cohort, without implying that all broadcasters are original licensed hardware.

| Observed name token | Candidate and source | Variants / addresses / scans |
|---|---|---:|
| `Pokemon GO Plus +` | [Pokémon GO Plus+](https://www.pokemongoplusplus.com/en/) (also works with Pokémon Sleep) | **42 / 41 / 924** |
| `Pokemon GO Plus` | [Original GO Plus or compatible accessory](https://niantic.helpshift.com/hc/en/6-pokemon-go/faq/77-official-bluetooth-devices-1689609298/) | **10 / 10 / 99** |

The Plus+ group has ordered AD types `01,FF,20,09`, data lengths `1,17,4,17` and auxiliary company field 1363; the earlier-name group uses four shapes and sometimes a service-data UUID field. Third-party auto-catchers or software emulation **cannot be ruled out**, especially for the earlier-name group. No Poké Ball Plus attribution is made.

---
qmd:
  metadata:
    title: "Fitbit Charge* health bands"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Fitbit `Charge*` health bands

## How to recognize it

- **Local name:** `Charge*`
- **Service / service-data UUID:** `abbaff00-e56a-484c-b832-8b17cf6cbfe8`, `FD62`, `180D`
- **What it is:** Fitbit Charge 5 and Charge 6 name tokens; other `Charge*`/`Charger*` names unassigned.
- **Confidence and limits:** Model-name candidates; names/UUIDs can be spoofed. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## Fitbit Charge health bands

A current case-insensitive `Charge*` cohort is **43 / 37 / 333**, up from 39 / 33 / 267 in an earlier snapshot. Two exact model-name groups are supported; other `Charge*` or `Charger*` names are **unassigned**, not automatically JBL speakers or charging hardware.

| Name token / Google product source | Variants / addresses / scans | Supporting feature |
|---|---:|---|
| [Fitbit Charge 5](https://support.google.com/googlehealth/answer/14237922?hl=en) | **4 / 4 / 7** | UUID `abbaff00-e56a-484c-b832-8b17cf6cbfe8`; model candidate. |
| [Fitbit Charge 6](https://store.google.com/product/fitbit_charge_6?hl=en-US) | **30 / 28 / 250** | Many advertise `FD62`, a [Google LLC-assigned member service UUID](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/uuids/member_uuids.yaml) associated with the Fitbit ecosystem; some carry `180D` or no service UUID. |
| Other `Charge*` / `Charger*` | **9 / 5 / 76** | No reliable product attribution. |

Names/UUIDs can be spoofed, and the unknown group is not assigned to [JBL Charge](https://www.jbl.com/JBLCHARGE6BLKAM.html) from a generic word alone.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.

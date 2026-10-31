---
qmd:
  metadata:
    title: "Hospital-relevance evidence tiers"
    date: "2026-10-16"
    tags: [hospital, iot, evidence]
    confidence: medium
---

# Hospital-relevance evidence tiers

**No saved observation proves that a particular hospital installed, owns or used any identified device.** Manufacturer documents establish *possible product functions*, while historical WiGLE names and saved OSM-polygon intersections are not authenticated hardware IDs or institutional inventories. The first-party `cluetooth` BLE catalogue is a **different, unfiltered population** and cannot be added to or matched against the hospital-region WiGLE counts by name.

| Tier | Examples | What is supported, and what is not |
|---|---|---|
| Original manufacturer material describes clinical or hospital-facing use | [ResMed AirMini](resmed-airmini.md), [Enovate EmTag](enovate-emtag.md), [Gozio wayfinding ecosystem](gozio-wayfinding-beacon.md), [Honeywell Xenon HC base family](honeywell-xenon-hc-base.md), [BIXOLON SLP-DX220B](bixolon-slp-dx220b.md) | Respiratory care, clinical asset workflows, wayfinding, healthcare barcode workflows or healthcare-labelled printing are plausible **product applications**. None confirms observed unit identity, a particular hospital deployment, patient monitoring or printed content. The BIXOLON healthcare application is not medical-device certification. |
| Hospital facilities or office infrastructure is plausible | [Sonicu environment/cold-chain monitoring](sonicu-sonishield-duo.md), [Primex clock/repeater](primex-sync-clock.md), [HID Seos access ecosystem](hid-seos.md), [nLight AIR](nlight-res7.md), [Wattstopper FSP-321](wattstopper-fsp-321.md), [Cisco 8945](cisco-ip-phone-8945.md) / [8851](cisco-ip-phone-8851.md) phones, [HP office printers](hp-color-laserjet-m479fdw.md), [Sony professional displays / Alphabet lighting](historical-facility-display-lighting-leads.md) | Product categories fit buildings and offices, including hospitals, **but** that is not evidence of an installation. BLE discovery is not a captured control command. [HP 3001dw's `DIRECT-` lead](hp-laserjet-3001dw.md) especially resembles Wi-Fi Direct and has **no verified Bluetooth frames**. |
| Ordinary consumer devices, no hospital attribution | [Molekule purifier](molekule-purifier.md), [Levoit Core 400S](levoit-core-400s.md), [Anker PowerConf family lead](historical-ambiguous-connectivity-leads.md) | A consumer product may occur near a hospital, but proximity or a common function does not establish healthcare deployment. Do not borrow a different model's medical claims or merge first-party and WiGLE records into one device count. |
| Product identity or even class unresolved | [`BladderSc`](verathon-bladderscan.md), [CAREL host/controller](carel-hvac.md), [`NET*` / masked LifeSpan name](historical-ambiguous-connectivity-leads.md), first-party [ResMed](resmed-company-909.md) and [Baxter](unassigned-ble-name-prefixes.md) name signals | Similar names, company allocations and generic services can suggest research directions but may conflict; no exact clinical model or hardware class is established. In particular `BladderSc` is **not** a confirmed Verathon unit and a `Baxter*` name with another company's field is **not** a confirmed infusion device. |

**Measurement rule:** historical returned entries, distinct WiGLE API identifiers and polygon-intersecting entries are three separate quantities, none a physical-device count. First-party BLE exact `(addr,raw)` variants, addresses and scans are three more separate quantities. API BT/BLE labels, static client capabilities and manufacturer specifications are not raw observed ADV/GATT, authenticated identities, pairing events or use-state evidence. All published names are public models/families or masked patterns; no raw identifiers or precise locations appear here.

The separate [first-party BLE family pages](README.md) is **mixed**, not one consumer tier: its public tokens include consumer devices, facility lighting such as `rPP20`, and unresolved Philips name groups. Classify individual entries by evidence rather than placing the whole catalogue in any hospital-relevance tier.

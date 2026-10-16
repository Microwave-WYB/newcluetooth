---
qmd:
  metadata:
    title: "IoT product fingerprint research wiki"
    date: "2026-10-16"
    tags: [iot, ble]
    confidence: medium
---

# IoT product fingerprint research wiki

Earlier entries are sanitized hospital-region leads from private research recorded since **2026-10-14**; newer entries also use first-party BLE advertisements and **do not require any hospital association**.
Dates are research-record dates, **not** inferred radio-observation dates. Confidence describes product attribution, not verified installation.

[**What is actually hospital-related? Evidence tiers and limits**](hospital-relevance-evidence-tiers.md) distinguishes original hospital-facing product functions from plausible facilities equipment, consumer devices, and unknown identities. No listed observation establishes an installed or owned device at a particular hospital.

## Earlier hospital-region device and family pages

- [Gozio-associated hospital-wayfinding signal — hardware model unknown](gozio-wayfinding-beacon.md)
- [ResMed AirMini candidate](resmed-airmini.md)
- [Enovate EmTag asset tag](enovate-emtag.md)
- [Sonicu SoniShield Duo](sonicu-sonishield-duo.md)
- [Primex Sync clock/repeater family candidate](primex-sync-clock.md)
- [HID Seos door-reader / credential ecosystem](hid-seos.md)
- [Cisco Unified IP Phone 8945 candidate](cisco-ip-phone-8945.md)
- [Cisco IP Phone 8851 candidate](cisco-ip-phone-8851.md)
- [Honeywell Xenon HC base family](honeywell-xenon-hc-base.md)
- [HP Color LaserJet Pro MFP M479fdw candidate](hp-color-laserjet-m479fdw.md)
- [HP LaserJet Pro MFP 4101fdw candidate](hp-laserjet-4101fdw.md)
- [HP LaserJet Pro 3001dw Wi-Fi Direct name candidate — Bluetooth unverified](hp-laserjet-3001dw.md)
- [BIXOLON SLP-DX220B Bluetooth label printer candidate](bixolon-slp-dx220b.md)
- [nLight AIR rES7](nlight-res7.md)
- [nLight AIR rSDGR](nlight-rsdgr.md)
- [nLight AIR rIO ZT](nlight-rio-zt.md)
- [nLight AIR rPP8 DS](nlight-rpp8-ds.md)
- [Wattstopper FSP-321](wattstopper-fsp-321.md)
- [CAREL HVAC/R candidate](carel-hvac.md)
- [Molekule purifier candidate](molekule-purifier.md)
- [`BladderSc` name — product identity unknown](verathon-bladderscan.md)
- [Historical professional BRAVIA display and Alphabet Lighting downlight leads — low confidence](historical-facility-display-lighting-leads.md)
- [Historical NET, PowerConfBLE and LifeSpan connectivity leads — unresolved](historical-ambiguous-connectivity-leads.md)

## Broader first-party BLE product fingerprints

- [Levoit Core 400S smart air purifier](levoit-core-400s.md)
- [Broader first-party BLE fingerprint catalogue](first-party-ble-fingerprints-2026-10-16.md)
- [Model-name fingerprints: Govee lighting and BlackVue dashcams](model-name-iot-fingerprints-2026-10-16.md)
- [More model-name fingerprints: Bose, Poly, Polestar, Amazfit, Shokz, Marshall, Sony and Jabra](more-iot-model-tokens-2026-10-16.md)

These entries use anonymized, read-only first-party `cluetooth` observations rather than WiGLE names. Fingerprints can combine sanitized names, service UUIDs, manufacturer data, address-prefix patterns and ordered AD structures; shape is optional and rarely unique. No hospital relationship is required or implied.

## Reading the evidence

The earlier hospital-region pages cover clinical, navigation, facilities, and office-infrastructure candidates, not an inventory or ownership claim. Generic phones and printers can be retained as possible hospital-system equipment; their model identification and plausible use do **not** establish hospital deployment.

Saved responses were considered without requiring hospital-polygon membership. WiGLE observation and distinct `netid` counts are **not physical equipment counts or proof of deployment**; names, company IDs, and BT/BLE labels do not authenticate identity.

For earlier WiGLE-based pages, no raw ADV/GATT frames were available to verify identifications. APK findings are static client capabilities, not captured sessions or vulnerability proof. No new WiGLE queries or device operations were used for those pages. Raw addresses, coordinates, device serials, responses, and APK binaries remain outside Git.

Private provenance: `hospital_iot_candidates_2026-10-14.md`,
`medical_exploration_report.md`, `today_all_records_2026-10-14.md`,
`gozio_protocol_notes.md`, and `resmed_airmini_protocol_notes.md` in the
external research run `us-osm-hospitals/20261013T225104Z`. Code citations on
APK-backed pages are relative to that run, not repository links.
Newer pages cite their own dated read-only research helpers; the private saved response corpus remains outside Git.

Original manufacturer/FCC URLs are primary. Dated Wayback fallbacks are included
only where availability and replay were checked; missing fallbacks mean no
capture was verified for that cited URL, not that no archive exists. No sources
or private artifacts were uploaded to an archive.

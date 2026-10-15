---
qmd:
  metadata:
    title: "HP Color LaserJet Pro MFP M479fdw candidate"
    date: "2026-10-15"
    tags: [hospital, printing]
    confidence: medium
---

# HP Color LaserJet Pro MFP M479fdw candidate

## What was found

One saved result from the Pacific 2026-10-15 hospital-region collection has a sanitized name of `DIRECT-<id>-HP M479fdw Color LJ`, reports company ID **101** (HP), and is labeled **BLE** by the source API. The exact name suffix and network identifier are deliberately omitted. This is a strong **model-name lead** for the HP Color LaserJet Pro MFP M479fdw (HP product W1A80A), an office multifunction printer; it does **not** establish installation or ownership at any hospital.

HP's model specifications list Bluetooth Low Energy and Wi-Fi Direct. However, HP separately documents `DIRECT-xx-HP …` as the usual **Wi-Fi Direct network-name pattern**. The name in this BLE-labeled result may have been reused across radios or represented by the source in an unexpected way. With no original advertisement bytes, Wi-Fi scan, or device session, **do not infer that Wi-Fi Direct is a Bluetooth protocol, that printing uses BLE, or that this is a captured Wi-Fi Direct network**. The company ID and recognizable model name are clues, not device authentication.

Other HP-named results in this day's saved pages include ENVY, a generic Color LaserJet, and OfficeJet Pro strings, but their exact models are not resolved here. Records are not physical-printer counts.

## Sources

- [HP: Color LaserJet Pro MFP M479 printer specifications — original manufacturer page](https://support.hp.com/us-en/document/c06372746), model M479fdw, product W1A80A, BLE connectivity.
- [HP: M479fdw product specifications — original manufacturer page](https://support.hp.com/us-en/product/product-specs/hp/19202523), Wi-Fi Direct and wireless features.
- [HP: printer setup with Wi-Fi Direct — original manufacturer page](https://support.hp.com/ee-en/document/ish_1841315-1637332-16), `DIRECT-xx-HP` network-name pattern; this documents Wi-Fi naming, **not** the observed radio protocol.
- [Bluetooth SIG assigned company identifiers — official reference](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Assigned_Numbers/out/en/index-en.html), company ID `0x0065` / 101 assigned to HP; an ID alone does not authenticate the advertiser.
- Private sanitized read-only research: `hospital_iot_new_2026-10-15.md` and `triage_today_hp_names.py` in external run `us-osm-hospitals/20261013T225104Z`; no new WiGLE request, raw identifier, location, or connection was used. No dated Internet Archive capture was verified for these URLs.

---
qmd:
  metadata:
    title: "HP LaserJet Pro MFP 4101fdw candidate"
    date: "2026-10-15"
    tags: [hospital, printing]
    confidence: medium
---

# HP LaserJet Pro MFP 4101fdw candidate

## Identification and use

One newly saved Pacific **2026-10-15** record has the sanitized name `DIRECT-<id>-HP 4101fdw LJ`, reported company ID **101** (HP), and source API type **BLE**. HP's model specifications identify the **LaserJet Pro MFP 4101fdw** (product **2Z619F**) as a multifunction printer with Bluetooth Low Energy and Wi-Fi Direct. This is a model-name lead for an office printer that could support facility operations, **not** evidence it belongs to or operates inside a hospital.

The HP 4101–4104 specification page's **manufacturer search index** explicitly marks BLE as included for **4101fdw / 2Z619F**, independently of the Ethernet spec's `10/100 BT` or `Base-T` wording (**not** Bluetooth evidence). Direct retrieval of the full HP page and data-sheet PDF failed in the follow-up, so this is indexed product-capability evidence, **not** newly verified original radio traffic or a Bluetooth printing session.

HP separately documents `DIRECT-xx-HP…` as a **Wi-Fi Direct SSID format**. Although this record was returned with a BLE label, the name and ID do not prove a captured Wi-Fi network, raw BLE frame, printing over Bluetooth, or the radio's actual advertisement format. A reported company ID is not device authentication. No printer session, document contents, unique physical-device count, or hospital ownership was verified. This is a different model from the [M479fdw candidate](hp-color-laserjet-m479fdw.md).

Separately, an unfiltered first-party `cluetooth` BLE-name search for the **standalone `4101fdw` model token** anywhere in the name found **0 exact variants / 0 addresses / 0 scans**. That does not contradict HP's indexed BLE capability or identify the historic WiGLE observation; the two sources cannot be linked.

## Sources

- [HP LaserJet Pro MFP 4101–4104 printer specifications — original manufacturer page](https://support.hp.com/emea_middle_east-en/document/ish_5455443-5562705-16), 4101fdw / 2Z619F and Bluetooth Low Energy connectivity.
- [HP 4101–4104 data sheet — original manufacturer PDF](https://h20195.www2.hp.com/v2/getpdf.aspx/c08133187.pdf), Wi-Fi Direct, BLE and office multifunction capabilities; direct automated PDF retrieval was unavailable during this check.
- [HP printer setup with Wi-Fi Direct — original manufacturer page](https://support.hp.com/ee-en/document/ish_1841315-1637332-16), explains `DIRECT-xx-HP` Wi-Fi Direct network-name pattern, not the observed protocol.
- [Bluetooth SIG company identifiers — official reference](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Assigned_Numbers/out/en/index-en.html), ID `0x0065` / 101 assigned to HP; this is not cryptographic device identity.
- Private sanitized research: `triage_after_0054.py` on already saved responses in external run `us-osm-hospitals/20261013T225104Z`; no raw MAC, exact name suffix, coordinates, new WiGLE query or device action. No dated Wayback replay was verified (the availability service returned 429 during this check).

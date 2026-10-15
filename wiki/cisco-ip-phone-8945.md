---
qmd:
  metadata:
    title: "Cisco Unified IP Phone 8945 candidate"
    date: "2026-10-15"
    tags: [hospital, telephony]
    confidence: medium
---

# Cisco Unified IP Phone 8945 candidate

## Identification and use

Five records collected on Pacific **2026-10-15** have the exact name `CP-8945`, five distinct WiGLE network IDs, source API type **BT**, and no reported company ID. Cisco uses CP-8945 for its **Unified IP Phone 8945**, a wired enterprise video/VoIP desk phone. This is a phone-family lead for an organization's communications infrastructure, not evidence of a patient device, a particular hospital installation, or five physical phones. Collection date is not the radio observation date.

Cisco's [8945 product data sheet](https://www.cisco.com/c/ja_jp/products/collateral/collaboration-endpoints/unified-ip-phone-8945/data_sheet_c78-649595.html) specifically documents **Bluetooth 2.1** for compatible wireless headsets (including the cited Jawbone ICON example). That is a headset interface on an IP phone, **not** a verified BLE GATT path or evidence that the phone's IP calls traverse Bluetooth. The `CP-8945` name is not an authenticated hardware identifier: no raw advertisement, pairing session, headset traffic, call contents, or hospital ownership was verified.

## Sources

- [Cisco Unified IP Phone 8945 data sheet — original manufacturer page](https://www.cisco.com/c/ja_jp/products/collateral/collaboration-endpoints/unified-ip-phone-8945/data_sheet_c78-649595.html), product overview explicitly says “Bluetooth 2.1” for compatible headsets.
- [Cisco 8941/8945 operating guide — original manufacturer page](https://www.cisco.com/c/en/us/td/docs/voice_ip_comm/cucme/ip_phones/8941_45/english/user/guide/8941_45_ug_release9_0_chapter2.html), shared guide; by itself it does not distinguish the models' Bluetooth capabilities.
- [FCC ID LDK89450476, Cisco UC Phone — public filing index mirror](https://fccid.io/LDK89450476). Related filing context only; the index page alone does **not** prove any observed record is this certified unit.
- Private sanitized research: `triage_after_0054.py` run against newly saved accepted responses under external run `us-osm-hospitals/20261013T225104Z`. No original MACs, coordinates, login, pairing, or WiGLE requests were used. No dated Wayback replay was verified for the original Cisco page (the availability service returned 429 during this check).

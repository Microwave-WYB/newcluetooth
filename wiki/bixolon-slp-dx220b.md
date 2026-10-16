---
qmd:
  metadata:
    title: "BIXOLON SLP-DX220B Bluetooth label-printer candidate"
    date: "2026-10-15"
    tags: [hospital, printing, labeling]
    confidence: medium
---

# BIXOLON SLP-DX220B Bluetooth label-printer candidate

## Identification and possible use

Two saved BT-labeled records have the sanitized name pattern `SLP-DX220B_<digits>`: two distinct WiGLE network IDs, with one returned entry intersecting a source hospital polygon. BIXOLON's original user manual identifies the **SLP-DX220B** as a Bluetooth-enabled label-printer variant, and the manufacturer's SLP-DX220 brochure explicitly lists **Healthcare** among its applications. It is a plausible label-printing candidate for hospital operations, but these records do **not** establish an installation, hospital ownership, printing activity, label contents, or two physical printers. Neither Bluetooth traffic nor printed output was captured.

The [manufacturer pairing instructions](https://bixolon.com/_upload/manual/Manual_SLP-DX220B_DX223B_user_english_Rev_1_03.pdf), pp. 30–31, show the public name `SLP-DX220B`, strengthening the model lead but **not** authenticating the historical numeric suffix, physical unit or actual radio frame. The manual's discussion of SSP introduced in Bluetooth Core 2.1 does **not** prove the printer's exact Bluetooth version or BLE capability. **Healthcare is a brochure application, not medical-device certification**; no wristband, patient label or actual printed content was observed.

## Sources

- [BIXOLON SLP-DX220B / SLP-DX223B user manual — manufacturer PDF](https://bixolon.com/_upload/manual/Manual_SLP-DX220B_DX223B_user_english_Rev_1_03.pdf), pp. 1, 11 and 31: model, internal Bluetooth module, and pairing instructions.
- [BIXOLON SLP-DX220 brochure — manufacturer PDF](https://www.bixolon.com/_upload/product/BR_SLP-DX220_EN_AUG24_V1.pdf), pp. 1–2: 2-inch direct-thermal label-printer family; Healthcare listed as an application.
- Private sanitized research: `hospital_iot_followup_2026-10-15.md` and read-only helper `triage_new_office_facility.py` under external run `us-osm-hospitals/20261013T225104Z`. No new WiGLE query or raw identifiers were published.

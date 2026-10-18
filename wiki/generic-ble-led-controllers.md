---
qmd:
  metadata:
    title: "Generic BLE LED-strip / colour-light controller name prefixes"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Generic BLE LED-strip / colour-light controller name prefixes

## How to recognize it

- **Local name:** `LEDDMX-00-*`, `LEDDMX-03-*`, `ELK-BLEDOM*`, `ELK-BLEDDM*`, `lednetwf*`, `QHM-*`, `Triones*`, `YX_LED fiber light`
- **Company field:** company field 12849, company field 259
- **Service / service-data UUID:** `FFB0`, `FFD0`, `FFD5`, `FFF0`, `4353`, `7777`
- **AD shape (ordered type/data length):** `01/1,09/8`, `01/1,09/13`, `01/1,02/4,FF/8,09/18`, `01/1,09/20`
- **What it is:** Multi-brand, white-label or compatible-app BLE LED controllers identified only by name prefix (LEDDMX, ELK-BLEDOM, ELK-BLEDDM, lednetwf, QHM, Triones, YX_LED).
- **Confidence and limits:** Class candidates only; each prefix is a distinct protocol and no maker is established. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [LED LAMP-compatible color-light controller candidate](https://apps.apple.com/tm/app/led-lamp/id1449109039) | `LEDDMX-00-*` (10 / 10 / 1,453) or `LEDDMX-03-*` (3 / 3 / 11); some advertise `FFB0`; [independent hands-on name clue](https://github.com/user154lt/LEDDMX-00) | **13 / 13 / 1,464** | App developer is **not** proven the hardware maker; `DMX` name does not establish professional DMX512 support. Hands-on GATT `FFE0` and observed advertising `FFB0` are distinct, with no asserted equivalence. |
| [Generic ELK-BLEDOM BLE RGB LED-strip controllers](https://manuals.plus/richsing/duocol-elk-bledom-strip-led-lights-user-manual-richsing) | `ELK-BLEDOM*`; 11 ordered AD layouts, manufacturer field 12849, 259 or absent; [independent controller app](https://play.google.com/store/apps/details?id=com.leet.elkotrol) | **38 / 38 / 596** | Third-party-hosted manual and compatible app support a **multi-brand/white-label** lighting-controller family, not one hardware maker or model. Earlier snapshot was 37 / 37 / 575. |
| [Generic BLE LED strip/light controller `ELK-BLEDDM*` candidate](https://github.com/dave-code-ruiz/elkbledom/issues/92) | Name `ELK-BLEDDM*` (suffix hidden); 9 with `01/1,09/13`, 3 with repeated `09` fields; no advertised UUID/company field | **12 / 12 / 312** | Third-party hands-on report pairs the same-named light controller, but its GATT `FFF0/FFF3/FFF4` are **not** observed in these advertisements. Maker/model and RGB capabilities unknown; no attribution to ELK security products. |
| [Zengge/Magic Hue-compatible color-light controller candidate](https://github.com/8none1/zengge_lednetwf) | Case-insensitive `lednetwf*` (suffix hidden), no advertised service UUID; `01/1,09/20 or 26,FF/29` or reordered `FF/29,01/1,09/20` | **11 / 11 / 89** | Independent hardware reverse-engineering links the name to RGB/white-ring and USB-strip controls; no original SKU mapping, Wi-Fi support, lamp form factor or model split established. |
| [Multi-brand BLE LED lighting/control `QHM-*` candidate](https://www.home-assistant.io/integrations/led_ble/) | Literal `QHM-*` (suffix hidden, name 8 bytes); `01/1,09/8` only, no company/service UUID | **14 / 14 / 331** | Home Assistant **third-party** integration names `QHM` among supported multi-brand LED BLE devices; not a manufacturer, model, integrated fixture vs strip distinction, or medical sensor. Earlier 5 / 5 / 200 snapshot differs; exact-prefix recheck yields 14 / 14 / 331. |
| [Triones-compatible Bluetooth color-light control family](https://play.google.com/store/apps/details?hl=en_US&id=com.xiaoyu.gtriones) | Case-insensitive `Triones*` (device suffix hidden), **5 / 5 / 26** with `01/1,02/4,FF/8,09/18` and UUIDs `FFD0/FFD5`; **6 / 6 / 43** name-only `01/1,09/20` | **11 / 11 / 69** | App developer's listing names `Triones` as a pairing prefix for Bluetooth lights. Compatible devices may be multi-brand; neither AD form identifies fixture vs controller, bulb vs strip, retail model or two distinct product lines. Earlier 7 / 7 / 46 snapshot superseded. |
| [Generic Bluetooth fiber-optic star-ceiling light controller `YX_LED fiber light` candidate](https://community.home-assistant.io/t/figured-out-how-to-control-a-bluetooth-device-normally-controlled-via-a-shell-script-using-esphome-ble-because-bluetooth-proxy-cant-do-it/599707) | Exact public descriptive name; UUID `FFF0` (**3 / 3 / 10**), `4353` (**1 / 1 / 1**), `7777` (**1 / 1 / 11**), or `FFB0` (**1 / 1 / 38**) | **6 / 6 / 60** | **Third-party hardware test** controls a star-ceiling unit via `FFB0/FFB1`, corroborating category for that subgroup only. Other UUIDs may be different makers/protocols; no retail brand/model, pixel-display classification or cross-group protocol compatibility claimed. |

---
qmd:
  metadata:
    title: "More first-party BLE model-name fingerprints"
    date: "2026-10-16"
    tags: [iot, ble, models]
    confidence: medium
---

# More first-party BLE model-name fingerprints

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## Bose personal audio

Across `LE-Bose*`, `Bose*` and `LE-P-Bose*`, the current cohort is **734 / 718 / 36,900**. Normalize the `LE-` / `LE-P-` prefix and QC aliases **before** grouping models; do not sum per-prefix address counts. The eight leading identified model-name groups below are only a subset of the full cohort.

| Sanitized model-name group / Bose source | Variants / addresses / scans |
|---|---:|
| [QuietComfort Earbuds, generation unknown](https://www.bose.com/p/all-earbuds/bose-quietcomfort-earbuds/QCEARB-HEADPHONEIN-STNBLU-WW.html), excluding identified II/Ultra | **103 / 97 / 8,750** |
| [QuietComfort 45 / QC45](https://support.bose.com/s/article/quietcomfort-45-headphones-specifications) | **29 / 29 / 493** |
| [QuietComfort Ultra Earbuds](https://www.bose.com/p/SET-QCUH-QCUE.html) | **24 / 23 / 889** |
| [Sport Earbuds](https://www.bose.com/p/all-refurbished/bose-sport-earbuds/SPORT-HEADPHONEIN-FR.html) | **7 / 7 / 19** |
| [QuietComfort Ultra Headphones](https://www.bose.com/p/SET-QCUH-QCUE.html) | **7 / 7 / 157** |
| [QuietComfort 35 II / QC35 II](https://support.bose.com/s/article/quietcomfort-35-ii-specifications) | **6 / 5 / 66** |
| [Noise Cancelling Headphones 700 / NC700](https://www.bose.com/pressroom/bose-new-era-headphones) | **5 / 5 / 25** |
| [QuietComfort Earbuds II](https://support.bose.com/s/article/qcearbii-headphonein-download-the-bose-app) | **3 / 3 / 7** |

Do not infer an exact generation from generic `QuietComfort Earbuds` or `Ultra` names. These groups are distinct from the small, custom-named [Bose UUID/company-field candidate](first-party-ble-fingerprints-2026-10-16.md); no duplicate-free combined total is claimed.

## HP Poly Bluetooth audio

The `Poly*` cohort is **20 / 20 / 306**. HP's linked sources document the hardware; model-name tokens, not shared Fast Pair `FE2C`, distinguish the rows.

| Sanitized product token / HP source | Variants / addresses / scans |
|---|---:|
| [Voyager Legend 50 headset](https://www.hp.com/us-en/poly/headsets/bluetooth-headsets-and-earbuds/voyager-legend-50-series.html) | **16 / 16 / 275** |
| [Voyager Legend 30 headset](https://www.hp.com/us-en/poly/headsets/bluetooth-headsets-and-earbuds.html) | **1 / 1 / 1** |
| [Voyager Free 60 wireless earbuds](https://kaas.hpcloud.hp.com/pdf-public/pdf_8578296_en-US-1.pdf) | **1 / 1 / 6** |
| [BT700 USB Bluetooth audio adapter](https://www.hp.com/us-en/shop/pdp/poly-bt700-usb-a-bluetooth-adapter) | **2 / 2 / 24** |

BT700 is **an adapter, not a headset**. `FE2C` is not Poly-exclusive; a subset of Legend 50 also advertises UUID `91617f9c-29cc-4951-8a9a-c16f73706363`.

## Polestar electric vehicles

The `Polestar*` cohort is **14 / 14 / 495**. Explicit model-name tokens support two model candidates; the shorter brand token is left unresolved. [Polestar 2 Bluetooth pairing](https://www.polestar.com/us/manual/polestar-2/2024/article/bd436382fbed6372c0a8015105dbf15d/) and [Polestar 3 digital-key information](https://www.polestar.com/us/support/faq/polestar-3-digital-key/) show product capabilities, **not** which protocol these advertisements implement.

| Sanitized name token | Variants / addresses / scans | Limit |
|---|---:|---|
| `Polestar2` | **8 / 8 / 451** | Eight varying 128-bit service UUIDs; no fixed model UUID. |
| `Polestar 3` | **4 / 4 / 12** | No advertised service UUID or company field. |
| `Polestar*`, model hidden/unresolved | **2 / 2 / 32** | `FDFD` service UUID and company field 1534 are auxiliary, not a model identity. |

Neither group proves phone-key use, vehicle ownership or number of distinct cars.

## Amazfit health wearables

The `Amazfit*` cohort is **17 / 17 / 517**. Five named groups cover **15 / 15 / 513**; two further variants/addresses and four scans remain unassigned. All five have auxiliary company field 343 and service UUID `FEE0`, which **does not distinguish a model**.

| Sanitized name token / Amazfit source | Variants / addresses / scans |
|---|---:|
| [Helio Strap, screenless training/recovery band](https://us.amazfit.com/products/helio-strap) | **10 / 10 / 149** |
| [Band 7 activity band](https://us.amazfit.com/products/amazfit-band-7) | **2 / 2 / 7** |
| [T-Rex 3 outdoor watch](https://support.amazfit.com/en/faq/10760), excluding separate Pro token | **1 / 1 / 353** |
| [Band 5 activity band](https://support.amazfit.com/it/amazfit_band_5/user-guide) | **1 / 1 / 3** |
| [T-Rex Pro outdoor watch](https://support.amazfit.com/en/faq/10760) | **1 / 1 / 1** |

Helio Strap also advertises the generic `180D` Heart Rate service; no Strap Pro or exact revision is inferred.

## Shokz bone-conduction sports headset

All **40 / 40 / 713** `LE-OpenRun*` observations have the sanitized exact model-name token `LE-OpenRun Pro`, supporting a [Shokz OpenRun Pro](https://shokz.com/pages/openrunpro) candidate. Most have ordered AD types `03,09,FF` (37 / 37 / 675); three add flags `01` (3 / 3 / 38). Advertised `180F` is the generic Battery Service, **not** a Shokz-specific identifier. The name alone does not distinguish Standard/Mini fit, generation or revision; no separate OpenRun Pro 2 cohort was found.

## Marshall Acton Bluetooth speakers

The `Acton*` cohort is **13 / 13 / 190**. A distinct II/2 versus III/3 model token, rather than a shared generic shape, supports two [Marshall Acton speaker](https://www.marshall.com/us/en/product/acton-iii) generation candidates:

| Sanitized model token / original product source | Variants / addresses / scans | Auxiliary advertising signal |
|---|---:|---|
| [Acton II Bluetooth](https://www.marshall.com/cz/en/support/speakers/support-for-acton-ii-bluetooth/how-to-connect-and-pair) | **8 / 8 / 48** | `FE8F` service UUID; `01/1,03/2,FF/2,09/8` |
| [Acton III](https://www.marshall.com/us/en/product/acton-iii) | **5 / 5 / 142** | Four variants with `FE2C` service data; one with `AA00` service UUID and auxiliary company field 1626. |

`FE2C` is a generic Fast Pair service, not a Marshall-specific identity. Names and auxiliary UUIDs do not authenticate the device or determine hardware revisions.

## Sony WH wireless headphones

The `LE_WH-*` cohort is **170 variants / 137 addresses / 10,820 scans**, rechecked after an earlier 164 / 131 / 10,662 snapshot. [Sony's wireless-headphone model directory](https://www.sony.com/electronics/support/headphones-wireless-headphones) and [Sound Connect compatibility list](https://www.sony.com/electronics/support/wireless-headphones-bluetooth-headphones/wh-ult900n/articles/00346846) support seven model-name candidates. One model can yield many `(addr,raw)` variants; `LE_` does not prove LE Audio or active playback.

| Advertised model-name token | Variants / addresses / scans |
|---|---:|
| `LE_WH-1000XM5` | **96 / 64 / 2,024** |
| `LE_WH-1000XM4` | **62 / 61 / 8,380** |
| `LE_WH-1000XM6` | **4 / 4 / 30** |
| `LE_WH-XB910N` | **4 / 4 / 153** |
| `LE_WH-1000XM3` | **2 / 2 / 228** |
| `LE_WH-H910N` | **1 / 1 / 1** |
| `LE_WH-XB900N` | **1 / 1 / 4** |

## Jabra earbuds, office headsets and speakerphone

The `Jabra*` cohort is **15 variants / 14 addresses / 268 scans**. Match the public model token and hide any suffix; shared UUIDs, company fields and Fast Pair service `FE2C` **do not** distinguish these models.

| Sanitized model token / manufacturer source | Product category | Variants / addresses / scans |
|---|---|---:|
| [Elite 3](https://www.jabra.com/bluetooth-headsets/jabra-elite-3/ecom) | Consumer wireless earbuds | **2 / 2 / 35** |
| [Elite 4](https://www.jabra.com/bluetooth-headsets/jabra-elite-4) | Consumer ANC earbuds | **4 / 3 / 189** |
| [Elite Active 65t](https://www.jabra.com/en-MEA/footerpages/declarations-of-conformity) | Sports earbuds | **1 / 1 / 14** |
| [Elite Active 75t](https://www.jabra.com/en-MEA/footerpages/declarations-of-conformity) | Sports earbuds | **2 / 2 / 10** |
| [Evolve2 65](https://www.jabra.com/_/media/Jabra_VXi_Product-Documentation/Jabra-Evolve2-65/Technical-specifications/RevB/Jabra-Evolve2-65-Techsheet-A4-Web.pdf) | Office wireless headset | **3 / 3 / 14** |
| [Evolve2 75](https://www.jabra.com/-/media/Images/SA-Pages/Zoom/2025/Jabra-Portfolio-Guide-Zoom-A4-051125.pdf) | Office ANC headset | **1 / 1 / 1** |
| [Evolve2 85](https://www.jabra.com/-/media/Images/SA-Pages/Zoom/2025/Jabra-Portfolio-Guide-Zoom-A4-051125.pdf) | Office over-ear headset | **1 / 1 / 2** |
| [Speak2 75](https://www.jabra.com/business/speakerphones/jabra-speak-series/jabra-speak2-75) | Portable meeting speakerphone | **1 / 1 / 3** |

These name-based candidates cannot establish UC/Teams subvariants or device authenticity.

## Sony WF true-wireless earbuds

The literal `LE_WF-*` cohort currently has **25 variants / 25 addresses / 249 scans**, versus an earlier 15 / 15 / 210 snapshot. Sony's [XM4](https://helpguide.sony.net/mdr/wf1000xm4/v1/en/contents/TP1000240552.html) and [XM5](https://helpguide.sony.net/mdr/2963/v1/en/contents/TP1000781303.html) guides acknowledge `LE_WF-` connection names. Model-name tokens, not company/UUID alone, support these candidates:

| Sanitized name token / original model source | Variants / addresses / scans |
|---|---:|
| [`LE_WF-1000XM5`](https://helpguide.sony.net/mdr/2963/v1/en/contents/TP1000781303.html) | **11 / 11 / 137** |
| [`LE_WF-C510`](https://electronics.sony.com/audio/headphones/all-headphones/p/wfc510-b) | **7 / 7 / 21** |
| [`LE_WF-1000XM6`](https://electronics.sony.com/audio/headphones/truly-wireless-earbuds/p/wf1000xm6-b/) | **2 / 2 / 36** |
| [`LE_WF-1000XM4`](https://helpguide.sony.net/mdr/wf1000xm4/v1/en/contents/TP1000240552.html) | **2 / 2 / 37** |
| [`LE_WF-C500`](https://www.sony.com/electronics/support/wireless-headphones-bluetooth-headphones/wf-c500) | **2 / 2 / 15** |
| [`LE_WF-C710N`](https://electronics.sony.com/audio/headphones/all-headphones/p/wfc710n-w/) — anomalous parsed fields, lower-confidence lead | **1 / 1 / 3** |

Some have Sony auxiliary company field 301 / `FD2A`, others `FE03` or Fast Pair `FE2C`, and some have no manufacturer field. None of these is a mandatory model identifier. One address does not prove one earbud set, case or physical device.

## Beats headphones, earbuds and portable speaker

All **27 / 27 / 148** `LE-Beats*` variants split by name token into three product candidates. [Beats](https://www.beatsbydre.com/) documents the products; a shared service UUID `5052494d-2dab-0341-6972-6f6861424c45` and ordered types `01,FF,07,09` do **not** distinguish the models.

| Sanitized model token / manufacturer source | Variants / addresses / scans |
|---|---:|
| [Studio Pro over-ear ANC headphones](https://www.beatsbydre.com/headphones/studio-pro-wireless) | **13 / 13 / 68** |
| [Solo Buds true-wireless earbuds](https://www.beatsbydre.com/earbuds/solo-buds) | **8 / 8 / 60** |
| [Pill portable Bluetooth speaker](https://www.beatsbydre.com/speakers/beats-pill), generation unknown | **6 / 6 / 20** |

## Cardo FREECOM motorcycle helmet intercoms

The `Freecom*` cohort is **13 / 13 / 51**. Name-model tokens support [FREECOM 4X](https://cardosystems.com/products/freecom-4x) (**7 / 7 / 26**) and [FREECOM 2X](https://cardosystems.com/products/freecom-2x) (**5 / 5 / 22**); one further variant/address/three scans has no mapped model token. Both identified products are Bluetooth rider-to-rider helmet communicators; common AD order `01,07,FF,09`, a shared service UUID and auxiliary field 41222 do **not** identify the model. BLE observation cannot establish active intercom use.

## Fitbit Charge health bands

A current case-insensitive `Charge*` cohort is **43 / 37 / 333**, up from 39 / 33 / 267 in an earlier snapshot. Two exact model-name groups are supported; other `Charge*` or `Charger*` names are **unassigned**, not automatically JBL speakers or charging hardware.

| Name token / Google product source | Variants / addresses / scans | Supporting feature |
|---|---:|---|
| [Fitbit Charge 5](https://support.google.com/googlehealth/answer/14237922?hl=en) | **4 / 4 / 7** | UUID `abbaff00-e56a-484c-b832-8b17cf6cbfe8`; model candidate. |
| [Fitbit Charge 6](https://store.google.com/product/fitbit_charge_6?hl=en-US) | **30 / 28 / 250** | Many have Fitbit-assigned `FD62`; some carry `180D` or no service UUID. |
| Other `Charge*` / `Charger*` | **9 / 5 / 76** | No reliable product attribution. |

Names/UUIDs can be spoofed, and the unknown group is not assigned to [JBL Charge](https://www.jbl.com/JBLCHARGE6BLKAM.html) from a generic word alone.

## HP ENVY all-in-one printers

Actual BLE advertisements in the `Envy*` cohort total **11 variants / 10 addresses / 21,778 scans**. All share auxiliary HP company field 101 and service UUID `FE78`; this is **not** a Wi-Fi Direct SSID survey. [HP's Bluetooth setup guide](https://support.hp.com/us-en/document/ish_2026021-1796387-16) discusses Bluetooth Smart discovery/setup, **not** printing over Bluetooth.

| Sanitized model-series name / official HP source | Variants / addresses / scans |
|---|---:|
| [ENVY Photo 7100 series](https://support.hp.com/us-en/product/setup-user-guides/hp-envy-photo-7100-all-in-one-printer-series/9073119) | **5 / 4 / 8,934** |
| [ENVY Photo 7800 series](https://support.hp.com/us-en/product/setup-user-guides/hp-envy-photo-7800-all-in-one-printer-series/9073159) | **3 / 3 / 12,836** |
| [ENVY 5000 series](https://support.hp.com/us-en/product/setup-user-guides/hp-envy-5000-all-in-one-printer-series/14095644) | **3 / 3 / 8** |

Five advertisements across four addresses do **not** mean five 7100 printers; scans do not represent print jobs. No retail SKU within these printer series is inferred.

## COROS GPS sports watches

`COROS*` has **9 variants / 9 addresses / 12 scans**. [Official COROS battery/model specifications](https://support.coros.com/hc/en-us/articles/23476623096980-Battery-Specifications) and [APEX](https://web-eu.coros.com/apex2)/[VERTIX](https://www.coros.com/vertix2s) product pages support seven advertised model-name tokens. Match a token with its full model boundary (e.g. APEX 2 excludes APEX 2 Pro; VERTIX 2 excludes 2S).

| Sanitized model-name token | Variants / addresses / scans |
|---|---:|
| `COROS PACE PRO*` | **3 / 3 / 3** |
| `COROS PACE 2*` | **1 / 1 / 2** |
| `COROS PACE 3*` | **1 / 1 / 3** |
| `COROS APEX 2*` (not Pro) | **1 / 1 / 1** |
| `COROS APEX 2 PRO*` | **1 / 1 / 1** |
| `COROS VERTIX 2*` (not 2S) | **1 / 1 / 1** |
| `COROS VERTIX 2S*` | **1 / 1 / 1** |

These observations share `180A,180F,FEE7`, which are not seven model identifiers. The names are candidates, not authenticated watches; no DURA cycling-computer token was observed.

## EPOS office Bluetooth headsets

Five exact public product names form **5 variants / 5 addresses / 113 scans**. Ordered AD layouts and optional manufacturer fields are auxiliary; the explicit name-model token distinguishes the three headset candidates.

| Exact advertised model name / original EPOS source | Variants / addresses / scans |
|---|---:|
| [EPOS IMPACT 1060 ANC](https://www.eposaudio.com/en-us/products/audio/headsets/epos-impact-1060-anc/) | **2 / 2 / 8** |
| [EPOS IMPACT 1060T ANC](https://www.eposaudio.com/en-us/products/audio/headsets/epos-impact-1060t-anc/) (Teams/Open Office-certified variant) | **2 / 2 / 102** |
| [EPOS ADAPT 660](https://www.eposaudio.com/en-hk/products/audio/series/epos-adapt-660/) | **1 / 1 / 3** |

No active call, USB-A/USB-C bundle choice or independent adapter is inferred from these advertisements.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.

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

The separate `OpenSwim*` cohort has **5 exact `(addr,raw)` variants / 5 addresses / 103 scans**, all with visible model token `OpenSwim Pro` (device-specific suffix hidden), Shokz-assigned company field 3244 and ordered `01/1,FF/8,09/21`. [Shokz's original support page](https://shokz.com/pages/openswimpro-support) distinguishes **OpenSwim Pro** (Bluetooth 5.4 and local MP3 storage) from the original non-Bluetooth **OpenSwim**. Bluetooth does not work underwater: this is neither evidence of underwater streaming nor proof of swimming activity or a pool sensor.

## Marshall Acton Bluetooth speakers

The `Acton*` cohort is **13 / 13 / 190**. A distinct II/2 versus III/3 model token, rather than a shared generic shape, supports two [Marshall Acton speaker](https://www.marshall.com/us/en/product/acton-iii) generation candidates:

| Sanitized model token / original product source | Variants / addresses / scans | Auxiliary advertising signal |
|---|---:|---|
| [Acton II Bluetooth](https://www.marshall.com/cz/en/support/speakers/support-for-acton-ii-bluetooth/how-to-connect-and-pair) | **8 / 8 / 48** | `FE8F` service UUID; `01/1,03/2,FF/2,09/8` |
| [Acton III](https://www.marshall.com/us/en/product/acton-iii) | **5 / 5 / 142** | Four variants with `FE2C` service data; one with `AA00` service UUID and auxiliary company field 1626. |

`FE2C` is a generic Fast Pair service, not a Marshall-specific identity. Names and auxiliary UUIDs do not authenticate the device or determine hardware revisions.

## Marshall Stanmore home Bluetooth speakers

The `Stanmore*` cohort is **8 exact `(addr,raw)` variants / 8 addresses / 96 scans**. Public generation tokens distinguish two **mains-powered home speakers**, not portable battery speakers:

| Sanitized model token / original product source | Variants / addresses / scans | Advertising clue |
|---|---:|---|
| [Stanmore II Bluetooth](https://www.marshall.com/tr/en/support/speakers/support-for-stanmore-ii-bluetooth) | **3 / 3 / 31** | Name exactly `Stanmore II`, UUID `FE8F`; `01/1,03/2,FF/2,09/11` |
| [Stanmore III](https://www.marshall.com/product/stanmore-iii) | **5 / 5 / 65** | Name contains `Stanmore III` (suffix hidden); two with UUID `AA00`, three with service-data `FE2C` |

Two Stanmore III broadcast layouts do **not** imply extra speaker models; `FE2C` is generic Fast Pair, and neither names nor UUIDs authenticate speakers or identify color/market variants.

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

A separate non-`LE_` `WF-C510` name cohort adds **5 exact variants / 5 addresses / 81 scans** (ordered AD `0A/1,16/8 or 15,09/11`, Fast Pair service-data UUID `FE2C`). Together with the **7 / 7 / 21** `LE_WF-C510` row above, the **same Sony WF-C510 model** has **12 name/raw variants / 12 observed addresses / 102 scans**, with no address overlap in this snapshot. This extends advertisement coverage, **not** the number of identified product models or verified earbud units; different/random addresses cannot prove different physical sets.

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

## Sony Alpha interchangeable-lens cameras

The `ILCE-*` first-party BLE cohort has **15 exact `(addr,raw)` variants / 10 addresses / 4,065 scans**. Match complete public Sony model tokens with boundaries; all device-specific name suffixes remain hidden. Official product links establish camera models, **not** advertiser authenticity or active shooting.

| Advertised camera model token / Sony product | Variants / addresses / scans |
|---|---:|
| [`ILCE-7SM3` → α7S III](https://electronics.sony.com/imaging/interchangeable-lens-cameras/all-interchangeable-lens-cameras/p/ilce7sm3-b) | **9 / 6 / 2,703** |
| [`ILCE-7M4` → α7 IV](https://electronics.sony.com/imaging/interchangeable-lens-cameras/all-interchangeable-lens-cameras/p/ilce7m4-b) | **3 / 2 / 1,315** |
| [`ILCE-1` → α1](https://electronics.sony.com/imaging/interchangeable-lens-cameras/all-interchangeable-lens-cameras/p/ilce1-b) | **2 / 1 / 46** |
| [`ILCE-7RM5` → α7R V](https://electronics.sony.com/imaging/interchangeable-lens-cameras/full-frame/p/ilce7rm5-b) | **1 / 1 / 1** |

`ILCE-1` must be bounded so it does not absorb unrelated longer `ILCE-1…` models. Variant/address counts are not verified camera counts, lens assignments or firmware revisions.

## DJI Osmo Action cameras

Five `OsmoAction*` observations represent **5 variants / 5 addresses / 36 scans**. Public model-name tokens plus auxiliary DJI company field 2218 support three [DJI Osmo Action-series](https://www.dji.com/products/comparison-action) camera candidates; all also advertise generic service UUID `FFF0`, which is **not DJI-exclusive**.

| Sanitized model token / original product source | Variants / addresses / scans |
|---|---:|
| [`OsmoAction3` → Osmo Action 3](https://dl.djicdn.com/downloads/DJI_Osmo_%20Action_%203/UM/0930/DJI_Osmo_Action_3_User_Manual_v1.0_en.pdf) | **1 / 1 / 4** |
| [`OsmoAction4` → Osmo Action 4](https://www.dji.com/support/product/osmo-action-4) | **2 / 2 / 12** |
| [`OsmoAction5Pro` → Osmo Action 5 Pro](https://www.dji.com/products/comparison-action) | **2 / 2 / 20** |

The device suffixes remain hidden; neither BLE shape nor service UUID authenticates a camera, determines a lens/firmware version or proves it was filming.

## Garmin fēnix multisport GPS watches

The `fenix*` first-party cohort is **9 exact `(addr,raw)` variants / 9 addresses / 614 scans**. Match complete visible Garmin model tokens with boundaries (`5X` must not collapse into `5`):

| Sanitized name model token / original product source | Variants / addresses / scans |
|---|---:|
| [`fenix 5` → Garmin fēnix 5](https://www.garmin.com/en-US/blog/outdoor/fenix-5-family-size-comparison/) | **4 / 4 / 603** |
| [`fenix 5X` → Garmin fēnix 5X](https://www.garmin.com/en-US/blog/outdoor/fenix-5-family-size-comparison/) | **3 / 3 / 8** |
| [`fenix 3` → Garmin fēnix 3](https://www.garmin.com/en-US/newsroom/press-release/outdoor/2015-introducing-fnix-3-the-next-smart-multisport-gps-watch-from-garmin/) | **1 / 1 / 1** |
| [`fenix 6X Pro Solar` → Garmin fēnix 6X Pro Solar](https://www.garmin.com/en-US/p/641375/pn/010-02157-23/) | **1 / 1 / 2** |

Three earlier generations advertise the same 128-bit service UUID `6a4e3e10-667b-11e3-949a-0800200c9a66`; the 6X entry advertises generic Heart Rate service `180D`. Name tokens support candidates, not device authentication, precise hardware revisions or the number of physical watches.

## Dreo fan model-name aliases

Another **6 exact `(addr,raw)` variants / 6 addresses / 51 scans** advertise a `Dreo*` name with complete public model-code tokens (all device-specific suffixes withheld), ordered `01/1,09/21,08/21` and no company/service UUID. Original product sources place these **within the existing pedestal-fan (PF) or tower-fan (TF) categories**, not four new appliance families:

| Advertised public code / original manufacturer product line | Variants / addresses / scans |
|---|---:|
| [`DR-HAF003S` → TurboPoly 513S pedestal/air-circulation fan](https://support.dreo.com/en/support/product/pf513s-613s/smart) | **2 / 2 / 13** |
| [`DR-HTF001S` → tower fan](https://www.dreo.com/pages/product-security-policy) | **1 / 1 / 1** |
| [`DR-HTF002S` → smart tower fan](https://www.dreo.com/pages/product-security-policy) | **2 / 2 / 34** |
| [`DR-HTF007S` → Tower Fan 307S](https://ca.dreo.com/products/tower-fan-307s) | **1 / 1 / 3** |

These full-name code observations are not included in the separate `DreoPF*`/`DreoTF*` **prefix** row counts, but are the **same product categories**, not extra product-family discoveries. The advertisement shape alone does not authenticate any model or feature.

## ION Audio Pathfinder outdoor speakers

Five exact `Pathfinder*` observations (**5 variants / 5 addresses / 15 scans**) advertise two complete public product-name tokens with `_BLE` suffix. Both use generic HID service UUID `1812` and ordered `01/1,03/2,FF/3,09/<name length>`; that UUID alone does not identify ION or a speaker:

| Sanitized model token / original manufacturer guide | Variants / addresses / scans |
|---|---:|
| [`Pathfinder 4` + `_BLE`](https://support.ionaudio.com/en/support/solutions/articles/69000800442-ion-audio-pathfinder-4-frequently-asked-questions) | **3 / 3 / 8** |
| [`Pathfinder 280` + `_BLE`](https://support.ionaudio.com/en/support/solutions/articles/69000800441-ion-audio-pathfinder-280-frequently-asked-questions) | **2 / 2 / 7** |

Model-name tokens support two ION Audio outdoor-speaker candidates, not authenticated hardware, independent unit counts or any inferred pet-tracking/navigation function.

## Rockford Fosgate PMX-1 marine media receiver

Eight exact `(addr,raw)` variants / 8 addresses / 17 scans have the public model-name token `PMX-1`, service UUIDs `5D00` and `FFC0`, auxiliary company field 1705, and ordered AD `01/1,02/4,FF/8,09/20,0A/1`. The [original Rockford Fosgate PMX-1 product page](https://rockfordfosgate.com/products/details/pmx-1/) confirms a Bluetooth-enabled marine/outdoor digital-media receiver with RF Connect App control; [FCC filing mirror](https://fccid.io/2AB7SPMX1) provides an OEM context, **not** advertiser authentication. No installation location, vessel, audio stream or physical-unit count can be inferred from BLE ads.

## OontZ portable Bluetooth speakers

The `OontZ*` cohort has **5 exact `(addr,raw)` variants / 5 addresses / 79 scans**. Both groups advertise generic service UUID `FFC0` and ordered `01/1,03/2,FF/14,09/<name length>`; only complete public model tokens support distinguishing the speakers:

| Sanitized advertised model token / original manufacturer product | Variants / addresses / scans |
|---|---:|
| [`OontZ` + `Angle 3U` / Ultra → Angle 3 Ultra](https://www.theoontz.com/products/oontz-ultra) | **3 / 3 / 76** |
| [`OontZ` + `Angle 3 Pro` → Angle 3 Pro](https://www.theoontz.com/products/oontz) | **2 / 2 / 3** |

Device-specific suffixes remain hidden. Shared UUID/AD shape cannot distinguish model generation or authenticate hardware; address count is not a confirmed number of physical speakers.

## TOZO T-series true-wireless earbuds

The `TOZO-T*` cohort contains **11 exact `(addr,raw)` variants / 11 addresses / 230 scans**. Complete public name tokens distinguish two [TOZO](https://www.tozostore.com/) in-ear earbud model candidates; both mainly show ordered `09,FF`, auxiliary company field 64600 (not brand authentication), and no advertised service UUID:

| Advertised public model token / original manufacturer product | Variants / addresses / scans |
|---|---:|
| [`TOZO-T6` → T6](https://www.tozostore.com/products/t6) | **6 / 6 / 147** |
| [`TOZO-T10` → T10](https://www.tozostore.com/products/t10) | **5 / 5 / 83** |

The name-column length sometimes differs from the AD name-data length; no repair or hardware-revision inference is made. Advertisement names do not authenticate products or count complete earbud sets, and current retail specifications do not prove the observed generation.

A **separate** `TOZO-A*` cohort has **6 exact variants / 6 addresses / 60 scans**, all with the public pairing name `TOZO-A1`, ordered `FF/9,09/8` and no advertised service UUID. [Original TOZO A1 Mini product page](https://www.tozostore.com/products/a1) and indexed pairing name support the A1 Mini earbud model candidate, not hardware/firmware revision or authentic unit counts. These six observations are **not** included in the T6/T10 counts above.

## HUAWEI watches and fitness bands

The case-insensitive `HUAWEI*` cohort is **5 exact `(addr,raw)` variants / 5 addresses / 63 scans** (exact mixed-case `Huawei*` finds none). Complete public model-name tokens plus [original WATCH GT 3/GT 4 comparison](https://consumer.huawei.com/cn/support/content/zh-cn15972101/), [Band 9/10 support](https://consumer.huawei.com/mx/support/content/es-us15994489/) and [Band 10 product page](https://consumer.huawei.com/en/wearables/band10/) support four wearable model candidates:

| Advertised public model token | Variants / addresses / scans | Service-**data** UUID |
|---|---:|---|
| `HUAWEI WATCH GT 3` | **2 / 2 / 59** | `FDEE` |
| `HUAWEI WATCH GT 4` | **1 / 1 / 1** | `FDEE` |
| `HUAWEI Band 9` | **1 / 1 / 1** | `3802` |
| `HUAWEI Band 10` | **1 / 1 / 2** | `3802` |

The private name suffixes stay hidden; none advertises a service UUID list (the table is **service data**). Company field 637 appears in all but GT 4; neither name nor auxiliary fields authenticate hardware, sizes or revisions. No phone, earbud or router model was identified in this cohort.

## Status Audio Between 3ANC earbuds

All **6 exact `(addr,raw)` variants / 6 addresses / 443 scans** in the case-insensitive `Between*` cohort have public model-name token `Between 3ANC`, ordered `09/12,FF/11` and no advertised service UUID. [Status Audio's original 3ANC product page](https://www.status.co/products/between-3anc) and [Bluetooth/Status Hub support](https://www.status.co/pages/troubleshooting) establish a true-wireless noise-cancelling earbud model candidate. Neither name nor auxiliary advertising data authenticate devices or hardware revisions; this is **not** a Between Pro cohort, and addresses do not count confirmed physical earbud sets.

## Sony WI-C100 neckband-style wireless earphones

All **7 exact `(addr,raw)` variants / 7 addresses / 10 scans** in the `LE_WI-C*` cohort include complete public token `LE_WI-C100`, supporting the [original Sony WI-C100 product](https://electronics.sony.com/audio/headphones/all-headphones/p/wic100-w). Six variants (**6 / 6 / 8**) advertise generic Fast Pair service-**data** `FE2C` without company field; one (**1 / 1 / 2**) also advertises service UUID `FD82` and Sony-assigned company field 301. These are **one model**, not separate earphone models; WI-C100 has left/right ears connected by a neck cable, **not** a `WF-` true-wireless earbud. Fast Pair alone does not identify Sony, and advertisements do not prove authenticity, color, firmware, audio playback or physical-unit count.

## Cleer wireless headphones and open-ear earbuds

The `Cleer*` cohort has **5 exact `(addr,raw)` variants / 5 addresses / 33 scans**, with four **distinct public name groups**, all supported by original Cleer product or series material:

| Exact advertised public name / original manufacturer source | Variants / addresses / scans | Product boundary |
|---|---:|---|
| [`Cleer ARC 3`](https://cleeraudio.com/products/arc-3-open-ear-earbuds-black) | **2 / 2 / 26** | Open-ear earbuds; two ads do not imply two generations. |
| [`Cleer ARC II`](https://cleeraudio.com/blogs/news/cleer-arc-series-open-ear-audio-designed-for-everyday-life) | **1 / 1 / 3** | Open-ear series; no Sport subvariant established. |
| [`Cleer ARC`](https://cleeraudio.com/blogs/news/cleer-arc-series-open-ear-audio-designed-for-everyday-life) | **1 / 1 / 2** | Open-ear family only; **generation unspecified**. |
| [`Cleer ENDURO ANC`](https://cleeraudio.com/products/enduro-anc-noise-cancelling-headphones-with-60hr-battery-life-midnight-blue) | **1 / 1 / 2** | Over-ear noise-cancelling headset. |

Model evidence comes from names, **not** generic battery/device-info service UUIDs or varying auxiliary company fields. No speaker model, hardware authenticity or number of physical units is established.

## Anker SOLIX portable power stations and Prime power-bank family

Literal `Anker*` has **5 exact `(addr,raw)` variants / 5 addresses / 70 scans**, with four distinct **public SOLIX model tokens**:

| Advertised public token / original manufacturer source | Variants / addresses / scans | Product candidate |
|---|---:|---|
| [`Anker SOLIX C1000`](https://www.anker.com/nz/anker-portable-power-station-warranty-policy) | **1 / 1 / 3** | C1000 portable power station. |
| [`Anker SOLIX C300`](https://support.anker.com/s/article/Anker-SOLIX-C300-Portable-Power-Station-Guide-de-l-utilisateur-A1722-CA) | **1 / 1 / 59** | C300 portable power station; not necessarily this guide's region or revision. |
| [`Anker SOLIX C800 Plus`](https://service.anker.com/article-description/Anker-SOLIX-C800-PLUS-Portable-Power-Station) | **1 / 1 / 1** | C800 Plus portable power station. |
| [`Anker SOLIX F2000`](https://service.anker.com/article-description/Anker-SOLIX-F2000-Portable-Power-Station-PowerHouse-767-USER-MANUAL) | **1 / 1 / 2** | F2000 / PowerHouse 767 portable power station. |
| [`Anker Prime Power Bank`](https://www.anker.com/products/a1340-250w-power-bank) | **1 / 1 / 5** | **Prime power-bank family only**: linked A1340 is an example, **not** an identified model here. |

Four SOLIX ads carry service UUID `FF09` and ordered `01,FF,03,09,0A`; the Prime ad instead carries `2215` and `01,FF,03,09`. All have uninformative company field `0xFFFF`. No exact `(addr,raw)` or address overlap with the separately recorded `AFYDKPN*` smart-charging lead in this snapshot, but different/random addresses cannot establish different physical units. Only the four explicit SOLIX name tokens add model candidates; ads do not authenticate hardware, region, capacity, firmware or active operating state.

## Samsung Galaxy Watch7 smartwatches

All **16 exact `(addr,raw)` variants / 16 addresses / 5,626 scans** have public name token `Galaxy Watch7`, supporting **one** [original Samsung Galaxy Watch7 product family](https://www.samsung.com/us/watches/galaxy-watch7/buy/galaxy-watch7-40mm-cream-wifi-bluetooth-sku-sm-l300nzeaxaa/), not two models. **8 / 8 / 1,387** carry service and service-data UUID `FD69` with ordered `01/1,03/2,16/22,09/20`; the other **8 / 8 / 4,239** carry Samsung-assigned company field 117 with `01/1,FF/15,19/2,09/20` and no listed service UUID. The [44 mm manufacturer example](https://www.samsung.com/us/watches/galaxy-watch7/buy/galaxy-watch7-44mm-silver-wifi-bluetooth-sku-sm-l310nzsaxaa/) establishes available variants, **not** which are present here. Neither advertising form nor `FD69` alone identifies watch size, radio option, order code, authenticity, ownership, lost/offline state or physical-watch count.

## Samsung Galaxy Watch6 and Watch6 Classic smartwatches

A separate **18 exact `(addr,raw)` variants / 18 addresses / 226 scans** with complete public `Galaxy Watch6` token split into **two name-confirmed families**, not four advertising models: [`Galaxy Watch6`](https://news.samsung.com/us/samsung-galaxy-watch6-series-classic-inspiring-best-self-day-night-unpacked-2023/) without `Classic` (**12 / 12 / 60**) and [`Galaxy Watch6 Classic`](https://www.samsung.com/mm/watches/galaxy-watch/galaxy-watch6-classic-43mm-silver-bluetooth-sm-r950nzsaasa/) (**6 / 6 / 166**). Within Watch6, six variants carry `FD69` service/service-data UUID (**6 / 6 / 15**) and six Samsung-assigned company field 117 (**6 / 6 / 45**); all Classic variants carry company 117, with two auxiliary-data lengths (**3 / 3 / 3** and **3 / 3 / 163**). The original Samsung series announcement distinguishes Classic's rotating bezel, but no radio size, LTE variant, color, order SKU, firmware or authentic physical-watch count is inferred. Shared `FD69`/company-field shapes with Watch7 do **not** merge generations.

## Epson EcoTank ET-series all-in-one printers

All **6 exact `(addr,raw)` variants / 6 addresses / 197 scans** advertise exact public name `ET-2850 Series`, supporting the [original Epson ET-2850 series](https://support.epson.com/Support/Printers/All-In-Ones/ET-Series/Epson-ET-2850/s/SPT_C11CJ63202): color ink-tank printing, scanning, copying and automatic duplex printing. Ordered AD `01/1,FF/5,06/16,09/14`; auxiliary company field 64, unverified 128-bit UUID withheld. This is separate from HP printer leads; the advertising does not distinguish country/retail SKU, authenticate manufacture or count physical printers.

Five **other complete public ET-series name tokens**, explicitly excluding ET-2850 above, have original Epson model pages:

| Name token / original manufacturer model page | Exact variants / addresses / scans | Product-family distinction |
|---|---:|---|
| [`ET-3850`](https://epson.com/For-Work/Printers/Inkjet/EcoTank-ET-3850-Wireless-Color-All-in-One-Cartridge-Free-Supertank-Printer-with-Scanner%2C-Copier%2C-ADF-and-Ethernet/p/C11CJ61201) | **4 / 4 / 246** | Color ink tank print/scan/copy with ADF. |
| [`ET-4850`](https://support.epson.com/select/For-Work/Printers/Inkjet/EcoTank-ET-4850-Wireless-Colour-All-in-One-Cartridge-Free-Supertank-Printer-with-Scanner%2C-Copier%2C-Fax%2C-ADF-and-Ethernet/p/C11CJ60202) | **3 / 3 / 53** | Color ink tank multifunction with fax and ADF. |
| [`ET-2980`](https://epson.com/For-Home/Printers/Inkjet/EcoTank-ET-2980-Wireless-All-in-One-Color-Supertank-Printer-%E2%80%93-Black/p/C11CL41201) | **3 / 3 / 24** | Color ink tank print/scan/copy. |
| [`ET-2800`](https://epson.com/For-Work/Printers/Inkjet/EcoTank-ET-2800-Wireless-Color-All-in-One-Cartridge-Free-Supertank-Printer-with-Scan-and-Copy/p/C11CJ66202) | **6 / 6 / 13** | Color ink tank print/scan/copy. |
| [`ET-3950`](https://epson.com/Support/Printers/All-In-Ones/ET-Series/Epson-ET-3950/s/SPT_C11CL43201) | **2 / 2 / 20** | Color ink tank print/scan/copy with ADF and duplex; [shared manufacturer setup guide](https://files.support.epson.com/docid/cpd6/cpd64026.pdf) also covers other, **distinct** ET models. |

These five cohorts also share company field 64 and ordered `01/1,FF/5,06/16,09/14` with ET-2850; a 128-bit UUID is withheld. **The shared shape does not distinguish models**: the full name tokens do. Manufacturer example SKU/region/color does not identify observed configurations, BLE presence does not show print data transport, and no address count establishes physical printers or hospital deployment.

## Hyperice Hypervolt 2 and Hypervolt 2 Pro massage devices

The **3 exact `(addr,raw)` variants / 3 addresses / 131 scans** in this cohort split by **complete public name tokens** into `Hypervolt 2` (**2 / 2 / 23**) and `Hypervolt 2 Pro` (**1 / 1 / 108**). The [original Hyperice Hypervolt family page](https://get.hyperice.com/hypervolt) supports two distinct percussion-massage/recovery model candidates, **not** evidence of clinical or hospital use. Both have Hyperice-assigned company field 2234, generic Device Information service `180A` and ordered `19,01,03,09,FF,09`; neither company code nor generic service alone identifies a model. Product links now redirect to third-generation pages, so no second-generation specifications, hardware revision or physical-unit count are inferred.

## JBL Xtreme 3 portable speaker

Exact public name `JBL Xtreme 3` appears in **5 exact `(addr,raw)` variants / 5 addresses / 489 scans**, supporting the [original JBL Xtreme 3 portable waterproof Bluetooth speaker](https://global.jbl.com/bluetooth-speakers/XTREME-3-.html). All include Harman-assigned company field 87 and service-**data** UUID `FDDF`, not an advertised service UUID. Three AD variants (`FF,16,09` with differing company-field lengths, or `01,FF,16,09`) are **not** three hardware versions. Neither the high scan total nor address count establishes playback, usage rate, authentic hardware, physical-unit count or hospital setting.

## Samsung Galaxy Ring smart ring

One exact `(addr,raw)` variant / one address / **3 scans** has public token `Galaxy Ring`, Samsung-assigned company field 117 and ordered `01/1,FF/20,09/19`, without service or service-data UUID. [Original Samsung Galaxy Ring support](https://www.samsung.com/us/support/answer/ANS10003278/) establishes the sleep/activity-monitoring smart-ring family, **distinct from the Galaxy Watch cohort**; advertising does not authenticate an individual ring or reveal size, color, usage or deployment setting.

## JBL Endurance Peak 3 sports earbuds

The complete public `Endurance Peak 3` token appears in **16 exact `(addr,raw)` variants / 15 addresses / 581 scans**, supporting the [original JBL Endurance Peak 3 true-wireless, ear-hook sport earbuds](https://www.jbl.com/ENDURANCE-PEAK-3.html). All include generic Fast Pair service-**data** UUID `FE2C` and no company field; varied service-data lengths and eight observed length combinations do **not** represent eight product models or authenticate JBL. Separate Endurance Race 2 (**8 / 7 / 189**) and Endurance Peak II (**1 / 1 / 8**) names are **excluded** from Peak 3 and independently verified below. Fifteen observed addresses do not establish fifteen physical earbud sets.

### Distinct JBL Endurance Race 2 and Peak II

Two further **non-overlapping name groups** have original product matches: [`Endurance Race 2`](https://www.jbl.com/ENDURANCE-RACE-2.html) true-wireless noise-cancelling sport earbuds (**8 exact variants / 7 addresses / 189 scans**) and [`Endurance Peak II`](https://www.jbl.com/JBLENDURPEAKIIBLAM.html) ear-hook true-wireless sport earbuds (**1 / 1 / 8**). Race 2 advertises ordered `01,16,03,09` or `0A,16,09`; Peak II `01,16,0A,09`, both without company field. Their public model names, not the differing AD shapes, support separate candidates; neither authenticity, hardware revision nor physical-set counts follow from the advertisements. These figures were previously listed as **excluded from** Peak 3 and are **not** added to its count.

## Logitech M720 Triathlon wireless mouse

One exact `(addr,raw)` variant / one address / **2 scans** advertises the complete public product name `M720 Triathlon`, generic HID service UUID `1812` and Mouse Appearance 962 (`0x03C2`), with ordered `01/1,19/2,03/2,09/14,0A/1`. [Original Logitech M720 Triathlon product](https://www.logitech.com/en-us/shop/p/m720-triathlon) supports a multi-device wireless-mouse model candidate; no company field authenticates the brand. These BLE advertisements do not establish Unifying receiver use, host connection, active use or a verified physical-unit count.

## Philips TAB5309 home soundbar

One exact `(addr,raw)` variant / one address / **3 scans** in the case-insensitive `Philips*` name cohort carries public model token `TAB5309`, supporting the [original Philips TAB5309 2.1-channel soundbar with wireless subwoofer](https://www.usa.philips.com/c-p/TAB5309_37/soundbar-21-with-wireless-subwoofer/support). Ordered AD `01/1,FF/26,09/18`; auxiliary company field 15619, no listed service UUID. The linked `/37` country SKU is **not** identified by the broadcast. The other **3 / 3 / 13** `Philips*` variants lack a parsed public model and remain **category unknown**, not confirmed soundbars or medical products. The combined `Philips*` name cohort is **4 / 4 / 16**; no authenticity, physical-unit count or hospital installation is inferred.

## Aranet4 indoor air-quality monitor

One exact `(addr,raw)` variant / one address / **9 scans** advertises complete public token `Aranet4` with a hidden device-specific suffix, SAF TEHNIKA-assigned company field 1794 and ordered `01/1,09/13,06/16,FF/9` (128-bit UUID withheld). The [original Aranet4 product page](https://pro.aranet.com/products/aranet4/) and [manufacturer manual](https://assets.aranet.com/documents/aranet4_user_manual_v25_web.pdf) support a room-environment monitor of **CO₂, temperature, humidity and atmospheric pressure**. No HOME/PRO version, hardware revision, authenticity or physical-unit count is established. This is **not** a human medical diagnostic device, and no hospital installation or relation to earlier WiGLE identifiers follows.

## nLight AIR rPP20 lighting power/relay pack

The complete public `rPP20` model token appears in **2 exact `(addr,raw)` variants / 2 addresses / 15 scans**, with name suffix hidden, ordered `01/1,09/12,FF/20`, auxiliary company field 10245 and no advertised service UUID. [Original Acuity/nLight rPP20 product listing](https://www.acuitybrands.com/products/detail/771135/nlight/rpp20-relay-pack/rpp20-power-relay-pack) identifies a lighting power/relay pack in the nLight AIR ecosystem; manufacturer [installation instructions](https://img.acuitybrands.com/public-assets/catalog/771135/is-rpp20-002.pdf) are indexed but returned HTTP 403 to direct retrieval, so **no unverified configuration-specific electrical claims** are imported. This is a **different model token** from [rPP8 DS](nlight-rpp8-ds.md), without proof of interchangeability or replacement. Ordinary nLight AIR control uses a separate 900 MHz channel; these BLE ads do not demonstrate relay activity, control traffic, exact variant, physical-unit count or hospital deployment.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.

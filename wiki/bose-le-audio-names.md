---
qmd:
  metadata:
    title: "Bose LE- / Bose* audio names and FEBE custom-name candidates"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Bose `LE-` / `Bose*` audio names and `FEBE` custom-name candidates

## How to recognize it

- **Local name:** `LE-Bose*`, `Bose*`, `LE-P-Bose*`, `LE-Maestro`, `LE-Flexo-Ersto`, `LE-RIPS*`, `LE-ZEN*`, `LE-Blue*`, `LE-Black*`, `LE-Reserved_C`
- **Company field:** company field 158, company field 14083, company field 17153, company field 2817
- **Service / service-data UUID:** `FEBE`
- **AD shape (ordered type/data length):** `01/1,02/2,FF/11,09/14`, `01/1,03/2,FF/13,09/10,0A/1`
- **What it is:** Bose personal audio and speakers (named `Bose*` tokens) plus custom `LE-` names carrying the Bose-assigned `FEBE` service UUID.
- **Confidence and limits:** Family candidate; the custom-named `FEBE` rows do not identify a Bose model and could be custom. No duplicate-free combined total is claimed. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## Bose personal audio

Across `LE-Bose*`, `Bose*` and `LE-P-Bose*`, the current cohort is **734 / 718 / 36,900**. Normalize the `LE-` / `LE-P-` prefix and QC aliases **before** grouping models; do not sum per-prefix address counts. The **12 public product-name groups** below are only a subset of the full cohort; `LE-` in a name does not demonstrate LE Audio.

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
| [SoundLink Max portable Bluetooth speaker](https://www.bose.com/p/speakers/bose-soundlink-max-portable-speaker/SLMAX-SPEAKERWIRELESS.html) | **5 / 5 / 9** |
| [SoundLink Micro Bluetooth speaker, generation unknown](https://www.bose.com/pressroom/meet-the-micro) | **2 / 2 / 13** |
| [Frames Tempo sport audio sunglasses](https://www.bose.com/pressroom/new-bose-frames) | **1 / 1 / 1** |
| [Frames Tenor square-frame audio sunglasses](https://www.bose.com/pressroom/new-bose-frames) | **1 / 1 / 10** |

Do not infer an exact generation from generic `QuietComfort Earbuds`, `Ultra` or `SoundLink Micro` names; product-name tokens and original product descriptions are candidates, not broadcaster authentication or evidence audio was playing. These groups are distinct from the small, custom-named [Bose UUID/company-field candidate](#catalogue-entries); no duplicate-free combined total is claimed.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Bose Bluetooth audio-family candidate](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Assigned_Numbers/out/en/index-en.html) | Bose-assigned service UUID `FEBE` **plus** auxiliary company field 158; `01/1, 02/2, FF/11, 09/14`. Device-specific/custom local name withheld. | **15 / 15 / 295** | UUID/company combination supports Bose family, but does **not** distinguish earbuds/speaker/model; do not use the custom name as a brand. |
| [Bose audio-family `LE-Maestro` **candidate**](https://support.bose.com/article/quietcomfort-ultra-headphones-no-audio-from-product?language=en_US) | Name exactly `LE-Maestro` plus Bose-assigned service UUID `FEBE`; `01/1,03/2,FF/13,09/10,0A/1`; company field 14083 unverified | **6 / 6 / 624** | Bose documents some `LE-` connection names, **not** this model. Maestro may be custom; no Lutron lighting attribution, specific Bose model or overlap-free total with other Bose cohorts claimed. |
| [Bose audio-ecosystem `LE-Flexo-Ersto` **candidate**](https://support.bose.com/article/quietcomfort-ultra-headphones-no-audio-from-product?language=en_US) | Exact name `LE-Flexo-Ersto` and Bose-assigned `FEBE`; `01/1,03/2,FF/9,0A/1,09/14`; company field 17153 unverified | **14 / 14 / 711** | Bose support documents `LE-` connection names but does **not** map this label to a model. Could be custom; neither SoundLink Flex, headset vs speaker, nor overlap-free combined Bose count is claimed. |
| [Bose audio-ecosystem `LE-RIPS*` **candidate**](https://support.bose.com/article/quietcomfort-ultra-headphones-no-audio-from-product?language=en_US) | Name `LE-RIPS*` plus Bose-assigned service UUID `FEBE`; chiefly `01/1,03/2,FF/13,09/7,0A/1`; auxiliary company field unverified | **12 / 12 / 150** | Bose documents `LE-` connection entries, **not** a `RIPS` product. Possibly custom; no headset vs speaker, model or additional distinct Bose product established. |
| [Bose audio-ecosystem `LE-ZEN*` **candidate**](https://support.bose.com/article/quietcomfort-ultra-headphones-no-audio-from-product?language=en_US) | `LE-ZEN*` (suffix hidden) **with Bose-assigned `FEBE` UUID**, `01/1,03/2,FF/10 or 13,09/11,0A/1`; auxiliary company field unverified | **6 / 6 / 60** | Bose's `LE-` support note does **not** establish a `ZEN` product, QuietComfort model, sleep device or speaker/headset type. A further **1 / 1 / 39** same-name-pattern variant has only `01/1,09/11` and is **excluded** from Bose attribution. |
| [Bose-related `LE-Blue*` BLE-service **candidate**](https://support.bose.com/article/quietcomfort-ultra-headphones-no-audio-from-product?language=en_US) | Case-insensitive `LE-Blue*` (suffix hidden) plus Bose-assigned service UUID `FEBE`, typically `01/1,03/2,FF/9–15,0A/1,09/12–13`; inconsistent auxiliary company fields | **8 / 8 / 18** | Original Bose `LE-` support explains secondary connection names, **not** this exact model; no `Bose`, `SoundLink` or `Micro` token. Not a verified blue-colored speaker, Blue microphone, headset vs speaker type, or distinct new Bose product. Earlier 7 / 7 / 17 count superseded. |
| [Bose-related `LE-Black*` BLE-service **candidate**](https://support.bose.com/article/quietcomfort-ultra-headphones-no-audio-from-product?language=en_US) | `LE-Black*` (suffix hidden) with Bose-assigned UUID `FEBE`; **1 / 1 / 10** also Bose company field 158, remaining **4 / 4 / 42** have other auxiliary fields | **5 / 5 / 52** | Original Bose `LE-` guidance does not identify this model. `Black` is not verified color, headset vs speaker, or a new distinct Bose product; AD-layout differences do not imply new hardware or update mode. |
| [Unresolved `LE-Reserved_C` auxiliary BLE candidate](https://support.bose.com/article/quietcomfort-ultra-headphones-no-audio-from-product?language=en_US) | Exact hyphenated name (`LE_RESERVED_C` with an underscore has **zero** matches); Bose-assigned service UUID `FEBE`, conflicting Resideo-assigned company field 2817; `01/1,03/2,FF/9,0A/1,09/13` | **8 / 7 / 18** | Original Bose `LE-` support only explains possible auxiliary entries; conflicting assigned fields do **not** authenticate either manufacturer or model. Not evidence for a standards-reserved name, update mode, or another identified audio product. |

## Related observations

One `LE-ZEN*` observation (**1 exact variant / 1 address / 39 scans**) has only flags and a name, without Bose's `FEBE` or another independent brand clue. It is excluded from the six-variant Bose candidate row; its product identity remains unknown.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.

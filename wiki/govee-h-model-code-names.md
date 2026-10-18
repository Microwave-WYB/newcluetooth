---
qmd:
  metadata:
    title: "Govee H-code name tokens: Govee_H*, ihoment_H*, GBK_H*"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Govee H-code name tokens: `Govee_H*`, `ihoment_H*`, `GBK_H*`

## How to recognize it

- **Local name:** `Govee_H*`, `ihoment_H*`, `GBK_H*`
- **Company field:** company field 34818, company field 34819, company field 0x8802
- **AD shape (ordered type/data length):** `01,09,FF,09`
- **What it is:** Govee lighting, plug, environmental-monitoring and other products identified by the public `H` model code in the local name (three name prefixes, same code scheme).
- **Confidence and limits:** Candidate per model code from manufacturer/third-party material; ranges from original manufacturer mappings to lower-confidence third-party candidates (see per-code rows). Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

First-party simi `cluetooth` read-only aggregates, researched **2026-10-16**. The principal feature here is a **sanitized model token in the advertised name**, not a claim that a generic BLE shape uniquely identifies a model. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, **not confirmed physical devices**. Hide all device-specific suffixes; names can be spoofed, and a research date is not an observation date. No hospital association is required.

## Govee lighting and environmental monitoring

The `Govee_H*` cohort contains **439 variants / 421 addresses / 15,145 scans**, spanning **104 distinct `H` model tokens**. The following **48 tokens** have been individually checked; the remaining **56 tokens / 70 exact variants / 309 scans** have not been individually validated (their distinct-address count was not recomputed). A bounded follow-up found **no further directly manufacturer-proven non-lighting category** among them; this does **not** prove they are all lights. Five checked tokens are confirmed only in a manufacturer model list, **without a product category**. Original Govee product/support material corroborates many model families but not the authenticity of advertisers; H5074 relies on a third-party manual.

| Advertised model token (suffix hidden) | Public model-to-category evidence | Variants / addresses / scans |
|---|---|---:|
| `Govee_H6076*` | [RGBICW smart floor lamp](https://us.govee.com/products/govee-rgbicw-smart-corner-floor-lamp) | **51 / 50 / 4,269** |
| `Govee_H6099*` | [TV Backlight 3 Lite](https://us.govee.com/products/govee-tv-backlight-3-lite) | **24 / 23 / 553** |
| `Govee_H6056*` | [RGBICWW light bars / Flow Plus](https://us.govee.com/products/govee-rgbicww-light-bar) | **19 / 17 / 2,672** |
| `Govee_H607C*` | [Floor Lamp 2](https://us.govee.com/products/govee-floor-lamp-2) | **18 / 17 / 62** |
| `Govee_H7075*` | [Outdoor Wall Light](https://us.govee.com/products/govee-outdoor-wall-light) | **14 / 14 / 177** |
| `Govee_H617A*` | [5 m Bluetooth light strip](https://community.govee.com/support/faqs/specs) | **14 / 14 / 101** |
| `Govee_H6046*` | [TV light bars](https://us.govee.com/products/govee-rgbic-tv-light-bars-for-55-75-inch-tvs) | **13 / 11 / 375** |
| `Govee_H7021*` | [96 ft outdoor string lights](https://us.govee.com/products/refurbished-govee-rgbic-warm-white-wi-fi-bluetooth-smart-outdoor-string-lights) | **13 / 13 / 121** |
| `Govee_H5151*` | [Wi-Fi/BLE gateway in a thermometer/hygrometer kit](https://us.govee.com/products/refurbished-wi-fi-digital-thermometer-hygrometer), **not** a count of standalone probes | **12 / 12 / 72** |
| `Govee_H5074*` | [Bluetooth thermometer/hygrometer](https://www.manualslib.com/manual/4009235/Govee-H5074.html) — **third-party-hosted manual**, no reliable direct original-manufacturer URL confirmed | **10 / 8 / 11** |
| `Govee_H6199*` | [DreamView T1 camera-based TV backlight](https://us.govee.com/products/refurbished-govee-dreamview-t1-tv-backlight-1) | **12 / 12 / 1,738** |
| `Govee_H6168*` | [FCC application mirror](https://fccid.io/2A7VD-H6168) and [Govee-branded manual cover, Amazon mirror](https://m.media-amazon.com/images/I/B1qcwy7nX2L.pdf#page=1): RGBIC LED TV Backlight | **6 / 5 / 1,649** |
| `Govee_H6022*` | [Table Lamp 2](https://us.govee.com/products/led-table-lamp-2) | **11 / 10 / 813** |
| `Govee_H61D5*` | [Neon Rope Light 2, 5 m](https://us.govee.com/products/govee-neon-rope-light-2?variant=45351533609145) | **3 / 3 / 208** |
| `Govee_H6169*` | [FCC application mirror](https://fccid.io/2A7VD-H6169): TV backlight for 70–80-inch TVs; [Govee-branded manual cover, Amazon mirror](https://m.media-amazon.com/images/I/B1g1XVIpvTL.pdf#page=1): RGBIC LED TV Backlight | **4 / 3 / 203** |
| `Govee_H6098*` | [Govee Care original model video](https://www.youtube.com/watch?v=GXV6zk6Nrng): TV Backlight 3S **candidate** | **12 / 12 / 186** |
| `Govee_H5051*` | [Original FAQ](https://ca.govee.com/pages/easy-faqs): Wi-Fi temperature monitor, initially configured by Bluetooth | **2 / 2 / 4** |
| `Govee_H5179*` | [Original FAQ](https://ca.govee.com/pages/easy-faqs): temperature-monitoring family **candidate**; humidity/connectivity details unverified | **1 / 1 / 1** |
| `Govee_H70B3*` | [Curtain Lights 2](https://us.govee.com/products/govee-curtain-lights-2) | **2 / 2 / 145** |
| `Govee_H610A*` | [Glide Lively modular wall lights, manufacturer model/spec listing](https://community.govee.com/support/faqs/specs) | **11 / 11 / 132** |
| `Govee_H617C*` | [RGBIC Basic Bluetooth LED strip](https://us.govee.com/products/govee-rgbic-basic-led-strip-lights-refurbished) | **8 / 7 / 122** |
| `Govee_H618F*` | [2 × 15 m RGBIC LED Strip Light with Alexa, original model directory](https://app2.govee.com/bff-app/v1/user/guide/device/info?market=US&name=H618F) | **7 / 7 / 118** |
| `Govee_H605C*` | [Envisual TV Backlight T2 dual-camera system](https://us.govee.com/products/govee-envisual-tv-backlight-t2) | **9 / 9 / 111** |
| `Govee_H612D*` | [CSA certified Govee Strip Light S](https://csa-iot.org/csa_product/govee-strip-light-s-5/): `Family Variant SKU: H612D`, RGBICW strip | **12 / 12 / 83** |
| `Govee_H6072*` | [Lyra RGBICWW corner floor lamp](https://ca.govee.com/products/refurbished-govee-lyra-rgbicww-corner-floor-lamp) | **10 / 9 / 39** |
| `Govee_H6052*` | [Aura smart table lamp](https://uk.govee.com/products/refurbished-aura-smart-table-lamp) | **8 / 8 / 54** |
| `Govee_H7015*` | [48 ft, 15-bulb outdoor string lights](https://community.govee.com/support/faqs/specs) | **6 / 6 / 53** |
| `Govee_H7039*` | [Outdoor String Lights 2, 144 ft](https://us.govee.com/products/govee-outdoor-string-lights-2) | **5 / 5 / 24** |
| `Govee_H6001*` | [FCC application mirrored by FCCID.io](https://fccid.io/2AQA6-H6001): LED bulb light | **4 / 4 / 27** |
| `Govee_H60B0*` | [Uplighter floor lamp, Ripple Lighting Effect model](https://us.govee.com/products/govee-uplighter-floor-lamp), distinct from H60B3 | **4 / 4 / 18** |
| `Govee_H6176*` | [Wi-Fi RGBIC Outdoor Strip Lights](https://us.govee.com/products/govee-wi-fi-rgbic-outdoor-strip-lights?variant=43029980709049) | **4 / 4 / 10** |
| `Govee_H6097*` | [TV Backlight 3 Lite](https://us.govee.com/products/govee-tv-backlight-3-lite?variant=45351535083705), distinct public code from H6099 | **3 / 3 / 45** |
| `Govee_H612F*` | [CSA certified Govee Strip Light S](https://csa-iot.org/csa_product/govee-strip-light-s/): `Family Variant SKU: H612F` (strip; exact RGBICW feature not transferred from H612D) | **3 / 3 / 4** |
| `Govee_H702C*` | [Outdoor S14 Bulb String Lights 2](https://us.govee.com/products/govee-outdoor-s14-bulb-string-lights-2) | **3 / 3 / 48** |
| `Govee_H70A1*` | [RGBICWW Outdoor LED Strip Lights](https://us.govee.com/products/govee-rgbicww-outdoor-led-strip-lights?variant=45351535214777) | **3 / 3 / 11** |
| `Govee_H6047*` | [RGBIC Wi-Fi Gaming Light Bars with Smart Controller](https://us.govee.com/products/refurbished-govee-rgbic-wi-fi-gaming-light-bars-with-smart-controller) | **2 / 2 / 9** |
| `Govee_H605D*` | **Third-party, lower-confidence candidate**: [reflowed manual](https://www.manualslib.tech/guide/793986/govee-rgbicww-h605d-manual.html) names a camera-synchronized RGBICWW TV light kit (strip + two light bars) and instructs App search for `H605D`; [independent seller catalog](https://www.ebay.com/p/26058480558) labels H605D a TV backlight kit with camera. Neither is a verified original manual cover or App catalog entry. | **2 / 2 / 25** |
| `Govee_H6078*` | [RGBICWW Cylinder Floor Lamp](https://us.govee.com/products/refurbished-govee-rgbicww-cylinder-floor-lamp) | **2 / 2 / 11** |
| `Govee_H6167*` | [2.4 m RGBIC TV Backlight, original model directory](https://app2.govee.com/bff-app/v1/user/guide/device/info?market=US&name=H6167) | **3 / 3 / 89** |
| `Govee_H617E*` | [RGBIC Basic LED strip, 65.6 ft](https://us.govee.com/products/govee-rgbic-basic-led-strip-lights-refurbished) | **3 / 3 / 67** |
| `Govee_H61B5*` | [RGBIC LED strip with diffusing covers, 16.4 ft](https://us.govee.com/products/govee-rgbic-led-strip-lights-with-covers) | **3 / 3 / 35** |
| `Govee_H7016*` | [2 × 48 ft, 30-bulb Bluetooth string lights](https://community.govee.com/support/faqs/specs) | **3 / 3 / 7** |
| `Govee_H7028*` | [LED Bulb String Lights, original model directory](https://app2.govee.com/bff-app/v1/user/guide/device/info?market=US&name=H7028) | **3 / 2 / 8** |
| `Govee_H61F5*` | [Strip Light 2 Pro](https://us.govee.com/products/govee-led-strip-light-2-pro?variant=45351530528953) | **2 / 1 / 91** |
| `Govee_H619E*` | [RGBIC LED strip with protective coating, 2 × 32.8 ft](https://us.govee.com/products/refurbished-rgbic-led-strip-lights-with-protective-coating) | **1 / 1 / 72** |
| `Govee_H61C3*` | [RGBIC Neon Rope Lights for Desks, 10 ft/3 m](https://us.govee.com/products/govee-rgbic-led-neon-rope-lights-for-desks) | **1 / 1 / 67** |
| `Govee_H6065*` | [Glide Y modular wall lights](https://eu.govee.com/products/govee-glide-rgbic-y-lights) | **1 / 1 / 53** |
| `Govee_H616C*` | [RGBIC Outdoor Strip Lights 2, 10 m, original model directory](https://app2.govee.com/bff-app/v1/user/guide/device/info?market=US&name=H616C) | **2 / 2 / 43** |

The match rule is `^Govee_(H[0-9A-Za-z]{4})` applied to the local name, followed by a hidden per-device suffix. Marketing names can vary by region; no exact hardware revision or ownership is inferred. The first eight added rows (H6199 through H5179) total **51 exact variants / 4,802 scans**; the following five lighting rows total **37 variants / 36 observed addresses / 628 scans**. Both are **subsets** of the 439 / 421 / 15,145 cohort above, not extra brands or devices. The five lighting rows share `01,09,FF,09` and auxiliary fields 34818/34819; earlier lighting and temperature groups also share layouts, but **neither AD pattern establishes category, identity or authenticity**. H5151 gateway addresses are not individual sensor counts; H5074 classification has weaker third-party support.

Five further reviewed public codes (H612D, H6072, H6052, H7015, H7039) account for **41 exact variants / 253 scans** within the same cohort; the sum of their **40 per-code addresses** is not a deduplicated across-code or cohort address count. Four have original manufacturer category mappings; H612D is now independently mapped to Strip Light S by a same-page CSA variant-SKU entry. Refurbished product-page citations establish code-to-model context, **not** that observed units were refurbished.

A second non-overlapping five-code review (H6001, H60B0, H6176, H6097, H612F) contributes **18 exact variants / 104 scans** within the same cohort. Three have direct manufacturer category mappings; H6001 now has an FCC-application LED-bulb description on a third-party mirror, and CSA lists H612F as a Strip Light S variant. Different public codes H6097 and H6099 may share a TV Backlight 3 Lite *product line* without establishing identical retail configuration or two physical units.

A third disjoint five-code review (H702C, H70A1, H6047, H605D, H6078) adds **12 exact variants / 104 scans** within the existing cohort. Four codes have original category mappings; H605D has only a **third-party lower-confidence TV light-kit candidate** based on a reflowed manual's same-page App pairing code and product description, plus a seller catalog, not an original cover or App directory entry. The cited refurbished offerings do not identify the condition of observed units.

The other five codes from this ranked review (H6167, H617E, H61B5, H7016, H7028) have direct original model-to-category descriptions and account for **15 exact variants / 206 scans** inside the 439-variant parent cohort. H7028's **3 variants / 2 addresses** shows why variants and addresses must not be equated. A cited refurbished listing is model context only, not evidence of unit condition.

A further review ranked by **scan count rather than variant count** identified H61F5, H619E, H61C3, H6065 and H616C: **7 exact variants / 326 scans**, all with original code-to-category mappings and no duplicate public code among the already listed `ihoment_H*`/`GBK_H*` groups. This checks observation priority, **not** product prevalence; refurbished source pages do not describe the observed unit's condition.

## `ihoment_H` / Govee multi-category devices

This separate name cohort has **153 exact variants / 144 addresses / 2,327 scans** and **42 public H model codes**. Match `ihoment_Hxxxx` only when the token is followed by a delimiter or end of name; hide the rest. The **18 reviewed codes** below cover **125 variants / 2,102 scans**, leaving **24 unreviewed codes / 28 variants / 225 scans** (remaining distinct addresses not recomputed). They span lighting, power, leak detection, an air purifier and fans; do not merge observations or addresses with `Govee_H*` or `GBK_H*` on a shared code.

| Sanitized name token | Product / source | Variants / addresses / scans |
|---|---|---:|
| `ihoment_H6008*` | [Govee Wi-Fi/Bluetooth color bulb](https://us.govee.com/products/govee-wi-fi-bluetooth-led-bulbs-2-pack) | **40 / 36 / 166** |
| `ihoment_H6006*` | [Govee A19 smart bulb](https://us.govee.com/products/govee-smart-a19-led-light-bulbs) | **14 / 14 / 272** |
| `ihoment_H5083*` | [Govee H5083 smart plug; manufacturer manual hosted by Amazon](https://m.media-amazon.com/images/I/B1F87OLQR8L.pdf) | **10 / 10 / 117** |
| `ihoment_H5082*` | [Govee two-outlet smart plug](https://community.govee.com/support/video-guide/194) | **8 / 8 / 50** |
| `ihoment_H6110*` | [Govee RGB smart strip](https://community.govee.com/support/faqs/specs) | **8 / 8 / 614** |
| `ihoment_H6004*` | [Govee RGBWW bulb](https://app-h5.govee.com/user-manual/wlan-guide?im_rewards=1) | **7 / 7 / 49** |
| `ihoment_H6114*` | [Govee car interior ambient lighting](https://community.govee.com/support/faqs/specs) | **5 / 5 / 174** |
| `ihoment_H7131*` | [Govee portable electric space heater](https://us.govee.com/products/govee-smart-portable-electric-space-heater?Style=H7131) | **5 / 5 / 191** |
| `ihoment_H5043*` | [GoveeLife water-leak detection system family](https://us.govee.com/products/refurbished-goveelife-water-leak-detector-2): original kit lists **H5043 & H5058**; observed emitter role within the kit unresolved | **4 / 3 / 10** |
| `ihoment_H600A*` | [1200 lm RGBWW smart light bulb](https://us.govee.com/products/h600a-smart-led-night-light-bulb); two-/four-pack size is not another model | **4 / 2 / 17** |
| `ihoment_H5080*` | [FCC application mirrored by FCCID.io](https://fccid.io/2AQA6-H5080): smart plug | **3 / 3 / 17** |
| `ihoment_H6159*` | [5 m RGB light strip, original device directory](https://app2.govee.com/bff-app/v1/user/guide/device/info?market=US&name=H6159); **also appears as `GBK_H6159*` below** | **3 / 3 / 36** |
| `ihoment_H7100*` | [Round table fan, original specification list](https://community.govee.com/support/faqs/specs) | **3 / 2 / 28** |
| `ihoment_H7126*` | [GoveeLife Smart Air Purifier Lite](https://us.govee.com/products/goveelife-smart-air-purifier-lite) | **3 / 3 / 16** |
| `ihoment_H6010*` | [BR30 RGBWW smart bulb, 1200 lm](https://us.govee.com/products/govee-rgbww-smart-led-light-bulbs) | **2 / 1 / 3** |
| `ihoment_H6052*` | [Aura smart table lamp](https://uk.govee.com/products/refurbished-aura-smart-table-lamp); **already listed as `Govee_H6052*`** | **2 / 2 / 5** |
| `ihoment_H6054*` | [DreamView P1 camera-assisted screen-backlight light bars](https://ca.govee.com/products/refurbished-govee-dreamview-p1-light-bars) | **2 / 2 / 14** |
| `ihoment_H6117*` | [2 × 5 m RGBIC strip lights, original model directory](https://app2.govee.com/bff-app/v1/user/guide/device/info?market=US&name=H6117) | **2 / 2 / 323** |

Govee has [announced a voluntary electric-heater recall](https://community.govee.com/posts/govee-life-and-govees-voluntary-recall-of-electric/219685). A historical advertisement does **not** establish current use or whether any unit was recalled. Matching model-name tokens plus product listings supports candidate attribution, not device authentication or hardware-revision identification.

The newly listed `ihoment_H6159*` is the **same public H6159 code** already listed under `GBK_H*`; it adds another name-prefix observation, **not** a new retail model or a deduplicated cross-prefix count. H5043 appears in a kit listing alongside H5058, but the ad does not distinguish the gateway, probe or kit component.

`ihoment_H6052*` likewise repeats the **public H6052 code** already present under `Govee_H*`; do not count another product or equate the two prefixes with one physical unit. The air-purifier model page is product-category evidence, not evidence of medical approval, air-quality results or clinical use.

## `GBK_H` Govee vehicle/home lighting

Keep `GBK_H*` separate from `Govee_H*` and `ihoment_H*`: **60 exact variants / 58 addresses / 4,472 scans** across the **16 public H codes** below. Shared H codes across prefixes do not establish distinct physical devices or additive address counts. [Govee specifications](https://community.govee.com/support/faqs/specs), [original product-code directory](https://app2.govee.com/bff-app/v1/user/guide/device/info?market=US&name=H619B), and [supported-model list](https://developer.govee.com/docs/support-product-model) support categories only where an individual code is mapped.

| Sanitized name-model token | Candidate category | Variants / addresses / scans |
|---|---|---:|
| `GBK_H6114*` | RGB vehicle interior lights | **24 / 24 / 718** |
| `GBK_H6159*` | RGB home light strip | **5 / 5 / 50** |
| `GBK_H613E*` | RGB long light strip | **4 / 4 / 462** |
| `GBK_H619Z*` | RGBIC Pro light strip | **4 / 3 / 36** |
| `GBK_H61A0*` | RGBIC flexible neon light strip | **4 / 4 / 28** |
| `GBK_H6119*` | RGBIC vehicle interior lights | **4 / 4 / 9** |
| `GBK_H7090*` | RGBIC vehicle interior lights | **3 / 3 / 101** |
| `GBK_H618A*` | RGBIC Basic light strip | **2 / 1 / 2,834** |
| `GBK_H613D*` | [Original specification-index clue](https://community.govee.com/support/faqs/specs) suggests RGB strip; full original category text not retrieved, **provisional only** | **2 / 2 / 190** |
| `GBK_H6168*` | [FCC application mirrored by FCCID.io](https://fccid.io/2A7VD-H6168): Govee RGBIC LED TV Backlight; same public code already under `Govee_H*` | **2 / 2 / 12** |
| `GBK_H613G*` | [Govee-branded manual cover, Amazon mirror](https://m.media-amazon.com/images/I/C1-J9TE21SL.pdf#page=1): RGB LED Strip Light | **1 / 1 / 1** |
| `GBK_H6167*` | [2.4 m RGBIC TV Backlight, original model directory](https://app2.govee.com/bff-app/v1/user/guide/device/info?market=US&name=H6167); also under `Govee_H*` | **1 / 1 / 4** |
| `GBK_H618F*` | [2 × 15 m RGBIC LED Strip Light with Alexa, original model directory](https://app2.govee.com/bff-app/v1/user/guide/device/info?market=US&name=H618F); also under `Govee_H*` | **1 / 1 / 2** |
| `GBK_H619B*` | [7.5 m RGBIC Pro Strip Lights with Alexa, original model directory](https://app2.govee.com/bff-app/v1/user/guide/device/info?market=US&name=H619B) | **1 / 1 / 2** |
| `GBK_H619D*` | [2 × 7.5 m RGBIC Pro Strip Lights with Alexa, original model directory](https://app2.govee.com/bff-app/v1/user/guide/device/info?market=US&name=H619D) | **1 / 1 / 22** |
| `GBK_H61A2*` | [5 m RGBIC LED Neon Rope Lights, original model directory](https://app2.govee.com/bff-app/v1/user/guide/device/info?market=US&name=H61A2) | **1 / 1 / 1** |

Auxiliary company field `0x8802` is not sufficient brand proof; duplicate and reordered AD fields do not uniquely identify a model. The sharp scan imbalance for H618A is observation frequency, **not** a sales estimate.

The additional eight `GBK_H*` codes above account for the previously unlisted **10 exact variants / 234 scans**. H6168, H6167 and H618F are **already listed in `Govee_H*`**; H6114 and H6159 also occur under `ihoment_H*`. Duplicate public codes across prefixes are not extra product models or proof of identical broadcasting units. H613D's RGB-strip category remains an incomplete specification-index clue, not a directly verified original mapping.

The H613G, H6168 and H6169 descriptions also appear alongside the exact codes on original-manufacturer manuals hosted by Amazon; these are **manual mirrors, not Govee App category records**. The name/model match remains a candidate and does not authenticate a received BLE advertisement.

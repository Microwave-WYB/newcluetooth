---
qmd:
  metadata:
    title: "IoT fingerprints from broadcast model-name tokens"
    date: "2026-10-16"
    tags: [iot, ble, models]
    confidence: medium
---

# IoT fingerprints from broadcast model-name tokens

First-party simi `cluetooth` read-only aggregates, researched **2026-10-16**. The principal feature here is a **sanitized model token in the advertised name**, not a claim that a generic BLE shape uniquely identifies a model. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, **not confirmed physical devices**. Hide all device-specific suffixes; names can be spoofed, and a research date is not an observation date. No hospital association is required.

## Govee lighting and environmental monitoring

The `Govee_H*` cohort contains **439 variants / 421 addresses / 15,145 scans**, spanning **104 distinct `H` model tokens**. The following **23 tokens** have been individually checked; **81 others have not** and must not all be labeled lights. Two checked tokens are confirmed only in a manufacturer model list, **without a product category**. Original Govee product/support material corroborates many model families but not the authenticity of advertisers; H5074 relies on a third-party manual.

| Advertised model token (suffix hidden) | Manufacturer product / application | Variants / addresses / scans |
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
| `Govee_H6168*` | [Manufacturer supported-model list](https://developer.govee.com/docs/support-product-model): **category unknown** | **6 / 5 / 1,649** |
| `Govee_H6022*` | [Table Lamp 2](https://us.govee.com/products/led-table-lamp-2) | **11 / 10 / 813** |
| `Govee_H61D5*` | [Neon Rope Light 2, 5 m](https://us.govee.com/products/govee-neon-rope-light-2?variant=45351533609145) | **3 / 3 / 208** |
| `Govee_H6169*` | [Manufacturer supported-model list](https://developer.govee.com/docs/support-product-model): **category unknown** | **4 / 3 / 203** |
| `Govee_H6098*` | [Govee Care original model video](https://www.youtube.com/watch?v=GXV6zk6Nrng): TV Backlight 3S **candidate** | **12 / 12 / 186** |
| `Govee_H5051*` | [Original FAQ](https://ca.govee.com/pages/easy-faqs): Wi-Fi temperature monitor, initially configured by Bluetooth | **2 / 2 / 4** |
| `Govee_H5179*` | [Original FAQ](https://ca.govee.com/pages/easy-faqs): temperature-monitoring family **candidate**; humidity/connectivity details unverified | **1 / 1 / 1** |
| `Govee_H70B3*` | [Curtain Lights 2](https://us.govee.com/products/govee-curtain-lights-2) | **2 / 2 / 145** |
| `Govee_H610A*` | [Glide Lively modular wall lights, manufacturer model/spec listing](https://community.govee.com/support/faqs/specs) | **11 / 11 / 132** |
| `Govee_H617C*` | [RGBIC Basic Bluetooth LED strip](https://us.govee.com/products/govee-rgbic-basic-led-strip-lights-refurbished) | **8 / 7 / 122** |
| `Govee_H618F*` | [RGBIC LED strip model/connectivity guide](https://app-h5.govee.com/user-manual/wlan-guide) | **7 / 7 / 118** |
| `Govee_H605C*` | [Envisual TV Backlight T2 dual-camera system](https://us.govee.com/products/govee-envisual-tv-backlight-t2) | **9 / 9 / 111** |

The match rule is `^Govee_(H[0-9A-Za-z]{4})` applied to the local name, followed by a hidden per-device suffix. Marketing names can vary by region; no exact hardware revision or ownership is inferred. The first eight added rows (H6199 through H5179) total **51 exact variants / 4,802 scans**; the following five lighting rows total **37 variants / 36 observed addresses / 628 scans**. Both are **subsets** of the 439 / 421 / 15,145 cohort above, not extra brands or devices. The five lighting rows share `01,09,FF,09` and auxiliary fields 34818/34819; earlier lighting and temperature groups also share layouts, but **neither AD pattern establishes category, identity or authenticity**. H5151 gateway addresses are not individual sensor counts; H5074 classification has weaker third-party support.

## Sony EXTRA BASS portable speakers

Five exact `LE_SRS-XB` model-name tokens appear in **25 variants / 25 addresses / 167 scans**. [Sony's SRS-XB43 specifications](https://www.sony.com/electronics/support/wireless-speakers/srs-xb43/specifications) and [XB41/XB31 series guide](https://www.sony.fr/electronics/support/articles/00200861) document Bluetooth portable speakers; the model token is the primary fingerprint. `LE_` in a name does **not** imply LE Audio support.

| Advertised name-model token | Variants / addresses / scans |
|---|---:|
| `LE_SRS-XB23` | **5 / 5 / 54** |
| `LE_SRS-XB31` | **1 / 1 / 18** |
| `LE_SRS-XB33` | **9 / 9 / 44** |
| `LE_SRS-XB41` | **2 / 2 / 23** |
| `LE_SRS-XB43` | **8 / 8 / 28** |

Sixteen variants have `FF/21, 01/1, 09/11` with auxiliary Sony company field 301; nine have `16/(10,11 or 15), 0A/1, 09/11` with `FE2C` service data. A model can appear in both layouts: neither shape alone identifies the model.

## Amazon Echo subfamilies

An exact-case `Echo*` name cohort has **29 variants / 29 addresses / 330 scans**; an earlier case-insensitive prefix survey counted 30 / 30 / 335 and should not be silently combined. [Amazon's Echo buying guide](https://www.aboutamazon.com/news/devices/amazon-echo-buying-guide) distinguishes Dot/Pop smart speakers from Buds wireless earbuds; names do not establish product generation.

| Sanitized name token | Candidate category | Variants / addresses / scans |
|---|---|---:|
| `Echo Dot*` | Alexa smart speaker | **21 / 21 / 206** |
| `Echo Pop*` | Compact Alexa smart speaker | **4 / 4 / 97** |
| `Echo Buds*` | Alexa wireless earbuds, **not a speaker** | **2 / 2 / 22** |
| Other `Echo*` | Unresolved Echo-family lead | **2 / 2 / 5** |

Dot/Pop generally combine their name token with Amazon-assigned service-data UUID `FE00`; `01/1,16/25,09/12,0A/1` is common, with one shorter service-data field. Buds combine their name with auxiliary Amazon company field 369 and a different service UUID; malformed-looking parsed AD fields are not a model discriminator. No `Echo Show` token was identified in this cohort.

## Meta Quest VR / mixed-reality headsets

The `Quest*` cohort has **26 variants / 26 addresses / 175 scans**. [Meta's Quest device comparison](https://developers.meta.com/horizon/essentials/compare-devices/) corroborates the three name-model candidates. Match `Quest 3` with a model-token boundary so it does **not** include `Quest 3S`.

| Name-model token | Variants / addresses / scans |
|---|---:|
| `Quest 2` | **1 / 1 / 1** |
| `Quest 3` (not `3S`) | **11 / 11 / 137** |
| `Quest 3S` | **14 / 14 / 37** |

All groups carry service/service-data UUID `FEB8` and auxiliary company field 1422; ordered AD types `01,09,03,FF,16` with name-data length 7 or 8. Name and UUID are **candidate features, not identity authentication** or proof the headset was running.

## Pokémon GO physical accessories

The case-insensitive `Pokemon*` cohort has **52 variants / 51 addresses / 1,023 scans**. Two exact product-name tokens account for the cohort, without implying that all broadcasters are original licensed hardware.

| Observed name token | Candidate and source | Variants / addresses / scans |
|---|---|---:|
| `Pokemon GO Plus +` | [Pokémon GO Plus+](https://www.pokemongoplusplus.com/en/) (also works with Pokémon Sleep) | **42 / 41 / 924** |
| `Pokemon GO Plus` | [Original GO Plus or compatible accessory](https://niantic.helpshift.com/hc/en/6-pokemon-go/faq/77-official-bluetooth-devices-1689609298/) | **10 / 10 / 99** |

The Plus+ group has ordered AD types `01,FF,20,09`, data lengths `1,17,4,17` and auxiliary company field 1363; the earlier-name group uses four shapes and sometimes a service-data UUID field. Third-party auto-catchers or software emulation **cannot be ruled out**, especially for the earlier-name group. No Poké Ball Plus attribution is made.

## `ihoment_H` / Govee multi-category devices

This separate name cohort has **153 exact variants / 144 addresses / 2,327 scans** and 42 model tokens. Match `ihoment_Hxxxx` only when the token is followed by a delimiter or end of name; hide the rest. The eight most frequent tokens are **not all lighting products**, and none should be merged with the `Govee_H*` counts above.

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

Govee has [announced a voluntary electric-heater recall](https://community.govee.com/posts/govee-life-and-govees-voluntary-recall-of-electric/219685). A historical advertisement does **not** establish current use or whether any unit was recalled. Matching model-name tokens plus product listings supports candidate attribution, not device authentication or hardware-revision identification.

## `GBK_H` Govee vehicle/home lighting

Keep `GBK_H*` separate from `Govee_H*` and `ihoment_H*`: **60 variants / 58 addresses / 4,472 scans**. These eight model tokens account for 50 variants and 4,238 scans, but a repeated H-model token across different name prefixes does **not** prove a different physical device. [Govee specifications](https://community.govee.com/support/faqs/specs) and the [official supported-model list](https://developer.govee.com/docs/support-product-model) corroborate product-model candidates.

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

Auxiliary company field `0x8802` is not sufficient brand proof; duplicate and reordered AD fields do not uniquely identify a model. The sharp scan imbalance for H618A is observation frequency, **not** a sales estimate.

## BlackVue connected dashcams

Name-model tokens distinguish seven candidate series within **19 variants / 19 addresses / 176 scans**; the broader case-insensitive `BlackVue*` prefix has one further **unassigned** variant/address/scan (20 / 20 / 177 total). The [official BlackVue smartphone connection instructions](https://manual.blackvue.com/docs/dr770x/playing-and-managing-videos/using-your-smartphone-android-ios-dr750x-plus-series/) describe Bluetooth Seamless Pairing followed by Wi-Fi; Cloud connectivity is optional, not inferred from a BLE advertisement. These are series-name leads, not proof of camera sub-configuration.

| Name-model token (suffix hidden) | Variants / addresses / scans | Ordered AD types / data lengths in bytes |
|---|---:|---|
| `750XPLUS*` | **4 / 4 / 5** | `01/1, 09/23` |
| `970XLTE*` | **3 / 3 / 124** | `01/1, 09/23` |
| `770X*` | **3 / 3 / 5** | `01/1, 09/19` |
| `900X*` | **3 / 3 / 12** | `01/1, 0A/1, 09/19` |
| `900XPLUS*` | **3 / 3 / 3** | `01/1, 09/23` |
| `970X*` | **2 / 2 / 25** | `01/1, 09/20` |
| `750XLTE*` | **1 / 1 / 2** | `01/1, 09/26` |

AD lengths exclude the length/type bytes. The `900X` and `970X` rows exclude their separately listed `PLUS` and `LTE` tokens respectively. These advertisements do not carry a manufacturer ID or service UUID. Identical simple AD shapes across distinct names are **not** grounds to merge product series. No full MACs, per-device names, serials, payloads or locations are published.

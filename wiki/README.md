---
qmd:
  metadata:
    title: "IoT product fingerprint research wiki"
    date: "2026-10-16"
    tags: [iot, ble]
    confidence: medium
---

# IoT product fingerprint research wiki

**Purpose: lookup.** When you see a Bluetooth advertisement, use this page to find out what it is: look up its company field, service/service-data UUID, local-name prefix or ordered AD-structure shape in the tables below, then open the family page. Pages are organized by **advertisement-structure / protocol family**, not by brand; each opens with a "How to recognize it" block.

Earlier entries are sanitized hospital-region leads from private research recorded since **2026-10-14**; newer entries also use first-party BLE advertisements and **do not require any hospital association**.
Dates are research-record dates, **not** inferred radio-observation dates. Confidence describes product attribution, not verified installation.

[**What is actually hospital-related? Evidence tiers and limits**](hospital-relevance-evidence-tiers.md) distinguishes original hospital-facing product functions from plausible facilities equipment, consumer devices, and unknown identities. No listed observation establishes an installed or owned device at a particular hospital.

Identifiers in the tables are values already recorded on the linked pages; they are candidates for lookup, **not authentication** (names, company fields and UUIDs can be spoofed or shared). Company fields are written as decimal `company field N` (or as the hex form the source gave); 16-bit UUIDs are uppercase; 128-bit UUIDs lowercase; AD shapes are `type/length` pairs in observed order (length excludes the length and type bytes). An identifier listed under several pages is **ambiguous** and must not be resolved to one family without another identifier. The index covers the first-party catalogue pages; the earlier hospital-region pages below are not yet indexed by identifier except CAREL, Levoit Core 400S and ResMed.

## Lookup by company field

| Company field | Family page(s) | Note |
|---|---|---|
| company field 64 | [Epson ET-* / WF-* printers](epson-ecotank-printers.md) |  |
| company field 76 | [Nutale Nutale_FMN Find My item tags](nutale-find-my-tags.md)<br>[RoadPro POWERDRIVER-L* inverters](powerdrive-inverters.md)<br>[Nanoleaf Nanoleaf*A19* Essentials bulbs](nanoleaf-essentials.md) | Apple-associated company field; not brand proof; **ambiguous: shared by 3 family pages** — disambiguate with the other identifiers on each page |
| company field 87 | [JBL JBL Xtreme 3 speaker](jbl-xtreme-3.md) |  |
| company field 88 | [VIZIO VIZIO Mobile App name](vizio-mobile.md) |  |
| company field 89 | [Plaud Plaud Note* / Plaud NotePin* AI recorders](plaud-recorders.md)<br>[Pedal Commander Pedal Commander and pedal*](pedal-commander.md) | **ambiguous: shared by 2 family pages** — disambiguate with the other identifiers on each page |
| company field 93 | [Plaud Plaud Note* / Plaud NotePin* AI recorders](plaud-recorders.md)<br>[GREE / TOSOT GR-AC_* appliances](gree-tosot-gr-ac.md) | **ambiguous: shared by 2 family pages** — disambiguate with the other identifiers on each page |
| company field 101 | [HP-number-associated FE78 / company field 101 broadcasts](hp-fe78-company-101-printers.md) |  |
| company field 117 | [Samsung Galaxy Watch and Galaxy Ring](samsung-galaxy-wearables.md)<br>[Samsung SmartThings appliances](samsung-smartthings-appliances.md)<br>[living* shared room-name prefix](living-room-name-prefix.md) | Samsung-assigned company field; **ambiguous: shared by 3 family pages** — disambiguate with the other identifiers on each page |
| company field 135 | [Garmin-assigned FRONT* signal](garmin-front-fe1f.md) |  |
| company field 158 | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| company field 160 | [Acuity lighting control](acuity-nlight-lighting-control.md) |  |
| company field 220 | [Oral-B Oral-B Toothbrush](oral-b.md) |  |
| company field 256 | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| company field 259 | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |
| company field 269 | [Toyota-associated Toyota* multimedia](toyota-denso-ten.md) |  |
| company field 283 | [Unnamed broadcast population](unnamed-broadcast-opaque-clusters.md) |  |
| company field 301 | [Sony LE_ audio name tokens](sony-le-prefixed-audio-names.md) |  |
| company field 305 | [Air Lift wirelessone-* / wirelessair-* air suspension](air-lift-wireless-suspension.md) |  |
| company field 315 | [Schlage Schlage* locks](schlage-locks.md) |  |
| company field 343 | [Amazfit Amazfit* wearables](amazfit-fee0.md) |  |
| company field 369 | [Amazon Echo* Dot / Pop / Buds](amazon-echo-name-tokens.md)<br>[Ring Ring* security ecosystem](ring-security.md) | **ambiguous: shared by 2 family pages** — disambiguate with the other identifiers on each page |
| company field 555 | [tsTPMS aftermarket Tesla-compatible TPMS](tesla-aftermarket-tpms.md) |  |
| company field 637 | [HUAWEI HUAWEI* watches and bands](huawei-wearables.md) |  |
| company field 683 | [Stripe Reader M2 / BBPOS STRM*](stripe-reader-m2-strm.md) |  |
| company field 690 | [Oura Oura* ring](oura-ring.md) |  |
| company field 737 | [Victron Energy](victron-energy-ble.md) |  |
| company field 881 | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| company field 909 | [ResMed ResMed* first-party name cohort](resmed-company-909.md) |  |
| company field 945 | [Otodata level](otodata-level.md) |  |
| company field 961 | [Ember Ember Ceramic Mug* / Ember Travel Mug*](ember-mugs.md) |  |
| company field 963 | [Casambi lighting](casambi-wireless-lighting.md) |  |
| company field 1021 | [Trimble trimble*](trimble-company-1021.md) |  |
| company field 1087 | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| company field 1151 | [Orbit bhyve_* irrigation](orbit-bhyve.md) |  |
| company field 1363 | [Pokémon GO Plus / Plus+ Pokemon* accessories](pokemon-go-plus.md) |  |
| company field 1422 | [Meta Quest* headsets](meta-quest-feb8.md) |  |
| company field 1458 | [CAREL HVAC/R candidate](carel-hvac.md) |  |
| company field 1479 | [Lippert OneControl LCIRemote*](lippert-onecontrol-lciremote.md) |  |
| company field 1480 | [living* shared room-name prefix](living-room-name-prefix.md) |  |
| company field 1534 | [Polestar Polestar* vehicle names](polestar-vehicle-names.md) |  |
| company field 1556 | [OnAsset onasset*](onasset.md) |  |
| company field 1626 | [Marshall Acton and Stanmore speakers](marshall-home-speakers.md) |  |
| company field 1678 | [Razer Razer* peripherals](razer.md) |  |
| company field 1705 | [Rockford Fosgate PMX-1 marine receiver](rockford-fosgate-pmx1.md) |  |
| company field 1744 | [Levoit LAP-* / Core*S* air purifiers](levoit-company-1744.md)<br>[Levoit Core 400S smart air purifier](levoit-core-400s.md) | **ambiguous: shared by 2 family pages** — disambiguate with the other identifiers on each page |
| company field 1767 | [Platco PR1 BLE HID remote](platco-pr1.md) |  |
| company field 1794 | [Aranet4 air-quality monitor](aranet4.md) |  |
| company field 1875 | [JLG ClearSky* Smart Fleet](jlg-clearsky.md) |  |
| company field 2016 | [Edifier EDIFIER BLE](edifier-ble.md) |  |
| company field 2059 | [Nanoleaf Nanoleaf*A19* Essentials bulbs](nanoleaf-essentials.md) |  |
| company field 2160 | [Wyze Wyze_Hub](wyze-hub.md) |  |
| company field 2168 | [Chamberlain MyQ-* / myQ-F* smart garage](chamberlain-myq.md) |  |
| company field 2175 | [Phillips Connect trailer telematics](phillips-connect-trailer-ads.md) |  |
| company field 2218 | [DJI Osmo Action OsmoAction* cameras](dji-osmo-action-cameras.md) |  |
| company field 2234 | [Hyperice Hypervolt 2 / Hypervolt 2 Pro](hyperice-hypervolt.md) |  |
| company field 2355 | [SRAM SRAM* AXS](sram-axs-fe51.md) |  |
| company field 2369 | [Rivian Rivian* vehicle BLE](rivian.md) |  |
| company field 2519 | [BlazePod BlazePod pods](blazepod.md) |  |
| company field 2817 | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| company field 3027 | [Procon ARROWQ vehicle telematics](procon-arrowq.md) |  |
| company field 3244 | [Shokz LE-OpenRun* / OpenSwim* bone-conduction headsets](shokz-bone-conduction.md) |  |
| company field 3363 | [GREE / TOSOT GR-AC_* appliances](gree-tosot-gr-ac.md) |  |
| company field 4101 | [Acuity lighting control](acuity-nlight-lighting-control.md) |  |
| company field 5324 | [Edifier EDIFIER BLE](edifier-ble.md) |  |
| company field 10245 | [Acuity lighting control](acuity-nlight-lighting-control.md) |  |
| company field 12849 | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |
| company field 14083 | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| company field 15619 | [Philips TAB5309 soundbar](philips-tab5309-soundbar.md) |  |
| company field 15833 | [Unnamed broadcast population](unnamed-broadcast-opaque-clusters.md) |  |
| company field 17153 | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| company field 17992 | [Dreo appliances on 5348](dreo-5348-appliances.md) |  |
| company field 23556 | [Big Ass Fans Haiku*](big-ass-fans-haiku.md) |  |
| company field 34818 | [Govee H-code name tokens](govee-h-model-code-names.md) |  |
| company field 34819 | [Govee H-code name tokens](govee-h-model-code-names.md) |  |
| company field 41222 | [Cardo Freecom* helmet intercoms](cardo-freecom.md) |  |
| company field 64600 | [TOZO TOZO-T* / TOZO-A* earbuds](tozo-earbuds.md) |  |
| company field 0x0211 | [Unnamed Bluetooth Mesh Proxy](mesh-proxy-1828-unnamed.md) |  |
| company field 0x07A5 | [Unnamed Bluetooth Mesh Proxy](mesh-proxy-1828-unnamed.md) |  |
| company field 0x8802 | [Govee H-code name tokens](govee-h-model-code-names.md) |  |
| company field 0xFFFF | [Anker Anker* SOLIX / Prime and AFYDKPN*](anker-ff09-power-products.md)<br>[Hoopo HOOPO* hoopoSense asset tracker](hoopo-hoopo-sense.md)<br>[Ember Ember Ceramic Mug* / Ember Travel Mug*](ember-mugs.md) | uninformative company field, not vendor evidence; **ambiguous: shared by 3 family pages** — disambiguate with the other identifiers on each page |

## Lookup by service / service-data UUID

| UUID | Family page(s) | Note |
|---|---|---|
| `1122` | [tsTPMS aftermarket Tesla-compatible TPMS](tesla-aftermarket-tpms.md) |  |
| `1800` | [Right Weigh RWLS-* load scales](right-weigh-rwls.md)<br>[Unassigned name prefixes](unassigned-ble-name-prefixes.md) | generic; **ambiguous: shared by 2 family pages** — disambiguate with the other identifiers on each page |
| `1802` | [Platco PR1 BLE HID remote](platco-pr1.md) |  |
| `180A` | [COROS COROS* GPS sports watches](coros-watches.md)<br>[Hyperice Hypervolt 2 / Hypervolt 2 Pro](hyperice-hypervolt.md)<br>[Ember Ember Ceramic Mug* / Ember Travel Mug*](ember-mugs.md)<br>[Right Weigh RWLS-* load scales](right-weigh-rwls.md)<br>[Platco PR1 BLE HID remote](platco-pr1.md) | generic Device Information service; **ambiguous: shared by 5 family pages** — disambiguate with the other identifiers on each page |
| `180D` | [Amazfit Amazfit* wearables](amazfit-fee0.md)<br>[Fitbit Charge* health bands](fitbit-charge.md)<br>[Garmin fenix* multisport watches](garmin-fenix-watches.md)<br>[CodeReader* BLE barcode/QR scanner class](codereader-barcode.md)<br>[Hume hume band* wearable](hume-band.md) | generic Heart Rate service; **ambiguous: shared by 5 family pages** — disambiguate with the other identifiers on each page |
| `180F` | [Shokz LE-OpenRun* / OpenSwim* bone-conduction headsets](shokz-bone-conduction.md)<br>[COROS COROS* GPS sports watches](coros-watches.md)<br>[Platco PR1 BLE HID remote](platco-pr1.md) | generic Battery Service; **ambiguous: shared by 3 family pages** — disambiguate with the other identifiers on each page |
| `1812` | [ION Audio Pathfinder* speakers](ion-pathfinder-speakers.md)<br>[Logitech M720 Triathlon / MX Master 2S HID mice](logitech-hid-mice.md)<br>[Platco PR1 BLE HID remote](platco-pr1.md) | generic HID service; **ambiguous: shared by 3 family pages** — disambiguate with the other identifiers on each page |
| `1828` | [Unnamed Bluetooth Mesh Proxy](mesh-proxy-1828-unnamed.md) |  |
| `18F0` | [OBD-II BLE diagnostic adapters](obd-ii-ble-adapters.md) |  |
| `2215` | [Anker Anker* SOLIX / Prime and AFYDKPN*](anker-ff09-power-products.md) |  |
| `252A` | [living* shared room-name prefix](living-room-name-prefix.md) |  |
| `3802` | [HUAWEI HUAWEI* watches and bands](huawei-wearables.md) |  |
| `4353` | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |
| `5348` | [Dreo appliances on 5348](dreo-5348-appliances.md) |  |
| `5D00` | [Rockford Fosgate PMX-1 marine receiver](rockford-fosgate-pmx1.md) |  |
| `7777` | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |
| `AA00` | [Marshall Acton and Stanmore speakers](marshall-home-speakers.md) |  |
| `CA00` | [Edifier EDIFIER BLE](edifier-ble.md) |  |
| `D700` | [Edifier EDIFIER BLE](edifier-ble.md) |  |
| `EC00` | [Edifier EDIFIER BLE](edifier-ble.md) |  |
| `F300` | [Edifier EDIFIER BLE](edifier-ble.md) |  |
| `FA00` | [Air Lift wirelessone-* / wirelessair-* air suspension](air-lift-wireless-suspension.md) |  |
| `FC6D` | [Motive fleet AABL* / Motive_AABL*](motive-aabl.md) |  |
| `FD2A` | [Sony LE_ audio name tokens](sony-le-prefixed-audio-names.md) |  |
| `FD56` | [ResMed ResMed* first-party name cohort](resmed-company-909.md) |  |
| `FD62` | [Fitbit Charge* health bands](fitbit-charge.md) |  |
| `FD65` | [Razer Razer* peripherals](razer.md) |  |
| `FD69` | [Samsung Galaxy Watch and Galaxy Ring](samsung-galaxy-wearables.md) |  |
| `FD82` | [Sony LE_ audio name tokens](sony-le-prefixed-audio-names.md) |  |
| `FDDF` | [JBL JBL Xtreme 3 speaker](jbl-xtreme-3.md) |  |
| `FDEE` | [HUAWEI HUAWEI* watches and bands](huawei-wearables.md) |  |
| `FDF7` | [HP-number-associated FE78 / company field 101 broadcasts](hp-fe78-company-101-printers.md) |  |
| `FDFD` | [Polestar Polestar* vehicle names](polestar-vehicle-names.md) |  |
| `FE00` | [Amazon Echo* Dot / Pop / Buds](amazon-echo-name-tokens.md) |  |
| `FE03` | [Sony LE_ audio name tokens](sony-le-prefixed-audio-names.md) |  |
| `FE0D` | [Oral-B Oral-B Toothbrush](oral-b.md) |  |
| `FE0F` | [living* shared room-name prefix](living-room-name-prefix.md) |  |
| `FE1F` | [Garmin-assigned FRONT* signal](garmin-front-fe1f.md) |  |
| `FE2C` | [Sony LE_ audio name tokens](sony-le-prefixed-audio-names.md)<br>[HP Poly Poly* Bluetooth headsets and adapter](hp-poly-bluetooth-audio.md)<br>[Marshall Acton and Stanmore speakers](marshall-home-speakers.md)<br>[Jabra Jabra* earbuds, headsets and speakerphone](jabra-name-tokens.md)<br>[JBL Endurance Peak 3 / Race 2 / Peak II earbuds](jbl-endurance-earbuds.md)<br>[Razer Razer* peripherals](razer.md)<br>[Unassigned name prefixes](unassigned-ble-name-prefixes.md) | generic Google Fast Pair service; **ambiguous: shared by 7 family pages** — disambiguate with the other identifiers on each page |
| `FE32` | [Orbit bhyve_* irrigation](orbit-bhyve.md) |  |
| `FE48` | [GM GM_PEPS_VKM](gm-peps-fe48.md) |  |
| `FE4D` | [Casambi lighting](casambi-wireless-lighting.md) |  |
| `FE4F` | [living* shared room-name prefix](living-room-name-prefix.md) |  |
| `FE51` | [SRAM SRAM* AXS](sram-axs-fe51.md) |  |
| `FE59` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `FE78` | [HP-number-associated FE78 / company field 101 broadcasts](hp-fe78-company-101-printers.md) |  |
| `FE8F` | [Marshall Acton and Stanmore speakers](marshall-home-speakers.md) |  |
| `FEA0` | [Google Cast / Home devices on FEA0](google-cast-home-fea0.md)<br>[living* shared room-name prefix](living-room-name-prefix.md) | Google-assigned; shared by several Google Cast / Home products; **ambiguous: shared by 2 family pages** — disambiguate with the other identifiers on each page |
| `FEA6` | [GoPro BLE protocol](gopro-fea6.md) |  |
| `FEB8` | [Meta Quest* headsets](meta-quest-feb8.md) |  |
| `FEBE` | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| `FEE0` | [Amazfit Amazfit* wearables](amazfit-fee0.md) |  |
| `FEE7` | [COROS COROS* GPS sports watches](coros-watches.md) |  |
| `FEED` | [Tile Tile item finder](tile-feed.md) |  |
| `FEF1` | [Kuna Kuna / Kuna*](kuna-fef1.md) |  |
| `FF09` | [Anker Anker* SOLIX / Prime and AFYDKPN*](anker-ff09-power-products.md) |  |
| `FFA0` | [Stripe Reader M2 / BBPOS STRM*](stripe-reader-m2-strm.md) |  |
| `FFB0` | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |
| `FFC0` | [Rockford Fosgate PMX-1 marine receiver](rockford-fosgate-pmx1.md)<br>[OontZ OontZ* Angle 3 speakers](oontz-speakers.md) | **ambiguous: shared by 2 family pages** — disambiguate with the other identifiers on each page |
| `FFD0` | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md)<br>[Renogy-compatible BT-TH-*](renogy-bt-th.md) | **ambiguous: shared by 2 family pages** — disambiguate with the other identifiers on each page |
| `FFD5` | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |
| `FFF0` | [DJI Osmo Action OsmoAction* cameras](dji-osmo-action-cameras.md)<br>[Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md)<br>[coolledux programmable LED display](coolledux.md)<br>[coolledx programmable LED display](coolledx.md)<br>[OBD-II BLE diagnostic adapters](obd-ii-ble-adapters.md)<br>[CodeReader* BLE barcode/QR scanner class](codereader-barcode.md) | generic; **ambiguous: shared by 6 family pages** — disambiguate with the other identifiers on each page |
| `FFF1` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `00000000-00b7-4807-beee-e0b0879cf3dd` | [Lippert TireLinc TPMS repeater](lippert-tire-linc.md) |  |
| `00035b03-58e6-07dd-021a-08123a000300` | [Switch-Pros SWCHPRO SP9100](switch-pros-sp9100.md) |  |
| `021a9004-0382-4aea-bff4-6b3f1c5adfb4` | [Fellow EKG-* kettle](fellow-ekg-kettle.md) |  |
| `06aa1910-f22a-11e3-9daa-0002a5d5c51b` | [Nespresso Vertuo_DV* / Vertuo_CV*](nespresso-vertuo.md) |  |
| `1581ee61-0815-4b7c-b117-bed8758fee7c` | [Square Reader Square Reader <four digits>](square-reader.md) |  |
| `1b19b844-038f-11e5-8418-1697f925ec7b` | [Xirgo/Harp HarpBT* ELD telematics](xirgo-harpbt-eld.md) |  |
| `26d91a37-c279-4d0f-96a1-532ce41ce0f6` | [Chamberlain MyQ-* / myQ-F* smart garage](chamberlain-myq.md) |  |
| `5052494d-2dab-0341-6972-6f6861424c45` | [Beats LE-Beats* headphones, earbuds and speaker](beats-audio.md) |  |
| `50c928bd-4cb8-4c84-b745-0e58a0280cd6` | [BlazePod BlazePod pods](blazepod.md) |  |
| `6a4e3e10-667b-11e3-949a-0800200c9a66` | [Garmin fenix* multisport watches](garmin-fenix-watches.md) |  |
| `6e400054-b5a3-f393-e0a9-e50e24dcca9e` | [MIRA-* name with Nordic-like UUID](mira-name-unknown-uuid.md) |  |
| `91617f9c-29cc-4951-8a9a-c16f73706363` | [HP Poly Poly* Bluetooth headsets and adapter](hp-poly-bluetooth-audio.md) |  |
| `923b0d97-2131-4d77-9ce9-56ff8998b4e9` | [Furbo Furbo* pet cameras](furbo.md) |  |
| `945ca2b0-852c-4ab8-b654-354df41c2795` | [Yamaha YamahaAV AV / MusicCast](yamaha-musiccast-av.md) |  |
| `9760face-a234-4686-9e00-fcbbee000002` | [Ring Ring* security ecosystem](ring-security.md) |  |
| `98ed0001-a541-11e4-b6a0-0002a5d5c51b` | [Oura Oura* ring](oura-ring.md) |  |
| `a59611ba-78b7-4fd2-96fb-9b0f66d2311e` | [Xirgo/Harp HarpBT* ELD telematics](xirgo-harpbt-eld.md) |  |
| `abbaff00-e56a-484c-b832-8b17cf6cbfe8` | [Fitbit Charge* health bands](fitbit-charge.md) |  |
| `cd54c00a-880b-425b-b167-81ed6a15e913` | [Victron Energy](victron-energy-ble.md) |  |
| `cd54c012-880b-425b-b167-81ed6a15e913` | [Victron Energy](victron-energy-ble.md) |  |
| `cd55a060-880b-425b-b167-81ed6a15e913` | [Victron Energy](victron-energy-ble.md) |  |
| `d2d3f8ef-9c99-4d9c-a2b3-91c85d44326c` | [Google Nest Cam](google-nest-cam.md) |  |
| `da2b84f1-6279-48de-bdc0-afbea0226079` | [PayRange PayRange BluKey](payrange-blukey.md) |  |
| `e0fc1000-1fb1-4168-96df-b3f057a86e01` | [Big Ass Fans Haiku*](big-ass-fans-haiku.md) |  |
| `ea1979cf-5313-4152-b056-37619c1da100` | [VIZIO VIZIO Mobile App name](vizio-mobile.md) |  |
| `fc5421a1-236c-4c94-8fa9-944a3e5353fa` | [Ember Ember Ceramic Mug* / Ember Travel Mug*](ember-mugs.md) |  |

## Lookup by local-name prefix or pattern

| Local name | Family page(s) | Note |
|---|---|---|
| (no local name) | [HP-number-associated FE78 / company field 101 broadcasts](hp-fe78-company-101-printers.md)<br>[Unnamed Bluetooth Mesh Proxy](mesh-proxy-1828-unnamed.md)<br>[Unnamed broadcast population](unnamed-broadcast-opaque-clusters.md) | unnamed advertisements; **ambiguous: shared by 3 family pages** — disambiguate with the other identifiers on each page |
| `750XLTE*` | [BlackVue BlackVue* dashcam series names](blackvue-dashcams.md) |  |
| `750XPLUS*` | [BlackVue BlackVue* dashcam series names](blackvue-dashcams.md) |  |
| `770X*` | [BlackVue BlackVue* dashcam series names](blackvue-dashcams.md) |  |
| `900X*` | [BlackVue BlackVue* dashcam series names](blackvue-dashcams.md) |  |
| `900XPLUS*` | [BlackVue BlackVue* dashcam series names](blackvue-dashcams.md) |  |
| `970X*` | [BlackVue BlackVue* dashcam series names](blackvue-dashcams.md) |  |
| `970XLTE*` | [BlackVue BlackVue* dashcam series names](blackvue-dashcams.md) |  |
| `AABL*` | [Motive fleet AABL* / Motive_AABL*](motive-aabl.md) |  |
| `Acton*` | [Marshall Acton and Stanmore speakers](marshall-home-speakers.md) |  |
| `AFYDKPN*` | [Anker Anker* SOLIX / Prime and AFYDKPN*](anker-ff09-power-products.md) |  |
| `Amazfit*` | [Amazfit Amazfit* wearables](amazfit-fee0.md) |  |
| `Anker*` | [Anker Anker* SOLIX / Prime and AFYDKPN*](anker-ff09-power-products.md) |  |
| `Aranet4` | [Aranet4 air-quality monitor](aranet4.md) |  |
| `ARROWQ` | [Procon ARROWQ vehicle telematics](procon-arrowq.md) |  |
| `AZPDKC*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `Baxter*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `Between*` | [Status Audio Between* 3ANC earbuds](status-audio-between-3anc.md) |  |
| `bhyve_*` | [Orbit bhyve_* irrigation](orbit-bhyve.md) |  |
| `BlazePod` | [BlazePod BlazePod pods](blazepod.md) |  |
| `Bose*` | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| `BT-TH-*` | [Renogy-compatible BT-TH-*](renogy-bt-th.md) |  |
| `BTSMP*` | [Current/Hubbell BTSMP* luminaire sensor modules](current-hubbell-btsmp.md) |  |
| `BTSQ*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `CAR-BT-*` | [CAR-BT-* wireless CarPlay/Android Auto units](car-bt-carplay-units.md) |  |
| `CAREL*` | [CAREL HVAC/R candidate](carel-hvac.md) |  |
| `CBU-PWM4*` | [Casambi lighting](casambi-wireless-lighting.md) |  |
| `Cerbo*` | [Victron Energy](victron-energy-ble.md) |  |
| `CFQU*` | [Phillips Connect trailer telematics](phillips-connect-trailer-ads.md) |  |
| `Charge*` | [Fitbit Charge* health bands](fitbit-charge.md) |  |
| `Chromecast*` | [Google Cast / Home devices on FEA0](google-cast-home-fea0.md) |  |
| `ClearSky*` | [JLG ClearSky* Smart Fleet](jlg-clearsky.md) |  |
| `Cleer*` | [Cleer Cleer* headphones and open-ear earbuds](cleer-audio.md) |  |
| `CodeReader*` | [CodeReader* BLE barcode/QR scanner class](codereader-barcode.md) |  |
| `coolledux` | [coolledux programmable LED display](coolledux.md) |  |
| `coolledx` | [coolledx programmable LED display](coolledx.md) |  |
| `Core200S*` | [Levoit LAP-* / Core*S* air purifiers](levoit-company-1744.md) |  |
| `Core300S*` | [Levoit LAP-* / Core*S* air purifiers](levoit-company-1744.md) |  |
| `Core400S*` | [Levoit LAP-* / Core*S* air purifiers](levoit-company-1744.md)<br>[Levoit Core 400S smart air purifier](levoit-core-400s.md) | **ambiguous: shared by 2 family pages** — disambiguate with the other identifiers on each page |
| `COROS*` | [COROS COROS* GPS sports watches](coros-watches.md) |  |
| `DEI-*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `DFU-V*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `Dreo*  (DR-HAF003S, DR-HTF001S, DR-HTF002S, DR-HTF007S)` | [Dreo DR-H… model-code names](dreo-dr-code-names.md) |  |
| `DreoAC*` | [Dreo appliances on 5348](dreo-5348-appliances.md) |  |
| `DreoCD05W*` | [Dreo appliances on 5348](dreo-5348-appliances.md) |  |
| `DreoCD06W*` | [Dreo appliances on 5348](dreo-5348-appliances.md) |  |
| `DreoCD08W*` | [Dreo appliances on 5348](dreo-5348-appliances.md) |  |
| `DreoHM*` | [Dreo appliances on 5348](dreo-5348-appliances.md) |  |
| `DreoPF*` | [Dreo appliances on 5348](dreo-5348-appliances.md) |  |
| `DreoSH*` | [Dreo appliances on 5348](dreo-5348-appliances.md) |  |
| `DreoTF*` | [Dreo appliances on 5348](dreo-5348-appliances.md) |  |
| `dryer` | [Samsung SmartThings appliances](samsung-smartthings-appliances.md) |  |
| `DT-TAG*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `Echo Buds*` | [Amazon Echo* Dot / Pop / Buds](amazon-echo-name-tokens.md) |  |
| `Echo Dot*` | [Amazon Echo* Dot / Pop / Buds](amazon-echo-name-tokens.md) |  |
| `Echo Pop*` | [Amazon Echo* Dot / Pop / Buds](amazon-echo-name-tokens.md) |  |
| `Echo*` | [Amazon Echo* Dot / Pop / Buds](amazon-echo-name-tokens.md) |  |
| `EDIFIER BLE` | [Edifier EDIFIER BLE](edifier-ble.md) |  |
| `EKG-*` | [Fellow EKG-* kettle](fellow-ekg-kettle.md) |  |
| `ELK-BLEDDM*` | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |
| `ELK-BLEDOM*` | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |
| `Ember Ceramic Mug*` | [Ember Ember Ceramic Mug* / Ember Travel Mug*](ember-mugs.md) |  |
| `Ember Travel Mug*` | [Ember Ember Ceramic Mug* / Ember Travel Mug*](ember-mugs.md) |  |
| `Endurance Peak 3` | [JBL Endurance Peak 3 / Race 2 / Peak II earbuds](jbl-endurance-earbuds.md) |  |
| `Endurance Peak II` | [JBL Endurance Peak 3 / Race 2 / Peak II earbuds](jbl-endurance-earbuds.md) |  |
| `Endurance Race 2` | [JBL Endurance Peak 3 / Race 2 / Peak II earbuds](jbl-endurance-earbuds.md) |  |
| `Envoy*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `Envy*` | [HP-number-associated FE78 / company field 101 broadcasts](hp-fe78-company-101-printers.md) |  |
| `EPOS ADAPT 660` | [EPOS IMPACT / ADAPT office headsets](epos-headsets.md) |  |
| `EPOS IMPACT 1060 ANC` | [EPOS IMPACT / ADAPT office headsets](epos-headsets.md) |  |
| `EPOS IMPACT 1060T ANC` | [EPOS IMPACT / ADAPT office headsets](epos-headsets.md) |  |
| `ET-2800` | [Epson ET-* / WF-* printers](epson-ecotank-printers.md) |  |
| `ET-2850 Series` | [Epson ET-* / WF-* printers](epson-ecotank-printers.md) |  |
| `ET-2980` | [Epson ET-* / WF-* printers](epson-ecotank-printers.md) |  |
| `ET-3830` | [Epson ET-* / WF-* printers](epson-ecotank-printers.md) |  |
| `ET-3850` | [Epson ET-* / WF-* printers](epson-ecotank-printers.md) |  |
| `ET-3950` | [Epson ET-* / WF-* printers](epson-ecotank-printers.md) |  |
| `ET-4800` | [Epson ET-* / WF-* printers](epson-ecotank-printers.md) |  |
| `ET-4850` | [Epson ET-* / WF-* printers](epson-ecotank-printers.md) |  |
| `ET-8550 Series` | [Epson ET-* / WF-* printers](epson-ecotank-printers.md) |  |
| `fenix*` | [Garmin fenix* multisport watches](garmin-fenix-watches.md) |  |
| `FI-FC*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `FJLB*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `Freecom*` | [Cardo Freecom* helmet intercoms](cardo-freecom.md) |  |
| `FRONT*` | [Garmin-assigned FRONT* signal](garmin-front-fe1f.md) |  |
| `FSCE*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `FSLB*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `Furbo*` | [Furbo Furbo* pet cameras](furbo.md) |  |
| `Galaxy Ring` | [Samsung Galaxy Watch and Galaxy Ring](samsung-galaxy-wearables.md) |  |
| `Galaxy Watch*` | [Samsung Galaxy Watch and Galaxy Ring](samsung-galaxy-wearables.md) |  |
| `gate*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `GBK_H*` | [Govee H-code name tokens](govee-h-model-code-names.md) |  |
| `glow` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `GM_PEPS_VKM` | [GM GM_PEPS_VKM](gm-peps-fe48.md) |  |
| `GoPro*` | [GoPro BLE protocol](gopro-fea6.md) |  |
| `Govee_H*` | [Govee H-code name tokens](govee-h-model-code-names.md) |  |
| `GR-AC_*` | [GREE / TOSOT GR-AC_* appliances](gree-tosot-gr-ac.md) |  |
| `Haiku*` | [Big Ass Fans Haiku*](big-ass-fans-haiku.md) |  |
| `HarpBT*` | [Xirgo/Harp HarpBT* ELD telematics](xirgo-harpbt-eld.md) |  |
| `HN_ACCF*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `HOOPO*` | [Hoopo HOOPO* hoopoSense asset tracker](hoopo-hoopo-sense.md) |  |
| `HUAWEI*` | [HUAWEI HUAWEI* watches and bands](huawei-wearables.md) |  |
| `hume band*` | [Hume hume band* wearable](hume-band.md) |  |
| `Hypervolt 2` | [Hyperice Hypervolt 2 / Hypervolt 2 Pro](hyperice-hypervolt.md) |  |
| `Hypervolt 2 Pro` | [Hyperice Hypervolt 2 / Hypervolt 2 Pro](hyperice-hypervolt.md) |  |
| `ihoment_H*` | [Govee H-code name tokens](govee-h-model-code-names.md) |  |
| `ILCE-*` | [Sony Alpha ILCE-* camera names](sony-alpha-ilce-cameras.md) |  |
| `ios-vlink` | [OBD-II BLE diagnostic adapters](obd-ii-ble-adapters.md) |  |
| `IS-IHMT` | [Cooper WaveLinx IS-IHMT sensor](cooper-wavelinx-is-ihmt.md) |  |
| `Jabra*` | [Jabra Jabra* earbuds, headsets and speakerphone](jabra-name-tokens.md) |  |
| `JBL Xtreme 3` | [JBL JBL Xtreme 3 speaker](jbl-xtreme-3.md) |  |
| `kitchen*` | [Google Cast / Home devices on FEA0](google-cast-home-fea0.md) |  |
| `Kuna` | [Kuna Kuna / Kuna*](kuna-fef1.md) |  |
| `Kuna*` | [Kuna Kuna / Kuna*](kuna-fef1.md) |  |
| `LAP-C601S*` | [Levoit LAP-* / Core*S* air purifiers](levoit-company-1744.md) |  |
| `LAP-V102S*` | [Levoit LAP-* / Core*S* air purifiers](levoit-company-1744.md) |  |
| `LAP-V201S*` | [Levoit LAP-* / Core*S* air purifiers](levoit-company-1744.md) |  |
| `LCIRemote*` | [Lippert OneControl LCIRemote*](lippert-onecontrol-lciremote.md) |  |
| `LDL-*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `LE-Beats*` | [Beats LE-Beats* headphones, earbuds and speaker](beats-audio.md) |  |
| `LE-Black*` | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| `LE-Blue*` | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| `LE-Bose*` | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| `LE-Flexo-Ersto` | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| `LE-Maestro` | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| `LE-OpenRun*` | [Shokz LE-OpenRun* / OpenSwim* bone-conduction headsets](shokz-bone-conduction.md) |  |
| `LE-P-Bose*` | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| `LE-Reserved_C` | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| `LE-RIPS*` | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| `LE-ZEN*` | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| `LE_SRS-XB*` | [Sony LE_ audio name tokens](sony-le-prefixed-audio-names.md) |  |
| `LE_WF-*` | [Sony LE_ audio name tokens](sony-le-prefixed-audio-names.md) |  |
| `LE_WH-*` | [Sony LE_ audio name tokens](sony-le-prefixed-audio-names.md) |  |
| `LE_WI-C100` | [Sony LE_ audio name tokens](sony-le-prefixed-audio-names.md) |  |
| `LEDDMX-00-*` | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |
| `LEDDMX-03-*` | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |
| `lednetwf*` | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |
| `level: <reading withheld>` | [Otodata level](otodata-level.md) |  |
| `lime-*` | [Lime lime-* shared micromobility](lime-micromobility.md) |  |
| `living*` | [living* shared room-name prefix](living-room-name-prefix.md) |  |
| `M720 Triathlon` | [Logitech M720 Triathlon / MX Master 2S HID mice](logitech-hid-mice.md) |  |
| `memo-dem*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `MIRA-*` | [MIRA-* name with Nordic-like UUID](mira-name-unknown-uuid.md) |  |
| `Motive_AABL*` | [Motive fleet AABL* / Motive_AABL*](motive-aabl.md) |  |
| `MX Master 2S` | [Logitech M720 Triathlon / MX Master 2S HID mice](logitech-hid-mice.md) |  |
| `MyQ-*` | [Chamberlain MyQ-* / myQ-F* smart garage](chamberlain-myq.md) |  |
| `myQ-F*` | [Chamberlain MyQ-* / myQ-F* smart garage](chamberlain-myq.md) |  |
| `Nanoleaf*A19*` | [Nanoleaf Nanoleaf*A19* Essentials bulbs](nanoleaf-essentials.md) |  |
| `nECYD*` | [Acuity lighting control](acuity-nlight-lighting-control.md) |  |
| `Nest Audio*` | [Google Cast / Home devices on FEA0](google-cast-home-fea0.md) |  |
| `Nest Cam` | [Google Nest Cam](google-nest-cam.md) |  |
| `Nest Mini*` | [Google Cast / Home devices on FEA0](google-cast-home-fea0.md) |  |
| `Nutale_FMN` | [Nutale Nutale_FMN Find My item tags](nutale-find-my-tags.md) |  |
| `OBDII` | [OBD-II BLE diagnostic adapters](obd-ii-ble-adapters.md) |  |
| `office*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `OMNI` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `onasset*` | [OnAsset onasset*](onasset.md) |  |
| `OontZ*` | [OontZ OontZ* Angle 3 speakers](oontz-speakers.md) |  |
| `OpenSwim*` | [Shokz LE-OpenRun* / OpenSwim* bone-conduction headsets](shokz-bone-conduction.md) |  |
| `Oral-B Toothbrush` | [Oral-B Oral-B Toothbrush](oral-b.md) |  |
| `Orange` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `Orion XS*` | [Victron Energy](victron-energy-ble.md) |  |
| `OsmoAction*` | [DJI Osmo Action OsmoAction* cameras](dji-osmo-action-cameras.md) |  |
| `Oura*` | [Oura Oura* ring](oura-ring.md) |  |
| `Pathfinder*` | [ION Audio Pathfinder* speakers](ion-pathfinder-speakers.md) |  |
| `PayRange` | [PayRange PayRange BluKey](payrange-blukey.md) |  |
| `PCAM_*` | [Phillips Connect trailer telematics](phillips-connect-trailer-ads.md) |  |
| `PCAM_F*` | [Phillips Connect trailer telematics](phillips-connect-trailer-ads.md) |  |
| `pctgw_*` | [Phillips Connect trailer telematics](phillips-connect-trailer-ads.md) |  |
| `Pedal Commander` | [Pedal Commander Pedal Commander and pedal*](pedal-commander.md) |  |
| `pedal*` | [Pedal Commander Pedal Commander and pedal*](pedal-commander.md) |  |
| `Philips*` | [Philips TAB5309 soundbar](philips-tab5309-soundbar.md) |  |
| `PitPat-T*` | [PitPat PitPat-T*](pitpat-t.md) |  |
| `Platco PR1` | [Platco PR1 BLE HID remote](platco-pr1.md) |  |
| `Plaud Note*` | [Plaud Plaud Note* / Plaud NotePin* AI recorders](plaud-recorders.md) |  |
| `Plaud NotePin*` | [Plaud Plaud Note* / Plaud NotePin* AI recorders](plaud-recorders.md) |  |
| `PMX-1` | [Rockford Fosgate PMX-1 marine receiver](rockford-fosgate-pmx1.md) |  |
| `POD-*` | [Eight Sleep POD-*](eight-sleep-pod.md) |  |
| `Pokemon GO Plus` | [Pokémon GO Plus / Plus+ Pokemon* accessories](pokemon-go-plus.md) |  |
| `Pokemon GO Plus +` | [Pokémon GO Plus / Plus+ Pokemon* accessories](pokemon-go-plus.md) |  |
| `Polestar 3` | [Polestar Polestar* vehicle names](polestar-vehicle-names.md) |  |
| `Polestar*` | [Polestar Polestar* vehicle names](polestar-vehicle-names.md) |  |
| `Polestar2` | [Polestar Polestar* vehicle names](polestar-vehicle-names.md) |  |
| `Poly*` | [HP Poly Poly* Bluetooth headsets and adapter](hp-poly-bluetooth-audio.md) |  |
| `POWERDRIVER-L*` | [RoadPro POWERDRIVER-L* inverters](powerdrive-inverters.md) |  |
| `PROV_*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `PSC-WCM-450*` | [Casambi lighting](casambi-wireless-lighting.md) |  |
| `QHM-*` | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |
| `QRRM*` | [QRRM* RMControl adjustable-bed controllers](qrrm-rmcontrol-beds.md) |  |
| `quec_ble_*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `Quest*` | [Meta Quest* headsets](meta-quest-feb8.md) |  |
| `raven*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `Razer*` | [Razer Razer* peripherals](razer.md) |  |
| `refrigerato` | [Samsung SmartThings appliances](samsung-smartthings-appliances.md) |  |
| `ResMed*` | [ResMed ResMed* first-party name cohort](resmed-company-909.md)<br>[ResMed AirMini candidate](resmed-airmini.md) | **ambiguous: shared by 2 family pages** — disambiguate with the other identifiers on each page |
| `Ring*` | [Ring Ring* security ecosystem](ring-security.md) |  |
| `Rivian*` | [Rivian Rivian* vehicle BLE](rivian.md) |  |
| `rnet` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `rPP20*` | [Acuity lighting control](acuity-nlight-lighting-control.md) |  |
| `RWLS-*` | [Right Weigh RWLS-* load scales](right-weigh-rwls.md) |  |
| `sande` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `Schlage*` | [Schlage Schlage* locks](schlage-locks.md) |  |
| `SensorSwitch*` | [Acuity lighting control](acuity-nlight-lighting-control.md) |  |
| `SmartSolar*` | [Victron Energy](victron-energy-ble.md) |  |
| `Square Reader <four digits>` | [Square Reader Square Reader <four digits>](square-reader.md) |  |
| `SRAM*` | [SRAM SRAM* AXS](sram-axs-fe51.md) |  |
| `Stanmore*` | [Marshall Acton and Stanmore speakers](marshall-home-speakers.md) |  |
| `STRM*` | [Stripe Reader M2 / BBPOS STRM*](stripe-reader-m2-strm.md) |  |
| `SWCHPRO` | [Switch-Pros SWCHPRO SP9100](switch-pros-sp9100.md) |  |
| `TAB5309` | [Philips TAB5309 soundbar](philips-tab5309-soundbar.md) |  |
| `TCL_B*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `Tile` | [Tile Tile item finder](tile-feed.md) |  |
| `TireLinc` | [Lippert TireLinc TPMS repeater](lippert-tire-linc.md) |  |
| `TNNOW` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `Toyota*` | [Toyota-associated Toyota* multimedia](toyota-denso-ten.md) |  |
| `TOZO-A*` | [TOZO TOZO-T* / TOZO-A* earbuds](tozo-earbuds.md) |  |
| `TOZO-T*` | [TOZO TOZO-T* / TOZO-A* earbuds](tozo-earbuds.md) |  |
| `trimble*` | [Trimble trimble*](trimble-company-1021.md) |  |
| `Triones*` | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |
| `tsTPMS` | [tsTPMS aftermarket Tesla-compatible TPMS](tesla-aftermarket-tpms.md) |  |
| `Tsunghanrog` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `VEEPEAK` | [OBD-II BLE diagnostic adapters](obd-ii-ble-adapters.md) |  |
| `Vertuo_CV*` | [Nespresso Vertuo_DV* / Vertuo_CV*](nespresso-vertuo.md) |  |
| `Vertuo_DV*` | [Nespresso Vertuo_DV* / Vertuo_CV*](nespresso-vertuo.md) |  |
| `VIZIO Mobile App` | [VIZIO VIZIO Mobile App name](vizio-mobile.md) |  |
| `washer` | [Samsung SmartThings appliances](samsung-smartthings-appliances.md) |  |
| `WF-2950 Series` | [Epson ET-* / WF-* printers](epson-ecotank-printers.md) |  |
| `WF-C510` | [Sony LE_ audio name tokens](sony-le-prefixed-audio-names.md) |  |
| `WH-CH720N` | [Sony LE_ audio name tokens](sony-le-prefixed-audio-names.md) |  |
| `WHOOP*` | [WHOOP WHOOP* wearables](whoop.md) |  |
| `wirelessair-*` | [Air Lift wirelessone-* / wirelessair-* air suspension](air-lift-wireless-suspension.md) |  |
| `wirelessone-*` | [Air Lift wirelessone-* / wirelessair-* air suspension](air-lift-wireless-suspension.md) |  |
| `Wyze_Hub` | [Wyze Wyze_Hub](wyze-hub.md) |  |
| `XH_CL_B*` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `YamahaAV` | [Yamaha YamahaAV AV / MusicCast](yamaha-musiccast-av.md) |  |
| `YCCU_*` | [Yamaha YCCU_* motorcycle connectivity unit](yamaha-yccu-motorcycle.md) |  |
| `YX_LED fiber light` | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |

## Lookup by ordered AD-structure shape

Shapes are generic and often shared; use them only together with another identifier.

| AD shape | Family page(s) | Note |
|---|---|---|
| `01,03,09,FF` | [Shokz LE-OpenRun* / OpenSwim* bone-conduction headsets](shokz-bone-conduction.md) |  |
| `01,03,16,09,FF` | [Garmin-assigned FRONT* signal](garmin-front-fe1f.md) |  |
| `01,03,FF,16,09` | [Garmin-assigned FRONT* signal](garmin-front-fe1f.md) |  |
| `01,07,FF,09` | [Cardo Freecom* helmet intercoms](cardo-freecom.md) |  |
| `01,09,03,FF,16` | [Meta Quest* headsets](meta-quest-feb8.md) |  |
| `01,09,FF,09` | [Govee H-code name tokens](govee-h-model-code-names.md) |  |
| `01,16,03,09` | [JBL Endurance Peak 3 / Race 2 / Peak II earbuds](jbl-endurance-earbuds.md) |  |
| `01,16,03,FF` | [HP-number-associated FE78 / company field 101 broadcasts](hp-fe78-company-101-printers.md) |  |
| `01,16,0A,09` | [JBL Endurance Peak 3 / Race 2 / Peak II earbuds](jbl-endurance-earbuds.md) |  |
| `01,FF` | [Unnamed broadcast population](unnamed-broadcast-opaque-clusters.md) |  |
| `01,FF,02,09` | [tsTPMS aftermarket Tesla-compatible TPMS](tesla-aftermarket-tpms.md) |  |
| `01,FF,03,09` | [Anker Anker* SOLIX / Prime and AFYDKPN*](anker-ff09-power-products.md) |  |
| `01,FF,03,09,0A` | [Anker Anker* SOLIX / Prime and AFYDKPN*](anker-ff09-power-products.md) |  |
| `01,FF,07,09` | [Beats LE-Beats* headphones, earbuds and speaker](beats-audio.md) |  |
| `01,FF,16,09` | [JBL JBL Xtreme 3 speaker](jbl-xtreme-3.md) |  |
| `01,FF,20,09` | [Pokémon GO Plus / Plus+ Pokemon* accessories](pokemon-go-plus.md) |  |
| `01/1,02/2,FF/11,09/14` | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| `01/1,02/2,FF/13,12/4,0A/1,12/4,09/8` | [coolledx programmable LED display](coolledx.md) |  |
| `01/1,02/2,FF/14,09/10,16/10` | [GoPro BLE protocol](gopro-fea6.md) |  |
| `01/1,02/2,FF/6,0A/1,09/18` | [Renogy-compatible BT-TH-*](renogy-bt-th.md) |  |
| `01/1,02/4,FF/8,09/18` | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |
| `01/1,02/4,FF/8,09/20,0A/1` | [Rockford Fosgate PMX-1 marine receiver](rockford-fosgate-pmx1.md) |  |
| `01/1,02/8,09/10,FF/13,0A/1,12/4,19/2` | [Platco PR1 BLE HID remote](platco-pr1.md) |  |
| `01/1,03/2,09/*` | [WHOOP WHOOP* wearables](whoop.md) |  |
| `01/1,03/2,09/*,07/16` | [WHOOP WHOOP* wearables](whoop.md) |  |
| `01/1,03/2,09/12,FF/4` | [Orbit bhyve_* irrigation](orbit-bhyve.md) |  |
| `01/1,03/2,09/7` | [OBD-II BLE diagnostic adapters](obd-ii-ble-adapters.md) |  |
| `01/1,03/2,16/11` | [Unnamed Bluetooth Mesh Proxy](mesh-proxy-1828-unnamed.md) |  |
| `01/1,03/2,16/11,FF/29` | [Unnamed Bluetooth Mesh Proxy](mesh-proxy-1828-unnamed.md) |  |
| `01/1,03/2,16/12,09/4` | [Tile Tile item finder](tile-feed.md) |  |
| `01/1,03/2,16/15,09/12` | [Google Cast / Home devices on FEA0](google-cast-home-fea0.md) |  |
| `01/1,03/2,16/15,09/13` | [Google Cast / Home devices on FEA0](google-cast-home-fea0.md) |  |
| `01/1,03/2,16/19` | [Unnamed Bluetooth Mesh Proxy](mesh-proxy-1828-unnamed.md) |  |
| `01/1,03/2,16/22,09/12` | [GM GM_PEPS_VKM](gm-peps-fe48.md) |  |
| `01/1,03/2,16/22,09/20` | [Samsung Galaxy Watch and Galaxy Ring](samsung-galaxy-wearables.md) |  |
| `01/1,03/2,FF/13,09/10,0A/1` | [Bose LE- / Bose* audio names and FEBE custom-name candidates](bose-le-audio-names.md) |  |
| `01/1,03/2,FF/13,09/9` | [coolledux programmable LED display](coolledux.md) |  |
| `01/1,03/2,FF/14,09/<name length>` | [OontZ OontZ* Angle 3 speakers](oontz-speakers.md) |  |
| `01/1,03/2,FF/17,09/17` | [Anker Anker* SOLIX / Prime and AFYDKPN*](anker-ff09-power-products.md) |  |
| `01/1,03/2,FF/2,09/11` | [Marshall Acton and Stanmore speakers](marshall-home-speakers.md) |  |
| `01/1,03/2,FF/2,09/8` | [Marshall Acton and Stanmore speakers](marshall-home-speakers.md) |  |
| `01/1,03/2,FF/3,09/<name length>` | [ION Audio Pathfinder* speakers](ion-pathfinder-speakers.md) |  |
| `01/1,03/4,07/16,09/22` | [Right Weigh RWLS-* load scales](right-weigh-rwls.md) |  |
| `01/1,06/16` | [QRRM* RMControl adjustable-bed controllers](qrrm-rmcontrol-beds.md) |  |
| `01/1,06/16,09/7,19/2,FF/4` | [Chamberlain MyQ-* / myQ-F* smart garage](chamberlain-myq.md) |  |
| `01/1,06/16,FF/4,09/24` | [Current/Hubbell BTSMP* luminaire sensor modules](current-hubbell-btsmp.md) |  |
| `01/1,06/16,FF/6,09/11 or 14` | [Oura Oura* ring](oura-ring.md) |  |
| `01/1,06/16,FF/8,0A/1,09/23` | [Nespresso Vertuo_DV* / Vertuo_CV*](nespresso-vertuo.md) |  |
| `01/1,07/16,09/7` | [Switch-Pros SWCHPRO SP9100](switch-pros-sp9100.md) |  |
| `01/1,07/16,09/8,FF/15` | [PayRange PayRange BluKey](payrange-blukey.md) |  |
| `01/1,07/16,09/8,FF/8` | [BlazePod BlazePod pods](blazepod.md) |  |
| `01/1,09/10,FF/29` | [Plaud Plaud Note* / Plaud NotePin* AI recorders](plaud-recorders.md) |  |
| `01/1,09/11,03/2,19/2,FF/5` | [Dreo appliances on 5348](dreo-5348-appliances.md) |  |
| `01/1,09/12,08/12` | [Dreo appliances on 5348](dreo-5348-appliances.md) |  |
| `01/1,09/12,FF/20` | [Acuity lighting control](acuity-nlight-lighting-control.md) |  |
| `01/1,09/13` | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |
| `01/1,09/13,06/16,FF/9` | [Aranet4 air-quality monitor](aranet4.md) |  |
| `01/1,09/13,1B/6,01/1,03/2,0A/1,FF/3` | [ResMed ResMed* first-party name cohort](resmed-company-909.md) |  |
| `01/1,09/15,01/1` | [CAR-BT-* wireless CarPlay/Android Auto units](car-bt-carplay-units.md) |  |
| `01/1,09/15,0A/1` | [Pedal Commander Pedal Commander and pedal*](pedal-commander.md) |  |
| `01/1,09/16,07/16,FF/2` | [VIZIO VIZIO Mobile App name](vizio-mobile.md) |  |
| `01/1,09/17,FF/20` | [Acuity lighting control](acuity-nlight-lighting-control.md) |  |
| `01/1,09/17,FF/21` | [Nanoleaf Nanoleaf*A19* Essentials bulbs](nanoleaf-essentials.md) |  |
| `01/1,09/17,FF/7` | [Acuity lighting control](acuity-nlight-lighting-control.md) |  |
| `01/1,09/18` | [Lippert OneControl LCIRemote*](lippert-onecontrol-lciremote.md) |  |
| `01/1,09/18,09/18,FF/4` | [Air Lift wirelessone-* / wirelessair-* air suspension](air-lift-wireless-suspension.md) |  |
| `01/1,09/19` | [BlackVue BlackVue* dashcam series names](blackvue-dashcams.md)<br>[Yamaha YCCU_* motorcycle connectivity unit](yamaha-yccu-motorcycle.md) | **ambiguous: shared by 2 family pages** — disambiguate with the other identifiers on each page |
| `01/1,09/20` | [BlackVue BlackVue* dashcam series names](blackvue-dashcams.md)<br>[Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) | **ambiguous: shared by 2 family pages** — disambiguate with the other identifiers on each page |
| `01/1,09/21,08/21` | [Dreo DR-H… model-code names](dreo-dr-code-names.md) |  |
| `01/1,09/23` | [BlackVue BlackVue* dashcam series names](blackvue-dashcams.md) |  |
| `01/1,09/26` | [BlackVue BlackVue* dashcam series names](blackvue-dashcams.md) |  |
| `01/1,09/7` | [Cooper WaveLinx IS-IHMT sensor](cooper-wavelinx-is-ihmt.md) |  |
| `01/1,09/7,03/2,0A/1` | [OBD-II BLE diagnostic adapters](obd-ii-ble-adapters.md) |  |
| `01/1,09/8` | [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md) |  |
| `01/1,09/9 or 10,FF/12` | [Furbo Furbo* pet cameras](furbo.md) |  |
| `01/1,09/9,07/16,FF/3` | [Big Ass Fans Haiku*](big-ass-fans-haiku.md) |  |
| `01/1,0A/1,02/2,09/9` | [OBD-II BLE diagnostic adapters](obd-ii-ble-adapters.md) |  |
| `01/1,0A/1,09/19` | [BlackVue BlackVue* dashcam series names](blackvue-dashcams.md) |  |
| `01/1,0A/1,FF/10,09/6` | [Procon ARROWQ vehicle telematics](procon-arrowq.md) |  |
| `01/1,0A/1,FF/9,09/17` | [RoadPro POWERDRIVER-L* inverters](powerdrive-inverters.md) |  |
| `01/1,16/17,02/2,09/11 or 15` | [Google Cast / Home devices on FEA0](google-cast-home-fea0.md) |  |
| `01/1,16/25,09/12,0A/1` | [Amazon Echo* Dot / Pop / Buds](amazon-echo-name-tokens.md) |  |
| `01/1,16/4,09/18` | [Air Lift wirelessone-* / wirelessair-* air suspension](air-lift-wireless-suspension.md) |  |
| `01/1,19/2,03/2,09/12,0A/1` | [Logitech M720 Triathlon / MX Master 2S HID mice](logitech-hid-mice.md) |  |
| `01/1,19/2,03/2,09/14,0A/1` | [Logitech M720 Triathlon / MX Master 2S HID mice](logitech-hid-mice.md) |  |
| `01/1,FF/13,02/2,09/17,12/4,0A/1` | [Oral-B Oral-B Toothbrush](oral-b.md) |  |
| `01/1,FF/13,09/10` | [Phillips Connect trailer telematics](phillips-connect-trailer-ads.md) |  |
| `01/1,FF/13,09/11` | [Phillips Connect trailer telematics](phillips-connect-trailer-ads.md) |  |
| `01/1,FF/13,09/11,07/16` | [MIRA-* name with Nordic-like UUID](mira-name-unknown-uuid.md) |  |
| `01/1,FF/13,09/8` | [Wyze Wyze_Hub](wyze-hub.md) |  |
| `01/1,FF/14,09/8` | [Levoit Core 400S smart air purifier](levoit-core-400s.md) |  |
| `01/1,FF/14,09/<name length>` | [Levoit LAP-* / Core*S* air purifiers](levoit-company-1744.md) |  |
| `01/1,FF/15,19/2,09/20` | [Samsung Galaxy Watch and Galaxy Ring](samsung-galaxy-wearables.md) |  |
| `01/1,FF/17,09/22` | [GREE / TOSOT GR-AC_* appliances](gree-tosot-gr-ac.md) |  |
| `01/1,FF/19,09/15` | [Schlage Schlage* locks](schlage-locks.md) |  |
| `01/1,FF/2,03/2,09/14` | [Motive fleet AABL* / Motive_AABL*](motive-aabl.md) |  |
| `01/1,FF/2,16/4 or 6,09/14` | [Hoopo HOOPO* hoopoSense asset tracker](hoopo-hoopo-sense.md) |  |
| `01/1,FF/20,09/19` | [Samsung Galaxy Watch and Galaxy Ring](samsung-galaxy-wearables.md) |  |
| `01/1,FF/20,09/20` | [JLG ClearSky* Smart Fleet](jlg-clearsky.md) |  |
| `01/1,FF/21,08/3,09/17` | [Nanoleaf Nanoleaf*A19* Essentials bulbs](nanoleaf-essentials.md) |  |
| `01/1,FF/21,09/11–14` | [Toyota-associated Toyota* multimedia](toyota-denso-ten.md) |  |
| `01/1,FF/22,09/22` | [Victron Energy](victron-energy-ble.md) |  |
| `01/1,FF/24,09/20` | [Victron Energy](victron-energy-ble.md) |  |
| `01/1,FF/25,0A/1,03/2,16/13,09/5` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `01/1,FF/26,01/1,09/20,0A/1` | [OnAsset onasset*](onasset.md) |  |
| `01/1,FF/26,09/13 or 15` | [Plaud Plaud Note* / Plaud NotePin* AI recorders](plaud-recorders.md) |  |
| `01/1,FF/26,09/18` | [Philips TAB5309 soundbar](philips-tab5309-soundbar.md) |  |
| `01/1,FF/26,FF/10,09/11` | [Samsung SmartThings appliances](samsung-smartthings-appliances.md) |  |
| `01/1,FF/26,FF/10,09/5` | [Samsung SmartThings appliances](samsung-smartthings-appliances.md) |  |
| `01/1,FF/26,FF/10,09/6` | [Samsung SmartThings appliances](samsung-smartthings-appliances.md) |  |
| `01/1,FF/4,09/13,02/2` | [CodeReader* BLE barcode/QR scanner class](codereader-barcode.md) |  |
| `01/1,FF/4,09/16,FF/10` | [CAREL HVAC/R candidate](carel-hvac.md) |  |
| `01/1,FF/5,06/16,09/14` | [Epson ET-* / WF-* printers](epson-ecotank-printers.md) |  |
| `01/1,FF/6,09/13,FF/14` | [Unassigned name prefixes](unassigned-ble-name-prefixes.md) |  |
| `01/1,FF/6,09/18,07/16` | [Lippert OneControl LCIRemote*](lippert-onecontrol-lciremote.md) |  |
| `01/1,FF/8,09/11` | [Phillips Connect trailer telematics](phillips-connect-trailer-ads.md) |  |
| `01/1,FF/8,09/21` | [Shokz LE-OpenRun* / OpenSwim* bone-conduction headsets](shokz-bone-conduction.md) |  |
| `03,09,FF` | [Shokz LE-OpenRun* / OpenSwim* bone-conduction headsets](shokz-bone-conduction.md) |  |
| `06/16,16/8,08/19` | [living* shared room-name prefix](living-room-name-prefix.md) |  |
| `07/16,09/11` | [Eight Sleep POD-*](eight-sleep-pod.md) |  |
| `07/16,09/8` | [Yamaha YamahaAV AV / MusicCast](yamaha-musiccast-av.md) |  |
| `07/16,FF/11,01/1,08/8` | [Google Nest Cam](google-nest-cam.md) |  |
| `08/10` | [QRRM* RMControl adjustable-bed controllers](qrrm-rmcontrol-beds.md) |  |
| `09,FF` | [TOZO TOZO-T* / TOZO-A* earbuds](tozo-earbuds.md) |  |
| `09/12,FF/11` | [Status Audio Between* 3ANC earbuds](status-audio-between-3anc.md) |  |
| `09/15–16,FF/10–14` | [Trimble trimble*](trimble-company-1021.md) |  |
| `09/17` | [RoadPro POWERDRIVER-L* inverters](powerdrive-inverters.md) |  |
| `0A,16,09` | [JBL Endurance Peak 3 / Race 2 / Peak II earbuds](jbl-endurance-earbuds.md) |  |
| `16/(10,11 or 15),0A/1,09/11` | [Sony LE_ audio name tokens](sony-le-prefixed-audio-names.md) |  |
| `19,01,03,09,FF,09` | [Hyperice Hypervolt 2 / Hypervolt 2 Pro](hyperice-hypervolt.md) |  |
| `19/2,01/1,FF/3,09/17,03/2` | [Ember Ember Ceramic Mug* / Ember Travel Mug*](ember-mugs.md) |  |
| `19/2,01/1,FF/5,09/13,07/16` | [Pedal Commander Pedal Commander and pedal*](pedal-commander.md) |  |
| `FF` | [Unnamed broadcast population](unnamed-broadcast-opaque-clusters.md) |  |
| `FF,16,09` | [JBL JBL Xtreme 3 speaker](jbl-xtreme-3.md) |  |
| `FF/21,01/1,09/11` | [Sony LE_ audio name tokens](sony-le-prefixed-audio-names.md) |  |
| `FF/25,01/1,12/4,09/13` | [Casambi lighting](casambi-wireless-lighting.md) |  |
| `FF/25,01/1,12/4,09/20` | [Casambi lighting](casambi-wireless-lighting.md) |  |
| `FF/28,09/14,01/1,03/2` | [Hume hume band* wearable](hume-band.md) |  |
| `FF/29,08/10` | [Nutale Nutale_FMN Find My item tags](nutale-find-my-tags.md) |  |
| `FF/29,09/14` | [Plaud Plaud Note* / Plaud NotePin* AI recorders](plaud-recorders.md) |  |
| `FF/6,08/10` | [Nutale Nutale_FMN Find My item tags](nutale-find-my-tags.md) |  |
| `FF/9,09/8` | [TOZO TOZO-T* / TOZO-A* earbuds](tozo-earbuds.md) |  |

## Earlier hospital-region device and family pages

- [Gozio-associated hospital-wayfinding signal — hardware model unknown](gozio-wayfinding-beacon.md)
- [ResMed AirMini candidate](resmed-airmini.md)
- [Enovate EmTag asset tag](enovate-emtag.md)
- [Sonicu SoniShield Duo](sonicu-sonishield-duo.md)
- [Primex Sync clock/repeater family candidate](primex-sync-clock.md)
- [HID Seos door-reader / credential ecosystem](hid-seos.md)
- [Cisco Unified IP Phone 8945 candidate](cisco-ip-phone-8945.md)
- [Cisco IP Phone 8851 candidate](cisco-ip-phone-8851.md)
- [Honeywell Xenon HC base family](honeywell-xenon-hc-base.md)
- [HP Color LaserJet Pro MFP M479fdw candidate](hp-color-laserjet-m479fdw.md)
- [HP LaserJet Pro MFP 4101fdw candidate](hp-laserjet-4101fdw.md)
- [HP LaserJet Pro 3001dw Wi-Fi Direct name candidate — Bluetooth unverified](hp-laserjet-3001dw.md)
- [BIXOLON SLP-DX220B Bluetooth label printer candidate](bixolon-slp-dx220b.md)
- [nLight AIR rES7](nlight-res7.md)
- [nLight AIR rSDGR](nlight-rsdgr.md)
- [nLight AIR rIO ZT](nlight-rio-zt.md)
- [nLight AIR rPP8 DS](nlight-rpp8-ds.md)
- [Wattstopper FSP-321](wattstopper-fsp-321.md)
- [CAREL HVAC/R candidate](carel-hvac.md)
- [Molekule purifier candidate](molekule-purifier.md)
- [`BladderSc` name — product identity unknown](verathon-bladderscan.md)
- [Historical professional BRAVIA display and Alphabet Lighting downlight leads — low confidence](historical-facility-display-lighting-leads.md)
- [Historical NET, PowerConfBLE and LifeSpan connectivity leads — unresolved](historical-ambiguous-connectivity-leads.md)

## Broader first-party BLE family pages

Each page is one advertisement-structure / protocol family. These entries use anonymized, read-only first-party `cluetooth` observations rather than WiGLE names. Fingerprints can combine sanitized names, service UUIDs, manufacturer data, address-prefix patterns and ordered AD structures; shape is optional and rarely unique. No hospital relationship is required or implied.

- [Acuity lighting control: `SensorSwitch*`, `rPP20*`, `nECYD*`](acuity-nlight-lighting-control.md)
- [Air Lift `wirelessone-*` / `wirelessair-*` air suspension (`FA00`)](air-lift-wireless-suspension.md)
- [Amazfit `Amazfit*` wearables (`FEE0`, company field 343)](amazfit-fee0.md)
- [Amazon `Echo*` Dot / Pop / Buds (`FE00`, company field 369)](amazon-echo-name-tokens.md)
- [Anker `Anker*` SOLIX / Prime and `AFYDKPN*` (`FF09`)](anker-ff09-power-products.md)
- [Aranet4 air-quality monitor (company field 1794)](aranet4.md)
- [Beats `LE-Beats*` headphones, earbuds and speaker](beats-audio.md)
- [Big Ass Fans `Haiku*`](big-ass-fans-haiku.md)
- [BlackVue `BlackVue*` dashcam series names](blackvue-dashcams.md)
- [BlazePod `BlazePod` pods](blazepod.md)
- [Bose `LE-` / `Bose*` audio names and `FEBE` custom-name candidates](bose-le-audio-names.md)
- [`CAR-BT-*` wireless CarPlay/Android Auto units](car-bt-carplay-units.md)
- [Cardo `Freecom*` helmet intercoms](cardo-freecom.md)
- [Casambi lighting: `PSC-WCM-450*`, `CBU-PWM4*` (company field 963)](casambi-wireless-lighting.md)
- [Chamberlain `MyQ-*` / `myQ-F*` smart garage](chamberlain-myq.md)
- [Cleer `Cleer*` headphones and open-ear earbuds](cleer-audio.md)
- [`CodeReader*` BLE barcode/QR scanner class](codereader-barcode.md)
- [`coolledux` programmable LED display (`FFF0`)](coolledux.md)
- [`coolledx` programmable LED display (`FFF0`)](coolledx.md)
- [Cooper WaveLinx `IS-IHMT` sensor](cooper-wavelinx-is-ihmt.md)
- [COROS `COROS*` GPS sports watches](coros-watches.md)
- [Current/Hubbell `BTSMP*` luminaire sensor modules](current-hubbell-btsmp.md)
- [DJI Osmo Action `OsmoAction*` cameras](dji-osmo-action-cameras.md)
- [Dreo appliances on `5348` (company field 17992): `DreoTF*`, `DreoAC*`, `DreoCD*`, `DreoHM*`, `DreoPF*`, `DreoSH*`](dreo-5348-appliances.md)
- [Dreo `DR-H…` model-code names](dreo-dr-code-names.md)
- [Edifier `EDIFIER BLE`](edifier-ble.md)
- [Eight Sleep `POD-*`](eight-sleep-pod.md)
- [Ember `Ember Ceramic Mug*` / `Ember Travel Mug*` (company field 961)](ember-mugs.md)
- [EPOS IMPACT / ADAPT office headsets](epos-headsets.md)
- [Epson `ET-*` / `WF-*` printers (company field 64)](epson-ecotank-printers.md)
- [Fellow `EKG-*` kettle (low confidence)](fellow-ekg-kettle.md)
- [Fitbit `Charge*` health bands](fitbit-charge.md)
- [Furbo `Furbo*` pet cameras](furbo.md)
- [Garmin `fenix*` multisport watches](garmin-fenix-watches.md)
- [Garmin-assigned `FRONT*` signal (`FE1F`, company field 135)](garmin-front-fe1f.md)
- [Generic BLE LED-strip / colour-light controller name prefixes](generic-ble-led-controllers.md)
- [GM `GM_PEPS_VKM` (`FE48`)](gm-peps-fe48.md)
- [Google Cast / Home devices on `FEA0`: Chromecast, Nest Audio, Nest Mini, `kitchen*`](google-cast-home-fea0.md)
- [Google `Nest Cam` (128-bit UUID)](google-nest-cam.md)
- [GoPro BLE protocol (`FEA6`)](gopro-fea6.md)
- [Govee H-code name tokens: `Govee_H*`, `ihoment_H*`, `GBK_H*`](govee-h-model-code-names.md)
- [GREE / TOSOT `GR-AC_*` appliances](gree-tosot-gr-ac.md)
- [Hoopo `HOOPO*` hoopoSense asset tracker](hoopo-hoopo-sense.md)
- [HP-number-associated `FE78` / company field 101 broadcasts: ENVY printers and the unnamed HP-associated group](hp-fe78-company-101-printers.md)
- [HP Poly `Poly*` Bluetooth headsets and adapter](hp-poly-bluetooth-audio.md)
- [HUAWEI `HUAWEI*` watches and bands](huawei-wearables.md)
- [Hume `hume band*` wearable](hume-band.md)
- [Hyperice `Hypervolt 2` / `Hypervolt 2 Pro`](hyperice-hypervolt.md)
- [ION Audio `Pathfinder*` speakers](ion-pathfinder-speakers.md)
- [Jabra `Jabra*` earbuds, headsets and speakerphone](jabra-name-tokens.md)
- [JBL Endurance Peak 3 / Race 2 / Peak II earbuds](jbl-endurance-earbuds.md)
- [JBL `JBL Xtreme 3` speaker](jbl-xtreme-3.md)
- [JLG `ClearSky*` Smart Fleet](jlg-clearsky.md)
- [Kuna `Kuna` / `Kuna*` (`FEF1`)](kuna-fef1.md)
- [Levoit `LAP-*` / `Core*S*` air purifiers (company field 1744)](levoit-company-1744.md)
- [Lime `lime-*` shared micromobility](lime-micromobility.md)
- [Lippert OneControl `LCIRemote*` (company field 1479)](lippert-onecontrol-lciremote.md)
- [Lippert `TireLinc` TPMS repeater](lippert-tire-linc.md)
- [`living*` shared room-name prefix](living-room-name-prefix.md)
- [Logitech `M720 Triathlon` / `MX Master 2S` HID mice](logitech-hid-mice.md)
- [Marshall Acton and Stanmore speakers (`FE8F`, `AA00`, `FE2C`)](marshall-home-speakers.md)
- [Unnamed Bluetooth Mesh Proxy (`1828`) service signal](mesh-proxy-1828-unnamed.md)
- [Meta `Quest*` headsets (`FEB8`)](meta-quest-feb8.md)
- [`MIRA-*` name with Nordic-like UUID (manufacturer unknown)](mira-name-unknown-uuid.md)
- [Motive fleet `AABL*` / `Motive_AABL*` (`FC6D`)](motive-aabl.md)
- [Nanoleaf `Nanoleaf*A19*` Essentials bulbs](nanoleaf-essentials.md)
- [Nespresso `Vertuo_DV*` / `Vertuo_CV*`](nespresso-vertuo.md)
- [Nutale `Nutale_FMN` Find My item tags (company field 76)](nutale-find-my-tags.md)
- [OBD-II BLE diagnostic adapters: `OBDII`, `ios-vlink`, `VEEPEAK`](obd-ii-ble-adapters.md)
- [OnAsset `onasset*` (company field 1556)](onasset.md)
- [OontZ `OontZ*` Angle 3 speakers](oontz-speakers.md)
- [Oral-B `Oral-B Toothbrush` (`FE0D`)](oral-b.md)
- [Orbit `bhyve_*` irrigation (`FE32`)](orbit-bhyve.md)
- [Otodata `level: …` tank monitors (company field 945)](otodata-level.md)
- [Oura `Oura*` ring](oura-ring.md)
- [PayRange `PayRange` BluKey](payrange-blukey.md)
- [Pedal Commander `Pedal Commander` and `pedal*`](pedal-commander.md)
- [Philips `TAB5309` soundbar (company field 15619)](philips-tab5309-soundbar.md)
- [Phillips Connect trailer telematics: `PCAM_*`, `pctgw_*`, `CFQU*` (company field 2175)](phillips-connect-trailer-ads.md)
- [PitPat `PitPat-T*` (low confidence)](pitpat-t.md)
- [`Platco PR1` BLE HID remote](platco-pr1.md)
- [Plaud `Plaud Note*` / `Plaud NotePin*` AI recorders](plaud-recorders.md)
- [Pokémon GO Plus / Plus+ `Pokemon*` accessories](pokemon-go-plus.md)
- [Polestar `Polestar*` vehicle names](polestar-vehicle-names.md)
- [RoadPro `POWERDRIVER-L*` inverters](powerdrive-inverters.md)
- [Procon `ARROWQ` vehicle telematics (company field 3027)](procon-arrowq.md)
- [`QRRM*` RMControl adjustable-bed controllers](qrrm-rmcontrol-beds.md)
- [Razer `Razer*` peripherals (company field 1678)](razer.md)
- [Renogy-compatible `BT-TH-*` (`FFD0`)](renogy-bt-th.md)
- [ResMed `ResMed*` first-party name cohort (company field 909, `FD56`)](resmed-company-909.md)
- [Right Weigh `RWLS-*` load scales](right-weigh-rwls.md)
- [Ring `Ring*` security ecosystem (company field 369)](ring-security.md)
- [Rivian `Rivian*` vehicle BLE (company field 2369)](rivian.md)
- [Rockford Fosgate `PMX-1` marine receiver](rockford-fosgate-pmx1.md)
- [Samsung Galaxy Watch and Galaxy Ring (company field 117, `FD69`)](samsung-galaxy-wearables.md)
- [Samsung SmartThings appliances: `washer`, `dryer`, `refrigerato` (company field 117)](samsung-smartthings-appliances.md)
- [Schlage `Schlage*` locks (company field 315)](schlage-locks.md)
- [Shokz `LE-OpenRun*` / `OpenSwim*` bone-conduction headsets](shokz-bone-conduction.md)
- [Sony Alpha `ILCE-*` camera names](sony-alpha-ilce-cameras.md)
- [Sony `LE_` audio name tokens: SRS-XB, WH, WF and WI-C speakers, headphones and earbuds](sony-le-prefixed-audio-names.md)
- [Square Reader `Square Reader <four digits>`](square-reader.md)
- [SRAM `SRAM*` AXS (`FE51`)](sram-axs-fe51.md)
- [Status Audio `Between*` 3ANC earbuds](status-audio-between-3anc.md)
- [Stripe Reader M2 / BBPOS `STRM*` (`FFA0`, company field 683)](stripe-reader-m2-strm.md)
- [Switch-Pros `SWCHPRO` SP9100](switch-pros-sp9100.md)
- [`tsTPMS` aftermarket Tesla-compatible TPMS (company field 555)](tesla-aftermarket-tpms.md)
- [Tile `Tile` item finder (`FEED`)](tile-feed.md)
- [Toyota-associated `Toyota*` multimedia (company field 269)](toyota-denso-ten.md)
- [TOZO `TOZO-T*` / `TOZO-A*` earbuds](tozo-earbuds.md)
- [Trimble `trimble*` (company field 1021)](trimble-company-1021.md)
- [Unassigned name prefixes: no product identification](unassigned-ble-name-prefixes.md)
- [Unnamed broadcast population: opaque clusters](unnamed-broadcast-opaque-clusters.md)
- [Victron Energy: `Cerbo*`, `SmartSolar*`, `Orion XS*` (company field 737)](victron-energy-ble.md)
- [VIZIO `VIZIO Mobile App` name](vizio-mobile.md)
- [WHOOP `WHOOP*` wearables](whoop.md)
- [Wyze `Wyze_Hub`](wyze-hub.md)
- [Xirgo/Harp `HarpBT*` ELD telematics](xirgo-harpbt-eld.md)
- [Yamaha `YamahaAV` AV / MusicCast](yamaha-musiccast-av.md)
- [Yamaha `YCCU_*` motorcycle connectivity unit](yamaha-yccu-motorcycle.md)
- [Levoit Core 400S smart air purifier](levoit-core-400s.md)

## Reading the evidence

The earlier hospital-region pages cover clinical, navigation, facilities, and office-infrastructure candidates, not an inventory or ownership claim. Generic phones and printers can be retained as possible hospital-system equipment; their model identification and plausible use do **not** establish hospital deployment.

Saved responses were considered without requiring hospital-polygon membership. WiGLE observation and distinct `netid` counts are **not physical equipment counts or proof of deployment**; names, company IDs, and BT/BLE labels do not authenticate identity.

For earlier WiGLE-based pages, no raw ADV/GATT frames were available to verify identifications. APK findings are static client capabilities, not captured sessions or vulnerability proof. No new WiGLE queries or device operations were used for those pages. Raw addresses, coordinates, device serials, responses, and APK binaries remain outside Git.

Private provenance: `hospital_iot_candidates_2026-10-14.md`,
`medical_exploration_report.md`, `today_all_records_2026-10-14.md`,
`gozio_protocol_notes.md`, and `resmed_airmini_protocol_notes.md` in the
external research run `us-osm-hospitals/20261013T225104Z`. Code citations on
APK-backed pages are relative to that run, not repository links.
Newer pages cite their own dated read-only research helpers; the private saved response corpus remains outside Git.

Original manufacturer/FCC URLs are primary. Dated Wayback fallbacks are included
only where availability and replay were checked; missing fallbacks mean no
capture was verified for that cited URL, not that no archive exists. No sources
or private artifacts were uploaded to an archive.

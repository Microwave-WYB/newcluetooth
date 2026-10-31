---
qmd:
  metadata:
    title: "IoT product fingerprint research wiki"
    date: "2026-10-16"
    tags: [iot, ble]
    confidence: medium
---

# IoT product fingerprint research wiki

This wiki is a **record of identified Bluetooth advertisement families**. Each page is a structured record of one advertisement-structure / protocol family, kept in a consistent form (a "How to recognize it" block with consistently written identifiers, then the evidence) so the records can later be compiled into a queryable index. This page is only a plain list of the pages; it is not a lookup index.

Earlier entries are sanitized hospital-region leads from private research recorded since **2026-10-14**; newer entries also use first-party BLE advertisements and **do not require any hospital association**.
Dates are research-record dates, **not** inferred radio-observation dates. Confidence describes product attribution, not verified installation.

[**What is actually hospital-related? Evidence tiers and limits**](hospital-relevance-evidence-tiers.md) distinguishes original hospital-facing product functions from plausible facilities equipment, consumer devices, and unknown identities. No listed observation establishes an installed or owned device at a particular hospital.

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

Each page is one advertisement-structure / protocol family. These entries use anonymized, read-only first-party `cluetooth` observations rather than WiGLE names. Fingerprints can combine sanitized names, service UUIDs, manufacturer data, address-prefix patterns and ordered AD structures; shape is optional and rarely unique. Identifiers on a page are candidates for attribution, not authentication, and may be shared with other families. No hospital relationship is required or implied.

- [Acuity lighting control: `SensorSwitch*`, `rPP20*`, `nECYD*`](acuity-nlight-lighting-control.md) — Acuity Brands SensorSwitch / nLight AIR products with name + manufacturer-data layouts.
- [Air Lift `wirelessone-*` / `wirelessair-*` air suspension (`FA00`)](air-lift-wireless-suspension.md) — Air Lift WirelessOne and WirelessAir air-suspension control manifolds.
- [Amazfit `Amazfit*` wearables (`FEE0`, company field 343)](amazfit-fee0.md) — Amazfit bands and watches distinguished by name token.
- [Amazon `Echo*` Dot / Pop / Buds (`FE00`, company field 369)](amazon-echo-name-tokens.md) — Amazon Echo-family name tokens.
- [Anker `Anker*` SOLIX / Prime and `AFYDKPN*` (`FF09`)](anker-ff09-power-products.md) — Anker SOLIX power stations, Prime power bank and the `AFYDKPN*` smart-charging candidate.
- [Aranet4 air-quality monitor (company field 1794)](aranet4.md) — Aranet4 CO₂/temperature/humidity/pressure room monitor.
- [Beats `LE-Beats*` headphones, earbuds and speaker](beats-audio.md) — Beats Studio Pro, Solo Buds and Pill distinguished by name token.
- [Big Ass Fans `Haiku*`](big-ass-fans-haiku.md) — Big Ass Fans Haiku smart ceiling-fan family.
- [BlackVue `BlackVue*` dashcam series names](blackvue-dashcams.md) — BlackVue connected-dashcam series name tokens.
- [BlazePod `BlazePod` pods](blazepod.md) — BlazePod LED reaction-training Pod family.
- [Bose `LE-` / `Bose*` audio names and `FEBE` custom-name candidates](bose-le-audio-names.md) — Bose personal audio and speakers (named `Bose*` tokens) plus custom `LE-` names carrying the Bose-assigned `FEBE` service UUID.
- [`CAR-BT-*` wireless CarPlay/Android Auto units](car-bt-carplay-units.md) — Generic wireless CarPlay/Android Auto multimedia unit candidate.
- [Cardo `Freecom*` helmet intercoms](cardo-freecom.md) — Cardo FREECOM 4X / 2X motorcycle helmet communicators.
- [Casambi lighting: `PSC-WCM-450*`, `CBU-PWM4*` (company field 963)](casambi-wireless-lighting.md) — Casambi-based wireless lighting controller and PWM dimmer.
- [Chamberlain `MyQ-*` / `myQ-F*` smart garage](chamberlain-myq.md) — Chamberlain myQ smart-garage ecosystem.
- [Cleer `Cleer*` headphones and open-ear earbuds](cleer-audio.md) — Cleer ARC / ARC II / ARC 3 / ENDURO ANC name tokens.
- [`CodeReader*` BLE barcode/QR scanner class](codereader-barcode.md) — Generic BLE barcode/QR-scanner class candidate.
- [`coolledux` programmable LED display (`FFF0`)](coolledux.md) — CoolLEDUX programmable RGB LED pixel-message display candidate.
- [`coolledx` programmable LED display (`FFF0`)](coolledx.md) — CoolLEDX / CoolLED1248-compatible programmable LED display candidate.
- [Cooper WaveLinx `IS-IHMT` sensor](cooper-wavelinx-is-ihmt.md) — Cooper WaveLinx industrial fixture-mounted occupancy/light sensor candidate.
- [COROS `COROS*` GPS sports watches](coros-watches.md) — COROS PACE/APEX/VERTIX watches distinguished by model-name token.
- [Current/Hubbell `BTSMP*` luminaire sensor modules](current-hubbell-btsmp.md) — Current/Hubbell BTSMP Bluetooth luminaire sensor-module family candidate.
- [DJI Osmo Action `OsmoAction*` cameras](dji-osmo-action-cameras.md) — DJI Osmo Action-series cameras identified by the model-name token.
- [Dreo appliances on `5348` (company field 17992): `DreoTF*`, `DreoAC*`, `DreoCD*`, `DreoHM*`, `DreoPF*`, `DreoSH*`](dreo-5348-appliances.md) — Dreo-named connected tower fans, air conditioners, appliances and heaters sharing one broadcast structure.
- [Dreo `DR-H…` model-code names](dreo-dr-code-names.md) — Dreo pedestal/tower fans advertising full public model codes, ordered differently from the `5348` Dreo shape.
- [Edifier `EDIFIER BLE`](edifier-ble.md) — Edifier-related generic audio BLE companion candidate.
- [Eight Sleep `POD-*`](eight-sleep-pod.md) — Eight Sleep Pod smart-bed temperature-control family candidate.
- [`ELK-BLEDDM*` generic BLE LED strip/light controllers](elk-bleddm-led-controllers.md) — Generic BLE LED strip/light controller candidate named `ELK-BLEDDM*`.
- [`ELK-BLEDOM*` generic BLE RGB LED-strip controllers](elk-bledom-led-controllers.md) — Generic ELK-BLEDOM BLE RGB LED-strip controller family.
- [Ember `Ember Ceramic Mug*` / `Ember Travel Mug*` (company field 961)](ember-mugs.md) — Ember temperature-controlled cup family.
- [EPOS IMPACT / ADAPT office headsets](epos-headsets.md) — EPOS office Bluetooth headsets distinguished by the explicit model name.
- [Epson `ET-*` / `WF-*` printers (company field 64)](epson-ecotank-printers.md) — Epson EcoTank ET-series and WorkForce WF-2950 name tokens.
- [Fellow `EKG-*` kettle (low confidence)](fellow-ekg-kettle.md) — Fellow EKG connected-kettle low-confidence candidate using the reusable Espressif provisioning UUID.
- [Fitbit `Charge*` health bands](fitbit-charge.md) — Fitbit Charge 5 and Charge 6 name tokens; other `Charge*`/`Charger*` names unassigned.
- [Furbo `Furbo*` pet cameras](furbo.md) — Furbo pet-camera family.
- [Garmin `fenix*` multisport watches](garmin-fenix-watches.md) — Garmin fēnix 3 HR / 5 / 5X / 6X Pro Solar name tokens.
- [Garmin-assigned `FRONT*` signal (`FE1F`, company field 135)](garmin-front-fe1f.md) — Brand-level Garmin signal on `FRONT*` names; product class unknown.
- [GM `GM_PEPS_VKM` (`FE48`)](gm-peps-fe48.md) — GM digital-key / passive-entry BLE-module candidate.
- [Google Cast / Home devices on `FEA0`: Chromecast, Nest Audio, Nest Mini, `kitchen*`](google-cast-home-fea0.md) — Google-assigned `FEA0` service/service-data on Chromecast and Nest speaker names and a `kitchen*` room-name candidate.
- [Google `Nest Cam` (128-bit UUID)](google-nest-cam.md) — Google Nest Cam family.
- [GoPro BLE protocol (`FEA6`)](gopro-fea6.md) — GoPro action-camera family via the documented Open GoPro BLE service.
- [Govee H-code name tokens: `Govee_H*`, `ihoment_H*`, `GBK_H*`](govee-h-model-code-names.md) — Govee lighting, plug, environmental-monitoring and other products identified by the public `H` model code in the local name (three name prefixes, same code scheme).
- [GREE / TOSOT `GR-AC_*` appliances](gree-tosot-gr-ac.md) — GREE home-appliance ecosystem candidate.
- [Hoopo `HOOPO*` hoopoSense asset tracker](hoopo-hoopo-sense.md) — Hoopo hoopoSense asset-tracker family.
- [HP-number-associated `FE78` / company field 101 broadcasts: ENVY printers and the unnamed HP-associated group](hp-fe78-company-101-printers.md) — Named `Envy*` HP all-in-one printers, and a much larger unnamed group sharing the HP-assigned `FE78` / `FDF7` / company field 101 signature.
- [HP Poly `Poly*` Bluetooth headsets and adapter](hp-poly-bluetooth-audio.md) — HP Poly Voyager headsets/earbuds and BT700 adapter distinguished by model-name token.
- [HUAWEI `HUAWEI*` watches and bands](huawei-wearables.md) — Huawei WATCH GT 3/GT 4 and Band 9/10 name tokens.
- [Hume `hume band*` wearable](hume-band.md) — Hume Health Hume Band wellness wearable.
- [Hyperice `Hypervolt 2` / `Hypervolt 2 Pro`](hyperice-hypervolt.md) — Hyperice Hypervolt percussion-massage device name tokens.
- [ION Audio `Pathfinder*` speakers](ion-pathfinder-speakers.md) — ION Audio Pathfinder 4 / 280 outdoor speakers (`_BLE` suffix).
- [Jabra `Jabra*` earbuds, headsets and speakerphone](jabra-name-tokens.md) — Jabra consumer and office products distinguished by model-name token.
- [JBL Endurance Peak 3 / Race 2 / Peak II earbuds](jbl-endurance-earbuds.md) — JBL Endurance sport earbuds distinguished by name token.
- [JBL `JBL Xtreme 3` speaker](jbl-xtreme-3.md) — JBL Xtreme 3 portable speaker name token.
- [JLG `ClearSky*` Smart Fleet](jlg-clearsky.md) — JLG ClearSky equipment-networking family.
- [Kuna `Kuna` / `Kuna*` (`FEF1`)](kuna-fef1.md) — Kuna/Maximus outdoor security light-camera ecosystem.
- [`LEDDMX-*` LED LAMP-compatible colour-light controllers](leddmx-led-lamp-controllers.md) — LED LAMP-compatible colour-light controller candidate identified by the `LEDDMX-00-*` / `LEDDMX-03-*` name prefix.
- [`lednetwf*` Zengge / Magic Hue-compatible colour-light controllers](lednetwf-zengge-controllers.md) — Zengge/Magic Hue-compatible colour-light controller candidate.
- [Levoit `LAP-*` / `Core*S*` air purifiers (company field 1744)](levoit-company-1744.md) — Levoit smart air-purifier model-name leads sharing one structure.
- [Lime `lime-*` shared micromobility](lime-micromobility.md) — Lime shared bike/scooter fleet candidate.
- [Lippert OneControl `LCIRemote*` (company field 1479)](lippert-onecontrol-lciremote.md) — Lippert OneControl RV connectivity/control-module family.
- [Lippert `TireLinc` TPMS repeater](lippert-tire-linc.md) — Lippert Tire Linc repeater/gateway family.
- [`living*` shared room-name prefix](living-room-name-prefix.md) — Not one product: a room-name prefix over several unrelated ecosystems.
- [Logitech `M720 Triathlon` / `MX Master 2S` HID mice](logitech-hid-mice.md) — Logitech wireless-mouse name tokens with HID service and mouse appearance.
- [Marshall Acton and Stanmore speakers (`FE8F`, `AA00`, `FE2C`)](marshall-home-speakers.md) — Marshall Acton and Stanmore Bluetooth speakers distinguished by generation name token.
- [Unnamed Bluetooth Mesh Proxy (`1828`) and Provisioning (`1827`) service signals](mesh-proxy-1828-unnamed.md) — Unnamed advertisements carrying the SIG Mesh Proxy service UUID `1828` or Mesh Provisioning service UUID `1827`.
- [Meta `Quest*` headsets (`FEB8`)](meta-quest-feb8.md) — Meta Quest 2 / 3 / 3S name tokens.
- [`MIRA-*` name with Nordic-like UUID (manufacturer unknown)](mira-name-unknown-uuid.md) — `MIRA-*` name signal; manufacturer and product class unknown.
- [Motive fleet `AABL*` / `Motive_AABL*` (`FC6D`)](motive-aabl.md) — Motive fleet BLE endpoint candidates.
- [Nanoleaf `Nanoleaf*A19*` Essentials bulbs](nanoleaf-essentials.md) — Nanoleaf Essentials A19 smart-bulb family.
- [Nespresso `Vertuo_DV*` / `Vertuo_CV*`](nespresso-vertuo.md) — Nespresso Vertuo connected coffee-machine family names.
- [Nutale `Nutale_FMN` Find My item tags (company field 76)](nutale-find-my-tags.md) — Nutale Apple Find My item-tag family.
- [OBD-II BLE diagnostic adapters: `OBDII`, `ios-vlink`, `VEEPEAK`](obd-ii-ble-adapters.md) — Generic ELM327-style, Vgate iCar and Veepeak OBD-II BLE adapter candidates.
- [OnAsset `onasset*` (company field 1556)](onasset.md) — OnAsset Intelligence BLE asset/cargo-tracking ecosystem.
- [OontZ `OontZ*` Angle 3 speakers](oontz-speakers.md) — OontZ Angle 3 Ultra / Angle 3 Pro name tokens.
- [Oral-B `Oral-B Toothbrush` (`FE0D`)](oral-b.md) — Oral-B app-connected toothbrush family.
- [Orbit `bhyve_*` irrigation (`FE32`)](orbit-bhyve.md) — Orbit B-hyve Bluetooth irrigation-control family.
- [Otodata `level: …` tank monitors (company field 945)](otodata-level.md) — Otodata tank-level monitoring family candidate.
- [Oura `Oura*` ring](oura-ring.md) — Oura smart ring family.
- [PayRange `PayRange` BluKey](payrange-blukey.md) — PayRange machine-side payment hardware.
- [Pedal Commander `Pedal Commander` and `pedal*`](pedal-commander.md) — Pedal Commander throttle-response controller candidate; other `pedal*` names unassigned.
- [Philips `TAB5309` soundbar (company field 15619)](philips-tab5309-soundbar.md) — Philips TAB5309 soundbar name token; other `Philips*` names category unknown.
- [Phillips Connect trailer telematics: `PCAM_*`, `pctgw_*`, `CFQU*` (company field 2175)](phillips-connect-trailer-ads.md) — Phillips Connect trailer cameras, gateways and container telemetry candidates sharing company field 2175.
- [PitPat `PitPat-T*` (low confidence)](pitpat-t.md) — PitPat Fitness treadmill/fitness-device low-confidence candidate.
- [`Platco PR1` BLE HID remote](platco-pr1.md) — Generic BLE HID remote candidate.
- [Plaud `Plaud Note*` / `Plaud NotePin*` AI recorders](plaud-recorders.md) — Plaud Note card recorder and NotePin wearable.
- [Pokémon GO Plus / Plus+ `Pokemon*` accessories](pokemon-go-plus.md) — Pokémon GO Plus+ and original GO Plus (or compatible) accessories.
- [Polestar `Polestar*` vehicle names](polestar-vehicle-names.md) — Polestar electric-vehicle name tokens.
- [RoadPro `POWERDRIVER-L*` inverters](powerdrive-inverters.md) — PowerDrive Bluetooth vehicle-inverter family candidate.
- [Procon `ARROWQ` vehicle telematics (company field 3027)](procon-arrowq.md) — Procon-associated vehicle-telematics candidate.
- [`QHM-*` multi-brand BLE LED lighting controllers](qhm-led-controllers.md) — Multi-brand BLE LED lighting/control candidate named `QHM-*`.
- [`QRRM*` RMControl adjustable-bed controllers](qrrm-rmcontrol-beds.md) — RMControl-compatible adjustable-bed controller family candidate.
- [Razer `Razer*` peripherals (company field 1678)](razer.md) — Razer Bluetooth-peripheral brand-level candidate.
- [Renogy-compatible `BT-TH-*` (`FFD0`)](renogy-bt-th.md) — Renogy-compatible BLE solar-energy monitoring candidate.
- [ResMed `ResMed*` first-party name cohort (company field 909, `FD56`)](resmed-company-909.md) — ResMed-assigned company field and member UUID on `ResMed*` names.
- [Right Weigh `RWLS-*` load scales](right-weigh-rwls.md) — Right Weigh Bluetooth on-vehicle load/axle-weight scale candidate.
- [Ring `Ring*` security ecosystem (company field 369)](ring-security.md) — Ring connected-security ecosystem.
- [Rivian `Rivian*` vehicle BLE (company field 2369)](rivian.md) — Rivian vehicle-related BLE family.
- [Rockford Fosgate `PMX-1` marine receiver](rockford-fosgate-pmx1.md) — Rockford Fosgate PMX-1 marine/outdoor media receiver name token.
- [Samsung Galaxy Watch and Galaxy Ring (company field 117, `FD69`)](samsung-galaxy-wearables.md) — Samsung Galaxy Watch4–8 and Galaxy Ring name tokens.
- [Samsung SmartThings appliances: `washer`, `dryer`, `refrigerato` (company field 117)](samsung-smartthings-appliances.md) — Samsung SmartThings-connected washer, dryer and refrigerator family leads sharing one broadcast structure.
- [Schlage `Schlage*` locks (company field 315)](schlage-locks.md) — Schlage mobile-enabled smart-lock/access family.
- [Shokz `LE-OpenRun*` / `OpenSwim*` bone-conduction headsets](shokz-bone-conduction.md) — Shokz OpenRun Pro and OpenSwim Pro name tokens.
- [Sony Alpha `ILCE-*` camera names](sony-alpha-ilce-cameras.md) — Sony Alpha interchangeable-lens cameras identified by the `ILCE-<model>` name token.
- [Sony `LE_` audio name tokens: SRS-XB, WH, WF and WI-C speakers, headphones and earbuds](sony-le-prefixed-audio-names.md) — Sony portable speakers, wireless headphones and earbuds identified by an `LE_<model>` name token (plus a non-`LE_` WH-CH720N/WF-C510 variant).
- [Square Reader `Square Reader <four digits>`](square-reader.md) — Square contactless/chip payment terminal.
- [SRAM `SRAM*` AXS (`FE51`)](sram-axs-fe51.md) — SRAM AXS bicycle-electronics family.
- [Status Audio `Between*` 3ANC earbuds](status-audio-between-3anc.md) — Status Audio Between 3ANC true-wireless earbud name token.
- [Stripe Reader M2 / BBPOS `STRM*` (`FFA0`, company field 683)](stripe-reader-m2-strm.md) — Stripe Reader M2 / BBPOS payment-reader candidate.
- [Switch-Pros `SWCHPRO` SP9100](switch-pros-sp9100.md) — Switch-Pros SP9100 vehicle accessory panel candidate.
- [`tsTPMS` aftermarket Tesla-compatible TPMS (company field 555)](tesla-aftermarket-tpms.md) — Tesla-compatible aftermarket TPMS family candidate.
- [Tile `Tile` item finder (`FEED`)](tile-feed.md) — Tile Bluetooth item-finder family.
- [Toyota-associated `Toyota*` multimedia (company field 269)](toyota-denso-ten.md) — Toyota-associated in-vehicle multimedia/audio family candidate.
- [TOZO `TOZO-T*` / `TOZO-A*` earbuds](tozo-earbuds.md) — TOZO T6, T10 and A1 earbud name tokens.
- [Trimble `trimble*` (company field 1021)](trimble-company-1021.md) — Trimble-branded BLE equipment candidate; category unknown.
- [`Triones*` Bluetooth colour-light controllers (`FFD0`, `FFD5`)](triones-led-controllers.md) — Triones-compatible Bluetooth colour-light control family.
- [Unassigned name prefixes: no product identification](unassigned-ble-name-prefixes.md) — Name prefixes, shapes and company fields examined and left without a reliable product, vendor or category.
- [Unnamed broadcast population: opaque clusters](unnamed-broadcast-opaque-clusters.md) — Rollup of advertisements without a local name and two opaque `FF`-only clusters.
- [Victron Energy: `Cerbo*`, `SmartSolar*`, `Orion XS*` (company field 737)](victron-energy-ble.md) — Victron Cerbo GX gateway, SmartSolar MPPT and Orion XS DC-DC charger families.
- [VIZIO `VIZIO Mobile App` name](vizio-mobile.md) — VIZIO mobile control/pairing ecosystem.
- [WHOOP `WHOOP*` wearables](whoop.md) — WHOOP health/fitness wearable family.
- [Wyze `Wyze_Hub`](wyze-hub.md) — Wyze Sense Hub home-security hub candidate.
- [Xirgo/Harp `HarpBT*` ELD telematics](xirgo-harpbt-eld.md) — Xirgo/Harp vehicle ELD and telematics family.
- [Yamaha `YamahaAV` AV / MusicCast](yamaha-musiccast-av.md) — Yamaha AV / MusicCast audio family.
- [Yamaha `YCCU_*` motorcycle connectivity unit](yamaha-yccu-motorcycle.md) — Yamaha motorcycle/scooter smartphone-connectivity-unit candidate.
- [`YX_LED fiber light` fiber-optic star-ceiling light controller](yx-led-fiber-light-controller.md) — Generic Bluetooth fiber-optic star-ceiling light controller candidate.
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

---
qmd:
  metadata:
    title: "Historical hospital-region professional display and lighting leads"
    date: "2026-10-16"
    tags: [iot, facilities, display, lighting, wigle]
    confidence: low
---

# Historical professional display and lighting leads

These are **previously saved WiGLE-response name observations**, not newly queried radio data or first-party `cluetooth` advertisements. The underlying accepted-response cache and read-only ledger were not available for this follow-up, so the figures below are **historical report snapshots**, not current totals. Public model-like names and manufacturer specifications support product *candidates*; neither the API's BT/BLE labels nor an OSM hospital-polygon intersection proves a raw Bluetooth advertisement, authentic hardware, institution ownership, or installation.

| Sanitized public model name | Historical reported observations | Manufacturer category evidence | Limit |
|---|---:|---|---|
| `FW-75BZ35F` | **2 returned entries / 1 API netid / 2 polygon-intersecting entries** | [Sony's BZ35F professional BRAVIA display series](https://pro.sony/en_GB/products/pro-displays/bz35f-professional-displays) and [75-inch model page](https://pro.sony/en_CY/products/pro-displays/fw-75bz35f) identify a professional display suitable for digital signage. | Name-based lead; no verified hardware identity, signage content, or hospital installation. |
| `FW-85BZ35F` | **1 / 1 / 1** in the same snapshot | [Sony's 85-inch model page](https://pro.sony/en_BD/products/pro-displays/fw-85bz35f) identifies another BZ35F professional display. | Distinct model/size; do not treat the combined **3 returned entries** as three screens. |
| `NU6-RD-RGBW-10LM-43K` | **7 polygon-intersecting entries in an earlier historical subset**; full-corpus total and unique identifier count **not recalculated** | [Alphabet Lighting's original NU6RD RGBW specification](https://alphabetlighting.com/wp-content/uploads/NU6RD-RGBW-Spec-Sheet.pdf) explains the public ordering-code structure for a recessed round RGBW downlight, including `10LM` and `43K` options. | The name-to-hardware mapping is unverified; not therapeutic lighting, verified BLE control or a count of seven fixtures. |

The Alphabet code is **incomplete** as a fixture order: it does not choose a control option. The [manufacturer specification](https://alphabetlighting.com/wp-content/uploads/NU6RD-RGBW-Spec-Sheet.pdf) lists wired `DMX-RJ45`, `DMX-WL` (**lead-wire**, not wireless) and `DMX-XLR` plus optional `CAS` for [Casambi's BLE-based control platform](https://casambi.com/platform/). Neither the saved name nor the API's radio label shows that this fixture had a `CAS` option, was controlled by BLE or was even the authentic catalog fixture.

The original English Sony reference-guide link in the earlier report returned HTTP 403, but a separately retrieved [Sony Japan BZ35F reference guide](https://www.sony.jp/bravia-biz/supportbiz/ProBRAVIA_J1BZ35F_RG.PDF) directly lists **both 75- and 85-inch public model numbers** and **Bluetooth Version 4.1** in its printed p. 37 specifications; the [Japanese product-series page](https://www.sony.jp/bravia-biz/products2/FW-BZ35F_CT/) describes Bluetooth headset support. This establishes a **Japan-region product capability**, not a pairing name, exact historical transmitter, observed BLE advertisement or universal specification for other region/firmware variants. Manufacturer documentation describes products, not these specific radio records. OSM intersection is a geometric fact about an earlier response, **not proof of deployment in or ownership by a hospital**.

A later first-party `cluetooth` query found **zero** name-token matches for `FW-75BZ35F`, `FW-85BZ35F` and `NU6-RD-RGBW-10LM-43K` (literal punctuation and token boundaries, case-insensitive). This narrow negative does **not** disprove the earlier returned names or establish that no equivalent device used another name.

Private sanitized provenance: `hospital_iot_followup_2026-10-15.md` (Sony row, 221 accepted-page snapshot) and `medical_exploration_report.md` (Alphabet row, earlier subset), in external run `us-osm-hospitals/20261013T225104Z`. No raw identifiers, device suffixes, coordinates or responses are published.

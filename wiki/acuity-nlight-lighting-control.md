---
qmd:
  metadata:
    title: "Acuity lighting control: SensorSwitch*, rPP20*, nECYD*"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Acuity lighting control: `SensorSwitch*`, `rPP20*`, `nECYD*`

## How to recognize it

- **Local name:** `SensorSwitch*`, `rPP20*`, `nECYD*`
- **Company field:** company field 160, company field 10245, company field 4101
- **AD shape (ordered type/data length):** `01/1,09/17,FF/7`, `01/1,09/12,FF/20`, `01/1,09/17,FF/20`
- **What it is:** Acuity Brands SensorSwitch / nLight AIR products with name + manufacturer-data layouts.
- **Confidence and limits:** Family candidates; company fields are auxiliary. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## nLight AIR rPP20 lighting power/relay pack

The complete public `rPP20` model token appears in **2 exact `(addr,raw)` variants / 2 addresses / 15 scans**, with name suffix hidden, ordered `01/1,09/12,FF/20`, auxiliary company field 10245 and no advertised service UUID. [Original Acuity/nLight rPP20 product listing](https://www.acuitybrands.com/products/detail/771135/nlight/rpp20-relay-pack/rpp20-power-relay-pack) identifies a lighting power/relay pack in the nLight AIR ecosystem; manufacturer [installation instructions](https://img.acuitybrands.com/public-assets/catalog/771135/is-rpp20-002.pdf) are indexed but returned HTTP 403 to direct retrieval, so **no unverified configuration-specific electrical claims** are imported. This is a **different model token** from [rPP8 DS](nlight-rpp8-ds.md), without proof of interchangeability or replacement. Ordinary nLight AIR control uses a separate 900 MHz channel; these BLE ads do not demonstrate relay activity, control traffic, exact variant, physical-unit count or hospital deployment.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Acuity Brands SensorSwitch lighting-control family](https://www.acuitybrands.com/products/detail/1738720/sensorswitch/haleon-btp/fixture-integrated-occupancy-sensor-with-dimming-photocell) | `SensorSwitch*` (name 17 bytes); `01/1, 09/17, FF/7`; company field 160 auxiliary | **48 / 48 / 101** | The linked Haleon BTP is a product example, **not** an identified model for all observations. Name-wildcard shape also matches other names. |
| [Acuity Brands NECYD NLTAIR nLight AIR network-adapter family](https://www.villalighting.com/assets/docs/NECYD_NLTAIR.pdf) | Case-insensitive `nECYD*` (suffix hidden), `01/1,09/17,FF/20`, no advertised service UUID; auxiliary company field 4101 not used as proof | **9 / 9 / 40** | Original manufacturer datasheet hosted by distributor describes an adapter linking nLight AIR lighting network and nLight ECLYPSE controller. Public prefix supports family, **not** G2 generation, order code, hardware revision or authentication. |

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.

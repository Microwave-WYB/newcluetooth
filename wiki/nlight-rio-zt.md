---
qmd:
  metadata:
    title: "nLight AIR rIO ZT fixture interface"
    date: "2026-10-14"
    tags: [hospital, lighting]
    confidence: medium
---

# nLight AIR rIO ZT fixture interface

## Identification and use

The `rIO ZT…` historical name is consistent with the nLight AIR rIO fixture-embedded wireless-interface **family**, and an [original rIO specification](https://img.acuitybrands.com/public-assets/catalog/778845/rio.pdf) lists `ZT` as a 0–10 VDC ordering option. This is a product/option clue, **not** a manufacturer-documented default BLE broadcast-name rule, exact installed configuration or authenticated fixture. It is **not** evidence of a standalone line-voltage relay or an rPP power pack.

**BLE supports mobile commissioning/discovery; normal nLight AIR wireless
lighting control uses 900 MHz.** The saved Bluetooth metadata does not verify a
dimming command, luminaire state, or that 900 MHz link. The prefix supports a
family lead, not exact options, a hospital installation, or ownership; no raw
ADV/GATT frames were available.

## Sources

- [nLight rIO fixture-embedded network interface — original](https://nlight.acuitybrands.com/products/detail/778845/nlight/rio/fixture-embedded-nlight-air-network-interface).
- [nLight AIR operating architecture — original](https://nlight.acuitybrands.com/nlight-air/how-nlight-air-works).
- [nLight rIO original specification — 0–10 VDC `ZT` ordering option](https://img.acuitybrands.com/public-assets/catalog/778845/rio.pdf). Direct PDF retrieval was blocked in this follow-up; this detail was checked in the manufacturer-indexed listing, not a full reread.
- Private research: `hospital_iot_candidates_2026-10-14.md`, rIO ZT row.
  No dated Wayback fallback was verified for these original URLs.

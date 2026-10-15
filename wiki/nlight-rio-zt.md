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

The `rIO ZT…` name points to the nLight AIR rIO fixture-embedded wireless
interface with 0–10 V dimming. It is **not** evidence of a standalone
line-voltage relay or an rPP power pack.

**BLE is a commissioning/discovery interface; normal nLight AIR wireless
lighting control uses 900 MHz.** The saved Bluetooth metadata does not verify a
dimming command, luminaire state, or that 900 MHz link. The prefix supports a
family lead, not exact options, a hospital installation, or ownership; no raw
ADV/GATT frames were available.

## Sources

- [nLight rIO fixture-embedded network interface — original](https://nlight.acuitybrands.com/products/detail/778845/nlight/rio/fixture-embedded-nlight-air-network-interface).
- [nLight AIR operating architecture — original](https://nlight.acuitybrands.com/nlight-air/how-nlight-air-works).
- Private research: `hospital_iot_candidates_2026-10-14.md`, rIO ZT row.
  No dated Wayback fallback was verified for these original URLs.

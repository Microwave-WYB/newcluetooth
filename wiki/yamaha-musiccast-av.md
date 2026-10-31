---
qmd:
  metadata:
    title: "Yamaha YamahaAV AV / MusicCast"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Yamaha `YamahaAV` AV / MusicCast

## How to recognize it

- **Local name:** `YamahaAV`
- **Service / service-data UUID:** `945ca2b0-852c-4ab8-b654-354df41c2795`
- **AD shape (ordered type/data length):** `07/16,09/8`
- **What it is:** Yamaha AV / MusicCast audio family.
- **Confidence and limits:** Family candidate. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Yamaha AV / MusicCast audio family](https://usa.yamaha.com/products/audio_visual/av_receivers_amps/rx-v685_u/index.html) | Name exactly `YamahaAV` plus service UUID `945ca2b0-852c-4ab8-b654-354df41c2795`; `07/16,09/8` with or without `01/1` flags | **15 / 15 / 156** | Linked RX-V685 illustrates a Bluetooth-capable AV receiver, **not** an identification of these observations to that model. |

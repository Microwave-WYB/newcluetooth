---
qmd:
  metadata:
    title: "Motive fleet AABL* / Motive_AABL* (FC6D)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Motive fleet `AABL*` / `Motive_AABL*` (`FC6D`)

## How to recognize it

- **Local name:** `AABL*`, `Motive_AABL*`
- **Service / service-data UUID:** `FC6D`
- **AD shape (ordered type/data length):** `01/1,FF/2,03/2,09/14`
- **What it is:** Motive fleet BLE endpoint candidates.
- **Confidence and limits:** Candidates; the combined broadcast rule is not published and the hardware is unconfirmed. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Motive fleet BLE endpoint candidate, `AABL*`](https://gomotive.com/products/beacon/) | `AABL*` plus [Motive-assigned `FC6D`](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/uuids/member_uuids.yaml); chiefly `01/1, FF/2, 03/2, 09/14` | **74 / 66 / 349** | [Motive's Vehicle Gateway guidance](https://helpcenter.gomotive.com/hc/en-us/articles/6163665662365-Troubleshooting-common-issues-with-the-Vehicle-Gateway) says **some gateway serial numbers start `AABL`**, not that a bare `AABL*` BLE Local Name identifies the gateway. Beacon is an alternative ecosystem product, **not** an established role here; unreliable one/two-byte manufacturer fields do not resolve the hardware. |
| [Motive fleet BLE endpoint candidate, `Motive_AABL*`](https://helpcenter.gomotive.com/hc/en-us/articles/6163665662365-Troubleshooting-common-issues-with-the-Vehicle-Gateway) | `Motive_AABL*`; two variants also carry Motive-assigned `FC6D`, most have no service UUID | **25 / 24 / 47** | Original iOS Bluetooth troubleshooting says a Vehicle Gateway device name starts `MOTIVE_` and separately identifies some gateway serials as `AABL*`, **but never publishes the combined `Motive_AABL*` broadcast rule**. The exact hardware and whether this and bare `AABL*` represent the same hardware remain unknown; do not infer a gateway SKU or Beacon. |

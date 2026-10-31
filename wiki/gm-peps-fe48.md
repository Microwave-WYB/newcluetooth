---
qmd:
  metadata:
    title: "GM GM_PEPS_VKM (FE48)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# GM `GM_PEPS_VKM` (`FE48`)

## How to recognize it

- **Local name:** `GM_PEPS_VKM`
- **Service / service-data UUID:** `FE48`
- **AD shape (ordered type/data length):** `01/1,03/2,16/22,09/12`
- **What it is:** GM digital-key / passive-entry BLE-module candidate.
- **Confidence and limits:** Candidate. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [GM digital-key / passive-entry BLE-module candidate](https://news.gm.com/home.detail.html/Pages/topic/us/en/2026/sep/0923-key-digital-wallet.html) | Exact generic `GM_PEPS_VKM`, GM-assigned service and service-data UUID `FE48`; `01/1,03/2,16/22,09/12`; service-data bytes withheld | **6 / 6 / 25** | GM's digital-key page documents functionality, **not** this specific BLE name. No vehicle model, valid key, pairing state, UWB support or unlock event inferred; addresses are not cars. |

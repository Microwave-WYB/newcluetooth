---
qmd:
  metadata:
    title: "Amazon Echo* Dot / Pop / Buds (FE00, company field 369)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Amazon `Echo*` Dot / Pop / Buds (`FE00`, company field 369)

## How to recognize it

- **Local name:** `Echo Dot*`, `Echo Pop*`, `Echo Buds*`, `Echo*`
- **Company field:** company field 369
- **Service / service-data UUID:** `FE00`
- **AD shape (ordered type/data length):** `01/1,16/25,09/12,0A/1`
- **What it is:** Amazon Echo-family name tokens.
- **Confidence and limits:** Name candidates; Buds use company field 369 with a different service UUID. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

First-party simi `cluetooth` read-only aggregates, researched **2026-10-16**. The principal feature here is a **sanitized model token in the advertised name**, not a claim that a generic BLE shape uniquely identifies a model. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, **not confirmed physical devices**. Hide all device-specific suffixes; names can be spoofed, and a research date is not an observation date. No hospital association is required.

## Amazon Echo subfamilies

An exact-case `Echo*` name cohort has **29 variants / 29 addresses / 330 scans**; an earlier case-insensitive prefix survey counted 30 / 30 / 335 and should not be silently combined. [Amazon's Echo buying guide](https://www.aboutamazon.com/news/devices/amazon-echo-buying-guide) distinguishes Dot/Pop smart speakers from Buds wireless earbuds; names do not establish product generation.

| Sanitized name token | Candidate category | Variants / addresses / scans |
|---|---|---:|
| `Echo Dot*` | Alexa smart speaker | **21 / 21 / 206** |
| `Echo Pop*` | Compact Alexa smart speaker | **4 / 4 / 97** |
| `Echo Buds*` | Alexa wireless earbuds, **not a speaker** | **2 / 2 / 22** |
| Other `Echo*` | Unresolved Echo-family lead | **2 / 2 / 5** |

Dot/Pop generally combine their name token with Amazon-assigned service-data UUID `FE00`; `01/1,16/25,09/12,0A/1` is common, with one shorter service-data field. Buds combine their name with auxiliary Amazon company field 369 and a different service UUID; malformed-looking parsed AD fields are not a model discriminator. No `Echo Show` token was identified in this cohort.

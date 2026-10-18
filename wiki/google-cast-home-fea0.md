---
qmd:
  metadata:
    title: "Google Cast / Home devices on FEA0: Chromecast, Nest Audio, Nest Mini, kitchen*"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Google Cast / Home devices on `FEA0`: Chromecast, Nest Audio, Nest Mini, `kitchen*`

## How to recognize it

- **Local name:** `Chromecast*`, `Nest Audio*`, `Nest Mini*`, `kitchen*`
- **Service / service-data UUID:** `FEA0`
- **AD shape (ordered type/data length):** `01/1,03/2,16/15,09/13`, `01/1,03/2,16/15,09/12`, `01/1,16/17,02/2,09/11 or 15`
- **What it is:** Google-assigned `FEA0` service/service-data on Chromecast and Nest speaker names and a `kitchen*` room-name candidate.
- **Confidence and limits:** Name distinguishes candidates; `FEA0` is shared with other Google Cast products. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Google Nest Audio smart speaker](https://support.google.com/googlehome/answer/7072284?hl=en) | `Nest Audio*`; `01/1, 03/2, 16/15, 09/13`; UUID `FEA0` | **1 / 1 / 1** | Name distinguishes this candidate; UUID `FEA0` is shared with other Google Cast products. |
| [Google Nest Mini smart speaker](https://support.google.com/googlehome/answer/7072284?hl=en) | `Nest Mini*`; `01/1, 03/2, 16/15, 09/12`; UUID `FEA0` | **1 / 1 / 2** | One advertisement is only a lead, not a validated physical device or hardware generation. |
| [Google Chromecast / Cast-family media devices](https://support.google.com/chromecast/answer/7022492) | `Chromecast*` (name 14 bytes) **plus** service and service-data UUID `FEA0`, [assigned to Google](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/uuids/member_uuids.yaml); no manufacturer field required | **39 / 39 / 63** | Cannot distinguish Chromecast, Ultra, Audio or Google TV generations; not evidence media was playing. |
| [Google Home/Nest ecosystem `kitchen*` **candidate**](https://store.google.com/product/nest_hub_2nd_gen_specs?hl=en-US) | Customizable room-name prefix `kitchen*` (rest hidden) plus Google-assigned service/service-data UUID `FEA0`; `01/1,16/17,02/2,09/11 or 15` | **6 / 6 / 110** | One **1 / 1 / 102** name includes generic `Hub`, so a Nest Hub-like smart display is plausible; other **5 / 5 / 8** unspecified. Manufacturer model, second generation, broadcaster authenticity and kitchen-appliance status **not** established. |

---
qmd:
  metadata:
    title: "Fellow EKG-* kettle (low confidence)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Fellow `EKG-*` kettle (low confidence)

## How to recognize it

- **Local name:** `EKG-*`
- **Service / service-data UUID:** `021a9004-0382-4aea-bff4-6b3f1c5adfb4`
- **What it is:** Fellow EKG connected-kettle low-confidence candidate using the reusable Espressif provisioning UUID.
- **Confidence and limits:** Low-confidence candidate; manufacturer unverified. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Fellow EKG connected-kettle **low-confidence** candidate](https://help.fellowproducts.com/hc/en-us/articles/9313573421211-How-do-I-download-the-EKG-Updater-app-and-pair-it-to-my-Stagg-EKG-Pro-Corvo-EKG-Pro-or-Pro-Studio-Edition-kettle) | `EKG-*` (suffix hidden); UUID `021a9004-0382-4aea-bff4-6b3f1c5adfb4`, [reusable Espressif Wi-Fi BLE provisioning example](https://github.com/espressif/esp-idf/blob/v5.2/examples/provisioning/wifi_prov_mgr/main/app_main.c#L440-L450); no company field | **9 / 9 / 79** | Fellow's [EKG Updater guide](https://help.fellowproducts.com/api/v2/help_center/en-us/articles/9313573421211.json) and [EU Data Act page](https://fellowproducts.com/pages/eu-data-act) document Bluetooth-assisted Wi-Fi setup/firmware update, **not remote kettle control**; neither specifies this name/UUID. Manufacturer, kettle class and Stagg/Corvo SKU of the observed ads remain unverified; not an identified ECG instrument or captured setup session. |

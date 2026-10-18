---
qmd:
  metadata:
    title: "HP-number-associated FE78 / company field 101 broadcasts: ENVY printers and the unnamed HP-associated group"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# HP-number-associated `FE78` / company field 101 broadcasts: ENVY printers and the unnamed HP-associated group

## How to recognize it

- **Local name:** `Envy*`, (no local name)
- **Company field:** company field 101
- **Service / service-data UUID:** `FE78`, `FDF7`
- **AD shape (ordered type/data length):** `01,16,03,FF`
- **What it is:** Named `Envy*` HP all-in-one printers, and a much larger unnamed group sharing the HP-assigned `FE78` / `FDF7` / company field 101 signature.
- **Confidence and limits:** ENVY rows: model-series candidates. Unnamed group: company-associated broadcast signature only, not proof of an HP product. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## HP ENVY all-in-one printers

Actual BLE advertisements in the `Envy*` cohort total **11 variants / 10 addresses / 21,778 scans**. All share auxiliary HP company field 101 and service UUID `FE78`; this is **not** a Wi-Fi Direct SSID survey. [HP's Bluetooth setup guide](https://support.hp.com/us-en/document/ish_2026021-1796387-16) discusses Bluetooth Smart discovery/setup, **not** printing over Bluetooth.

| Sanitized model-series name / official HP source | Variants / addresses / scans |
|---|---:|
| [ENVY Photo 7100 series](https://support.hp.com/us-en/product/setup-user-guides/hp-envy-photo-7100-all-in-one-printer-series/9073119) | **5 / 4 / 8,934** |
| [ENVY Photo 7800 series](https://support.hp.com/us-en/product/setup-user-guides/hp-envy-photo-7800-all-in-one-printer-series/9073159) | **3 / 3 / 12,836** |
| [ENVY 5000 series](https://support.hp.com/us-en/product/setup-user-guides/hp-envy-5000-all-in-one-printer-series/14095644) | **3 / 3 / 8** |

Five advertisements across four addresses do **not** mean five 7100 printers; scans do not represent print jobs. No retail SKU within these printer series is inferred.

## Related observations

Another **unnamed, HP-number-associated** group advertises 16-bit service UUID `FE78` and service-data UUID `FDF7`, together with company field **101 (`0x0065`)**: **651 exact variants / 616 addresses / 92,862 scans**. Its largest ordered `01,16,03,FF` / 62-byte subshape accounts for **584 / 573 / 91,921**. The [SIG member-UUID registry](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/uuids/member_uuids.yaml) assigns `FE78` to Hewlett-Packard Company and `FDF7` to HP Inc.; the [company-ID registry](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml) assigns 101 to HP, Inc. This is a **company-associated broadcast signature**, not proof of an HP printer, PC, specific model, installation or genuine producer. A separate, publicly named `Envy*` comparator has `FE78` but **not** `FDF7` (**11 / 10 / 21,778**) and a different AD order; its identity must **not** be transferred to the unnamed group. In this bounded sweep, 20 preselected facility/medical/industrial member UUIDs yielded no unnamed group with three or more observed addresses; that is not evidence those categories are absent.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.

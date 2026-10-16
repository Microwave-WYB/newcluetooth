---
qmd:
  metadata:
    title: "HID Seos door-reader and credential ecosystem"
    date: "2026-10-14"
    tags: [hospital, access-control]
    confidence: medium
---

# HID Seos door-reader and credential ecosystem

## Identification and use

The name `Seos` supports an **HID Seos access-control credential ecosystem candidate**, not an authenticated reader or mobile credential. A subset of historical records reports company ID **302 (`0x012E`)**, which the [Bluetooth SIG assigns to **ASSA ABLOY**](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml), **not HID Global** (assigned ID **292 / `0x0124`**). This field neither authenticates HID equipment nor identifies the transmitter role. HID describes Seos credential technology and compatibility with Signo, iCLASS SE and multiCLASS SE readers, but no original source maps this exact historical name/AD structure to a specific reader.

Separately, an unfiltered first-party read-only `cluetooth` name-prefix survey found `Seos*` **116 exact `(addr,raw)` variants / 76 distinct observed addresses / 125,426 scans**. Every variant contains company field **302 (ASSA ABLOY)**: **60 variants / 60 addresses / 64,477 scans** have only 302, while **56 variants / 56 addresses / 60,949 scans** also contain company field **76 (Apple)**; none contains 292 (HID Global). The two variant groups have **overlapping addresses**, so their per-group address counts are **not additive**. Ordered AD forms are `01,07,0A,08,FF` or `01,FF,08,FF`; `08` is a shortened local name. No actual 128-bit UUID, manufacturer bytes, credential material or private name is published. The Apple field does not prove a phone-reader connection, device owner or access transaction. This first-party whole-database count is **not** the same population as historical WiGLE hospital-region entries and cannot authenticate a physical reader, credential holder or hospital installation.

This is intentionally an **ecosystem** page: the available records cannot
reliably separate door readers from credential-bearing/mobile devices or choose
a reader model. HID's Seos card documentation does not make every Seos credential
a Bluetooth transmitter. Likewise, a reported company ID is not authentication.
There is no evidence here of a particular door, hospital installation, access
event, credential content, or vulnerability.

The [original HID Mobile Access FAQ](https://doc.origo.hidglobal.com/faq/portal/HID_Mobile_Access_FAQ.pdf), §§3.1/3.5, describes mobile credentials, apps and compatible readers using BLE/NFC; §8.2 says a phone vibration/sound indicates that a reader communication **started a transaction**, not successful authorization, door release or physical entry. The historical records do not even establish such an exchange.

## Sources

- [HID Seos product family — original manufacturer page](https://www.hidglobal.com/product-mix/seos).
  [Verified Wayback fallback, captured 2025-11-02 20:03:43 UTC](https://web.archive.org/web/20251102200343/https://www.hidglobal.com/product-mix/seos).
  The availability API's later replay redirected to this actual capture; this
  is the final capture date, not the requested timestamp.
- [Bluetooth SIG assigned numbers — original company-ID reference](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml).
- [HID Mobile Access FAQ — original PDF](https://doc.origo.hidglobal.com/faq/portal/HID_Mobile_Access_FAQ.pdf), §§3.1, 3.5, 8.2: product communication and transaction-start feedback, **not** an observed access event.
- Private research: `hospital_iot_candidates_2026-10-14.md`, Seos row.

---
qmd:
  metadata:
    title: "Gozio hospital-wayfinding beacon family"
    date: "2026-10-14"
    tags: [hospital, wayfinding]
    confidence: medium
---

# Gozio hospital-wayfinding beacon family

## Identification and hospital relevance

The existing research reports company ID 506 (`0x01FA`), assigned to Gozio
Inc. Gozio documents beacon-assisted hospital wayfinding. Together these support
a vendor-family lead, **not** the exact Fusion Beacon model, a specific hospital
deployment, or ownership. A company ID can be copied or misreported.

The FCC index for GZFUSION1 is related product context only: the original
research encountered an access block and used indexed information, not a fully
inspected filing. Gozio can also reuse other vendors' beacon infrastructure;
this one client parser is not a universal Gozio-device fingerprint.

## Static Android sample provenance

- Third-party download source, **not official distribution**:
  <https://d.apkpure.net/b/APK/org.unchealthcare.go?version=latest>.
- XAPK manifest: package `org.unchealthcare.go`, version **3.11.0 (11637)**.
- XAPK/archive SHA256:
  `42b5d9fc4b9855f122ef7474a9969e6f0b196ef7f01b075f8cc22b520b439695`.
- Base APK SHA256:
  `2a1d5cf20fd86fae4f2517ea8f876383701873c37ea3cdf53bc4c94ebf8b7037`.
- Static unpacking/JADX only; no installation, execution, login, or connection.
  JADX reported **306 decompilation errors**. The cited scan paths are readable
  and agree, but remain evidence from one imperfectly decompiled sample.
  The mutable `latest` URL does not pin these historical bytes; the hashes do.

## What the inspected client actually parses

Paths below are relative to the external research run, not files published in
this repository. Quotes were checked against the extracted code.

`unc-decompiled/sources/com/goziohealth/ips/BleSensor.java:178`:

```java
ScanFilter scanFilterBuild = new ScanFilter.Builder().setManufacturerData(506, new byte[0]).build();
```

The parallel service uses the same filter at
`unc-decompiled/sources/com/goziohealth/ips/BleSensorService.java:134`.
These are manufacturer-data filters, not evidence of iBeacon/Eddystone filtering.

`unc-decompiled/sources/com/goziohealth/ips/BleSensor.java:281-290`:

```java
if (rssi <= 0 && scanRecord[3] == 13 && scanRecord[4] == -1 && scanRecord[5] == -6 && scanRecord[6] == 1) {
    long j5 = 0;
    for (int i6 = 0; i6 < 6; i6++) {
        j5 |= (((long) scanRecord[i6 + 7]) & 255) << (i6 * 8);
    }
    byte b3 = scanRecord[13];
    byte b4 = scanRecord[14];
    int i10 = 15 & scanRecord[15];
    short s6 = i10 == 1 ? (short) 2426 : i10 == 2 ? (short) 2480 : (short) 2402;
    BleScanResult bleScanResult = new BleScanResult(System.currentTimeMillis(), j5, rssi, s6, scanRecord[16], b3, b4);
```

The equivalent parser is at
`unc-decompiled/sources/com/goziohealth/ips/BleSensorService.java:185-194`.
Offsets are absolute within Android's complete `scanRecord`, **not** relative
to the manufacturer-data field:

| Offsets | Static interpretation |
|---|---|
| 3–6 | AD length `0D`, type `FF`, company bytes `FA 01` |
| 7–12 | Six-byte little-endian embedded identifier |
| 13, 14 | Hardware and software versions |
| 15, low nibble | 1 → 2426 MHz; 2 → 2480 MHz; otherwise 2402 MHz |
| 16 | Battery field; physical voltage units/scaling unknown |

The manufacturer field therefore has the client-expected shape
`FA 01 | id[6] | hw[1] | sw[1] | channel/unknown[1] | battery[1]`.
The high nibble at offset 15 is unexplained. The parser does not validate the
first three bytes; no particular prefix is established as mandatory.

The result fields are named `macAddress`, `batteryVoltage`, `hwVersion`,
and `swVersion` in
`unc-decompiled/sources/com/goziohealth/ips/BleScanResult.java:21-37,45-52`.
**The embedded identifier is not a verified radio MAC address**, and the name
`batteryVoltage` does not establish voltage units.

## Evidence boundary

This is a passive scanning/parser capability in a static client, not observed
radio-frame validation, GATT interaction, or proof about every Gozio product.
WiGLE company-ID metadata supplies no raw ADV bytes to compare against this
layout. No raw GATT, patient data, vulnerability, physical-device count, or
hospital deployment was verified.

## Public sources

- [Gozio FAQ — original manufacturer page](https://www.goziohealth.com/gozio-faqs).
  [Verified Wayback fallback, captured 2026-05-14 16:09:50 UTC](https://web.archive.org/web/20260514160950/https://www.goziohealth.com/gozio-faqs).
  Product context, not the source of the byte layout above.
- [Gozio/Acuity Atrius wayfinding integration — original vendor announcement](https://www.goziohealth.com/news/gozio-health-adds-atrius-iot-indoor-positioning-solution-acuity-brands-wayfinding-mobile-platform).
- [Fusion Beacon GZFUSION1, FCC ID 2AEFO-GZFUSION1 — third-party FCC index](https://fccid.io/2AEFO-GZFUSION1).
  Related model lead only; not an observed-device identification.
- [Bluetooth SIG company identifiers — original assignment reference](https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml).
- [US10244362B2 — patent publication](https://patents.google.com/patent/US10244362B2/en):
  sensor-fusion background, not proof of this advertisement format.
- [UNC Health Google Play listing — official-store context only](https://play.google.com/store/apps/details?id=org.unchealthcare.go).
  This is **not** the sample's download provenance.
- Private research: `gozio_protocol_notes.md` and
  `medical_exploration_report.md`, section 2; research record date 2026-10-14.

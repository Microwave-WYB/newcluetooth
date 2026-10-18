---
qmd:
  metadata:
    title: "Epson ET-* / WF-* printers (company field 64)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Epson `ET-*` / `WF-*` printers (company field 64)

## How to recognize it

- **Local name:** `ET-2850 Series`, `ET-3850`, `ET-4850`, `ET-2980`, `ET-2800`, `ET-3950`, `ET-4800`, `ET-3830`, `ET-8550 Series`, `WF-2950 Series`
- **Company field:** company field 64
- **AD shape (ordered type/data length):** `01/1,FF/5,06/16,09/14`
- **What it is:** Epson EcoTank ET-series and WorkForce WF-2950 name tokens.
- **Confidence and limits:** Model-name candidates; the shared shape does not distinguish models. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## Epson EcoTank ET-series all-in-one printers

All **6 exact `(addr,raw)` variants / 6 addresses / 197 scans** advertise exact public name `ET-2850 Series`, supporting the [original Epson ET-2850 series](https://support.epson.com/Support/Printers/All-In-Ones/ET-Series/Epson-ET-2850/s/SPT_C11CJ63202): color ink-tank printing, scanning, copying and automatic duplex printing. Ordered AD `01/1,FF/5,06/16,09/14`; auxiliary company field 64, unverified 128-bit UUID withheld. This is separate from HP printer leads; the advertising does not distinguish country/retail SKU, authenticate manufacture or count physical printers.

Bluetooth SIG [official company identifiers](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Assigned_Numbers/out/en/index-en.html) assign **64 / `0x0040` to Seiko Epson Corporation**. That agrees with the ET/WF public model-name candidates below but is **only a parsed auxiliary field**, not proof that an advertisement came from a genuine Epson printer or any particular ET/WF model.

Eight **other complete public ET-series name tokens**, explicitly excluding ET-2850 above, have original Epson model pages:

| Name token / original manufacturer model page | Exact variants / addresses / scans | Product-family distinction |
|---|---:|---|
| [`ET-3850`](https://epson.com/For-Work/Printers/Inkjet/EcoTank-ET-3850-Wireless-Color-All-in-One-Cartridge-Free-Supertank-Printer-with-Scanner%2C-Copier%2C-ADF-and-Ethernet/p/C11CJ61201) | **4 / 4 / 246** | Color ink tank print/scan/copy with ADF. |
| [`ET-4850`](https://support.epson.com/select/For-Work/Printers/Inkjet/EcoTank-ET-4850-Wireless-Colour-All-in-One-Cartridge-Free-Supertank-Printer-with-Scanner%2C-Copier%2C-Fax%2C-ADF-and-Ethernet/p/C11CJ60202) | **3 / 3 / 53** | Color ink tank multifunction with fax and ADF. |
| [`ET-2980`](https://epson.com/For-Home/Printers/Inkjet/EcoTank-ET-2980-Wireless-All-in-One-Color-Supertank-Printer-%E2%80%93-Black/p/C11CL41201) | **3 / 3 / 24** | Color ink tank print/scan/copy. |
| [`ET-2800`](https://epson.com/For-Work/Printers/Inkjet/EcoTank-ET-2800-Wireless-Color-All-in-One-Cartridge-Free-Supertank-Printer-with-Scan-and-Copy/p/C11CJ66202) | **6 / 6 / 13** | Color ink tank print/scan/copy. |
| [`ET-3950`](https://epson.com/Support/Printers/All-In-Ones/ET-Series/Epson-ET-3950/s/SPT_C11CL43201) | **2 / 2 / 20** | Color ink tank print/scan/copy with ADF and duplex; [shared manufacturer setup guide](https://files.support.epson.com/docid/cpd6/cpd64026.pdf) also covers other, **distinct** ET models. |
| [`ET-4800`](https://epson.com/p/C11CJ65201) | **3 / 3 / 6** | Color ink tank print/scan/copy/**fax** with ADF and Ethernet; unlike ET-4850, no automatic duplex in the [original model comparison](https://news.epson.com/news/ecotank-supertank-printers-home-office-2021). |
| [`ET-3830`](https://epson.com/p/C11CJ62201) | **1 / 1 / 6** | Color ink tank print/scan/copy, Ethernet/duplex, no ADF or fax; different from ET-3850. |
| [`ET-8550 Series`](https://epson.com/en/For-Work/Printers/Inkjet/EcoTank-Photo-ET-8550-All-in-One-Wide-format-Supertank-Printer/p/C11CJ21201) | **2 / 2 / 5** | EcoTank Photo six-ink wide-format photo multifunction model; product-page 13×19-inch capability is **not** an observed printing event. |

These eight cohorts also share company field 64 and ordered `01/1,FF/5,06/16,09/14` with ET-2850; a 128-bit UUID is withheld. **The shared shape does not distinguish models**: the full name tokens do. Manufacturer example SKU/region/color does not identify observed configurations, BLE presence does not show print data transport, and no address count establishes physical printers or hospital deployment.

The distinct [`WF-2950 Series`](https://epson.com/en/For-Work/Printers/Inkjet/WorkForce-WF-2950-Wireless-All-in-One-Color-Inkjet-Printer-with-Built-in-Scanner%2C-Copier%2C-Fax-and-Auto-Document-Feeder/p/C11CK62201) (**2 exact variants / 2 addresses / 3 scans**) is a **WorkForce cartridge-based color inkjet** print/scan/copy/fax model with ADF, **not** EcoTank. It shares the same AD shape and auxiliary company field 64, which do not prove ink system or exact model independently of the public name.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.

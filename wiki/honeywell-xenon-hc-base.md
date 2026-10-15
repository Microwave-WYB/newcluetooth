---
qmd:
  metadata:
    title: "Honeywell Xenon HC communication and charging base"
    date: "2026-10-14"
    tags: [hospital, barcode-scanning]
    confidence: medium
---

# Honeywell Xenon HC communication and charging base

## Identification and use

The sanitized pattern `Xenon_CCB-U…-HC_SN_<suffix>` points to a Honeywell
Xenon healthcare communication/charging-base family. It should not be relabeled
as the handheld scanner itself. Honeywell's healthcare scanners support barcode
workflows such as patient identification, medication, and specimen handling.

The base-family lead does **not** establish a paired handheld's model
(1962h or otherwise), its current use, barcode contents, or a hospital's
ownership. Product documentation for the 1962h is related family context, not
proof that an observed base was paired to that exact scanner. No scanner/base
session or raw radio frame was captured.

## Sources

- [Honeywell Xenon Ultra 1962h healthcare scanner — original](https://automation.honeywell.com/us/en/products/productivity-solutions/barcode-scanners/healthcare-scanners/xenon-ultra-1962h-healthcare-scanner).
  [Verified Wayback fallback, captured 2026-03-08 05:00:29 UTC](https://web.archive.org/web/20260308050029/https://automation.honeywell.com/us/en/products/productivity-solutions/barcode-scanners/healthcare-scanners/xenon-ultra-1962h-healthcare-scanner).
- [Honeywell Xenon Ultra 1960/1962 accessory guide — original PDF](https://prod-edam.honeywell.com/content/dam/honeywell-edam/sps/ppr/en-us/public/products/barcode-scanners/healthcare/1960h/documents/hon-ia-pss-xenon-ultra-1960-962-ag.pdf).
- Private research: `hospital_iot_candidates_2026-10-14.md`, Xenon HC row.

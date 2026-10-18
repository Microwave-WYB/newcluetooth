---
qmd:
  metadata:
    title: "Stripe Reader M2 / BBPOS STRM* (FFA0, company field 683)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Stripe Reader M2 / BBPOS `STRM*` (`FFA0`, company field 683)

## How to recognize it

- **Local name:** `STRM*`
- **Company field:** company field 683
- **Service / service-data UUID:** `FFA0`
- **What it is:** Stripe Reader M2 / BBPOS payment-reader candidate.
- **Confidence and limits:** Candidate; Stripe does not bind this advertisement to the model. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Stripe Reader M2 / BBPOS payment-reader candidate](https://docs.stripe.com/terminal/readers/stripe-m2) | `STRM*` (serial-bearing suffix hidden); service UUID `FFA0`, auxiliary BBPOS company field 683; repeated local-name AD field retained | **26 / 26 / 1,509** | Stripe confirms the BLE Reader M2; a [third-party POS integration guide](https://learn.aestheticrecord.com/en/articles/9339909-connecting-and-troubleshooting-the-m2-wifi-readers) relates `STRM` serial prefixes to M2, but Stripe's page does **not** bind this exact advertisement to the model. |

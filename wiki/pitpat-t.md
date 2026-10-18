---
qmd:
  metadata:
    title: "PitPat PitPat-T* (low confidence)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# PitPat `PitPat-T*` (low confidence)

## How to recognize it

- **Local name:** `PitPat-T*`
- **What it is:** PitPat Fitness treadmill/fitness-device low-confidence candidate.
- **Confidence and limits:** Low-confidence candidate. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [PitPat Fitness treadmill/fitness-device **low-confidence candidate**](https://www.pitpatfitness.com/faq/) | `PitPat-T*` (suffix hidden); two variable service/AD patterns, inconsistent manufacturer fields; [third-party-hosted same-name pairing clue](https://manuals.plus/m/50a5e2a178dab8c0634cadb0941620e1eeb70571e0948ddf94dc3534664b9e5d) | **6 / 6 / 75** | PitPat Fitness's FAQ documents Bluetooth connection to a treadmill but **not** the `PitPat-T` broadcast rule, UUID or model. The unrelated [PitPat pet-device brand](https://www.pitpat.com/device/) also uses Bluetooth; name and connectivity alone do not resolve producer or category. Neither a specific treadmill nor a pet tracker, exercise session or app synchronization is identified. |

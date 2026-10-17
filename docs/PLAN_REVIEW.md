# Plan review and implementation boundary

Reviewed 2026-10-17. The supplied document is preserved as `original-plan.md`.

The native Kotlin/Compose, CameraX, Room, and offline-first choices fit this task. The
most valuable requirements are conservative recognition, explicit physical-copy
confirmation, immutable sorting passes, and separation of retail provenance from
card face identity. These should remain invariants rather than later polish.

## Findings

| Risk | Evidence / implication | Decision in this alpha |
| --- | --- | --- |
| Catalog rights | Inspected Kamalisk/arkhamdb-json-data README and file tree at `6f4760ebe91db212b54473db605771167d86c842`; no explicit license found. Public availability is not a redistribution grant. | Bundle neither that dataset nor illustrations/rules text. Supply a local, metadata-only converter and a user-selected import. Rights review remains open. |
| Catalog coverage | Local conversion: 5,524 unique visible named printings, 101 referenced packs; 114 pack definitions in the source. This is coverage of the inspected snapshot, not all released content. | Pin provenance to source commit and imported bytes' SHA-256. No completeness claims. |
| Retail ambiguity | `dwlc`, `dwlp`, `ptcc`, `ptcp`, `tcuc`, `tcup`, `tdec`, `tdep`, `tfac`, `tfap`, `ticc`, `ticp` have no directly associated visible records in the inspected snapshot. | Reference-pack routing is explicitly provisional. Do not infer historical purchase or copy allocation. Domain model separates possible products and explicit allocations. |
| False matches | No licensed labeled specimen set or physical-device measurements supplied. OCR titles do not distinguish all editions, levels, faces, or shared titles. | OCR produces suggestions only. Automatic inventory acceptance is disabled. Rapid presentations always go to Re-sort; manual confirmation remains functional. |
| Removal detection | OCR disappearance can result from glare, not physical removal. | Conservative time gate with dropout suppression; label experimental. No ownership commits depend on this gate. True quadrilateral/scene-change detection remains a recognition milestone. |
| Sorting reliability | Re-presented identical copies cannot be individually identified by face alone. | Frozen source-count snapshots, event IDs, persistent pass ledgers, exhaustion checks; no sorting operation creates owned copies. Counts still require physical reconciliation. |
| Privacy | Deferred camera review conflicts with retaining diagnostic photos. | Store only candidate IDs and attempt metadata. Re-scan live. No image storage APIs, internet permission, or frame logging. Screenshot protection is enabled. |

## Working proofs in this repository

- Pure Kotlin planner and simulator for 5, 50, and 500 logical destinations, bounded
  physical capacity including Re-sort, deterministic versioning, ambiguous routing,
  and resumed source-count ledgers. This is a balancing heuristic, not an optimal
  packing algorithm.
- Explicit product allocations in completeness calculations; compatible origins
  are never automatically credited to all products.
- Room transactions for idempotent confirmations, independent pending attempts,
  audited undo, preserved historical catalog provenance, and ownership-neutral routes.
- Native manual inventory workflow, offline imported catalog, experimental live OCR,
  exportable records, and immutable pass maps.

## Gate status and next milestones

This is an **inventory/planning alpha**, not a completed recognition MVP. The plan's
full-scale recognition gate remains open. Do not enable automatic matches or ship
catalog illustrations until rights, on-device specimen validation, and exact
reference-image provenance are established.

Next required evidence: authorized reference imagery and labeled real specimens;
verified product manifests with reprint equivalences; physical S26 Ultra tests for
camera, speech, thermal behavior, and timing. Then implement perspective correction,
quality gates, field/visual consistency, measured reject thresholds, and historical
image-match identities. Full backup restore/migration testing and release signing
follow before production use.

Nonblocking defaults: English-language OCR, one local collection, five proposed
physical positions editable in settings, local Storage Access Framework exports,
foreground active-screen camera. Other languages, cloud sync, and analytics are not
introduced.

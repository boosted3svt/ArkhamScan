# Plan review and implementation boundary

Reviewed 2026-10-17. Updated for the user’s online-reference preference in v0.2.0. The supplied document is preserved as `original-plan.md`.

The native Kotlin/Compose, CameraX, Room, and offline-first choices fit this task. The
most valuable requirements are conservative recognition, explicit physical-copy
confirmation, immutable sorting passes, and separation of retail provenance from
card face identity. These should remain invariants rather than later polish.

## Findings

| Risk | Evidence / implication | Decision in this alpha |
| --- | --- | --- |
| Catalog rights | Inspected Kamalisk/arkhamdb-json-data README and file tree at `6f4760ebe91db212b54473db605771167d86c842`; no explicit license found. Public availability is not a redistribution grant. | Bundle neither that dataset nor illustrations/rules text. Download public API metadata on request and open card pages in the browser. Keep local-file import optional. Image redistribution/matching rights review is separate from website lookup. |
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
- Native manual inventory workflow, online ArkhamDB metadata download with offline cache, optional file import, browser reference links, experimental live OCR,
  exportable records, and immutable pass maps.

## Gate status and next milestones

This is an **inventory/planning alpha**, not a completed recognition MVP. The plan's
full-scale recognition gate remains open. Automatic acceptance requires on-device specimen validation; an offline illustration
cache or visual matching implementation separately requires its source terms and
exact reference-image provenance. Browsing ArkhamDB pages and downloading public
metadata do not require bundling or distributing card images.

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

## v0.3 field-feedback changes

Frame-by-frame candidate replacement caused reported tap/read flicker. A tested
presentation latch now requires repeated observations, then holds its entire list
through dropouts until selection or explicit retry. It is a UI stability mechanism,
not a calibrated recognition confidence score. Rapid exception metadata uses fresh
frame candidates rather than the held display list.

Collection +/- edits are transactional and audited, cannot go below zero, preserve
zero-count rows, and never assert a physical placement. Explicit scan confirmation
now atomically creates one copy and one live placement, followed by “Pile N.”

Live group slots remain stable under unknown future input. Table overflow goes to
a designated mixed pile, with a specific source-count snapshot for later splitting.
This trades some re-scans for stable numbers and bounded table use. A new map needs
a cleared table; active re-sort passes block conflicting new inventory/edits.
Reference-pack and source cycle/chapter groups are useful provisional destinations,
not claims of original purchased-box provenance.

Starting a re-sort closes the prior live table map. Additional source piles remain
inspectable in that saved map; previously processed sources cannot accidentally
start duplicate passes. After re-sorting, new inventory requires an explicit fresh
map on a cleared table, so old destinations cannot silently resume on reused slots.


## v0.4 unknown bulk collection

The active workflow assumes an unsorted bulk lot with no purchase history. No
owned-product checklist or purchase inference is required. Persistent cumulative
ownership is compared with a refreshed online reference index by source product,
logical cycle and encounter set. Progress caps each card at its target, lists
extras separately and never lets duplicates fill another card's missing copies.

Reference quantities remain provisional. Exact and equivalent printings are shown
separately, repackage membership with unverified quantities gets design coverage,
and products lacking records remain visible gaps. There is no overall catalog
percentage or claim that every known product was purchased. An optional cutoff
limits reference targets without changing inventory. Finishing all bins changes
outstanding labels to potentially missing; later relevant edits reopen review.
Progress and outstanding CSV complement the existing collection and audit exports.

Still pending: independently verified physical manifests, scenario dependency
readiness, restoreable backups, and physical-device recognition validation. The
new encounter view is content coverage rather than a ready-to-play assertion.


## Remote interface agenda

A phone-hosted browser interface is the preferred next feature for mounted-phone
scanning. The Windows/Ubuntu listener remains an alternative deployment. Both
keep the phone as the inventory authority and reuse existing confirmation and
sorting safeguards. See [the project agenda](ROADMAP.md) for scope, delivery steps,
network/session concerns and acceptance checks. This is a planned addition, not
an implemented capability of 0.4.0.

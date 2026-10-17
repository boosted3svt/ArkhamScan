# Source, rights, and coverage register

This app is unofficial and is not affiliated with Fantasy Flight Games. Arkham
Horror: The Card Game and its card illustrations belong to their respective rights
holders. No permission to redistribute images is asserted here.

| Source | Role | Observed status | Supported use in this project |
| --- | --- | --- | --- |
| [Kamalisk/arkhamdb-json-data](https://github.com/Kamalisk/arkhamdb-json-data) | English card/pack facts and stable source codes | Read-only Git access succeeded; pinned audit commit `6f4760ebe91db212b54473db605771167d86c842`. No explicit repository license found in inspected tree. | Optional local converter input. No dataset embedded in APK or source. |
| [zzorba/arkhamdb-json-data](https://github.com/zzorba/arkhamdb-json-data) | Alternative maintained data repository | HEAD resolved to `4ac7e1640dd75862b5e29bb6f9b604dc2e555355`; guessed LICENSE path returned 404. Full content reconciliation not performed. | Candidate for later audit, not authoritative or bundled. |
| [ArkhamDB public API](https://arkhamdb.com/api/public/cards/) | Optional online metadata | Live public packs and encounter-inclusive cards responses audited on 2026-10-17: 115 products, 5,693 visible records. Earlier proxy restriction resolved; this does not establish redistribution terms. | Optional in-app download of public cards (`encounter=1`) and packs metadata. No credentials required; no images/rules cached. |
| Publisher product lists / printed manifests | Authoritative physical contents | Not independently audited here. | Required before a product can be called verified or complete. |
| Catalog illustrations | Visual recognition / manual reference display | Rights, stable URLs, versions, hashes, and offline permission unverified. | Not downloaded or displayed. No placeholder image is described as matched evidence. |
| Google ML Kit bundled Latin OCR | Offline text suggestions | Version 16.0.1; dependency terms apply. No accuracy benchmark on target cards. | In-memory OCR only. INTERNET now permits optional metadata refresh; the catalog downloader has no access to camera frames. |

Audit counts are not a certification of release coverage. Source pack `quantity`
fields are deliberately omitted by the converter: they have not been independently
validated as expected physical quantities for every historical retail format.
Source IDs remain distinct; same-title records are never merged into one printing.
Hidden pseudo-cards are excluded. Conflicting duplicate IDs fail conversion.

Runtime catalog replacement does not rewrite historical copies. Each confirmation
retains source, imported-file hash, printing ID, title snapshot, and confirmation
method. `referenceImageId` is null because this alpha performs no image matching;
OCR suggestions do not fabricate image provenance.

## v0.2 online reference behavior

The app requests `https://arkhamdb.com/api/public/packs/` and
`https://arkhamdb.com/api/public/cards/?encounter=1`. The endpoint shape and
encounter flag were checked against Kamalisk/arkhamdb’s public API controller and
routing source. Initially only names, printing codes, printed positions, pack identifiers,
types, and encounter identifiers were normalized. Version 0.4 adds the reference
fields below. Artwork URLs, image bytes, rules text and flavor text remain discarded.

**View card on ArkhamDB** uses a fixed HTTPS card-page URL and opens the external
browser, which displays the source website normally. The app does not make an
offline image cache. Metadata cache replacement is transactional and preserves
historical observations and provenance hashes. User-provided local catalog files
remain an optional alternative, not a prerequisite.

## v0.3 cycle / chapter grouping

Optional `cycle_position`, `chapter`, and pack `position` fields from the existing
public packs response determine cycle/chapter membership and a first-pack display
label. No additional endpoint or guessed title-to-product map is used. Source
category positions 50/60/61/70/80/90 (Return To, investigator decks, standalones,
promos, parallel) fall back to individual packs instead of grouping unrelated
products as one campaign. Missing metadata also falls back to pack identity. The
new family fields are retained in schema 2; old catalogs get empty defaults until
refresh. Historical copy snapshots and prior pile rules remain unchanged.

## v0.4 bulk reference discovery

The live audit fetched the existing packs and encounter-inclusive cards endpoints.
It inspected 6,048 top-level records, retaining 5,693 visible records and excluding
hidden/pseudo faces (including the linked reverse of a double-sided card). No live
responses are bundled in the app or committed as a catalog.

Positive card `quantity` values are retained as **provisional source-listed copy
targets**. Missing quantities stay unknown. Pack `known` and `total` are retained
as raw source facts but never used as a physical denominator: their units differ
between products and can disagree with visible records. Publisher manifests have
not been independently checked; 100% means all listed quantities accounted for.

`duplicate_of_code` supplies explicit equivalence links. Same titles alone never
merge records. Logical content coverage can credit an equivalent printing while
reporting exact-printing counts separately. Cycle targets collapse equivalent
printings using the maximum listed quantity per design, not the sum across
alternative packages. Views overlap and do not establish historical packaging.

Pack `available` provides the optional release cutoff. Explicit `reprint_packs`
and `reprint_type` provide repackaging membership, following ArkhamDB's public
server filtering: campaign requires encounter membership; player excludes it.
For example, The Dunwich Legacy Investigator Expansion has inherited design
membership but no direct card records. Old-pack quantities are **not** asserted
as that expansion's physical manifest: its view reports design coverage only.
Products without card records remain visible as reference gaps, not zero-card
complete sets. Encounter-set coverage does not establish scenario dependencies.

The optional `-PliveReferenceDir=/tmp` test audits downloaded responses named
`arkham-packs-live.json` and `arkham-cards-live.json`; routine tests use synthetic
fixtures and require no network. Download files yourself from the endpoints above
before running the optional audit. Metadata source hashes survive replacement in
historical copies; reference refreshes preserve ownership and reopen review.

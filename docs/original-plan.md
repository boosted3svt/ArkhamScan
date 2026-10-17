# Arkham Horror LCG Card Scanner — Android Project Plan

## 1. Objective
Build a native Android APK for a Samsung Galaxy S26 Ultra (One UI) that identifies physical Arkham Horror: The Card Game cards through the camera, speaks a short pile number, and maintains a verifiable collection inventory with completeness and missing-card reporting.

Primary use: mount the phone, show one card face to the camera at a time, hear a success sound and “Pile N,” then move to the next card without touching the screen.

## 2. Product principles
- Offline-first; no mandatory cloud account or subscription.
- Hands-free during normal rapid scanning, with a separate manual resolution workflow.
- Never silently inventory uncertain matches.
- Preserve non-image scan metadata, candidate matches, reference-image identity, and user corrections. Do not retain photographs or video frames from the user’s camera, including failed scans.
- Separate card identity, physical copy count, product/set membership, and physical sorting destination.
- Support repeatable inventory sessions and reversible corrections.
- Avoid claims of completeness unless the reference catalog and expected quantities are verified.

## 3. Research and licensing gate (before implementation)
- Verify current Arkham Horror LCG release catalog, printings, revised-core variants, campaign/investigator products, older deluxe/Mythos distribution, standalone scenarios, Return To products, promotional cards, and special editions.
- Evaluate ArkhamDB API, data dumps, card identifiers, images, update cadence, and license/attribution requirements. Do not assume redistribution or embedding card images is permitted.
- Evaluate other authoritative metadata sources and reconcile discrepancies. Document source provenance and last sync.
- Determine stable identity keys across reprints, parallel card designs, revised numbering, and products with overlapping content.
- Verify collector numbers, encounter-set icons, class symbols, scenario identifiers, and the visual information available on actual cards. Do not assume one printed symbol uniquely identifies a retail product.
- Obtain a small representative test set, including cards with near-identical artwork or text, alternate printings, and duplicates.

## 4. Proposed Android architecture
- Language: Kotlin.
- UI: Jetpack Compose, with large controls and accessible high-contrast scan feedback.
- Camera: CameraX image analysis and preview.
- Local persistence: Room over SQLite; schema migrations and export support.
- Recognition: staged on-device pipeline (card detection, perspective correction, OCR, visual matching, catalog validation). Benchmark ML Kit Text Recognition and/or suitable on-device image models before committing.
- Speech: Android TextToSpeech; short confirmation/error cues via low-latency audio playback. Test audio focus, Bluetooth output, volume, and One UI behavior.
- Background work: WorkManager for optional catalog synchronization, with offline cached snapshots.
- Data serialization: versioned JSON/CSV exports and backups.
- Optional future backend: metadata sync only if coverage or licensing makes a local catalog impractical.

## 5. Data model
### Reference catalog
- `CardDefinition`: stable internal ID, external source IDs, canonical title, card type, faction/class, encounter set, scenario associations, cycle/campaign associations, collector number, printed symbols, edition/printing, card face, and available image/hash references.
- `Product`: retail product ID, title, product type, release format, edition, and source provenance.
- `ProductContents`: product ID, card definition/printing ID, expected physical quantity, verification status.
- `Scenario`: ID, campaign, required encounter sets, and optional/conditional content.
- `EncounterSet`: ID, title, symbol, constituent card definitions, expected counts.
- `CardAlias/Printing`: alternate printed names, art, numbering, and cross-edition equivalences.

### User collection
- `InventorySession`: ID, timestamp, chosen catalog version, sorting scheme, notes, and state.
- `ObservedCopy`: unique observation ID, matched card/printing, session, timestamp, confidence, user verification, matched catalog reference-image ID/URL, image version/hash, catalog provenance, status (active/void/uncertain).
- `ScanAttempt`: time, recognition candidates and scores, result, image quality flags, and reason for rejection (metadata only, no captured image); unsuccessful attempts do not count as owned cards.
- `PileAssignment`: scheme version, pile number, human-readable destination, rule, and overflow/subpile rules.
- `InventoryAdjustment`: audited manual corrections, removals, merges, and quantity edits.
- `DuplicateDecision`: scan attempt ID, card identity, existing confirmed quantity, expected quantity (if verified), user choice (`NEW_COPY`, `REPEAT_SCAN`, `CANCEL`), timestamp, session/pass, and inventory transaction ID if created. In rapid mode the decision remains pending and the physical card is routed to Re-sort; no confirmation dialog interrupts scanning.
- `ResortQueue`: pending exception ID, session/pass, candidate card IDs, exception reason (`DUPLICATE_CONFIRMATION`, `LOW_CONFIDENCE`, `ILLEGIBLE_FIELD`, `AMBIGUOUS_PRODUCT`, etc.), metadata-only scan attempt, resolution status, and any linked confirmed inventory transaction. Queue records are not physical-card identifiers and cannot prove that a later camera presentation is the same card.
- `SortingPassLedger`: pass ID, source pile, card identity, quantity routed in this pass, and links to existing inventory counts; later sorting passes do not create new owned copies.

**Important:** Multiple physical copies of the same card must increment quantities; the system must not confuse repeated presentation of the same physical card with a new copy.

## 6. Recognition pipeline
1. Receive continuous camera frames while scanning mode is active.
2. Detect a card quadrilateral, crop, deskew, and assess focus, exposure, glare, occlusion, and card-face visibility.
3. Wait for a stable card view over a configurable short interval; use a best-quality frame or a small frame ensemble.
4. Extract candidate fields (title, number, set icon, encounter-set marker, other useful text) with OCR and visual matching.
5. Match candidates against the local catalog, applying field consistency, edition constraints, and expected-printing distinctions.
6. Accept only a high-confidence, unambiguous match; otherwise show/sound an unresolved status and retain candidate IDs and diagnostic metadata for manual review, but never save camera images.
7. Apply a mode-specific decision policy. In `RAPID_NEW_INVENTORY`, a first-time high-confidence, unambiguous identity may be committed once, followed by success ding and “Pile N.” A repeated identity, ambiguous retail provenance requiring a decision, or any other need for confirmation **must not open a prompt or pause the scan loop**. Play the exception cue and say “Re-sort”; do not increment confirmed ownership. The user physically places that card in the dedicated Re-sort pile. In `INTERACTIVE_RESOLUTION`, explicitly ask whether a repeated identity represents a new physical copy or an already-counted card, and allow correction of uncertain fields using fresh live recognition and catalog reference images. Only a confirmed `NEW_COPY` increments inventory. In `RESORT_EXISTING`, never increment ownership automatically; use pass ledgers and reconciliation against source-pile counts.
8. Require card removal/change before accepting the next scan. Use scene change, visual fingerprint, motion, and a rearm state to avoid double counting a stationary card.
9. A removal/re-presentation event is necessary to trigger another attempt, but is **not proof** of another physical copy. Repeat matches require explicit confirmation in new-inventory mode, even if the product expects multiple copies. Show `confirmed / expected` (when known) and allow legitimate extras beyond the manifest with an advisory, not a hard block. Confirmation must be idempotent and reversible.
10. A failed scan can be retried immediately after repositioning; every retry must run recognition afresh, not reuse a previous classification as truth.

### Scan state machine
`IDLE -> SEEK_CARD -> STABILIZING -> RECOGNIZING -> {FIRST_MATCH | REPEAT_MATCH | UNRESOLVED}`

`FIRST_MATCH -> COMMIT -> ANNOUNCE_PILE -> WAIT_FOR_REMOVAL -> SEEK_CARD`

`RAPID: REPEAT_MATCH | UNRESOLVED | NEEDS_DECISION -> QUEUE_EXCEPTION -> ANNOUNCE_RE_SORT -> WAIT_FOR_REMOVAL -> SEEK_CARD`

`INTERACTIVE: REPEAT_MATCH -> AWAIT_DUPLICATE_CONFIRMATION -> {CONFIRM_NEW_COPY -> COMMIT | ALREADY_COUNTED -> NO_COUNT | CANCEL -> REVIEW}`

- Rapid mode must never block on duplicate confirmation, missing/illegible fields, candidate selection, or product-provenance questions. All such cards are directed to the reserved Re-sort pile with a distinct gentle sound and spoken “Re-sort.”
- Re-sort is a **physical** pile; metadata-only exception records are provisional and may not be reliably associated with individual identical physical cards. Interactive mode processes the physical pile card by card and reconciles counts rather than assuming one queue entry equals one unique card.
- A stationary card triggers at most one audible instruction until removal/re-presentation; recognition on interactive retry uses fresh camera frames.
- Maintain a clear on-screen count of cards directed to Re-sort during the current pass, independently of confirmed inventory totals. If queue entries and physical cards disagree, request reconciliation instead of guessing.
- Repeated cards in rapid mode are never silently accepted as additional owned copies, even when the catalog expects multiples. This deliberately trades extra Re-sort handling for reliable hands-free throughput.
- Ensure failure, interruption, or process death cannot turn pending exceptions into owned copies.

## 7. Sorting system
- Pile numbers are session-configurable and stable during a session.
- Sorting goal: reconstruct original purchased retail products (including historical packaging distinctions), with subgroups only when needed for verification or physical packing. Do not use gameplay-ready groupings as the default.
- Exact pile boundaries are chosen algorithmically from the actual catalog/product manifest and the cards observed, subject to available table space; do not assume a fixed number of products or piles.
- Do not conflate a reusable encounter set with a single scenario or assume old and new retail packages are equivalent.
- When destinations exceed practical table capacity, implement optimized hierarchical passes, reusing physical pile numbers between passes. Show the pass-specific pile map and instructions for which existing pile to rescan next.
- A card with multiple valid retail-product associations must not be silently attributed to one purchased box. Track compatible products, uncertain provenance, and a provisional holding pile until other evidence (printing, packaging, product quantities, user selection) resolves it.
- Preserve every assignment rule and its version so a later change does not corrupt earlier inventory records.
- Allow piles to be renamed, merged, and renumbered with an audit trail.

### Adaptive pile planning (mandatory)
- Distinguish **logical destinations** (retail products), **physical piles** (numbered places on a table), and **sorting passes** (a sequence of scans using a temporary pile mapping). There might be hundreds of logical destinations, but the app must never require hundreds of simultaneous piles.
- Default to the smallest practical set of piles consistent with useful progress. Start with broad discriminating categories; subdivide only when observed inventory requires it. Avoid creating one pile per card, encounter set, or hypothetical product.
- Support a user-adjustable `max_simultaneous_piles`, including an unset/unknown state. When unset, propose a modest initial capacity during setup but make it editable without restarting the inventory. Do not hard-code an arbitrary number as the actual collection size.
- Compute pass plans from the available product taxonomy, estimated/observed counts, ambiguous product mappings, and space limits. Optimize for few piles, few passes, few total card re-scans, and balanced pile sizes. Explain tradeoffs in UI; avoid claiming mathematically optimal solutions without proof.
- Reserve a dedicated, consistently labeled `Re-sort` holding pile for **all** cases requiring user interaction, including duplicates, low confidence, illegible fields, and ambiguous product assignment; it is not a fictitious product. A single pile may represent multiple logical destinations during early passes.
- For each pass, persist immutable routing rules and a pass-specific pile map. Speak only `Pile N` for accepted cards; display the underlying logical destinations for inspection.
- A new pass must specify **which source pile to pick up**, which piles to clear/reuse, and how to preserve previously scanned inventory counts. Re-scanning an already-inventoried physical card during sorting **must not add another owned copy**.
- Support separate workflows for `Inventory new cards` and `Re-sort already inventoried cards`. For re-sort, track physical card identity where feasible; if identical copies cannot be distinguished, use a pass ledger and quantities rather than pretending to know individual identity.
- If a destination is known but the precise original purchased product cannot be inferred (e.g. identical cards sold in multiple product formats), explicitly report `retail provenance ambiguous`; allow a user-approved allocation based on expected product quantities, without asserting historical certainty.
- Include a dry-run simulator that estimates number of passes, peak simultaneous piles, and re-scan volume before the user begins. Replan after each pass as the actual collection distribution becomes known.
- Acceptance tests must cover 5, 50, and 500 logical destinations with limited physical pile capacity, including pass interruption, mixed editions, and ambiguous product origins.

## 8. Hands-free audio behavior
- Success: short positive ding, followed by TTS “Pile 1,” “Pile 2,” etc.
- Failure/unresolved: distinct, gentle tone; no pile spoken.
- In rapid mode, repeated identity or any other unresolved decision: distinct non-jarring cue and TTS “Re-sort,” without confirmation questions or success ding. In interactive mode only, ask “Already scanned. New copy?” and display current/expected counts. Over-expected quantities require explicit confirmation but are permitted.
- Prevent repeated audio for the same stationary card.
- Queue or cancel speech intelligently so fast card presentation does not create a backlog of pile instructions.
- Offer silent mode, speech rate, voice choice, cue volume, and vibration option.
- Confirm behavior when phone is locked, display dims, or app loses camera focus; design for foreground active-screen scanning unless proven otherwise.

## 9. Manual resolution workflow
- Enter `INTERACTIVE_RESOLUTION` to work through the physical Re-sort pile after a rapid pass; do not require a tap or voice response during the rapid pass itself.
- For each presented Re-sort card, run a **fresh** recognition attempt. Present the best catalog candidates and missing/uncertain fields; require explicit user action to resolve duplicate physical-copy status and ambiguous product assignment.
- On successful resolution, commit the appropriate inventory transaction exactly once, announce/display the newly assigned numbered pile, and mark any reliably linked exception resolved. Keep unresolved cards in Re-sort or an explicitly labeled deferred pile.
- Permit the user to mark an exception as already counted, unknown, or deferred. Do not assume matching candidate identity or temporal proximity proves a physical-card link.
- Review unresolved scan records as an inbox containing metadata and candidate reference-card images only; never persist photographs from the camera, even for unresolved scans.
- Re-scan live with manual shutter, flash/torch, tap-to-focus, and adjustable crop/rotation; process the captured frame in memory and discard it immediately after matching.
- Show top candidate matches with the **catalog reference images** (not captured scan images), if permitted, plus titles, symbols, numbers, and reasons for uncertainty.
- Allow typing/searching card name, printed number, encounter-set symbol, campaign, card type, and edition.
- Allow direct manual selection and quantity adjustment, with a user-verified flag.
- Permit unknown-card placeholders without incorrectly attributing them to a known product.
- Corrections update inventory and may improve local recognition hints, but must not silently change authoritative catalog entries.

## 10. Inventory and completeness reporting
- Dashboard: total physical copies, distinct card definitions, recognized/unresolved counts, scanned sessions, and over-expected copies.
- Drill-down: cycle/campaign, product, scenario, encounter set, card type, printing, and physical pile.
- Completeness calculation per verified scope: `sum(min(observed_quantity, expected_quantity)) / sum(expected_quantity)`.
- Show separate metrics for distinct designs versus physical-copy quantity.
- Show explicit missing cards with required counts and observed counts; flag excess quantities.
- Distinguish `unknown ownership`, `not scanned`, `confirmed missing`, and `unresolved` rather than treating everything unscanned as missing.
- Prevent double counting in overlapping product and scenario views; do not sum overlapping denominators into an overall percentage.
- Mark a product as `reference incomplete` if its full manifest is not validated.
- Export inventory and missing lists as CSV and JSON; optional shareable HTML/PDF report later.

## 11. User interface
- Home: Start Scan, Resume Session, Inventory, Missing Cards, Resolve Unknowns, Pile Map, Settings.
- Scan screen: full camera preview, high-contrast result banner, pile number, recognized card summary, recent scans, running total, pause/undo.
- Pile map: compact numbered destinations, with labels and optional printable dividers.
- Inventory: search, filters, counts, completeness progress, and expandable card details.
- Manual review: candidate list, editable extracted fields, and verification action.
- Setup wizard: choose catalog edition(s), original-product reconstruction goal, available sorting space or maximum simultaneous piles (optional), and audio settings. Camera-image retention is always disabled.

## 12. Performance and reliability requirements
- Target fast, repeatable card throughput; establish latency goals after benchmarking real devices and cards.
- No internet dependency for recognition once reference metadata/model assets are installed.
- Avoid overheating, excessive battery drain, memory growth, and camera stalls during long sessions.
- Transactional writes; no lost or duplicated counts on interruption, process death, or app restart.
- Handle landscape/portrait, lighting variation, sleeves, glare, and rotated cards.
- Use sensible confidence thresholds based on measured false-accept rate, not arbitrary hardcoded assumptions.
- Respect Android camera permissions and avoid uploading camera frames without explicit opt-in.

## 13. Verification and test plan
- Build a labeled reference dataset from legitimately sourced cards/images; track licenses.
- Test varied classes, campaigns, editions, alternate art, duplicates, sleeved cards, and similar titles.
- Measure top-1 accuracy, false-positive rate, unresolved rate, per-card processing latency, and cards/minute throughput.
- Test stationary-card suppression, remove/re-present behavior, retries, false successes, audio timing, and undo.
- Validate catalog manifests and expected counts independently before showing completeness percentages.
- Test airplane mode, low light, flashlight, app restart, interrupted database writes, export/import, and schema migration.
- Test specifically on the Galaxy S26 Ultra / One UI; also run emulator/unit tests for non-camera logic.

## 14. Implementation phases
### Phase 0 — Source feasibility and specimen audit
Research card catalog completeness, image access/licensing, editions, metadata identifiers, and card print layouts. Capture a sample of actual cards. Deliver a source matrix, coverage report, and technical risk register.

### Phase 1 — Data foundation
Build importer, normalized schema, local reference catalog, manifest verification, and unit tests. Add basic inventory and completeness queries.

### Phase 2 — Camera proof of concept
Create a minimal Android app with CameraX, card cropping, OCR/visual candidates, and a debug overlay. Benchmark with a representative sample before deciding whether to train or bundle an image model.

### Phase 3 — Hands-free scan MVP
Add recognition state machine, transactional copy counting, pile routing, ding/TTS, removal detection, session recovery, and undo.

### Phase 4 — Manual review and inventory UI
Add unresolved inbox, candidate confirmation, search, quantity adjustments, missing-card lists, and CSV/JSON export.

### Phase 5 — Reliability and release
Test catalog coverage, edge cases, performance, privacy, backup/restore, signing, and release APK generation.

## 15. Codex deliverables
- Android Studio/Gradle project with documented build instructions.
- Debug and release APK build configurations; user-owned signing key process (never commit signing credentials).
- Room schema and migration tests.
- Catalog ingestion scripts, validation reports, and attribution/license documentation.
- On-device recognition module with replaceable interfaces and measured benchmarks.
- Compose UI and accessible audio feedback.
- Unit, integration, and on-device instrumentation tests.
- README, architecture decision records, sample configuration, and reproducible build steps.
- No fabricated catalog records or guessed card/product associations.

## 16. Confirmed decisions and remaining questions
### Confirmed
- Target: Samsung Galaxy S26 Ultra / One UI, native Android APK.
- Primary outcome: reconstruct original purchased retail products, with completeness by product and lower-level set/scenario as applicable.
- Scanning: fully offline after catalog assets are installed; online catalog/image updates are optional.
- Recognition: strict, reject uncertainty; no speculative automatic inventory commits.
- Camera images: never retain any user-captured scan photographs or frames. Temporary in-memory processing is permitted.
- Reference imagery: store the stable identity, provenance, version/hash and retrievable URL/cache key of the **digital catalog image matched against**; display that image in card details where permitted. Offline image availability requires an authorized local reference-image cache; if unavailable, show metadata without substituting a camera photo.
- Sorting: adaptive multi-pass plan, no preset number of piles or assumption that all destinations require simultaneous physical piles.

### Remaining implementation questions (nonblocking unless otherwise noted)
1. Are all cards English-language, and should promos/alternate art/foreign-language printings be supported?
2. Will cards be sleeved, and is the camera mount/lighting arrangement fixed?
3. Is there a rough maximum number of piles the user can physically place at once, or should the app request this only at the start of each sorting session?
4. Should inventory support multiple named collections or a single collection on this device?
5. Should backup/export remain local-only or support optional user-selected cloud storage?

## 17. Additional image/reference and privacy requirements
- A successful scan must record the catalog card/printing ID, the matched reference image ID, its canonical source URL or provider identifier, source version and (where feasible) checksum; distinguish the exact image used for matching from an alternative illustration shown for display.
- Store reference images locally only when licensing and source terms permit. Cache metadata separately from image bytes, and ensure an offline match remains reproducible against the same catalog/model version.
- Reference images can be refreshed online, but changes must not silently rewrite historical scan match provenance. Show missing/retired image status without breaking inventory records.
- Do not save thumbnails, photos, debug crops, screenshots of camera frames, or failed-scan imagery. Disable diagnostic frame logging and avoid third-party analytics receiving frames. If manual review is postponed, retain candidate IDs/field text/confidence only; a new live scan is required to inspect the card again.
- Clear temporary image buffers after processing as practicable, and document any OS/camera framework buffering beyond application control.

## 18. Initial acceptance criteria
- The app can recognize a validated sample of cards with documented accuracy and identify uncertain cases rather than silently guessing.
- One stationary card is never counted twice automatically; deliberate removal and re-presentation of an already known identity triggers an explicit `New physical copy` versus `Already counted` decision before changing the inventory.
- A card expected three times can be confirmed as three physical copies across three presentations, and a fourth legitimate copy can be confirmed with an over-expected warning.
- Declining a repeated scan leaves the owned quantity unchanged, but may still route the card during a later sorting pass.
- A repeat-match prompt survives interruption without double commits; duplicate confirmations and undo operations are transactional and auditable.
- After successful recognition the user hears a ding and an intelligible pile number, without touching the screen.
- Failed scans give a different restrained cue and can be retried using new frames.
- Manual review resolves uncertain cards using candidate metadata and live re-scanning; no captured camera imagery is stored.
- Inventory quantities persist across restarts and are exportable.
- Verified retail-product manifests yield accurate missing/excess lists and completeness percentages, while ambiguous product provenance is explicitly labeled.
- A reproducible signed APK can be installed on the target Galaxy S26 Ultra.

## 19. Codex execution order and hard gates
1. **Catalog feasibility first:** produce a source/rights/coverage matrix for metadata, images, product manifests and historical printings; document unsupported content. Do not fabricate missing manifest data.
2. **Retail identity modeling:** prove that the schema can represent identical card faces sold in different retail products, and that completeness calculations do not infer original ownership from identical cards alone.
3. **Pile planner prototype:** implement and test a pure Kotlin planner/simulator before camera integration. Inputs: logical destinations, membership ambiguity, quantities, maximum simultaneous piles (optional), and pass history. Outputs: numbered physical piles, source-pile instructions, estimated workload, and immutable route versions.
4. **Reference-image proof:** validate that the chosen image catalog and matching method can identify real specimens on-device without internet; persist reference-image provenance, not camera photos.
5. **Camera MVP:** demonstrate strict match/reject behavior, removal detection, cue and spoken pile assignment, and no user interaction between successful scans.
6. **Inventory and pass ledger:** guarantee that later re-sort passes never inflate collection quantities; support undo and recovery after interruption.
7. **Manual recovery and reports:** implement fresh live re-scan, candidate reference images, search/selection, ambiguous-product reconciliation, and exportable missing lists.
8. **Release:** device testing, backup/import verification, APK signing and reproducible build instructions.

**Do not begin full-scale app implementation until catalog licensing/coverage, retail-product ambiguity, and multi-pass inventory counting have working proofs of concept.**

## 19. Rapid-scan / Re-sort acceptance criteria (mandatory)
- Scanning an already-confirmed card identity in rapid mode says “Re-sort” and continues without any modal, button press, or ownership increment.
- Low-confidence matches, unreadable numbers, ambiguous printings, and any decision-dependent cases use the same Re-sort route.
- Every accepted rapid scan produces one success cue and numbered pile instruction; every exception produces one distinct cue and “Re-sort.”
- In interactive mode, scanning a second legitimate physical copy and selecting `New physical copy` increases its count exactly once; selecting `Already counted` does not.
- A card that fails interactive recognition can be retried from fresh frames or identified through manually entered fields and catalog reference images. No camera photos are saved.
- Subsequent sorting passes cannot increase owned quantities; pending exceptions and completed decisions survive restart.
- Re-sort pile size, unresolved queue entries, and confirmed inventory are distinct counts, with explicit reconciliation for mismatches.

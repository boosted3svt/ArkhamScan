# Validation — 0.5.0 alpha (2026-10-17)

| Check | Result |
| --- | --- |
| Domain tests | 18 passed |
| Android / Room / API-parser / UI tests | 50 passed (including optional live-source audit) |
| Actual HTTP + desktop Chromium workflow | 1 passed separately |
| Python catalog converter | 2 passed |
| Debug / unsigned release APK builds | Passed |
| Debug lint / release vital lint | Passed with warnings, no errors |
| Debug APK signature | Verified; same signer as 0.1, 0.2, 0.3 and 0.4 for in-place updates |
| Database upgrade | Real schemas 1, 2 and 3 migrated to schema 4; inventory, review state, attempts and pile assignments retained |

Total: 71 automated tests passed, no failed/skipped/error cases.
Executed with JDK 21, Gradle 8.13, SDK 35/build tools 35.0.0:
`./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug -PliveReferenceDir=/tmp`
and `python3 -m unittest discover -s tools/tests -v`.
The separate actual-browser integration used `bash tools/test-remote-browser.sh`
with installed desktop Chromium and Playwright.

## Phone-hosted remote desk

The actual HTTP listener test verifies public packaged assets, pairing, rejected
foreign Origins and nonliteral Hosts, unauthorized access, exclusive controller
pairing, SSE under a browser-style Accept-Encoding header, confirmation retries,
and credential/code rotation on revocation. No CORS relaxation or global Android
cleartext-policy change is required.

Command tests verify exactly-one ownership across replay/new coordinators, stale
presentation and collection-revision rejection, no acknowledgment on rejected
mutations, active re-sort restrictions, ownership-neutral routing, camera blockers,
and browser visibility/editing of historical cards absent from a new catalog.
The real schema-3 upgrade test verifies review/inventory preservation, rollback of
both a quantity change and its command outcome, and successful outcome persistence
across a file-backed Room close/reopen.

A separate Chromium test connects to the actual Robolectric phone HTTP service.
It verifies incorrect/correct pairing, a simulated OCR observation arriving via
live SSE without a refresh, search and match selection, then deliberately drops
the HTTP response after a real confirmation commits. The browser resolves that
saved result without creating a second copy. It also exercises +/- corrections,
pile contents, reference-group details, permission-error reporting and diagnostic
downloads with no pairing secret. A real browser check caught gzip buffering of
small SSE events; SSE compression is now disabled and this case is covered.

Synthetic cards are test-only. These checks do not measure physical camera focus,
recognition quality, Wi-Fi performance or long-session thermal behavior on the
user's phone. The browser can request capture, but Android still controls camera
permission, foreground access and process lifetime. Backup restore, extra read-only
viewers and the Windows/Ubuntu relay are not implemented.

## Bulk collection discovery

The Compose workflow test uses a clearly synthetic 100-copy reference and 82
owned copies across two sessions. It verifies visible 82% / 82-of-100 progress,
18 not-yet-accounted entries, changing to potentially missing after finishing all
bins, and automatically reopening after a quantity correction. A second screen
test applies a release cutoff and checks that ownership stays at 82.

Domain tests cover per-card capping, extras, explicit equivalence, maximum rather
than summed variant quantities, repackaging membership without invented physical
totals, missing quantities, empty product references, malformed alias cycles,
cutoff eligibility, and encounter-set versus scenario readiness limitations.
Room tests cover cumulative sessions, persisted finish/cutoff state, concurrent
stale-finish rejection, reference refresh/count/attempt changes reopening review,
and sorting alone preserving review status.

The live audit checks hidden reverse-face exclusion, explicit revised/core Knife
equivalence with separate exact-printing counts, and Dunwich investigator
repackaging design coverage without an asserted physical denominator. Source pack
known/total values are not used as commensurate physical manifests. These tests
validate source interpretation; they do not independently certify retail contents.

## Reported camera flicker

CandidateLatch requires repeated observations, then freezes the complete candidate
list through empty/noisy frames, including its ordering. It clears on explicit
Next card/retry or successful confirmation. Stationary confirmed cards are
suppressed until removal/change or explicit retry. No recognition-confidence
calibration is inferred from a display latch. Raw fresh-frame candidates remain
separate for rapid-mode exception records. The Compose workflow test verifies a
held candidate survives a simulated OCR dropout and remains selectable.

## Quantity editing

Collection +/- edits are transactional, audited, retry-idempotent and bounded at
zero. Zero-count rows remain editable. Edits change ownership without generating
physical placement records. The end-to-end Compose test confirms one copy, checks
the visible Pile 1 result, increments ownership to 2, decrements to 1, and verifies
only one actual scan placement exists. Active re-sort passes block conflicting
quantity changes; UI explains how to finish/abandon them first.

## Live pile routing

Confirmation atomically commits the copy, stable group assignment, placement and
audit record. Retries cannot create another copy or placement. Bounded live maps
were tested through 500 groups at several capacities. Dedicated slots retain
their groups; additional groups use the last numbered mixed pile, with the final
physical position reserved for Re-sort. Settings/catalog changes cannot silently
alter a live map. Pack metadata supplies optional source cycle/chapter grouping;
broad standalone/promo/Return To/deck categories fall back to individual packs.

A follow-up pass snapshots only active copies actually placed in its selected
source pile. Tests verify unrelated inventory is excluded and ownership stays
unchanged. Starting a re-sort closes the prior live table layout. An explicit
fresh map on a cleared table is required before live inventory resumes. Historical
maps and source ledgers remain stored; no original purchased-box certainty or
mathematically optimal online assignment is claimed.

## Persistence and migration

Schemas 1, 2, 3 and 4 are exported in app/schemas. The migration test constructs the old
database from its actual exported schema, adds representative card/copy/exception
records, opens it with the new Room database and validates retained contents.
Room's own schema validation checks the upgraded tables. There is no destructive
migration fallback. UI fixtures are seeded before activity startup. Each test clears its ViewModel
and closes Room afterward. Activities bind ViewModel factories to their own
Application rather than a process-cached factory that can leak a different test
Application. Subsequent count edits exercise live flow updates and visible controls.

## Limits

No physical S26 Ultra camera/audio/thermal run or measured card-recognition accuracy
was performed in this cloud workspace. OCR remains suggestion-only, with explicit
copy confirmation. Independently verified retail completeness, scenario dependency readiness and
restorable backup are still unfinished. Live ArkhamDB access now succeeds; the
optional audit checked both actual endpoint responses (115 products, 5,693 visible
records). No phone API connectivity measurement is claimed. Optional metadata fetch and browser
reference links remain available; no camera images are stored or uploaded by the
catalog client. Camera frames are not retained to hold suggestions.

## Download checksums

- `downloads/ArkhamScan-0.1.0-alpha-debug.apk`: SHA-256 `b575d8e915c586957c65f09984659f0be8907c73d192d22f2bdbebba8cce5b75`
- `downloads/ArkhamScan-0.2.0-alpha-debug.apk`: SHA-256 `0b54f5566590debc1f3850e9f6c215a4402a9d7dcefdc4e185b4664b466008a0`
- `downloads/ArkhamScan-0.3.0-alpha-debug.apk`: SHA-256 `aa2d1ccd90c6ac6837498f2a5aae233ed4d1b483eddf54f90162879f1f5c371e`
- `downloads/ArkhamScan-0.4.0-alpha-debug.apk`: SHA-256 `81fc9d5839abf74d9d547358030439ee325c620a4e0aa66c738ffb9e528d45d7`

- `downloads/ArkhamScan-0.5.0-alpha-debug.apk`: SHA-256 `8ad7367aa60688a15bd757af1e7a7db6b63a1d07726d0cad7348165d856e7df6`

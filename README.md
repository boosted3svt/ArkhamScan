# ArkhamScan

A native Android inventory and sorting **alpha** for Arkham Horror: The Card Game.
Kotlin, Jetpack Compose, Room, CameraX, and bundled on-device Latin OCR. Android 8+
(API 26), targeting API 35. Intended target phone: Galaxy S26 Ultra; physical-device
validation has not yet been performed.

## Download the Android alpha

[Download ArkhamScan 0.5.1 alpha (debug APK)](https://github.com/boosted3svt/ArkhamScan/raw/refs/heads/main/downloads/ArkhamScan-0.5.1-alpha-debug.apk)

Transfer the APK to your phone and open it to install. Android may ask you to allow
installation from the browser or file manager you selected. This is a development
build, not a production release. Open **Overview → Download from ArkhamDB** to get started. No database file,
account, or API key is required. Automatic recognition acceptance remains disabled;
confirm physical copies in Interactive mode as described below.
The APK's SHA-256 is recorded in [downloads/SHA256SUMS](downloads/SHA256SUMS).

## What works

- Run an opt-in local HTTP browser desk: start/stop capture remotely, review and
  confirm matches, correct counts, inspect piles/progress, resolve attempts and
  recover from errors. Camera processing and inventory stay on the phone.
- Download card/pack metadata directly from ArkhamDB (including encounter cards),
  then search it offline. No file preparation is required. Local import is optional.
- Open a card’s live ArkhamDB page in your browser to inspect the reference artwork
  and details. Images are not downloaded or retained by this app.
- Hold camera suggestions through OCR dropouts until selection or **Next card / retry**.
- Explicitly confirm physical copies, including legitimate extras, and hear/see
  **Pile N** immediately. Copy confirmation and pile placement are one transaction.
- Adjust collection quantities with **+ / −**, including returning a zero-count
  printing to the collection. Every change is audited; retries cannot double-apply.
- Experimental live OCR offers title candidates. Rapid presentations queue metadata
  and say “Re-sort”; no uncertain recognition creates an owned copy or interrupts
  rapid scanning with a dialog.
- Review the physical Re-sort pile separately from pending metadata records.
- Grow a stable live pile map as new groups are scanned. Choose reference-pack or
  cycle/chapter grouping, with bounded mixed-pile overflow and a reserved Re-sort
  position. Split mixed/cycle piles in later count-preserving passes.
- Simulate bounded re-sort plans and freeze their source quantities, routing
  existing copies without changing ownership.
- Track cumulative source-listed progress by box/set, cycle, and encounter set; inspect
  outstanding cards, extras, exact printings and source-declared equivalents.
- Optionally limit targets by release date; mark the bins inventoried after scanning,
  with automatic reopening when counts or reference targets change.
- Export progress/outstanding CSV reports and collection CSV and versioned JSON including copy history, attempts, source
  hashes, sessions, pass maps, routes, and audit events.

**Automatic recognition acceptance is intentionally disabled.** No real-specimen
accuracy measurements have been established. Online metadata and website lookup
do not depend on implementing offline reference-image matching. Reference packs are
provisional sorting destinations, not verified retail manifests. Progress percentages are provisional source-listed coverage, not certified retail
completeness. Repackaged sets have design coverage where physical quantities are
unverified. Original-box provenance, scenario dependency readiness, backup restore,
and reference imagery are not implemented. JSON is an audit export, not a restorable backup. See
[plan review](docs/PLAN_REVIEW.md) and [source register](docs/SOURCES.md).

## Remote browser interface

Mount the phone, grant camera permission, and enable **Run web server** on Overview
or Settings. Open the displayed `http://IP:port` from a computer on the same network,
and click **Start scanning** in the browser. Leave the
phone app open and unlocked. Corrections and confirmation update its database.
See the [remote desk guide](docs/REMOTE.md) for controls and connection recovery.

The [project agenda](docs/ROADMAP.md) tracks the remaining relay, backup and device
validation work.

## Build

Install JDK 21, Android SDK platform 35 and build tools 35.0.0. Open this directory
in Android Studio or set `ANDROID_HOME` to your SDK. The Gradle wrapper pins Gradle
8.13 with a distribution checksum.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Install with
`adb install -r app/build/outputs/apk/debug/app-debug.apk` or transfer it to your
phone and explicitly allow installation from your chosen file manager. Debug APKs
use the standard local development signing key and are not production releases.

This cloud workspace additionally uses `source tools/cloud-env.sh` before Gradle.
The existing checkouts are already isolated; do not create Git worktrees for setup.
`tools/setup-cloud.sh` refreshes dependencies using an already-installed SDK/JDK.

## Get started with ArkhamDB

1. Install the **0.5.1 alpha** APK above over the previous alpha. It uses the same
   development signing key and migrates schemas 1, 2 or 3 to schema 4 without clearing your
   cards, copies, corrections, pending attempts, or saved pile placements.
2. With internet available, open **Overview** and tap **Download from ArkhamDB**.
   The app fetches ArkhamDB’s public packs and cards APIs, with encounter cards
   included, and saves the selected metadata locally. No account or API key needed.
3. In **Settings**, choose table capacity (including Re-sort). In **Piles**, choose
   reference-pack or cycle/chapter grouping. Refresh ArkhamDB once after upgrading
   to populate copy quantities, release dates and the reference index; old catalogs
   retain ownership but have unknown copy targets until refreshed.
4. Go to **Scan**. **Interactive** is selected by default. Search the title or use
   camera OCR for suggestions, then select the exact printing. **View card on
   ArkhamDB** opens its live website page in your browser for comparison.
5. Confirm **New physical copy** once for each physical copy you hold. The app
   displays and speaks **Pile N**; put that card in that numbered physical pile.
   Suggestions stay fixed while you read/tap. After confirmation, remove/change the
   card; **Next card / retry** explicitly clears held candidates for a fresh attempt.
6. Use **Collection** to adjust counts with **+ / −** and export. Edits update owned
   quantities only, not physical placements. A zero-count row remains available.
7. Use **Piles** to inspect the growing map and split a mixed/cycle pile later. Rapid mode currently routes every camera presentation to Re-sort;
   it is not yet the finished hands-free recognition workflow.

Open **Collection → Sets & cycles** (or **Overview → Explore sets & cycles**) to
track this bulk lot without purchase history. Counts accumulate across sessions.
Switch between boxes/sets, cycles and encounter sets; tap a group to inspect the
source-listed targets and outstanding cards. Extra copies of one card cannot fill
another card's gap. Equivalent printings credit logical content; exact-printing
counts remain separate. These overlapping views must not be summed together.

Leave the optional reference cutoff blank unless you know the lot's latest release
date. It changes targets, never deletes ownership. When all bins have been scanned,
choose **Mark bins fully inventoried**: outstanding entries become *potentially
missing*, not proven absent. New scans, quantity edits, unresolved attempts, target
changes and reference refreshes reopen review automatically. Export progress or
outstanding CSV for the selected group or current reference targets.

Use **Settings → Refresh from ArkhamDB** for later updates. Failed downloads keep
your previous catalog and ownership unchanged. Cached metadata supports offline
search/OCR suggestions; browser pages require internet. No images, rules text, or
camera frames are uploaded or saved by the metadata downloader. It makes public
HTTPS GET requests with timeouts, redirect checks and bounded response sizes.

If a refresh fails, the app displays the reason and offers retry through the same
button. Live cloud downloads were audited against 115 products and 5,693 visible card
records on 2026-10-17. Phone connectivity and camera behavior still require physical
device validation.

## Optional local reference import

No card database, images, or rules text are bundled. Establish that your intended
use of a source is permitted. Given a local ArkhamDB JSON checkout:

```sh
python3 tools/import_arkhamdb.py /path/to/arkhamdb-json-data catalog-local.json
```

Transfer that JSON to the phone and choose **Import a catalog file instead**. The
converter performs no network requests, omits artwork/rules/expected quantities,
rejects conflicting IDs, and refuses to overwrite an existing output. Local
conversion in this workspace produced 5,524 printings at the audited source
revision; the generated file is ignored by Git and is not embedded in the APK.

Format: `{"schemaVersion":1,"source":"provenance URI or identifier","cards":[...]}`.
Each card needs `id`, `title`, `pack`, `packName`; `number`, `type`, and `encounter`
are optional strings. Import replaces only the current reference catalog. Existing
copy history and catalog hashes survive. Imports are transactional, limited to
16 MiB / 50,000 cards, and reject duplicate IDs.

## Live sorting and bounded table space

The first confirmation starts a live map automatically. Groups get stable slots
in first-seen order. At capacity 5, piles 1–3 are dedicated groups, pile 4 is the
mixed-groups holding pile, and position 5 is Re-sort. Additional groups join the
mixed pile; old destinations are never silently renumbered or redistributed.
This online heuristic favors stable instructions and bounded space, not a claim
of globally optimal sorting before the collection distribution is known.

- **Retail reference pack** keeps source pack identities separate.
- **ArkhamDB cycle / chapter** combines packs sharing source cycle/chapter metadata.
  It falls back to packs if metadata is absent. Broad standalone, Return To, promo,
  and investigator-deck categories are not treated as one campaign. Cycle labels
  use the first source pack's name and are explicitly group labels.
- Membership in a source group is not proof of the exact historical box purchased;
  identical reprints can remain ambiguous. No complete-retail-manifest claim is made.

Capacity, grouping, and catalog version freeze when a map starts. To change them,
clear/set aside the existing table and choose **Start fresh live pile map**. Old
maps and placements remain in the audit export. A changed catalog requires a fresh
map so historical routing rules cannot silently change.

For a mixed or broad cycle pile, select **Split Pile N by reference pack**. Pick up
that specific source pile and set other physical piles aside before clearing and
reusing table positions. The app snapshots only the active copies placed in that
source pile. Route them in **Re-sort** mode without incrementing ownership. Further
subdivisions use the existing bounded pass planner. Starting a re-sort closes the
live table map because its positions are being reused. Finish or abandon the active
re-sort before new inventory confirmations or quantity edits. Before inventorying
new cards afterward, clear the table and explicitly start a fresh live map; this
prevents old pile numbers from silently referring to a different physical layout.

Cards counted before live sorting, and copies added via quantity editing, have no
automatically asserted physical placement. Use the separate existing-inventory
re-sort workflow for them. Physical reconciliation still matters: software cannot
tell identical copies apart or know whether you moved a card to its announced pile.

## Inventory safeguards

The camera never silently adds inventory in this alpha. Fully remove the card
between rapid presentations. Speech requires an installed offline English TTS
voice; install one in Android text-to-speech settings if reported missing.

Confirm **New physical copy** only for an additional owned card. **Cancel** adds
none. For a linked Re-sort attempt, **Already counted** resolves the attempt
without adding ownership; dismissing the dialog keeps it pending. Re-sort session
counts track instructions, not unique physical cards. Reconcile the physical pile
separately from pending records.

Saved sorting passes freeze their source counts and maps. Route only existing
copies, complete/reconcile the parent before subdividing groups, or abandon a
mismatched pass without changing ownership and physically reconcile its piles.

## Privacy and architecture

The APK uses internet for optional ArkhamDB metadata refresh and an opt-in local
HTTP remote desk. The desk sends metadata and accepts browser commands;
camera frames stay on the phone. Card reference pages
open in the user’s browser. Camera recognition stays on-device; frames stay in
CameraX/ML Kit memory and are
closed after OCR; no capture, crop, screenshot, thumbnail, or video write path is
provided. Candidate IDs, attempt reasons, and timestamps are retained. Android and
camera libraries may buffer frames outside application control. Screenshot
protection and disabled automatic OS backup reduce unintended frame/database
capture. Exports use Android's user-selected document picker.

- `core/`: platform-free routing policy, presentation gate, retail model,
  verified-only completeness, planner/simulator, and source-count ledger.
- `app/`: Compose screens, bundled OCR analyzer, Room database (schema v4),
  transactional decisions, audio, public ArkhamDB metadata download, optional local
  catalog import, browser reference links, and exports.
- `app/schemas/`: exported Room schemas 1, 2, 3 and 4. Real version-1, version-2 and version-3 database
  fixtures are migrated and checked for retained inventory, attempts and live piles.
- Tests: JVM domain tests and Robolectric tests exercising real Room transactions,
  persistence/reopen, catalog replacement, sorting, and import validation.

## Release signing

`./gradlew :app:assembleRelease` creates an **unsigned** release APK. The owner must
supply and securely back up their own signing key. Never commit keystores or
passwords. Use Android Studio's **Generate Signed Bundle / APK** flow, or `zipalign`
and `apksigner` from the Android build tools with interactive password entry. Keep
the same owner-held key for upgrades. No release key has been created here.

Before production use: resolve catalog/image rights, verify product manifests,
benchmark legitimate specimens, test camera/audio/privacy/thermal behavior on the
phone, add restorable backup and future upgrade migrations, and complete release testing.

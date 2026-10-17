# ArkhamScan

A native Android inventory and sorting **alpha** for Arkham Horror: The Card Game.
Kotlin, Jetpack Compose, Room, CameraX, and bundled on-device Latin OCR. Android 8+
(API 26), targeting API 35. Intended target phone: Galaxy S26 Ultra; physical-device
validation has not yet been performed.

## Download the Android alpha

[Download ArkhamScan 0.2.0 alpha (debug APK)](https://github.com/boosted3svt/ArkhamScan/raw/refs/heads/main/downloads/ArkhamScan-0.2.0-alpha-debug.apk)

Transfer the APK to your phone and open it to install. Android may ask you to allow
installation from the browser or file manager you selected. This is a development
build, not a production release. Open **Overview → Download from ArkhamDB** to get started. No database file,
account, or API key is required. Automatic recognition acceptance remains disabled;
confirm physical copies in Interactive mode as described below.
The APK's SHA-256 is recorded in [downloads/SHA256SUMS](downloads/SHA256SUMS).

## What works

- Download card/pack metadata directly from ArkhamDB (including encounter cards),
  then search it offline. No file preparation is required. Local import is optional.
- Open a card’s live ArkhamDB page in your browser to inspect the reference artwork
  and details. Images are not downloaded or retained by this app.
- Explicitly confirm physical copies, including legitimate extras; audited undo and
  idempotent transactions keep repeated actions from inflating ownership.
- Experimental live OCR offers title candidates. Rapid presentations queue metadata
  and say “Re-sort”; no uncertain recognition creates an owned copy or interrupts
  rapid scanning with a dialog.
- Review the physical Re-sort pile separately from pending metadata records.
- Simulate bounded pile plans, freeze source quantities, route existing copies
  without changing ownership, and subdivide groups in later saved passes.
- Export collection CSV and versioned JSON including copy history, attempts, source
  hashes, sessions, pass maps, routes, and audit events.

**Automatic recognition acceptance is intentionally disabled.** No real-specimen
accuracy measurements have been established. Online metadata and website lookup
do not depend on implementing offline reference-image matching. Reference packs are
provisional sorting destinations, not verified retail manifests. Missing-card
percentages, original-box provenance, backup restore, and reference imagery are not
implemented. JSON is an audit export, not a restorable backup. See
[plan review](docs/PLAN_REVIEW.md) and [source register](docs/SOURCES.md).

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

1. Install the **0.2.0 alpha** APK above over the first alpha; it uses the same
   development signing key and database schema. Keep your existing inventory.
2. With internet available, open **Overview** and tap **Download from ArkhamDB**.
   The app fetches ArkhamDB’s public packs and cards APIs, with encounter cards
   included, and saves the selected metadata locally. No account or API key needed.
3. Go to **Scan**. **Interactive** is selected by default. Search the title or use
   camera OCR for suggestions, then select the exact printing. **View card on
   ArkhamDB** opens its live website page in your browser for comparison.
4. Confirm **New physical copy** once for each physical copy you hold. Use
   **Collection** to check counts, undo errors, and export.
5. Use **Piles** after recording inventory to plan and perform count-preserving
   sorting passes. Rapid mode currently routes every camera presentation to Re-sort;
   it is not yet the finished hands-free recognition workflow.

Use **Settings → Refresh from ArkhamDB** for later updates. Failed downloads keep
your previous catalog and ownership unchanged. Cached metadata supports offline
search/OCR suggestions; browser pages require internet. No images, rules text, or
camera frames are uploaded or saved by the metadata downloader. It makes public
HTTPS GET requests with timeouts, redirect checks and bounded response sizes.

If a refresh fails, the app displays the reason and offers retry through the same
button. This cloud environment blocks live ArkhamDB traffic until its network
settings are applied; parser/persistence tests use API-shaped fixtures, so live
API connectivity on a phone is not claimed as verified.

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

The APK uses internet for optional ArkhamDB metadata refresh. Card reference pages
open in the user’s browser. Camera recognition stays on-device; frames stay in
CameraX/ML Kit memory and are
closed after OCR; no capture, crop, screenshot, thumbnail, or video write path is
provided. Candidate IDs, attempt reasons, and timestamps are retained. Android and
camera libraries may buffer frames outside application control. Screenshot
protection and disabled automatic OS backup reduce unintended frame/database
capture. Exports use Android's user-selected document picker.

- `core/`: platform-free routing policy, presentation gate, retail model,
  verified-only completeness, planner/simulator, and source-count ledger.
- `app/`: Compose screens, bundled OCR analyzer, Room database (schema v1),
  transactional decisions, audio, public ArkhamDB metadata download, optional local
  catalog import, browser reference links, and exports.
- `app/schemas/`: Room's generated schema for future migrations. This is the initial
  schema; no upgrade migration is claimed or tested yet.
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
phone, add restorable backup and upgrade migrations, and complete release testing.

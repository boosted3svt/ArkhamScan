# ArkhamScan

A native Android inventory and sorting **alpha** for Arkham Horror: The Card Game.
Kotlin, Jetpack Compose, Room, CameraX, and bundled on-device Latin OCR. Android 8+
(API 26), targeting API 35. Intended target phone: Galaxy S26 Ultra; physical-device
validation has not yet been performed.

## Download the Android alpha

[Download ArkhamScan 0.1.0 alpha (debug APK)](https://github.com/boosted3svt/ArkhamScan/raw/refs/heads/main/downloads/ArkhamScan-0.1.0-alpha-debug.apk)

Transfer the APK to your phone and open it to install. Android may ask you to allow
installation from the browser or file manager you selected. This is a development
build, not a production release. Automatic recognition is disabled; import an
authorized reference catalog and confirm physical copies manually as described below.
The APK's SHA-256 is recorded in [downloads/SHA256SUMS](downloads/SHA256SUMS).

## What works

- Import a versioned metadata-only local catalog; search titles, codes, numbers,
  encounter identifiers, types, and reference packs offline.
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
accuracy or reference-image rights have been established. Reference packs are
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

## Local reference import

No card database, images, or rules text are bundled. Establish that your intended
use of a source is permitted. Given a local ArkhamDB JSON checkout:

```sh
python3 tools/import_arkhamdb.py /path/to/arkhamdb-json-data catalog-local.json
```

Transfer that JSON to the phone and choose **Import reference catalog**. The
converter performs no network requests, omits artwork/rules/expected quantities,
rejects conflicting IDs, and refuses to overwrite an existing output. Local
conversion in this workspace produced 5,524 printings at the audited source
revision; the generated file is ignored by Git and is not embedded in the APK.

Format: `{"schemaVersion":1,"source":"provenance URI or identifier","cards":[...]}`.
Each card needs `id`, `title`, `pack`, `packName`; `number`, `type`, and `encounter`
are optional strings. Import replaces only the current reference catalog. Existing
copy history and catalog hashes survive. Imports are transactional, limited to
16 MiB / 50,000 cards, and reject duplicate IDs.

## Use the alpha

1. Import a reference catalog. In **Scan**, search or start experimental live OCR.
2. In rapid mode, stable text presentations go to a physical **Re-sort** pile.
   The camera never silently adds inventory. Fully remove the card between scans.
   Speech requires an installed offline English TTS voice; install one in Android
   text-to-speech settings if the app reports it missing.
3. In interactive mode, select the correct printing and confirm **New physical
   copy** only for an additional owned card. **Cancel** adds none. For a linked Re-sort attempt, **Already counted** resolves
   that attempt without adding ownership; dismissing the dialog keeps it pending.
4. Use **Collection** to inspect counts, remove mistaken copies, and export records.
5. Set your table capacity in **Settings**, then simulate/freeze a plan in **Piles**.
   Pick up its specified source pile, keep other piles separate, and route existing
   copies. Source-count exhaustion blocks further routes. Complete/reconcile the
   parent before subdividing its groups. Earlier pass maps remain inspectable.
   An interrupted or mismatched pass may be abandoned without changing ownership;
   physically reconcile its piles before starting again.

The Re-sort session count counts camera instructions, not physical uniqueness.
Pending records and ownership totals are separate. Identical cards cannot be
reliably linked to earlier attempt records; reconcile manually.

## Privacy and architecture

The APK has no internet permission. Frames stay in CameraX/ML Kit memory and are
closed after OCR; no capture, crop, screenshot, thumbnail, or video write path is
provided. Candidate IDs, attempt reasons, and timestamps are retained. Android and
camera libraries may buffer frames outside application control. Screenshot
protection and disabled automatic OS backup reduce unintended frame/database
capture. Exports use Android's user-selected document picker.

- `core/`: platform-free routing policy, presentation gate, retail model,
  verified-only completeness, planner/simulator, and source-count ledger.
- `app/`: Compose screens, bundled OCR analyzer, Room database (schema v1),
  transactional decisions, audio, catalog import, and exports.
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

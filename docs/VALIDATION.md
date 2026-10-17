# Validation — 0.2.0 alpha (2026-10-17)

Prepared with JDK 21, Gradle 8.13, Android SDK 35 and build tools 35.0.0.

| Check | Result |
| --- | --- |
| Domain tests | 6 passed |
| Android / Room / API-parser / UI tests | 23 passed |
| Python catalog converter | 2 passed |
| Debug APK build | Passed |
| Unsigned release APK build | Passed |
| Debug lint / release vital lint | Passed with warnings, no errors |
| Debug APK signature | Verified; same certificate as 0.1.0 for in-place updates |
| Network policy in merged manifest | INTERNET enabled for optional metadata refresh; cleartext disabled |
| Camera permissions / processing | Camera permission still optional; OCR stays on-device |

Total: 31 automated tests passed, no failed/skipped/error cases.

Executed `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
:app:assembleRelease :app:lintDebug` and the Python importer suite. Room's schema
remains version 1; no schema migration or destructive reset is required for this
update. Existing confirmation, concurrency, undo, recovery, ambiguity, bounded
planning (5/50/500 destinations), and count-preserving sorting tests still pass.
New tests cover metadata requests including encounter cards, filtering hidden
records, omission of illustrations/rules from cached data, provenance hashes,
malformed catalog rejection, fixed-host reference links, first-run online setup,
actual Room persistence after a simulated download, and keeping previous catalog
and ownership on refresh failure.

## Live service limitation

This cloud machine's proxy returns CONNECT 403 for arkhamdb.com. This is not a
measured response from ArkhamDB itself. The endpoint paths and encounter flag were
verified against Kamalisk/arkhamdb's public routing/controller source; API-shaped
fixtures exercise parser, UI, failure and persistence behavior. A real live API
response and physical-phone download have **not** been verified. The app reports
connection errors without clearing saved data. Domain additions are saved in the
environment draft; saving does not apply them to this running machine.

## Privacy and reference behavior

The catalog client performs public HTTPS GETs for card/pack metadata. It receives
JSON in memory and stores only selected factual fields and a provenance hash.
Artwork URLs/bytes and rules/flavor text are not retained. Reference-card links
open the source website in the external browser. The catalog client has no access
to CameraX frames; camera images are neither stored nor uploaded by it.

This replaces the 0.1.0 alpha's no-INTERNET configuration. Optional online catalog
refresh is now supported, with offline metadata caching; the user does not need to
create or supply a local database file.

## Still not established

No real-card recognition accuracy, S26 Ultra camera/audio/thermal benchmark,
reference-image matching, verified retail manifests, restorable backup, or
schema-upgrade migration is claimed. Automatic inventory acceptance stays disabled.
Interactive mode is now the default. Rapid OCR routes presentations to Re-sort.

## Download checksums

- `downloads/ArkhamScan-0.1.0-alpha-debug.apk`: SHA-256 `b575d8e915c586957c65f09984659f0be8907c73d192d22f2bdbebba8cce5b75`
- `downloads/ArkhamScan-0.2.0-alpha-debug.apk`: SHA-256 `0b54f5566590debc1f3850e9f6c215a4402a9d7dcefdc4e185b4664b466008a0`

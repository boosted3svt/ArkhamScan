# Validation — 2026-10-17

Prepared and tested in the cloud workspace with JDK 21, Gradle 8.13, Android SDK
platform 35, and build tools 35.0.0. Distribution archives were verified against
checksums supplied by the official Gradle/Android/JDK endpoints; TLS verification
was retained. Java uses the environment's trusted CA store and existing proxy.

## Executed checks

| Check | Result |
| --- | --- |
| Domain | 6 passed; 0 failed, skipped, or errors |
| Android / Room / UI | 14 passed; 0 failed, skipped, or errors |
| Python catalog converter | 2 passed |
| `bash tools/setup-cloud.sh` | Passed in the documented checkout; repeated successfully |
| `:app:assembleDebug` | Passed |
| `:app:lintDebug` | Passed with warnings; no errors |
| `:app:assembleRelease` | Passed; unsigned artifact requires owner signing before installation |
| APK debug signature | Verified using APK Signature Scheme v2 |
| APK permissions | CAMERA, VIBRATE, internal receiver permission; no INTERNET |

The 22 tests cover bounded planning for 5/50/500 destinations, ambiguity, dropout
suppression, conservative scan policy, ledger recovery, completeness gating,
transaction idempotency, concurrent retries, audited undo, extras, pending decisions,
file-backed database reopen, catalog provenance, source exhaustion, abandoned passes,
import validation, app launch, camera permission, and collection/scan navigation.
UI tests use isolated in-memory Room storage; separate tests verify real-file
persistence. Robolectric's paused main looper is explicitly advanced while waiting
for asynchronous startup. Application-scoped Room ownership survives activity
recreation without closing an in-flight connection manager.

Lint warnings include KAPT-vs-KSP advice, legacy backup-content configuration advice
(allowBackup is false and Android 12+ exclusions are explicit), launcher icon
configuration, and optional KTX style suggestions. No lint failures are suppressed.

## Artifacts

- `app/build/outputs/apk/debug/app-debug.apk`: SHA-256 `b575d8e915c586957c65f09984659f0be8907c73d192d22f2bdbebba8cce5b75`
- `app/build/outputs/apk/release/app-release-unsigned.apk`: SHA-256 `045dd081ca2192b4986140a14d931465e4ae9859aba4e74d4b4a5d70a2ed5c60`

The debug APK uses a local development signing key, not an owner-controlled release
identity. Source and generated Room schema accompany the downloadable debug APK. The
repository download is a development build, not an owner-signed production release.

## Not established by these checks

No emulator/physical camera specimen, airplane-mode device run, S26 Ultra audio or
thermal benchmark, real-card recognition accuracy, catalog illustration rights,
verified retail manifests, schema-upgrade migration, or backup restore was tested.
Automatic match acceptance is disabled. Camera OCR is suggestion-only; every rapid
presentation that passes the experimental text stability gate goes to Re-sort.
No production readiness or original-box completeness claim is made.

The environment draft saves install_script and start_skill; saving a draft does not
publish it or prove restoration in a new cloud task. Review/save the configuration
in environment settings and publish to retain the prepared environment snapshot.

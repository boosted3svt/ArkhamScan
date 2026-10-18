# Remote browser desk — 0.5.1 alpha

The phone hosts the interface over local HTTP. Camera/OCR, collection storage,
command results, audit and physical pile rules stay on the phone. No Windows or
Ubuntu installation is needed. Camera frames are not streamed or stored.

## Start a mounted-phone session

1. Install 0.5.1 over the previous alpha. Its signing certificate is unchanged;
   schema 4 adds a command-result journal without clearing existing inventory.
2. Open Scan and grant camera permission once if needed. Download/refresh the
   ArkhamDB index if you have not already done so.
3. On Overview or Settings, choose a local HTTP port (8080 by default) and enable
   **Run web server**. Put the phone on power, mount it, and leave ArkhamScan open
   and unlocked. The app keeps its screen awake while remote mode is enabled.
4. Connect the computer to the same network. Open the phone's displayed
   `http://IP:port` address. The desk opens directly, without a code, login or cookie.
5. Click **Start scanning** in the browser. Status progresses from starting to
   camera-ready/scanning, or explains the blocker. Preview is optional: the phone
   can remain on Overview while the browser controls capture. The native Scan
   screen still provides a local preview and tap-to-focus.
6. Present a card, review the held candidates (or search the catalog), select a
   match, optionally open its live ArkhamDB page, then **Confirm new physical copy**.
   Place the card in the announced pile. Remove it before the next presentation;
   use Next card/retry for an explicit retry. OCR never silently adds ownership.

Pause suspends capture; Stop camera releases capture resources. Both keep the
held decision and owned collection. Restart camera/OCR explicitly clears the old
selection; present any uncounted physical card again. Camera capture pauses when
the phone app is backgrounded/locked and can resume when it returns to the
foreground. A browser cannot grant Android permissions, unlock the phone, or
relaunch a killed phone process.

## Browser controls

- Scan: start/pause/resume/stop, next/retry, workflow selection, held candidates,
  paginated title/number/set/code search, match selection, latest OCR text and ArkhamDB links.
- Collection: paginated owned-card search, +/- quantities, recent-copy undo,
  collection CSV and JSON audit downloads. Historical ownership absent from a
  replacement catalog remains visible and editable. Reconcile physical piles
  after corrections; quantity edits do not assert a placement.
- Piles: live group quantities and pile contents, explicit fresh-map setup,
  source-pile or whole-collection re-sort planning, active-pass routing,
  completion and abandonment. Existing active-pass restrictions still apply.
- Sets & cycles: provisional box/cycle/encounter progress, per-card details,
  outstanding/extras, cutoff, finish/resume inventory and CSV reports. Equivalent
  printing coverage and source gaps have the same limits as the native app.
- Review & recovery: uncertain-attempt identification, already-counted/defer,
  camera/OCR restart, metadata retry, browser resynchronization, recent app errors,
  diagnostic downloads, next-map settings.

A linked uncertain attempt is a user-reviewed association, not evidence that it
represents a unique physical card. Cancel attempt review or Next card to clear it.

## Interrupted operations and recovery

Live state is delivered through same-origin Server-Sent Events. Latest OCR text
is limited to 400 characters and refreshed at most once per second; held matches
and command outcomes update immediately. Ownership snapshots are cached by the
persisted inventory revision to avoid reloading the collection on each OCR update. Reconnection
loads the phone's current snapshot rather than replaying old button presses.
The browser disables edits while disconnected. Lists are paginated in groups of
40; progress and inventory are not re-sent in full for each OCR update.

Every inventory/sorting mutation has a unique command ID. The database change and
its result commit in one Room transaction. Retrying that identical command returns
the saved result instead of adding another copy. Changed collection revisions and
old presentation IDs reject stale requests. Native confirmations also reject a
selection superseded by browser interaction.

If a response is lost, **Check saved result** retrieves the durable outcome. If
nothing committed, explicitly retry the same request or discard it. The browser
retains its interrupted request in session storage across reloads; it never
silently replays confirmations. The phone retains command outcomes across app
restarts. JSON audit exports include command IDs, hashes and saved results.

Errors appear with their details and recovery controls. Diagnostics contain app
version, capture/foreground state, recent errors and command IDs; they omit camera frames. An unavailable host cannot serve recovery commands:
if the app has stopped, reopen it on the phone and enable the server again.

## Local connection details

Use the literal IPv4 address and chosen port shown by the phone. Guest Wi-Fi/client
isolation, VPN routing, computer firewalls and separate networks can prevent access.
After changing Wi-Fi or IP address, stop/start the server to refresh its addresses.
A busy/invalid port is reported on the phone; choose another port before enabling.

HTTP is the requested private-network transport; certificate setup is unnecessary.
The desk is directly available while Run web server is on. Multiple browsers can
open it; revision checks and command IDs still prevent conflicting edits and
retries from silently duplicating ownership. Host/Origin checks and request/worker
limits run automatically. Turning off the server
also stops capture and removes browser access without deleting inventory. Outbound
ArkhamDB requests continue using HTTPS.

## Current limits and validation

The shipped interface uses packaged HTML/CSS/JavaScript and NanoHTTPD 2.3.1, with
SSE gzip disabled so small live events are delivered promptly. No external frontend
CDN is needed. HTTP-library notices are available from the desk footer and bundled
in the APK.

Automated checks cover the actual HTTP listener and a desktop Chromium session,
including a deliberately lost post-commit confirmation response. Permission errors
are exercised; successful physical camera capture and long mounted sessions still
need a real-phone test. No live camera feed is sent to the computer.

The Windows/Ubuntu relay, independent read-only viewers, background or
locked-screen capture, and restorable backups remain future work. JSON is an audit
export, not a restorable backup. The debug APK is still an alpha.

For the optional desktop integration check in this cloud environment, run
`bash tools/test-remote-browser.sh`. It uses installed Chromium/Playwright and an
actual Robolectric phone session with synthetic reference cards; it does not
contact ArkhamDB or depend on a physical camera.

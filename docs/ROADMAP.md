# Project agenda

## Remote browser control

Status: phone-hosted HTTP control implemented in 0.5.1 alpha. See the
[remote desk guide](REMOTE.md). The sections below retain the design agenda;
Windows/Ubuntu relay, extra read-only viewers, restorable backups
and physical-device validation remain open.

The phone sits in a mount facing the table. Camera capture and OCR stay on the
phone. A browser on another machine handles candidate selection, reference-page
lookup, confirmation, next/retry, collection editing and pile inspection, so the
operator need not touch or shake the mounted phone. This is remote interaction,
not automatic recognition acceptance.

### Preferred deployment: phone-hosted web interface

Add an explicit **Run web server** toggle. Show the local network address and
port using local HTTP and a clear Stop control. The address opens the desk
directly; pairing codes, login and controller leases are not required. HTTPS/certificate provisioning is not a prerequisite for
this private-LAN workflow, per the requested deployment.
The desktop browser connects directly to the phone; no Windows/Ubuntu software,
cloud account, or collection synchronization is required. The phone remains the
only authority for Room storage, source references, inventory, audit and routing.
The browser holds a temporary view, not an independent collection database.

Stream held candidate lists, current presentation identity, OCR/status metadata,
confirmation results, announced pile destinations, collection quantities, group
progress and pile contents. Metadata is sufficient for the first version: camera
frames and live video are not transmitted. Reference buttons open the card's live
ArkhamDB page in the desktop browser. Viewing that page does not itself confirm
identity or create ownership.

### Alternative deployment: Windows/Ubuntu listener

Keep this as a later option using the same command/event protocol. The phone
opens an authenticated connection to the listener, which serves the browser UI
and relays commands and state. The listener must not become a second writable
collection database. This can provide a stable hostname, conventional HTTPS and
multiple viewing stations, but introduces installation, pairing, firewall,
reconnection and server maintenance work. It is not required for the first release.

### Implementation requirements

- Serve packaged frontend assets from the phone, with a small local API
  and an ordered SSE stream plus command requests. Initial
  connection and reconnection obtain an authoritative snapshot and revision;
  events update that view. Paginate the full catalog and collection instead of
  sending every card on every OCR frame.
- Move shared commands out of screen-specific state into an application/session
  coordinator used by both Compose and the web API. Reuse the same Room
  transactions, source-pile restrictions, quantity safeguards and audit records.
  Do not expose raw SQL or arbitrary database/file access.
- Give each physical presentation an ID. Candidate lists remain held until
  explicit next/retry or confirmation. Selection/confirmation commands include
  that ID and expected state revision; stale commands are rejected. A successful
  confirmation adds exactly one copy and placement and returns its pile. The
  existing removal/retry gate still prevents a stationary card from being counted
  repeatedly. Uncertain OCR never silently increments ownership.
- Give every mutation a unique command ID, persist its outcome with the change,
  and acknowledge only after commit. A lost acknowledgment or double click must
  not add a second copy. Reconnecting clients resynchronize; they must not replay
  a queue of stale confirmations. Multiple browsers can use the desk; revision
  and presentation checks reject stale edits.
- Validate HTTP Host and browser Origin, protect against cross-site commands and
  DNS rebinding, and limit input sizes and command rates. These checks run without
  user setup. The phone's Run web server toggle controls availability; no pairing
  code, login, credential expiry or controller handover is required.
- Use HTTP and a same-origin browser interface with an unencrypted local event
  connection (`ws://` if WebSocket is selected). Direct LAN access is intended;
  TLS and certificate provisioning are outside the initial private-LAN scope.
  Existing outbound ArkhamDB requests continue using HTTPS.
- Provide browser **Start scanning**, **Pause**, **Resume**, and **Stop camera**
  controls. Starting the web server does not automatically start capture. Remote
  start is an explicit user action routed to the visible phone app's lifecycle
  coordinator; acknowledge success only once CameraX/OCR is actually ready.
  Display starting, scanning, paused, camera stopped, blocked and error states.
  Pause suspends recognition/acceptance; Stop releases camera resources. Neither
  operation adds ownership or silently confirms/discards a held presentation.
- Grant Android camera permission on the phone during initial setup. After that,
  the browser can enable capture while the app is foreground and unlocked; it
  cannot bypass Android permission prompts, unlock the phone, or guarantee
  launching a stopped app/background camera. Keep the active remote scanning
  screen awake. Report permission, lifecycle and camera-in-use blockers with
  specific instructions when phone-side action is unavoidable. Screen-off or
  background operation remains a separately validated future feature.
- Handle Wi-Fi changes, address changes, access-point client isolation, guest
  networks, VPNs and disconnected browsers. Show a reconnecting/stale indicator;
  keep inventory safe if the browser disappears. The native interface remains
  available. Stopping remote mode closes the listener and ends
  the remote session without deleting inventory.
- Use bounded queues, coalesce OCR/status updates and keep networking off camera
  and database critical paths. Measure OCR latency, battery use and temperature
  during a long mounted session on the target phone.

### Browser corrections and app recovery

Remote control includes routine troubleshooting, not just accepting card matches:

- Correct candidate selection, search/select another printing, undo an eligible
  last confirmation, adjust quantities, and review/defer/resolve uncertain scan
  attempts using the same inventory and sorting rules as the phone UI. Explain
  any physical pile reconciliation needed after a correction.
- Show a persistent status/error panel for camera/OCR, catalog connectivity,
  storage operations, sorting constraints, and remote connection health. Errors
  need a readable explanation, whether a command committed, and relevant recovery
  actions rather than an unexplained failure or automatic confirmation retry.
- Offer bounded recovery commands: retry a failed metadata download, restart the
  camera/OCR pipeline, resynchronize browser state, retry a known-uncommitted
  operation, and return to a usable scanning state. Preserve inventory and
  pending decisions. Pipeline restart invalidates the old presentation ID and
  explicitly clears its selection without creating a copy; any uncounted physical
  card must be presented again. An ambiguous commit is resolved by command ID
  before another mutation is attempted.
- Provide downloadable diagnostic information with app version, session state,
  recent errors and command IDs. Omit camera frames. Avoid
  adding arbitrary shell commands, database editing or destructive reset controls.
- Distinguish recoverable subsystem errors from process death: if the app or web
  listener has stopped, the browser can detect disconnection and give recovery
  guidance, but cannot repair its unavailable host. Android permission changes,
  unlocking and relaunching a killed app may require the phone. The native UI
  remains a fallback, not a requirement for routine remote correction.

### Proposed delivery sequence

1. Phone-hosted opt-in service and directly accessible browser dashboard; visible
   scanning app remains active. Verify network reachability and session lifecycle.
2. Remote camera start/pause/resume/stop, match selection, ArkhamDB links, confirm,
   next/retry, undo and immediate pile instructions through the shared command
   path. Include quantity editing, uncertain-attempt review, app status/errors,
   camera/OCR restart, metadata retry and diagnostics. Add detailed collection,
   pile and progress views using existing safeguards.
3. Download audit/CSV exports through an explicit browser action.
   Add a genuinely restorable, versioned backup with consistent snapshots,
   integrity checks and tested restore as a separate milestone. The current JSON
   export is not a restorable backup; copying a live Room SQLite file without its
   journal/WAL is not a safe backup procedure.
4. Consider the external Windows/Ubuntu listener only after the phone-hosted
   workflow is proven and an actual need for the second deployment exists.

### Acceptance checks

Demonstrate a full mounted-phone scan session controlled from desktop browsers
without phone touches after server startup and initial permission setup, including
starting camera capture from the browser. Check that denied permission, locked or
background app, camera-in-use and failed startup produce accurate browser states.
Exercise pause/resume/stop and pipeline restart without changing inventory, and
verify held/stale presentations are handled explicitly. Inject OCR, metadata and
transaction failures: recovery must preserve ownership, explain commit outcomes
and keep diagnostics free of credentials/frames. Simulate process death and show
reconnect/relaunch guidance rather than claiming the browser can restart its dead
host. Verify that duplicate/retried commands add
one copy, old presentation IDs cannot confirm a newer card, phone/browser edits
cannot bypass active sorting restrictions, and two clients cannot race ownership
changes. Disconnect before and after commits and confirm recovery from the phone's
authoritative state. Test code-free access, origin validation, network
changes and foreground/lock transitions. Existing inventory and pile routing must
survive app restart and upgrade. Any new backup feature must pass a real restore
round trip before being labeled a backup.

## Continuing work

- Independently verified physical product manifests and clearer source gaps.
- Scenario dependency/readiness guidance beyond encounter-set coverage.
- Physical-device OCR, camera, audio and long-session performance validation.
- Restorable backups and owner-controlled production signing.

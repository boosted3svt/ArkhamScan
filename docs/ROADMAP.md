# Project agenda

## Next proposed feature: remote browser control

Status: planned; no web server or remote control is implemented in 0.4.0.

The phone sits in a mount facing the table. Camera capture and OCR stay on the
phone. A browser on another machine handles candidate selection, reference-page
lookup, confirmation, next/retry, collection editing and pile inspection, so the
operator need not touch or shake the mounted phone. This is remote interaction,
not automatic recognition acceptance.

### Preferred deployment: phone-hosted web interface

Add an explicit **Run web server** toggle. Show the local network address and
port, a pairing link/QR code, connected clients, and a clear Stop/revoke control.
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

- Serve packaged frontend assets from the phone, with a small authenticated API
  and an ordered event stream (WebSocket, or SSE plus command requests). Initial
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
  a queue of stale confirmations. Use one active controller initially, with
  additional clients read-only and explicit control handover/revocation.
- Make pairing and authentication mandatory even on a LAN. Use expiring,
  revocable session credentials, validate HTTP Host and browser Origin, protect
  against CSRF/cross-site WebSocket requests and DNS rebinding, limit input sizes
  and command rates, and avoid permissive cross-origin access. Do not expose the
  service to the public internet or configure router port forwarding.
- Decide transport explicitly: plain HTTP on a trusted private LAN is easiest but
  leaves tokens and data exposed to peers able to observe that traffic. Pairing
  is not encryption. HTTPS certificate trust on an IP-addressed phone needs a
  usable provisioning approach; an optional server deployment or secure tunnel
  can simplify that later. Do not disable Android's global outbound HTTPS policy
  merely to add an inbound listener.
- Start camera capture from the visible app after permission and deliberate user
  action. The initial version requires the scanning screen to remain foreground
  and awake, preferably with the phone on power. A foreground service alone does
  not grant unrestricted background/locked-screen camera access on modern
  Android. Any screen-off/background mode needs separate lifecycle, permission,
  notification and physical-device validation.
- Handle Wi-Fi changes, address changes, access-point client isolation, guest
  networks, VPNs and disconnected browsers. Show a reconnecting/stale indicator;
  keep inventory safe if the browser disappears. The native interface remains
  available. Stopping remote mode closes the listener, revokes clients and ends
  the remote session without deleting inventory.
- Use bounded queues, coalesce OCR/status updates and keep networking off camera
  and database critical paths. Measure OCR latency, battery use and temperature
  during a long mounted session on the target phone.

### Proposed delivery sequence

1. Phone-hosted opt-in service, pairing and read-only browser dashboard; visible
   scanning app remains active. Verify network reachability and session lifecycle.
2. Remote match selection, ArkhamDB links, confirm, next/retry, undo, and immediate
   pile instructions through the shared transactional command path. Add quantity
   editing and detailed collection/pile/progress views using existing safeguards.
3. Download audit/CSV exports through an authenticated, explicit browser action.
   Add a genuinely restorable, versioned backup with consistent snapshots,
   integrity checks and tested restore as a separate milestone. The current JSON
   export is not a restorable backup; copying a live Room SQLite file without its
   journal/WAL is not a safe backup procedure.
4. Consider the external Windows/Ubuntu listener only after the phone-hosted
   workflow is proven and an actual need for the second deployment exists.

### Acceptance checks

Demonstrate a full mounted-phone scan session controlled from desktop browsers
without phone touches after startup. Verify that duplicate/retried commands add
one copy, old presentation IDs cannot confirm a newer card, phone/browser edits
cannot bypass active sorting restrictions, and two clients cannot race ownership
changes. Disconnect before and after commits and confirm recovery from the phone's
authoritative state. Test pairing rejection, revocation, origin validation, network
changes and foreground/lock transitions. Existing inventory and pile routing must
survive app restart and upgrade. Any new backup feature must pass a real restore
round trip before being labeled a backup.

## Continuing work

- Independently verified physical product manifests and clearer source gaps.
- Scenario dependency/readiness guidance beyond encounter-set coverage.
- Physical-device OCR, camera, audio and long-session performance validation.
- Restorable backups and owner-controlled production signing.

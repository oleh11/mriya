# Mriya roadmap

Mriya is privacy first: your data goes from your own Dawarich server to your phone and nowhere else. Nothing on this
roadmap may weaken that. If a feature needs a server of ours, an account, analytics or a Google service, it is out.

Target audience includes GrapheneOS, Proton and F-Droid users, so **no dependency on Google Play services or other
closed-source Google SDKs**.

## Done

- Dawarich sync (Bearer header, resumable checkpoints, WorkManager background sync, survives screen lock and app kill).
- Flight detection from location history (airplane-mode gaps, airport-aware, spike rejection, long-gap flights,
  realistic flight times, airport tiers so ORD wins over a nearby general-aviation field).
- Passport card, vyshyvanka stamps, offline native 3D globe, stats, year selector, share as image.
- Hardened storage: Keystore-encrypted API key, HTTPS only in release, no cloud backup, atomic saves.
- Flight import, all parsed on the device and reviewed by the user before anything is added:
  share to Mriya (text, `.ics`, PDF, screenshot), boarding-pass barcodes, calendar scan.
- Boarding-pass barcodes read with ZXing (open source, offline, no telemetry). Google ML Kit removed.
- Trophies, XP and ranks; shareable trophy cards (totals only, no places or dates).
- Remove a country you never visited (with its flights), undo, restore from Settings. Choices live in their own
  `userdata` file, keyed by stable flight keys (airports plus day), so they survive resyncs and re-detection.
- Storage lock is process-wide (ViewModel and SyncWorker use separate repositories).

## Next, in order

### 1. On-device AI fallback (optional, off by default)

For booking text the rules cannot read. Never runs automatically; the user taps "Try with AI".

- **Runtime:** llama.cpp (MIT) in a separate, optional Gradle module behind a small `AiReader` interface. CPU only.
  Needs NDK 27 and CMake 3.22 (installed on the dev machine).
- **Model:** a small open model (Qwen2.5 0.5B Instruct, 4-bit, about 400 MB) as the default light option. Verify the
  licence before shipping. Not bundled in the APK.
- **Model delivery, the user's choice, in Settings > On-device AI:**
  1. *Download in the app*: one tap, from a stated address, SHA-256 pinned. This reveals the user's IP to the host.
  2. *Load my own file*: pick a `.gguf` with the system file picker, same checksum check. No network at all.
  Plus a "Delete model" button.
- **No Google parts:** do not use Gemini Nano / AICore / ML Kit GenAI (absent on GrapheneOS).
- **Safety:** trim the text to the lines around airport codes, dates and flight numbers (a few thousand characters);
  redact emails, phone numbers, URLs and long digit strings; grammar-constrained JSON output; the model has no tools and
  cannot act. Every airport, date and route it returns is validated against the bundled airport list; invalid items are
  dropped. The review sheet stays, and AI-read flights are labelled and start unticked.
- **UX:** progress bar, Cancel button, frees memory afterwards. Expect tens of seconds on a recent phone (to be measured
  on a real device).
- **Tests:** fake model for the pipeline; hostile samples (hidden instructions, invented airport codes).

### 2. Read-only mailbox connector (IMAP)

A middle path between "share one email at a time" and an agent that drives the mail app.

- **Why not an agent:** Android sandboxing means the only way to operate another app's screen is an AccessibilityService,
  which can read and tap everything on the phone. Out of scope, and a 0.5B on-device model cannot run a reliable agent
  loop. A cloud agent would send screen contents to a server. Not doing either.
- **Gmail API:** shipped as an optional, read-only connection (see docs/GMAIL_SETUP.md). It needs a per-developer Google client id; a public build needs Google verification plus a yearly security assessment. Does nothing for Proton.
- **Design:**
  - The user enters host, port, username and an app password. Stored encrypted with the Keystore, like the Dawarich key.
  - Works with Gmail (2-step verification and an app password; some Workspace and Advanced Protection accounts will not
    allow it), Fastmail and any IMAP server. **Proton Mail works only through Proton Bridge**, which runs on the user's
    own computer or home server and is reached over the home network or Tailscale. It does not work from the phone alone.
  - TLS required (implicit TLS or enforced STARTTLS, never plaintext). Certificates validated by default. Bridge uses a
    self-signed certificate, so offer "trust this certificate" with the fingerprint shown and pinned.
  - **Read-only:** EXAMINE and BODY.PEEK only, so nothing is marked read, moved, flagged or deleted.
  - **Minimal reads:** search a chosen date range and a built-in list of airline senders and subjects, fetch only the
    matches, parse with the existing extractors (JSON-LD first, then the AI fallback if enabled), then discard the mail.
    Emails are never stored or uploaded; only the extracted flights are kept, after the user confirms them.
  - Runs only when the user taps Scan mailbox (optional periodic scan later). Progress and Cancel. A screen shows which
    folders will be scanned.
  - Client: evaluate angus-mail against a small purpose-built client (we only need LOGIN, SELECT/EXAMINE, SEARCH, FETCH);
    prefer the smaller, easier-to-audit option.
- **Tests:** in-process fake IMAP server for the protocol and TLS paths; the user tests against a real Gmail app password
  and Proton Bridge.
- **Parser lesson from Partiu (MIT, similar self-hosted project):** a generic "find a flight number and guess the rest"
  tier produced wrong legs on a real mailbox. Flights that come only from the plain-text tier must be labelled
  "unverified" and start unticked in the review sheet. Prefer structured sources (schema.org markup, boarding-pass
  barcodes, per-airline rules).

### 3. Release readiness (from the architecture review)

| Item | Why |
|---|---|
| ~~Separate **user data** from the rebuildable cache; stable flight keys~~ (done for hidden stamps, dismissals and seen trophies); still to do: versioned migrations | A format bump wipes the cache; user choices now survive |
| **Export / import backup file** the user controls | Cloud backup is off by design, so uninstalling erases imports |
| **String resources** and a text alternative to the globe for screen readers | All UI text is hard-coded today; no translation, weak accessibility |
| **Licence** (GPL-3 or AGPL-3 are typical for this audience), **privacy policy**, **release signing config**, **CI** (tests, lint, release build, dependency check) | Needed to publish |
| Optional **plain HTTP for private addresses** (explicit opt-in) and "trust my certificate" | Self-hosters on LAN or VPN |
| Keep one **backup** of the saved file and show an error instead of silently resetting | Corruption handling |

### 4. Structure

- Split the single ViewModel and the 440-line screens file by feature; one shared repository created at startup
  (manual DI, no framework).
- Move the data types the domain code uses into their own package so the domain stays pure Kotlin.
- Contract tests for the Dawarich API with recorded responses (including errors and rate limits); storage and worker tests.
- Extract the globe projection math so it is unit-testable; reduce-motion setting.

## Non-goals

- Any server run by us, accounts, analytics or crash reporting that leaves the device.
- Google Play services or closed-source Google SDKs.
- A cloud AI by default. A user-supplied cloud key is possible later, only with an exact "this is what will be sent"
  preview each time.
- An AccessibilityService "agent" that operates other apps.

## Prior art (GitHub, checked November 2026)

- **KDE Itinerary**: mature, privacy-focused Android app (F-Droid) that reads bookings and boarding passes. No Dawarich
  link, passport or globe.
- **Partiu** (MIT): self-hosted TripIt alternative scanning Gmail/IMAP; 19 airline parsers; useful reference.
- **AdventureLog** (GPL-3): self-hosted travel tracker with a visited-countries map; manual entry.
- **Dawarich** ecosystem: Home Assistant integration and a community Android tracker; nothing that detects flights from
  location history or builds a passport.

# Privacy Policy

Mriya is designed so your travel history stays on devices you control.

## Data collected by the developer

None. Mriya contains no analytics, crash reporting, advertising SDKs, telemetry, or closed-source Play Services libraries.

## Data stored on your device

All application files live inside Android app-private storage (`filesDir` and `SharedPreferences`) and are excluded from cloud backups and device transfers:

- **Dawarich connection settings (optional):** Server URL and API key (encrypted with an AES-256-GCM key in the Android Keystore). Sent strictly to the Dawarich server URL you configured using an `Authorization: Bearer` header by default.
- **Derived travel passport:** Country stamps, cities, detected flights, and hidden/dismissed/edited flight choices (`passport.json`, `userdata.json`, `imports.json`, and `takeout.json`, with `.bak` recovery copies).
- **Google Takeout import (optional):** Parsed locally via streaming JSON. Raw GPS coordinates are discarded immediately after deriving country stamps and flight legs.
- **Boarding-pass & screenshot import (optional):** Barcode decoding (ZXing) and text recognition for flight screenshots (open-source Tesseract) run 100% locally on your device. No images leave the phone.
- **Flight lookup (opt-in BYOK):** Off by default. If you configure an AeroDataBox or FlightAware API key in Settings (encrypted via Android Keystore) and tap **Look up**, Mriya sends only the selected flight number and date to fetch route, schedule, gate, and aircraft metadata.
- **Live ADS-B globe tracking (opt-in):** Off by default. When enabled and started by you, Mriya queries open community feeds (`adsb.lol` or `airplanes.live`) using only the flight callsign or aircraft tail number you enter.
- **Gmail search (optional, off unless built with a Google client id):** If you tap **Connect Gmail**, Google's own sign-in page opens in your browser and Mriya receives a read-only token (`gmail.readonly`), stored encrypted in the Android Keystore. Searches run only when you tap Search; matching emails are read and parsed on the phone and are not stored. Disconnecting deletes the token and asks Google to revoke it. Traffic goes only to Google sign-in and the Gmail API.
- **Passport holder identity & portrait (optional):** Stored only on this device as AES-256-GCM encrypted files (`profile.enc`, `profile_photo.enc`), never uploaded to any server.
- **Encrypted offline backup (optional):** Exported only when you explicitly create a backup file (`MRY1`), encrypted with your passphrase (PBKDF2-HMAC-SHA256 + AES-256-GCM). Dawarich and flight-lookup API credentials are never included in backups.

## Permissions

- `INTERNET`: Used solely to connect to your configured self-hosted Dawarich server, plus opt-in user-triggered flight lookups, live ADS-B tracking, or the optional Gmail search when enabled.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` / `POST_NOTIFICATIONS`: Keeps long sync jobs running reliably with a local progress notification.
- `READ_CALENDAR` (optional, requested only when scanning your calendar): Reads calendar events locally on the phone to find flight reservations.

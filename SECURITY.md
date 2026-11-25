# Security

## Cryptographic & storage protections

- **On-device secrets (`SecureStore`, `ProfileStore`):**
  - Dawarich API keys, optional flight-lookup API keys, holder identity JSON (`profile.enc`), and 512x512 square portraits (`profile_photo.enc`) are encrypted with AES-256-GCM (`AesGcmCodec`) backed by an Android Keystore key (`mriya_secrets_v1`).
  - Passwords/API keys use password keyboard hints and masked display by default.
- **Passphrase-encrypted backups (`BackupCrypto`):**
  - Backup archives use the `MRY1` envelope: `4B magic || 16B random salt || 4B PBKDF2 iteration count || 12B IV || AES-256-GCM ciphertext`.
  - Key derivation uses `PBKDF2WithHmacSHA256` with 210,000 iterations and zeroes intermediate password/key arrays after use.
  - Backups never include Dawarich or flight-lookup API credentials.
- **Network transport (`DawarichApi`, `FlightLookupApi`, `AdsbApi`):**
  - Release builds allow `https://` URLs only and disable cleartext HTTP via `network_security_config.xml`.
  - Requests authenticate with headers (`Authorization: Bearer <token>`, `X-RapidAPI-Key`, `x-apikey`) so API keys stay out of URLs and logs. Legacy Dawarich `?api_key=` fallback on HTTP 401/403 is off by default and must be explicitly enabled in Settings. Flight lookup and live ADS-B polling are strictly off until enabled by the user.
- **Untrusted local input hardening:**
  - Google Takeout `.zip` imports enforce entry-count, per-entry, and total decompressed size limits and reject path traversal (`..`).
  - Shared/picked flight PDFs, images, `.ics` files, and text are parsed offline inside app-private memory limits without executing scripts or following links.

## Reporting vulnerabilities

If you discover a security or privacy issue, please open a private security advisory or report it directly to the repository maintainer before public disclosure.

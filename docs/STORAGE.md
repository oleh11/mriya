# Mriya on-device storage & backup format

Mriya keeps all user data strictly in **app-private internal storage** (`Context.filesDir`, `Context.getSharedPreferences`, and temporary PNG share exports in `Context.cacheDir`). Nothing is written to shared external storage, and Android cloud backup and device-to-device transfer are disabled (`android:allowBackup="false"` and `res/xml/data_extraction_rules.xml`).

## App-private files

| Location | Purpose | Sensitivity / Encryption |
|---|---|---|
| `filesDir/passport.json` | Rebuildable Dawarich sync cache (`PassportData`, stamps, detected flights, stats) with `.bak` recovery copy. | Cleared by "Clear cache & full resync". |
| `filesDir/userdata.json` | User choices (`dismissedFlights`, `hiddenCountries`, `seenTrophies`, and `flightEdits` metadata overrides) with `.bak` recovery copy. | App-private; survives resyncs. |
| `filesDir/imports.json` | Flights added manually via share, boarding pass barcode, `.ics`, calendar, or manual dialog with `.bak` recovery copy. | App-private; survives resyncs. |
| `filesDir/takeout.json` | Derived stamps and flights from Google Takeout (raw location points are discarded) with `.bak` recovery copy. | App-private; survives resyncs. |
| `filesDir/profile.enc` | Optional `HolderProfile` JSON. | Encrypted with AndroidKeyStore AES-256-GCM (`mriya_secrets_v1`). |
| `filesDir/profile_photo.enc` | Optional 512×512 square portrait JPEG. | Encrypted with AndroidKeyStore AES-256-GCM (`mriya_secrets_v1`). |
| `shared_prefs/settings.xml` | Dawarich `serverUrl`, opt-in sync/query-auth/lookup/ADS-B preferences, and one-time notification prompt flag. | App-private. |
| `shared_prefs/secure.xml` | Dawarich API key (`api_key`) and optional flight lookup API key (`flight_lookup_api_key`). | Encrypted with AndroidKeyStore AES-256-GCM (`mriya_secrets_v1`). |
| `cacheDir/shared/*.png` | Latest temporary PNG exported to the Android share sheet via `FileProvider`. | Deleted/replaced on the next share. |

## Encrypted user backup (`MRY1`)

Because cloud backups are disabled by design, uninstalling the app deletes all app-private files. Users can export and restore a self-contained backup file (`*.mry`) from **Settings → Backup**:

- **Contents:** `userData`, `imports`, `takeout`, `profile`, and portrait photo bytes.
- **Excluded:** Dawarich `serverUrl` and `apiKey` are never exported.
- **Encryption:** Key derived from the user's passphrase (minimum 8 characters) using `PBKDF2WithHmacSHA256` (210,000 iterations, random 16-byte salt) and encrypted with `AES/GCM/NoPadding` (256-bit key, random 12-byte IV, 128-bit tag).
- **Binary layout:** `4B "MRY1" || 16B salt || 4B iterations (big-endian) || 12B IV || GCM ciphertext`.

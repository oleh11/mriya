# P1 Design Note: Consent-Based Passport Snapshot Sharing

## Goal
Allow a Mriya user to share a passport snapshot (countries visited, rank, totals, and optional holder card details like display name, portrait thumbnail, and decorative passport number) with trusted friends or family—without running any Mriya server or tracking anyone.

## Explicit Non-Goals
- **No live or continuous location tracking** of another person.
- **No GPS coordinates, timestamps, flight routes, or city lists** in shared payloads.
- **No accounts, servers, relays, or cloud sync** operated by Mriya.

## Opt-In Payload (`.mrypass` JSON / QR Code)
Before sharing, the owner sees a toggleable preview of every included field (nothing is sent implicitly):

| Field | Default | Notes |
|---|---|---|
| `displayName` | On (if profile set) | Given name + surname or custom nickname. |
| `passportNumber` | Optional | Decorative `MR#######` identifier only. |
| `photoThumbBase64` | Optional (`.mrypass` file only) | Small 128×128 JPEG thumbnail; omitted from QR codes for size. |
| `rank` & `xp` | On | Current rank title and XP total. |
| `countries` | On | List of ISO-2 codes and total day counts (no dates/cities). |
| `totals` | On | Total flight count, total km flown, unlocked trophy count. |
| `generatedAt` | On | Snapshot date so the recipient sees how recent it is. |

## Transport & Storage
1. **Export:** Owner taps **Share passport snapshot** → reviews exact preview → either:
   - displays an offline **QR code** (generated locally via ZXing, excluding portrait photo so it fits cleanly in a dense QR), or
   - exports a `.mrypass` JSON file through the Android share sheet (`FileProvider`).
2. **Import:** Recipient scans the QR code (via ZXing) or opens the `.mrypass` file with Mriya → reviews the snapshot → adds it to a **Friends** section inside the app.
3. **Updates & Revocation:** Because snapshots are static and offline, updates happen only when the owner exports a new snapshot. Removing a friend card deletes it locally; the owner stops sharing simply by not sending further snapshots.
4. **Storage:** Imported friend cards live strictly in app-private `filesDir/friends.json` (excluded from cloud backup and device transfer like all other Mriya data).

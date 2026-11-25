# Connecting Gmail (optional)

Mriya can search your Gmail for flight bookings from inside the app. It is **off unless you build it with your own Google
client id**, because Google issues those per developer and ties them to an app's package name and signing key.

## What the connection does

- Uses Google's own sign-in page in your browser (OAuth 2.0 with PKCE). Mriya never sees your Google password.
- Asks for one permission only: **read your email** (`gmail.readonly`). It cannot send, delete, label or change mail.
- Runs a Gmail search only when you tap **Search and find flights**. Matching emails are read on the phone, flights are
  extracted on the phone, and you review them before anything is saved. The emails themselves are not stored.
- The refresh token is stored encrypted with the Android Keystore. **Disconnect Gmail** deletes it and asks Google to
  revoke it. You can also remove Mriya at <https://myaccount.google.com/permissions>.
- Network traffic: Google sign-in (`accounts.google.com`, `oauth2.googleapis.com`) and the Gmail API
  (`gmail.googleapis.com`). Nothing goes anywhere else.
- No Google Play Services are needed, so it works on GrapheneOS and other de-Googled systems.

## Setup (about 10 minutes)

1. In the [Google Cloud console](https://console.cloud.google.com/), create a project (any name).
2. **APIs & Services → Library → Gmail API → Enable.**
3. **APIs & Services → OAuth consent screen**: user type **External**, fill in the app name and your email, and add the
   scope `https://www.googleapis.com/auth/gmail.readonly`.
4. **Publishing status.** While the app is in *Testing*, Google expires the sign-in after **7 days**. For personal use,
   choose **Publish app**. You will see an "unverified app" warning when you sign in (choose Advanced → continue), and the
   app is limited to 100 users, which is fine for yourself and friends. This is a Google rule, not an app limit.
5. **APIs & Services → Credentials → Create credentials → OAuth client ID → Android.**
   - Package name: `dev.mriya`
   - SHA-1 certificate fingerprint of the key you sign with:
     `keytool -list -v -keystore <your keystore> -alias <alias>`
     (debug builds use the debug keystore, release builds your release key; create one client per key.)
6. Copy the client id (it ends in `.apps.googleusercontent.com`) and give it to the build, either:
   - environment variable `MRIYA_GMAIL_CLIENT_ID`, or
   - a line `gmail.clientId=<id>` in the git-ignored `local.properties`.
7. Rebuild. **Add flights → Search my Gmail → Connect Gmail.**

## Notes

- **Public release.** Gmail read access is a restricted scope. An app offered to the general public needs Google's
  verification and a yearly third-party security assessment. Until then, each person builds with their own client id, or
  uses the share/paste routes, which need no sign-in.
- **Proton Mail and other providers.** This connects to Gmail only. Other mail apps can still share a booking email to
  Mriya, or paste its text.
- **Search syntax** is Gmail's own (`from:airline.com`, `after:2025/01/01`, `"confirmation code"`), so you can narrow
  or widen the default search.

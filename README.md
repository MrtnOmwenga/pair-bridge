# Pairbridge

A lightweight bidirectional file sharing system between a Linux laptop and an Android tablet over the local network. The laptop's shared folder shows up as a native storage location on Android — in Slack's, WhatsApp's, or any app's file picker — the same way Dropbox or Google Drive would.

## How it works

```
Laptop (Fedora)                        Android tablet
───────────────                        ──────────────
FastAPI server                    ←──  DocumentsProvider (SAF)
  - lists a shared folder                - shows "Laptop" in any file picker
  - serves file downloads                - lists/downloads over HTTP
  - accepts uploads                      - encrypted token storage (Keystore)
  - bearer-token auth
```

When you tap "Attach" in Slack, Android's file picker opens. "Laptop" appears in the sidebar. Tapping it lists the laptop's shared folder over HTTP; picking a file streams it to the tablet and hands it straight to Slack — no separate app, no manual copy step.

## Laptop server (`/laptop`)

A FastAPI app exposing:

- `GET  /health` — unauthenticated liveness check
- `GET  /files?path=` — list a directory
- `GET  /files/download?path=` — download a file
- `POST /files/upload?path=` — upload a file
- Bearer token required on every `/files/*` request

On first run it creates `~/.pairbridge/config.json` with a random token and a default shared root of `~/pairbridge`. Only files inside that folder are ever exposed — the server resolves every path against the shared root and rejects anything that escapes it (no `../../` traversal).

Run it:

```sh
cd laptop
python3 -m pip install --user -r requirements.txt
python3 server.py
```

It prints the shared root, port, and auth token on startup — you'll need the token to pair.

## Android app (`/android`)

Kotlin, built with Gradle. Key pieces:

- `PairingActivity` — one-time setup: enter the laptop's address/port and the token printed by the server, verified against `/health` and stored encrypted (Android Keystore via `EncryptedSharedPreferences`)
- `LaptopDocumentsProvider` — implements Android's `DocumentsProvider` interface (`queryRoots` / `queryChildDocuments` / `openDocument`). This is what makes "Laptop" appear in file pickers system-wide. Downloads stream through a pipe rather than fully buffering, so large files don't get loaded into memory first.
- `LaptopClient` — thin OkHttp wrapper around the server's endpoints; a single instance is cached and reused per pairing so repeated requests share one pooled connection instead of paying for a fresh TCP handshake each time.
- `CredentialStore` — encrypted storage for host/port/token
- `PairbridgeService` — foreground service placeholder for future reconnect/backoff logic

Build and install:

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If `adb install` fails with `INSTALL_FAILED_USER_RESTRICTED` on Xiaomi/HyperOS devices, push the APK and install it manually instead — the ADB-triggered install dialog on some HyperOS builds crashes itself before you can tap it:

```sh
adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/
# then open it from the Files app and tap Install
```

## Pairing

1. Start the laptop server; note the token it prints.
2. Open the app, enter the laptop's LAN IP, port (default `8765`), and the token.
3. Tap Pair — the app verifies it can reach `/health` and stores the credentials.

There's no PIN exchange or mDNS discovery yet (see Limitations) — pairing is manual, token-based.

## What's shared

Only what's inside the laptop's shared root (`~/pairbridge` by default) is visible to the tablet. To share other files, either move/symlink them into that folder, or change `shared_root` in `~/.pairbridge/config.json` and restart the server.

## Current limitations

- **Cleartext HTTP only**, scoped to trusted local networks (`android:usesCleartextTraffic="true"`) — there's no TLS.
- **No PIN pairing or mDNS discovery yet** — you enter the laptop's IP and the printed token by hand.
- **No reconnect/backoff logic** — `PairbridgeService` is a placeholder; each SAF call makes its own HTTP request independently.
- **Read-only from the tablet's perspective** — the server has an upload endpoint, but the Android app doesn't call it yet.

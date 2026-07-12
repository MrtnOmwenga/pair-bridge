# Pairbridge

A lightweight bidirectional file sharing system between a Linux laptop and an Android tablet over the local network.

- **Laptop → tablet**: the laptop's shared folders show up as a native storage location on Android — in Slack's, WhatsApp's, or any app's file picker — the same way Dropbox or Google Drive would. Fully working.
- **Tablet → laptop**: the tablet's own folders (Camera, Download, Pictures, Documents) are served over HTTP the same way, meant to be mounted on the laptop as a real folder via FUSE. Server-side code is complete and verified correct, but currently blocked by an unresolved HyperOS network restriction — see Limitations.

## How it works

```
Laptop (Fedora)                        Android tablet
───────────────                        ──────────────
FastAPI server                    ←──  DocumentsProvider (SAF)
  - lists configured shared folders      - shows each shared folder in any file picker
  - serves file downloads                - lists/downloads over HTTP
  - accepts uploads                      - encrypted token storage (Keystore)
  - bearer-token auth

FUSE mount (pairbridge_mount.py)  ──→  NanoHTTPD server (TabletFileServer)
  - mounts tablet folders locally        - serves Camera/Download/Pictures/Documents
  - reuses the same pairing token        - same token, same path-escape protection
```

When you tap "Attach" in Slack, Android's file picker opens. A single "Laptop" entry appears in the sidebar; opening it lists the laptop's shared folders (Downloads, Documents, ...) as subfolders. Picking a file streams it to the tablet and hands it straight to Slack — no separate app, no manual copy step.

## Laptop server (`/laptop`)

A FastAPI app exposing:

- `GET  /health` — unauthenticated liveness check
- `GET  /roots` — list the configured shared folders (names/ids only, never absolute paths)
- `GET  /files?root=&path=` — list a directory within a shared folder
- `GET  /files/stat?root=&path=` — metadata for a single file (used to resolve one document without listing its whole parent folder)
- `GET  /files/download?root=&path=` — download a file
- `POST /files/upload?root=&path=` — upload a file
- Bearer token required on every endpoint except `/health`

On first run it creates `~/.pairbridge/config.json` with a random token and seeds `shared_roots` with whichever of Downloads/Documents/Pictures/Videos exist in your home directory. Each root is an independent, named folder — the server resolves every path against the specific root it was addressed under and rejects anything that escapes it (no `../../` traversal), so a leaked token exposes only the folders you've explicitly shared, not your whole home directory.

To add or remove a shared folder, edit the `shared_roots` list in `~/.pairbridge/config.json` (each entry is `{"id", "name", "path"}`) and restart the server:

```json
{
  "shared_roots": [
    {"id": "downloads", "name": "Downloads", "path": "/home/you/Downloads"},
    {"id": "projects", "name": "Projects", "path": "/home/you/code"}
  ],
  "port": 8765,
  "token": "..."
}
```

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
- `LaptopDocumentsProvider` — implements Android's `DocumentsProvider` interface (`queryRoots` / `queryChildDocuments` / `openDocument` / `openDocumentThumbnail`). This is what makes "Laptop" appear in file pickers system-wide. Downloads stream through a pipe rather than fully buffering, so large files don't get loaded into memory first. Also generates real thumbnails (downsampled + cached) so pickers show correct previews instead of falling back to a full-resolution read.
- `LaptopClient` — thin OkHttp wrapper around the server's endpoints; a single instance is cached and reused per pairing so repeated requests share one pooled TCP connection. Runs three separate request queues (interactive stat/list/download, thumbnail fetches, background preload) so a burst of thumbnail or preload traffic can never queue in front of a request the user is actively waiting on.
- `PreloadCache` — on-disk cache keyed by `(root, path, size, mtime)`. When a folder is listed, files under `MAX_PRELOAD_BYTES` (10MB, see the constant in `LaptopDocumentsProvider`) download to local storage in the background; opening an already-cached file is served straight from disk with zero network calls. Because the cache key encodes the exact size/mtime seen at listing time, a changed file is detected (and re-fetched) the next time its folder is browsed — freshness is checked at browse time, not at open time, which is what avoids blocking a tap on the network.
- `CredentialStore` — encrypted storage for host/port/token, plus a `sharingEnabled` flag for the tablet→laptop server
- `PairbridgeService` — foreground service hosting `TabletFileServer` (see below)
- `TabletFileServer` — a NanoHTTPD server mirroring the laptop's endpoint shapes (`/roots`, `/files`, `/files/stat`, `/files/download`), scoped to the tablet's Camera/Download/Pictures/Documents folders, reusing the same pairing token

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

## Tablet → laptop (mounting the tablet's files)

The tablet can also serve its own folders back to the laptop. In the app, under "Share tablet files": grant "All files access" when prompted, then tap to start sharing — this starts `PairbridgeService`, which runs `TabletFileServer` on port 8766 using the same pairing token.

On the laptop, mount those folders as a real local directory:

```sh
cd laptop
python3 -m pip install --user requests
sudo dnf install fuse3-devel   # Debian/Ubuntu: libfuse3-dev
python3 -m pip install --user pyfuse3

# add to ~/.pairbridge/config.json: "tablet_host": "<tablet's LAN IP>"

python3 pairbridge_mount.py ~/pairbridge-tablet
# if pyfuse3 can't find libfuse3 at runtime:
LD_LIBRARY_PATH=/usr/lib64 python3 pairbridge_mount.py ~/pairbridge-tablet
```

**Known issue — not yet working over WiFi.** The tablet's server is verified correct (instant, correct responses over USB via `adb forward`), but connections to its WLAN IP hang indefinitely at the TCP level, even from the tablet connecting to itself. Ruled out so far: WLAN/background-data app permissions (both granted), battery restrictions (set to "No restrictions"), and IPv6-only binding (kernel dual-stack is enabled; `TabletFileServer` binds `0.0.0.0` explicitly regardless, as reasonable hygiene). The likely explanation is an undocumented HyperOS restriction on inbound connections to a third-party app's listening socket. `pairbridge_mount.py` itself has not been exercised end-to-end against a live tablet yet — verified independently: it imports cleanly, `PairbridgeFS` instantiates correctly as a `pyfuse3.Operations` subclass, and `TabletFileServer` was confirmed to serve requests correctly once reachable.

## Pairing

1. Start the laptop server; note the token it prints.
2. Open the app, enter the laptop's LAN IP, port (default `8765`), and the token.
3. Tap Pair — the app verifies it can reach `/health` and stores the credentials.

There's no PIN exchange or mDNS discovery yet (see Limitations) — pairing is manual, token-based.

## What's shared

Only the folders listed in `shared_roots` are visible to the tablet, and each is scoped independently — the app shows one sidebar entry per configured folder. This is the main safeguard against a leaked token: rather than trusting HTTPS (see below) to keep the connection private, exposure is capped to whatever folders you've explicitly opted in, which is why the defaults are Downloads/Documents/Pictures/Videos rather than the whole home directory.

## Current limitations

- **Cleartext HTTP only**, scoped to trusted local networks (`android:usesCleartextTraffic="true"`) — there's no TLS. The security model leans on network trust + token auth + the folder allowlist above rather than encryption; add TLS later if this ever needs to run over an untrusted network.
- **No PIN pairing or mDNS discovery yet** — you enter the laptop's IP and the printed token by hand.
- **No reconnect/backoff logic** — each SAF call makes its own HTTP request independently; nothing retries a dropped connection.
- **Laptop → tablet is read-only from the tablet's perspective** — the laptop server has an upload endpoint, but the Android app doesn't call it yet.
- **Folder management is laptop-side only** — add/remove shared folders by editing the laptop's config file; there's no admin UI on the tablet.
- **Cached files can be briefly stale** — a file preloaded/cached on the tablet is only checked for changes the next time its parent folder is browsed, not at the moment it's opened. A file edited on the laptop in between will serve the old cached copy until you re-browse that folder. This trade is deliberate: checking freshness at open time would mean a network round trip right when you tap, which is the exact delay the caching exists to avoid — see the note on WiFi radio wake latency below.
- **Occasional multi-second delay on first tap after idle** — confirmed (via matching timestamps between server and app logs) to be Android's WiFi radio powering down after a couple of seconds of inactivity; waking it back up for the next packet can cost 1-3s regardless of payload size, at the OS/driver level, outside the app's control. The preload cache sidesteps this for anything already browsed this session; a cold first request to an unvisited folder can still hit it.
- **Tablet → laptop doesn't work over WiFi yet** — see the "Tablet → laptop" section above; the server works correctly (verified over USB) but WLAN connections to it hang, likely a HyperOS-specific restriction not yet identified.

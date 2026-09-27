# Roadmap

Design notes for planned work, written before implementation so the architecture is settled
first. Every feature keeps to the rule the shipped ones follow: **the tablet only opens outbound
connections**, because HyperOS blocks inbound ones
([investigation](docs/hyperos-wifi-investigation.md)).

## Done

- Laptop → tablet file access in every Android file picker, with thumbnails and a preload cache
- Writing from the tablet: save, new folder, rename, delete
- "Send to laptop" from the share sheet
- QR pairing, and finding the PC again over mDNS when its address changes
- The `pairbridge` CLI: install as a service, pair, status, manage shared folders

## Next

### TLS with a pinned certificate

Traffic is plain HTTP today, so anyone on the same WiFi who captures the token gets read/write
access to the shared folders. Plan: the server generates a self-signed certificate on first run,
and the pairing QR code carries its SHA-256 fingerprint. The app pins that fingerprint in OkHttp
(`CertificatePinner` with a trust manager that accepts only the pinned key), so there is no CA
and no trust-on-first-use prompt. Manual pairing shows the fingerprint to compare.

### Clipboard drop zone

Explicit, on-demand clipboard exchange, not background sync: Android 10+ blocks background
clipboard reads, and there is no way around that for an ordinary app.

- **PC → tablet:** `pairbridge clip` reads the PC clipboard (`wl-paste` / `xclip`) and posts it
  to the server; the app fetches it when its clipboard screen opens and places it on the tablet
  clipboard, ready to paste.
- **Tablet → PC:** text pasted into a field in the app (or shared with "Send to laptop") is
  posted to the server, which writes it to the PC clipboard (`wl-copy` / `xclip`).

### Publish to PyPI

So installing is `pipx install pairbridge` instead of a git URL. Needs a release workflow and a
decision on versioning the app and the PC package together.

## Later

### Editing files in place

Apps that open a document in "rw" mode (in-place editors) can't save to the PC, because writes
stream through a pipe, which isn't seekable. Supporting it means staging a local copy, uploading
it when the app closes the file, and reporting a failed upload after the app has already moved
on (a notification).

### Notification syncing

Surface tablet notifications on the PC. The tablet reads its own notifications with
`NotificationListenerService` (the approach KDE Connect and Pushbullet use; the user grants
"Notification access" once) and pushes each one to the PC server, which shows it with
`notify-send`.

- **Presence-based routing, never suppression.** A notification always fires on the device it
  came from; it's additionally forwarded only when the other device looks active (screen on,
  recent input). The worst case is a duplicate, never a missed notification.
- **Duplicates from apps that notify on both devices** (calendar): an exclude list of packages
  that are never forwarded.
- Open question: forward full content (message previews can be sensitive) or only "new
  notification from Slack".

### Cross-device app triggers

Click an icon on the PC to open an app on the tablet. This can ride Android's own wireless
debugging (`adb connect`, then `adb shell am start -n <package>/<activity>`) rather than
Pairbridge's server. Caveat: some ROMs switch wireless debugging off after a reboot and change its
port, so the script has to fail with a clear message rather than hang. Worth a short spike on the
target tablet first.

### The tablet's own server

`TabletFileServer` and `pairbridge mount` exist for tablet → PC browsing, but HyperOS blocks
them and "Send to laptop" covers the main need. They also require the broad "All files access"
permission and depend on NanoHTTPD, which is no longer maintained. Decide whether to fix this
path on other devices or remove it.

## Out of scope

- **Screen mirroring:** [scrcpy](https://github.com/Genymobile/scrcpy) already does it well.
- **Reminders and other single-device tools:** not cross-device features.

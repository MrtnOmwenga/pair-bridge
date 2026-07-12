# Roadmap

Design notes for planned features, written before implementation so the architecture is settled first. None of what's below is built yet.

---

## Notification syncing

**Goal:** surface tablet notifications on the laptop (and vice versa), without duplicating notifications that already fire natively on both devices (e.g. calendar), and without hiding something important because of a wrong guess about which device you're using.

### Direction

The tablet reads its own notifications via Android's `NotificationListenerService` API (a real, first-party API — this is how Pushbullet/KDE Connect/Join do it; requires the user to grant "Notification access" once in Settings). It then **pushes** each notification to the laptop's existing FastAPI server as an outbound HTTP POST.

This deliberately avoids the tablet needing to accept an inbound connection — see [[feedback-pairbridge-hyperos-constraints]] for why that direction is currently unreliable on this tablet. The laptop side just needs a small listener that receives the POST and shows a native desktop notification (`notify-send` or similar) plus a system tray/AppIndicator icon for a running-in-background presence.

### Presence-based routing

Each device reports a lightweight heartbeat to the laptop's server: `{device: "tablet"|"laptop", active: bool, last_input_ts: ...}`, where `active` is derived from screen-on state + recent input. There's no reliable way to distinguish "half-watching a show" from "actively working" — any heuristic here will sometimes be wrong.

Design principle: **never suppress, only add.** A notification always fires natively on the device it originated on (free, zero risk of hiding something). It's *additionally* forwarded to the other device only when that device's heartbeat looks active. Worst case is a redundant ping, not a missed notification.

### Avoiding calendar (and similar) double-notification

The duplicate isn't caused by our forwarding — it's the same calendar account notifying natively on both devices already. Two options, not mutually exclusive:

1. **Zero-code fix:** turn off calendar notifications on whichever device you check less. Recommended as the default answer.
2. **Automatic:** maintain an exclude-list of app packages (calendar apps, and anything else that's known to already notify on both devices) that the forwarder simply never forwards, since forwarding would be pure duplication. Cheap to build once (1) proves insufficient.

### Open questions for implementation

- Notification content sometimes contains sensitive info (message previews) — decide whether to forward full content or just "you have a notification from Slack, go check" style summaries.
- Need a small persistent laptop-side process (tray icon) — decide GTK/Qt/whatever fits the desktop environment.

---

## Clipboard drop zone (scoped-down, intentional sync)

**Goal:** explicitly push clipboard content between devices on demand — not a transparent background sync (Android blocks background clipboard *reads* since Android 10 specifically to prevent silent clipboard-sniffing by apps; there's no way around this for an unprivileged app). The scoped-down version sidesteps that restriction entirely:

### Laptop → tablet

1. Laptop reads its own clipboard on demand (unrestricted) when you trigger a "push" action, and POSTs it to its own server's `/clipboard` endpoint (in-process, trivial).
2. Tablet **polls** `/clipboard` (outbound from tablet — the safe, proven direction) when you open the app's clipboard screen, or on a lightweight periodic poll if that turns out to be reliable in the background.
3. On new content, the app writes it into the tablet's system clipboard (`ClipboardManager.setPrimaryClip()` — writing from a foreground-ish context is fine, no special permission needed).
4. You paste manually (Ctrl+V equivalent) on the tablet.

### Tablet → laptop

1. You explicitly paste into a text field **inside the Pairbridge app** (a normal foreground text field — this deliberately avoids ever reading Android's system clipboard programmatically, which is the part that's restricted).
2. The app POSTs that text to the laptop's server (outbound from tablet again).
3. A small laptop-side listener writes it into the laptop's clipboard (`wl-copy` on Wayland / `xclip -selection clipboard` on X11 — unrestricted).

Neither direction needs the tablet to accept an inbound connection, so this works regardless of whether the HyperOS restriction ([[feedback-pairbridge-hyperos-constraints]]) ever gets resolved.

---

## Cross-device app triggers (desktop icon → open an app on the tablet)

**Goal:** click an icon on the laptop desktop, have it open a specific app (Slack, the notes app, ...) on the tablet.

**Feasible, and doesn't touch our custom server at all** — it rides Android's own ADB, not our HTTP infrastructure:

```sh
adb shell am start -n com.Slack/<launch-activity>
```

For this to work without a USB cable, the tablet needs **Wireless debugging** enabled (Settings → Developer options → Wireless debugging, Android 11+ — not the older `adb tcpip` approach, which needs USB to bootstrap every session). Wireless debugging pairs once via a 6-digit code (`adb pair <ip>:<port>`), and the pairing (trusted key) persists — reconnecting afterward is just `adb connect <ip>:<port>`, no cable needed. As a first-party Android feature (not a third-party app's listening socket), it's plausibly unaffected by whatever HyperOS restriction is blocking our own server, though that's not yet confirmed empirically.

**Caveat, honestly:** some ROMs turn Wireless debugging back off after a reboot as a security default (pairing survives, but the toggle itself may need re-enabling, and the port can change each time it's re-enabled). A desktop-icon script would attempt `adb connect` automatically and fail gracefully with a clear message ("enable Wireless debugging on the tablet") rather than hang — this isn't a fully invisible, zero-maintenance flow, but it's a real, buildable one.

**Status:** not started. Confirmed feasible in principle; worth a short spike to verify Wireless debugging actually survives this specific tablet's reboot/security behavior before building the desktop-icon tooling around it.

---

## Explicitly out of scope for this project (for now)

- **Hourly note-check reminder** — this is really a personal reminder/cron tool, not a cross-device sync feature; better as its own small unrelated script.
- **Screen mirroring / casting the tablet** — [`scrcpy`](https://github.com/Genymobile/scrcpy) already solves this well; no need to build our own.

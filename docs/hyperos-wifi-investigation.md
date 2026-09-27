# Investigation: the tablet can't accept connections over WiFi (HyperOS)

**Status:** unresolved. Pairbridge works around it: every feature that ships only needs the tablet
to make outbound connections (see [Design](../README.md#design)). This page records the
investigation so it isn't repeated.

## Symptom

`pairbridge mount` reads the tablet's folders from a small HTTP server inside the app
(`TabletFileServer`, port 8766). On a Xiaomi Pad running HyperOS (Android 16):

- Requests over USB (`adb forward tcp:8766 tcp:8766`) get instant, correct responses, so the
  server itself works.
- Connections to the tablet's WiFi address hang at the TCP level, even when the tablet connects
  to its own WiFi address. Logcat shows no denial or error of any kind.

## Ruled out

| Hypothesis | Test | Result |
|---|---|---|
| App permissions for WLAN / background data | Both granted in app settings | No change |
| Battery restrictions | Set to "No restrictions" | No change |
| Server bound to IPv6 only | Kernel dual-stack confirmed; server binds `0.0.0.0` explicitly | No change |
| Android 16 local-network permission | `NEARBY_WIFI_DEVICES` declared, `granted=true` in `dumpsys package` | No change |
| Android 13+ Restricted Settings for sideloaded apps | Allowed via Settings → Apps → Pairbridge → ⋮ | No change |
| A firewall on the network path | `nc -l -p 9999` started from `adb shell` was reachable from the laptop over the same WiFi | Reachable |

The last test is the useful one: a listener running as the `shell` user is reachable, so the
network path is fine and the block is specific to how HyperOS treats a regular app's listening
socket.

## Remaining leads

- The app was sideloaded through `com.google.android.packageinstaller`, not installed from a
  store. The two sideloading-related mechanisms above were tried without effect, but installer
  provenance hasn't been fully eliminated.
- Root-level diagnostics (iptables/nftables rules per UID) would likely show the drop; not
  attempted on a stock device.

## What Pairbridge does instead

Laptop → tablet access and "Send to laptop" both use connections the tablet opens to the laptop,
which HyperOS allows. The tablet server and `pairbridge mount` stay in the code as an
experimental path for devices without this restriction; the mount has only been verified up to
the point of the unreachable socket.

# BrainRotGuard TV Kiosk

A minimal Android TV / Google TV WebView kiosk for [BrainRotGuard](https://github.com/GHJJ123/brainrotguard) — the self-hosted YouTube approval system for kids.

It is **not** a native client. It's a fullscreen, D-pad friendly WebView shell that loads your existing BrainRotGuard server. All logic (search, requests, approvals, time limits) stays server-side, so this app stays trivial and almost never needs updating.

## Features

- Fullscreen, immersive, no URL bar, no escape hatches
- Auto-starts on TV boot (if configured)
- Screen stays awake while the app is open
- Approved videos auto-start (media playback without user gesture enabled)
- Offline screen with Retry if the server is unreachable
- First-run setup screen to enter the server URL
- Remote-friendly: D-pad **MENU** button reopens the URL editor; **BACK** navigates in-app, never exits

## Build

Prerequisites: Android Studio (or JDK 17 + Android SDK with platform 35).

```bash
# in Android Studio: File > Open this directory, then Build > Make Project
# or on the CLI:
./gradlew assembleDebug
```

The output APK is at `app/build/outputs/apk/debug/app-debug.apk`.

> `gradle wrapper` must be run once (Android Studio does this automatically) to generate the wrapper JAR.

## Install on your Google TV

See **[docs/install.md](docs/install.md)** for all installation methods (USB stick, ADB, server download, app stores). Quick version with ADB:

Enable Developer options + USB/Network debugging on the TV (Settings → Device Preferences → About → click Build 7×), then:

```bash
adb connect <tv-ip>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

First launch: enter your BrainRotGuard server URL (e.g. `http://192.168.1.50:8080`). Done.

## Lock down the TV (important)

The kiosk is only one layer. On the TV itself:

1. **Disable the YouTube app** (Settings → Apps → YouTube → Disable)
2. **Disable other browsers** and the Play Store if the kid can reach them
3. Consider a TV launcher that hides everything except allowed apps
4. Set a **PIN** on BrainRotGuard itself (`BRG_PIN`) — a TV is a shared device

## Design notes

- **Zero dependencies** — no AndroidX, no libraries; the entire app is one Activity + one BroadcastReceiver. Fewer things to break or update.
- **Server does the work** — features and fixes ship in the BrainRotGuard server, the kiosk picks them up on next page load.
- `usesCleartextTraffic=true` is required because the server runs plain HTTP on the LAN. Do not expose the server to the internet; use a VPN (e.g. Tailscale) for remote access.

## License

MIT — see [LICENSE](LICENSE).

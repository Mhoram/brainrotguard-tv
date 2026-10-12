# Installing BrainRotGuard TV on a Smart TV

The kiosk is not in any app store, so it is installed as an APK ("sideloaded").
This guide covers every method that works, roughly in order of convenience.

> **These methods only apply to Android TV / Google TV** (Sony Bravia with
> Google TV, TCL, Hisense, Philips, Nvidia Shield, Chromecast with Google TV,
> most cheap TV boxes). They do **not** work on LG webOS or Samsung Tizen.

---

## Method 1: USB stick (no computer needed on the TV side)

Recommended for non-technical setups. You need: the APK file, a USB stick
(FAT32 or exFAT), and a USB mouse or the TV remote with a pointer alternative
on some models. Tested on Sony Bravia (Google TV).

### One-time prep on the TV

1. **Enable installation from unknown sources.**
   - Google TV (Bravia with Google TV): Settings → Apps → Security &
     restrictions → Unknown sources. The list shown here is *per-app* — you
     allow a specific file manager, not a global switch.
   - Older Android TV (Bravia with Android TV 8/9): Settings → Device
     Preferences → Apps → Security & restrictions → Unknown sources.
2. **Install a file manager** if one isn't present. Newer Google TVs ship
   without one. Options:
   - *File Commander* — often preinstalled on Sony
   - *X-plore File Manager* — via Aptoide, or sideload once with Method 2
   - *Send Files to TV* — handy alternative (see Method 3)

### Install

1. Copy `app-debug.apk` (or the release APK) to the root of the USB stick and
   plug it into the TV.
2. If the TV pops up "USB device detected", open it; otherwise open the file
   manager and browse to the USB drive.
3. Select the APK → it asks which app should install it (the file manager you
   whitelisted in step 1) → allow → Install → Open.
4. On first launch, enter your BrainRotGuard server URL
   (e.g. `http://192.168.1.50:8080`).

### Sony Bravia specifics

- The USB port on the side panel works fine; avoid hubs.
- If the APK doesn't show up, the file manager may be hiding it — enable
  "show hidden files" / filter by "APK" or "all files".
- If "Install blocked" appears, you allowed the *wrong* app in Unknown
  sources. Go back and whitelist the file manager that is actually opening
  the APK.
- Some Bravia firmwares also have Settings → Apps → Special app access →
  Install unknown apps — same per-app switch, different location.

### Updating

Repeat the same steps with a newer APK; Android installs it as an update
(same package name, signature must match — debug builds are fine as long as
you always install debug builds, likewise release).

---

## Method 2: ADB over the network (from a computer)

The developer method, useful for frequent updates and for the initial
bootstrap of a file manager.

```bash
# On the TV: Settings → Device Preferences → About → click "Build" 7 times,
# then Settings → Device Preferences → Developer options →
# enable "USB debugging" (and "Network debugging" if present).

adb connect <tv-ip>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

Bravia notes:
- The TV shows a pairing/authorization dialog — accept it with the remote.
- If `adb connect` fails, try port `5555` after toggling "USB debugging" off
  and on; some firmwares only start the daemon when the setting is toggled.
- To launch the app immediately: `adb shell monkey -p com.mhoram.brainrotguardtv 1`

---

## Method 3: Download from your own server

Your BrainRotGuard server can serve the APK itself, so any TV on the LAN can
grab it without a USB stick or computer.

1. On the server, place the APK at `data/kiosk/brainrotguard-tv.apk`
   (create the `kiosk` folder if needed).
2. The server exposes it at `http://<server>:8080/kiosk.apk`.
3. On the TV, install via one of:
   - **Send Files to TV** (both TV and phone from the Play Store) — send the
     APK from a phone that has downloaded `http://<server>:8080/kiosk.apk`,
     then open the received file on the TV.
   - A file manager with network access (X-plore supports SMB/HTTP), or a
     downloader app pointed at the URL.

---

## Method 4: Third-party app stores

For households that want Play-Store-like updates without any computer:

- **Aptoide TV** — install the Aptoide TV APK once (Method 1 or 2), then
  search for and update sideloaded apps from its catalogue if published.
- **F-Droid** — reproducible, signed builds; a client can be sideloaded once
  and then handles updates.

---

## After installing (do this on every TV)

The kiosk is only one layer of the lockdown:

1. **Disable the YouTube app** — Settings → Apps → YouTube → Disable.
2. **Disable other browsers** and the Play Store if the child can reach them.
3. Consider a restricted TV launcher that hides everything except
   BrainRotGuard.
4. Set a PIN on the server (`BRG_PIN`) — a TV is a shared device.
5. Enable the kiosk's **boot-start** so it relaunches after power cycles.

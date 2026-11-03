English | [中文](README.md)

# DSH Phone Bridge — Android client

**This is a bridge client, not a phone build of DSH.** DSH runs on your computer;
this app puts that computer's UI on your phone.

- **If the computer is off or offline, the app is useless** — it has no DSH of its
  own, it is a remote control
- Sessions, files, plugins and keys stay on the computer; no copy is kept here
- **Remote connection is supported**: phone on 4G/5G, computer at home, across
  cities and carriers — you do not need to be on the same Wi-Fi
- A userspace Tailscale node (`tsnet`) is built in: it **does not take over the
  system VPN**, and you do not need the Tailscale app installed

> This is the Android repository of the **DSH Phone Bridge** project. The PC side
> (the DSH plugin) lives in a different repository:
> **[tihuzushi/dsh-phone-bridge](https://github.com/tihuzushi/dsh-phone-bridge)** ← install that first,
> otherwise the phone has nothing to connect to.

📦 Download the APK (Releases)

<https://github.com/tihuzushi/dsh-phone-bridge-app/releases/latest>

🎬 Author's Bilibili page

<https://space.bilibili.com/157183587>

---

## Set up from zero (follow it and it works)

The project has two halves: **the DSH plugin on your PC** (bridge + mobile UI)
and **this APK on your phone**. The two sides meet over a Tailscale tailnet —
peer-to-peer when possible, and falling back to Tailscale's encrypted relay when
it is not. The project itself runs no server.

### Step 0: what you need

| Need | Notes |
|---|---|
| A Windows PC | With DeepSeek Harness (DSH) installed and its UI opening normally |
| An Android phone | Android 8.0 (API 26) or newer |
| A Tailscale account | The free plan is enough — sign up at <https://login.tailscale.com/start> |

### Step 1: install Tailscale on the PC

1. Open <https://tailscale.com/download/windows> and install it
2. Sign in to the same Tailscale account from the tray icon and click **Connect**
3. Check that the state is Connected (the plugin settings page shows this PC's device name)

> Why this step: the phone ships its own Tailscale, but **the PC has to be in the
> same tailnet too**, otherwise the phone cannot find it.

### Step 2: install the DSH plugin on the PC

In DSH: **设置 → 插件** (Settings → Plugins) → **添加插件** (Add plugin), and enter:

```
https://github.com/tihuzushi/dsh-phone-bridge
```

After installing, **restart DSH**. Then under **设置 → DSH Phone Assistant**
(Settings → DSH Phone Assistant) you should see:

- Status: running
- **Pairing code** (6 digits — you will need it below)
- Tailscale row says "connected" plus your device name
- Port: 8765 by default

If the Tailscale row is red and says "this PC has no Tailscale", go back to step 1.

### Step 3: install this app on the phone

1. Open <https://github.com/tihuzushi/dsh-phone-bridge-app/releases/latest>
2. Download `DSH-Phone-Bridge-<version>.apk`
3. Tap it on the phone to install. The first time Android will ask to allow
   installs from unknown sources — allow it (the APK is signed with this
   repository's own key and is not on any app store)

### Step 4: sign the phone in to Tailscale

1. Open the app → **连接设置** (Connection settings) in the top right
2. Tap "打开 Tailscale 登录页" (Open the Tailscale sign-in page) and sign in to
   **the same Tailscale account** in the browser
3. Go back to the app and wait for the state to become "connected"

> The Tailscale inside the app is embedded. It is a second node of the same
> account as the one you installed on the PC in step 1.

### Step 5: pick the PC and enter the pairing code

1. Under "连接设备" (Devices) select your PC (the list comes from your tailnet)
2. Enter the **6-digit pairing code** that the plugin settings page shows on the PC
3. Tap "保存并连接" (Save and connect)

Once connected, the DSH UI opens inside the app, and the mobile layout is
injected by the plugin running on the PC.

### FAQ

| Symptom | Cause / fix |
|---|---|
| Screen keeps flashing, never gets in | Wrong pairing code, the app is retrying. Check the current code on the PC settings page; since plugin 0.4.5 retries no longer lock you out |
| Device list is empty | The phone side is not signed in to Tailscale, or the PC is not on the tailnet |
| PC settings page says "not running" | The plugin did not start — restart DSH. If it still fails, check whether port 8765 is taken |
| Changed PC / reinstalled the plugin | A new pairing code is generated; just enter it again |

---

## What it does

1. Signs in to your own tailnet with the embedded `tsnet` and lists the PCs in the account
2. Connects to port 8765 of DSH on that PC (cookie + pairing code)
3. Opens DSH in a WebView; the mobile layout is injected by the plugin
4. File preview / download / open-with all go through the app's own download folder

## Version and updates

At the bottom of the settings page there is "DSH PHONE BRIDGE / 当前版本 (current
version) / 检查更新 (check for updates)". Tapping it compares against
`api.github.com/repos/tihuzushi/dsh-phone-bridge-app/releases/latest`; when a
newer version exists it shows a dialog **with that release's changelog**, and
"去下载" (go download) opens the release page in your system browser. The APK is
never downloaded inside the app.

## Build it yourself

You need JDK 17, the Android SDK (`platforms/android-34` + `build-tools/34.0.0`),
and `libs/tsnetmobile.aar` (**not in this repository** — see below).

```powershell
# 1) generate the tsnet binding (once)
#    gomobile bind -target=android/arm64,android/amd64 -androidapi 26 \
#        -o android/libs/tsnetmobile.aar ./tsnetmobile
#    The build script extracts classes.jar and jni/*.so from the aar itself

# 2) build (produces an unsigned APK when no signing key is present)
$env:HP_STORE_PASS = "<your keystore password>"
powershell -ExecutionPolicy Bypass -File android\build-apk.ps1 `
    -Version 2.4.2 -VersionCode 70 `
    -KeystorePath C:\path\to\your.jks
```

Output: `android/dist/DSH-Phone-Bridge-<version>.apk`.

### Why these are not in the repository

| Thing | Reason |
|---|---|
| `libs/tsnetmobile.aar` (30 MB) | It is the Tailscale `tsnet` (BSD-3-Clause) packaged by `gomobile` — a build artifact, generated locally, not redistributed here |
| `keystore/*.jks` | Signing key, never goes into a repository |
| `dist/*.apk` | Build artifact; official builds only live in Releases |

`tsnet` belongs to Tailscale Inc. and is used under its BSD-3-Clause license;
this project only calls its public API. The `libgojni.so` inside the built APK
also statically links Tailscale's own dependencies — the full copyright notices
and license texts are in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md), and
are packed into the APK as `assets/licenses/THIRD-PARTY-NOTICES.txt`.

## Layout

```
android/
  build-apk.ps1                       # aapt2 + javac + d8 + zipalign + apksigner
  app/src/main/
    AndroidManifest.xml
    java/com/harness/phone/v2/
      MainActivity.java               # WebView, file bridge, reconnect
      ConnectionSettingsActivity.java # Tailscale sign-in / device pick / pairing code / update check
      UpdateChecker.java              # update check + changelog dialog
      TsnetManager.java, TsnetService.java
      FileOpenService.java, HarnessFileProvider.java
    res/                              # icons, theme, strings
    assets/offline/                   # offline shell: what you see when the PC is unreachable
```

## Related repositories

- **[tihuzushi/dsh-phone-bridge](https://github.com/tihuzushi/dsh-phone-bridge)** — the DSH plugin for the PC: bridge service + mobile UI + pairing code / port settings
- **[tihuzushi/dsh-phone-bridge-app](https://github.com/tihuzushi/dsh-phone-bridge-app)** — this repository: the Android client

## License

**The code written for this project** (Java, the offline shell's JS/CSS, the
build scripts) is MIT — see [LICENSE](LICENSE).

**The shipped APK is not pure MIT.** The `libgojni.so` it contains (the embedded
Tailscale userspace node) statically links 29 third-party Go modules:
BSD-3-Clause × 16, BSD-2-Clause × 2, MIT × 5, Apache-2.0 × 6. All of them are
permissive and MIT-compatible — none requires this project to open-source
anything — but they do require the copyright notices and license texts to be
reproduced when the binary is redistributed. The full inventory is in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

That inventory is read out of the library inside the APK with
`go version -m libgojni.so`; it is not hand-written.

---

## Who made this

Bilibili

<https://space.bilibili.com/157183587>

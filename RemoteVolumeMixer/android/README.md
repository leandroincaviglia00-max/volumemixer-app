# Remote Volume Mixer — Android app

Native Android client (Kotlin + Jetpack Compose, dark only) that drives the real
Windows per-application volume mixer over the LAN.

* package: `com.remotemixer.app`
* minSdk 24 (Android 7.0) · targetSdk/compileSdk 34 · JVM target 17
* no fake data anywhere: every slider is bound to a live Windows audio session

---

## 1. Open it

1. Android Studio (Ladybug / Koala or newer) → **File ▸ Open** → select this
   `android/` folder.
2. Let it sync. Gradle 8.7 + AGP 8.5.2 + Kotlin 1.9.24 are declared in
   `gradle/libs.versions.toml`.
3. This folder ships **without** `gradlew`/`gradle-wrapper.jar` (binary blob).
   Android Studio regenerates the wrapper on first sync. From a terminal with
   Gradle installed you can also run once:

   ```bash
   gradle wrapper --gradle-version 8.7
   ```

## 2. Run it

Plug the phone in, hit ▶ Run. On first launch:

1. type the PC IP shown by the server banner (or tap the PC in **Found on your
   network**)
2. port `8765`
3. **CONNECT**

The app remembers the last PC and reconnects by itself on every later launch.

## 3. Build a release APK

**Option A — Android Studio**

`Build ▸ Generate Signed App Bundle / APK… ▸ APK ▸ Create new… ` (keystore) →
pick `release` → Finish. Output:

```
android/app/build/outputs/apk/release/app-release.apk
```

**Option B — command line**

Create `android/keystore.properties` (git-ignored):

```properties
storeFile=C:/keys/remote-mixer.jks
storePassword=yourStorePassword
keyAlias=remote-mixer
keyPassword=yourKeyPassword
```

Generate the keystore once if you don't have one:

```bash
keytool -genkey -v -keystore remote-mixer.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias remote-mixer
```

Then:

```bash
./gradlew assembleRelease          # -> app/build/outputs/apk/release/app-release.apk
```

If `keystore.properties` is missing, `assembleRelease` still builds but the APK
stays unsigned (install it with `adb install -r` after signing, or just use the
Android Studio wizard).

Debug build for quick testing: `./gradlew installDebug`
(the debug app id is `com.remotemixer.app.debug`, so it can live next to the
release build on the same phone).

## 4. Architecture

```
MainActivity ─ Crossfade ─┬─ ConnectionScreen   (IP/port, discovery, pairing)
                          ├─ MixerScreen        (master + app cards)
                          └─ DiagnosticsScreen  (latency, raw sessions)
                                     │
                              MixerViewModel     search / sort / favourites
                                     │
                                MixerClient      OkHttp WebSocket, reconnect,
                                     │           throttling, echo suppression
                                     │
                              Discovery (UDP)    Prefs (SharedPreferences)
```

| File | Role |
|---|---|
| `data/Protocol.kt` | the whole wire protocol, one place, mirrors the server |
| `data/Prefs.kt` | last server, pairing token, favourites, sort order |
| `net/MixerClient.kt` | socket lifecycle, backoff reconnect, outbound throttling |
| `net/Discovery.kt` | UDP broadcast scan for servers on the LAN |
| `MixerViewModel.kt` | UI state: filtering, sorting, favourites-first ordering |
| `ui/components/VolumeSlider.kt` | custom 48dp-tall absolute-position slider |
| `ui/components/AppCard.kt` | per-session card: icon, name, %, slider, mute, ★ |

### Slider networking (the important bit)

Dragging updates local state on every pixel so the UI is buttery, but the
network sees at most **one frame per 60 ms per app** (a coalescing map, not a
queue: only the newest value survives). When the finger lifts, the final value
is sent immediately and unconditionally, so the PC can never end up on a stale
number.

Incoming `volume_update` frames for an id are ignored for 700 ms after a local
change (echo suppression) — that is what stops the slider from snapping
backwards while you drag. Values that did not actually change are dropped before
they reach Compose, so no needless recomposition, no jitter.

### Icons

The server exposes each session icon as `GET /api/icon/<id>` (extracted from the
real `.exe`). Coil loads and caches it. If the server has no icon, the card
falls back to a glyph guessed from the process name (browser / music / chat /
game / media / terminal / generic).

### Reconnect

`onFailure`/`onClosed` → exponential backoff 1s → 2s → 4s → 8s, forever, while
showing a calm `PC DISCONNECTED · Retrying…` banner instead of an error dialog.
Wi-Fi drop and server restart both heal on their own.

### Pairing

If the server runs with `--pairing`, the app gets `auth_required`, shows the
6-digit code field, and stores the returned token per host in SharedPreferences.
Later launches authenticate silently with that token.

## 5. Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`. Nothing else: no
notification, no location, no audio permission (the phone's own volume is never
touched).

Cleartext traffic is allowed via `res/xml/network_security_config.xml` because
the server is a LAN appliance speaking `ws://` and `http://`.

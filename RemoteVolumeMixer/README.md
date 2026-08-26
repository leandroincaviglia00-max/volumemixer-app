# Remote Volume Mixer

Turn an Android phone into a real remote for the **Windows Volume Mixer**.
Not the phone's volume: the per-application volume of Discord, Spotify, Chrome,
Steam, games, VLC… running on the PC.

```
        ANDROID APK
             |
        Wi-Fi LAN  (WebSocket, JSON)
             |
      WINDOWS SERVER  (FastAPI + uvicorn)
             |
      pycaw / Windows Core Audio
             |
      Windows Audio Sessions
             |
   Discord / Spotify / Chrome / games
```

Everything is wired to the real thing. No mock list, no hardcoded "Discord 50%",
no simulated slider: values are read from and written to live Windows Audio
Sessions through `ISimpleAudioVolume`, and the master slider drives
`IAudioEndpointVolume`.

---

## Quick start

**On the PC**

1. `windows-server/install_firewall.bat` → right click → **Run as administrator**
2. double click `windows-server/start_server.bat`
   (first run creates a venv and installs the dependencies)
3. note the LAN address in the banner, e.g. `192.168.1.100`

**On the phone**

4. install `app-release.apk`
5. same Wi-Fi as the PC
6. type the IP (or tap the PC under *Found on your network*), port `8765`
7. **CONNECT** → the app list appears immediately
8. drag a slider → that app's volume changes on the PC, for real

## Repository layout

```
RemoteVolumeMixer/
├── windows-server/
│   ├── server.py               FastAPI + WebSocket + pycaw audio engine
│   ├── test_client.py          console client, test without the phone
│   ├── requirements.txt
│   ├── start_server.bat        double-click launcher (auto venv + deps)
│   ├── start_server_debug.bat  same with --debug
│   ├── install_firewall.bat    TCP 8765 + UDP 8766, private profiles only
│   ├── remove_firewall.bat
│   ├── config.json
│   └── README.md               setup + full protocol spec + COM notes
├── android/
│   ├── app/                    Kotlin + Compose source, resources, icons
│   ├── gradle/libs.versions.toml
│   ├── build.gradle.kts · settings.gradle.kts · gradle.properties
│   └── README.md               build + signed APK instructions
├── TESTING.md                  the 11-test acceptance checklist
└── README.md                   this file
```

## What it does

| Feature | Where it lives |
|---|---|
| dynamic session detection (no hardcoded apps) | `AudioEngine._refresh_sessions` |
| several sessions per process handled separately | id = pid + hash of the session instance id |
| real volume / mute writes | `ISimpleAudioVolume`, `IAudioEndpointVolume` |
| live sync from the Windows mixer to the phone | 400 ms diff poll → `volume_update` broadcast |
| app opened / closed | 1.5 s enumeration diff → `apps_updated` |
| multiple phones at once | `ConnectionManager.broadcast`, sender excluded |
| icons from the real `.exe` | `IconCache` + `GET /api/icon/<id>` |
| LAN discovery | UDP 8766 probe/response + periodic announce |
| optional PIN pairing + token | `--pairing`, `tokens.json` |
| slider throttling | 60 ms coalescing map + guaranteed final value |
| auto reconnect | exponential backoff 1/2/4/8 s |
| search · sort · favourites | `MixerViewModel`, persisted locally |
| diagnostics | in-app screen + `/api/status`, `/api/apps`, `/docs` |

## Build phases (and how each one was verified)

**Phase 1 — Windows server, WebSocket, pycaw, get applications.**
`server.py` boots, prints the banner with the auto-detected LAN IP, enumerates
the real audio sessions and answers `get_apps`.
*Test:* `python test_client.py` → `list`, and compare with `GET /api/apps` and
the Windows mixer.

**Phase 2 — Android connection.**
`MixerClient` + `ConnectionScreen`: IP/port, CONNECT, status dot, latency from
ping/pong, persisted server, auto-connect on relaunch.
*Test:* connect, kill the server, watch the banner go red and retry.

**Phase 3 — Application list.**
`apps` frames decoded by `Protocol.kt` into cards, favourites-first ordering,
search, sorting, real `.exe` icons via Coil.
*Test:* open Spotify → the card appears within ~1.5 s.

**Phase 4 — Real volume control.**
Drag → optimistic local state + throttled `set_volume` → engine thread writes
`SetMasterVolume` → `volume_changed` ack.
*Test:* open the Windows mixer next to the phone and drag.

**Phase 5 — Mute.**
`set_mute` keeps the level (Windows semantics), and if an app was left at 0 the
remembered pre-mute value is restored on unmute.
*Test:* mute Discord from the phone, check the Windows mixer, unmute.

**Phase 6 — Live synchronisation.**
Diff poll broadcasts only actual changes; the client ignores echoes of its own
writes for 700 ms and drops no-op updates before recomposition.
*Test:* move the Windows mixer slider → the phone follows without jitter.

**Phase 7 — UI polish.**
Dark theme, light glassmorphism cards, per-app accent colour, 48 dp custom
slider with halo, crossfade navigation, calm offline banner, empty states.

**Phase 8 — Discovery.**
UDP responder on 8766; the app broadcasts a probe and lists every server found.
Manual IP always works and stays the primary path.
*Test:* launch the app with the server running → the PC appears under *Found on
your network*.

**Phase 9 — Pairing.**
`server.py --pairing` prints a 6-digit code; the app asks for it, gets a token,
stores it per host and authenticates silently afterwards.
*Test:* start with `--pairing`, pair, restart the app → no code asked again.

**Phase 10 — APK release.**
Signing config driven by `android/keystore.properties`, ProGuard rules for
OkHttp + kotlinx.serialization, `assembleRelease` → `app-release.apk`.
See `android/README.md` §3.

## Security posture

LAN only by default. The server binds `0.0.0.0` so phones can reach it, the
firewall script only opens **private/domain** profiles, and there is no upstream
call anywhere. Pairing is optional (`--pairing`) and, once done, uses a stored
token instead of the PIN. Do not port-forward this: it would let anyone on the
internet ride your audio sessions.

## Known limits (honest list)

* Windows only, by definition: it talks to Core Audio.
* Live sync is a 400 ms diff poll, not `IAudioSessionEvents` callbacks. It is
  simpler, version-proof across pycaw releases and costs ~0% CPU; worst case the
  phone is a few hundred ms behind a manual mixer change.
* Some apps (a few Electron builds, some anti-cheat protected games) expose an
  audio session with an empty display name; the server falls back to a
  prettified process name.
* Icon extraction needs `pywin32` + `Pillow`. Without them everything still
  works, cards just use generic glyphs.
* Python 3.14 is not recommended yet — see `windows-server/README.md` §12.

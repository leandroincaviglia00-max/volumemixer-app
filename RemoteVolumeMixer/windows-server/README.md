# Remote Volume Mixer — Windows Server

Exposes the **real Windows per-application volume mixer** over the LAN so an
Android phone can drive it. Built on FastAPI + WebSockets + pycaw (Windows Core
Audio). No hardcoded app list, no fake values: every number comes from a live
Windows Audio Session.

---

## 1. Requirements

* Windows 10 or Windows 11
* **Python 3.11, 3.12 or 3.13** (see the version note at the bottom)
* PC and phone on the same Wi-Fi / LAN

## 2. Install & run (the easy way)

Double click:

```
start_server.bat
```

First run creates a local `venv\` and installs everything from
`requirements.txt`. Every later run just boots the server (a couple of seconds).

For verbose logs use `start_server_debug.bat` (same as `server.py --debug`).

### Manual way

```bat
py -3.12 -m venv venv
venv\Scripts\activate
pip install -r requirements.txt
python server.py
```

## 3. Firewall

Right click → **Run as administrator**:

```
install_firewall.bat     :: allows TCP 8765 + UDP 8766 on private networks
remove_firewall.bat      :: removes those rules
```

Rules are scoped to `private,domain` profiles only — public networks stay
blocked on purpose. The server is **LAN only** by design; never port-forward it.

## 4. What you should see

```
========================================
 REMOTE VOLUME MIXER SERVER
========================================

 Server: ONLINE

 Local address:
   http://127.0.0.1:8765

 LAN address:
   http://192.168.1.100:8765

 WebSocket:
   ws://192.168.1.100:8765/ws

 Port:
   8765

 Waiting for Android client...

========================================
```

The LAN address is detected automatically. Type that IP into the Android app.

## 5. CLI flags

| Flag | Meaning |
|---|---|
| `--port 9000` | change the TCP port |
| `--host 127.0.0.1` | bind loopback only |
| `--debug` | verbose logging (every WS frame, every poll error) |
| `--pairing` | force the PIN pairing flow on |
| `--no-discovery` | disable the UDP discovery responder |
| `--no-icons` | skip icon extraction |

Same settings live in `config.json` (CLI wins over the file).

## 6. HTTP debug API

| Endpoint | Purpose |
|---|---|
| `GET /` | tiny dark status page with the live session table |
| `GET /api/status` | `{"status":"online","ip":...,"port":8765,"clients":1,...}` |
| `GET /api/apps` | current sessions + master volume as JSON |
| `GET /api/icon/{id}` | PNG icon of that session (cached, 404 if none) |
| `GET /docs` | auto-generated Swagger UI |

## 7. Test it without the phone

```bat
venv\Scripts\python.exe test_client.py            :: local
venv\Scripts\python.exe test_client.py 192.168.1.100
```

Then: `list`, `v 0 35` (set app #0 to 35%), `m 0` (toggle mute),
`master 60`, `ping`, `quit`. Open the Windows Volume Mixer next to it and watch
the values move in both directions.

---

## 8. WebSocket protocol (v1)

Endpoint: `ws://<pc-ip>:8765/ws` — JSON text frames, one object per frame.

### Handshake

Server speaks first:

```json
{"type":"hello","server":"remote-volume-mixer","version":"1.0.0",
 "hostname":"DESKTOP-LEAND","requires_pairing":false,"protocol":1}
```

Client answers:

```json
{"type":"auth","name":"Pixel 8","token":"<saved token or null>"}
```

* pairing disabled → `{"type":"auth_ok"}` followed by an `apps` frame
* pairing enabled, token bad/missing → `{"type":"auth_required"}`
  → client sends `{"type":"pair","code":"482731","name":"Pixel 8"}`
  → `{"type":"paired","token":"..."}` (store it, reuse it forever) or
    `{"type":"pair_failed"}`

### Client → Server

```json
{"type":"get_apps"}
{"type":"set_volume","id":"12345-a1b2c3d4e5","volume":65}
{"type":"set_mute","id":"12345-a1b2c3d4e5","muted":true}
{"type":"get_icon","id":"12345-a1b2c3d4e5"}
{"type":"refresh"}
{"type":"ping","t":1712345678901}
```

### Server → Client

```json
{"type":"apps","apps":[...],"master":{"volume":72,"muted":false}}
{"type":"apps_updated","apps":[...],"master":{...}}
{"type":"volume_changed","id":"...","volume":65}
{"type":"mute_changed","id":"...","muted":true}
{"type":"volume_update","id":"...","volume":70,"muted":false}
{"type":"icon","id":"...","png_base64":"iVBORw0..."}
{"type":"pong","t":1712345678901,"server_time":1712345678913}
{"type":"error","code":"session_gone","id":"...","message":"..."}
```

### App object

```json
{
  "id": "12345-a1b2c3d4e5",
  "pid": 12345,
  "process_name": "Discord.exe",
  "display_name": "Discord",
  "volume": 42,
  "muted": false,
  "icon": "/api/icon/12345-a1b2c3d4e5",
  "last_active": 1712345678901
}
```

* `id` = `<pid>-<sha1(SessionInstanceIdentifier)[:10]>`. It is **per audio
  session**, not per process: Chrome or a game with several sessions shows up as
  several independent entries, exactly like in the Windows mixer.
* `icon` is a **URL path**, not bytes. The client fetches
  `http://<pc-ip>:8765/api/icon/<id>` and caches it — that keeps WS frames tiny.
  `null` means "use your generic fallback icon".
* Master volume uses the reserved id `master`.

### Events / live sync

The engine re-reads session volumes every `poll_interval_ms` (400 ms) and
re-enumerates sessions every `enumerate_interval_ms` (1500 ms). It broadcasts
**only real changes**:

* someone moves the Windows mixer → `volume_update`
* an app starts or stops playing → `apps_updated`
* another phone changes something → `volume_update` to every other client

Multiple phones are fully supported; the sender gets `volume_changed`, everyone
else gets `volume_update`.

---

## 9. Threading / COM design (read this before editing)

All pycaw/COM work happens on **one** dedicated thread (`AudioEngine`), which
calls `CoInitialize()` once and owns every COM pointer. HTTP/WS handlers hand
work to it through a queue and await the result with `asyncio.to_thread`.

That is deliberate: uvicorn runs handlers on arbitrary threadpool threads, and
touching a COM object from a thread that never initialised COM is what produces

```
OSError: [WinError -2147221008] CoInitialize has not been called
```

If you add a feature that talks to pycaw, route it through
`AudioEngine.submit(...)` instead of calling pycaw directly.

## 10. Error handling

* no audio device → server still boots, master volume disabled, no crash
* app closes mid-command → `{"type":"error","code":"session_gone"}` + a fresh
  `apps_updated`
* PID vanished → controlled `LookupError`, never a traceback
* dead COM sessions are dropped from the cache on the next poll
* client disconnect / Wi-Fi loss → logged and cleaned up, other clients unaffected

## 11. Performance

Idle cost is two lightweight COM reads per session every 400 ms — effectively
0% CPU on a modern machine. Nothing polls in a tight loop. If you want it even
lazier, raise `poll_interval_ms` to 800 in `config.json`.

## 12. Python version note

`pycaw` + `comtypes` are validated on **Python 3.11–3.13**.

**Python 3.14 is not recommended yet**: `comtypes` relies on CPython internals
that changed in 3.14, and the usual symptom is an import-time or
`CoInitialize`-time failure inside `comtypes.client`. If you already have 3.14
installed, do not fight it — install 3.12 side by side and point the venv at it:

```bat
py -3.12 -m venv venv
venv\Scripts\python.exe -m pip install -r requirements.txt
venv\Scripts\python.exe server.py
```

`start_server.bat` uses `py -3` (the launcher), so if 3.12 is your default it is
picked automatically.

## 13. Logs

```
23:41:02 [SERVER] Started
23:41:02 [NETWORK] LAN IP: 192.168.1.100
23:41:02 [AUDIO] Default output device ready (master 72%)
23:41:02 [AUDIO] Applications detected: 5
23:41:09 [WS] Client connected (192.168.1.42)
23:41:14 [AUDIO] Discord volume changed: 45%
23:41:31 [AUDIO] Session opened: Spotify
23:42:02 [WS] Client disconnected (192.168.1.42)
```

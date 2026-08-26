# Acceptance checklist

Run these in order. Keep the Windows Volume Mixer
(`Win`+`R` → `sndvol.exe`) open next to the phone for the whole session, and
start the server with `start_server_debug.bat` so every step is logged.

| # | Test | How | Pass criteria |
|---|---|---|---|
| 1 | **Server starts** | double click `start_server.bat` | banner shows `Server: ONLINE`, a real LAN IP (not 127.0.0.1) and `Waiting for Android client...`; `http://127.0.0.1:8765/api/status` returns `"status":"online"` |
| 2 | **Android connects** | type the IP + `8765`, tap CONNECT | dot turns green `Connected`, PC name and a ping in ms appear; server logs `[WS] Client connected` |
| 3 | **Applications appear** | play audio in 2–3 apps | every app with a session shows up with the right name, icon and current % (cross-check against `sndvol.exe` and `GET /api/apps`) |
| 4 | **Discord slider is real** | drag the Discord card to ~35% | Windows mixer jumps to 35% while dragging; Discord audio actually gets quieter; log shows `[AUDIO] Discord volume set: 35%` |
| 5 | **Spotify slider is real** | drag the Spotify card | same, independently of Discord |
| 6 | **Mute works** | tap the speaker icon on a card | the app goes silent, the Windows mixer shows it muted, the card keeps showing the stored % ; tap again → sound and level are back |
| 7 | **Windows → Android sync** | drag the same app in `sndvol.exe` to 70% | the phone slider animates to 70% within ~0.5 s, and does not jitter or fight you afterwards |
| 8 | **New app appears** | launch Spotify (or a game) and play audio | its card appears within ~1.5 s without any manual refresh; server logs `[AUDIO] Session opened:` |
| 9 | **Closed app disappears** | quit that app | the card is removed within ~1.5 s; `[AUDIO] Session closed:` |
| 10 | **Wi-Fi drop / recovery** | turn phone Wi-Fi off for ~20 s, then on | red `PC DISCONNECTED · Retrying…` banner (no crash, no error dialog), then automatic reconnect and a fresh list |
| 11 | **Two clients** | connect a second phone (or `python test_client.py <ip>`) | both list the same apps; changing Discord on phone A moves phone B's slider within ~0.5 s; `/api/status` reports `"clients": 2` |

## Extra checks worth doing

| Area | Test | Pass criteria |
|---|---|---|
| Multi-session process | open two Chrome windows playing audio | Chrome appears as several independent cards, each with its own PID/id, and each slider only affects its own session |
| Master volume | drag the master card | the system volume (tray icon) follows; changing the tray volume moves the master card back |
| Throttling | drag one slider fast for ~5 s with `--debug` on | the log shows a steady stream (~15/s max), not hundreds per second, and the **last** logged value equals where you released |
| Server restart | close the server window, wait 10 s, start it again | the app reconnects on its own; no need to retype the IP |
| App restart | kill the app from the task switcher and reopen it | it auto-connects to the last PC without any input |
| Discovery | open the connection screen with the server up | the PC is listed under *Found on your network* with hostname, IP and port |
| Pairing | run `server.py --pairing`, connect | the app asks for the 6-digit code, wrong code is rejected, right code pairs; after killing/reopening the app it connects silently |
| Favourites / search / sort | star two apps, type in the search box, switch sort chips | starred apps stay on top, filtering is instant and local, sort choice survives a restart |
| No audio device | unplug/disable every output device, restart the server | the server starts anyway, logs a warning about the endpoint, does not crash |
| Idle cost | leave everything connected and watch Task Manager | the Python process sits at ~0% CPU |
| COM safety | hammer `GET /api/apps` and `/` in a browser while dragging sliders | no `WinError -2147221008`, no `CoInitialize` traceback (all COM work is on the single engine thread) |

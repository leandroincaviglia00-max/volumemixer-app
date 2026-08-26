#!/usr/bin/env python3
"""
Console test client for the Remote Volume Mixer server.

Lets you verify PHASE 1 (server + WebSocket + pycaw) without the phone.

    python test_client.py                 # connects to 127.0.0.1:8765
    python test_client.py 192.168.1.100   # connects over the LAN

Commands once connected:
    list                 refresh and print the audio sessions
    v <n> <0-100>        set volume of app #n
    m <n>                toggle mute of app #n
    master <0-100>       set master volume
    ping                 measure round trip latency
    quit
"""
import asyncio
import json
import sys
import time

try:
    import websockets
except ImportError:
    print("pip install websockets")
    sys.exit(1)


async def main() -> None:
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8765
    url = "ws://%s:%d/ws" % (host, port)
    print("connecting to %s ..." % url)
    apps: list = []

    async with websockets.connect(url, max_size=4_000_000) as ws:
        async def send(obj):
            await ws.send(json.dumps(obj))

        def show():
            print("\n  #  %-26s %-18s %6s %s" % ("APP", "PROCESS", "VOL", "MUTE"))
            for i, a in enumerate(apps):
                print("  %-2d %-26s %-18s %5d%% %s"
                      % (i, a["display_name"][:26], a["process_name"][:18],
                         a["volume"], "MUTED" if a["muted"] else ""))
            print("")

        async def reader():
            nonlocal apps
            async for raw in ws:
                m = json.loads(raw)
                t = m.get("type")
                if t == "hello":
                    print("server %s on %s (pairing: %s)"
                          % (m.get("version"), m.get("hostname"), m.get("requires_pairing")))
                    if m.get("requires_pairing"):
                        code = input("pairing code: ").strip()
                        await send({"type": "pair", "code": code, "name": "console"})
                    else:
                        await send({"type": "auth", "name": "console"})
                elif t in ("auth_ok", "paired"):
                    print("authenticated")
                    await send({"type": "get_apps"})
                elif t in ("apps", "apps_updated"):
                    apps = m.get("apps", [])
                    print("[%s] %d sessions | master %d%%"
                          % (t, len(apps), (m.get("master") or {}).get("volume", -1)))
                    show()
                elif t == "volume_update":
                    for a in apps:
                        if a["id"] == m["id"]:
                            a["volume"], a["muted"] = m["volume"], m["muted"]
                            print("<- live update: %s = %d%%%s"
                                  % (a["display_name"], m["volume"],
                                     " (muted)" if m["muted"] else ""))
                            break
                elif t == "pong":
                    print("<- pong: %d ms" % (int(time.time() * 1000) - int(m["t"])))
                elif t in ("volume_changed", "mute_changed"):
                    print("<- ack %s" % m)
                elif t == "error":
                    print("<- ERROR %s: %s" % (m.get("code"), m.get("message")))
                else:
                    print("<- %s" % m)

        task = asyncio.create_task(reader())
        loop = asyncio.get_running_loop()
        while not task.done():
            line = (await loop.run_in_executor(None, sys.stdin.readline)).strip()
            if not line:
                continue
            parts = line.split()
            cmd = parts[0].lower()
            try:
                if cmd in ("q", "quit", "exit"):
                    break
                elif cmd == "list":
                    await send({"type": "refresh"})
                elif cmd == "ping":
                    await send({"type": "ping", "t": int(time.time() * 1000)})
                elif cmd == "master" and len(parts) == 2:
                    await send({"type": "set_volume", "id": "master",
                                "volume": int(parts[1])})
                elif cmd == "v" and len(parts) == 3:
                    await send({"type": "set_volume", "id": apps[int(parts[1])]["id"],
                                "volume": int(parts[2])})
                elif cmd == "m" and len(parts) == 2:
                    a = apps[int(parts[1])]
                    await send({"type": "set_mute", "id": a["id"],
                                "muted": not a["muted"]})
                else:
                    print(__doc__)
            except (IndexError, ValueError):
                print("bad command / index")
        task.cancel()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass

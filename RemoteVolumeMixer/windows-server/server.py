#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
=====================================================================
 REMOTE VOLUME MIXER - WINDOWS SERVER
=====================================================================
Turns a Windows PC into a remote-controllable audio mixer.

 Android APK  <--- Wi-Fi LAN / WebSocket --->  this server
                                                   |
                                            pycaw / Core Audio
                                                   |
                                      Windows Audio Sessions
                                (Discord / Spotify / Chrome / games)

Everything here talks to the REAL Windows Core Audio API.
No fake data, no hardcoded application list.

Design notes
------------
* ALL COM / pycaw work happens inside ONE dedicated thread (AudioEngine).
  FastAPI / uvicorn run request handlers on arbitrary threadpool threads,
  and COM objects are apartment-bound -> touching pycaw from those threads
  is what produces `WinError -2147221008 (CoInitialize has not been called)`.
  We avoid that class of bug completely: the engine thread calls
  CoInitialize() once, owns every COM pointer, and other threads talk to it
  through a command queue + Futures.
* Live synchronisation is done by a cheap diff-poll inside the same engine
  thread (default: values every 400 ms, session enumeration every 1500 ms).
  Only ACTUAL changes are broadcast, so idle CPU stays near zero and the
  Android sliders never get jittered by redundant updates.

Author: built for succoallimoneice
=====================================================================
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import ctypes
import hashlib
import json
import logging
import os
import queue
import secrets
import socket
import sys
import threading
import time
from concurrent.futures import Future
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

# --------------------------------------------------------------------------
# Third party
# --------------------------------------------------------------------------
try:
    import psutil
except ImportError:  # pragma: no cover
    print("Missing dependency: psutil.  Run:  pip install -r requirements.txt")
    raise

try:
    import uvicorn
    from fastapi import FastAPI, WebSocket, WebSocketDisconnect
    from fastapi.responses import HTMLResponse, JSONResponse, Response
except ImportError:  # pragma: no cover
    print("Missing dependency: fastapi/uvicorn.  Run:  pip install -r requirements.txt")
    raise

IS_WINDOWS = sys.platform.startswith("win")

# pycaw / comtypes are Windows only. Import defensively so that the file can at
# least be inspected / linted on other platforms.
COM_OK = False
if IS_WINDOWS:
    try:
        import comtypes
        from comtypes import COMError, CLSCTX_ALL
        try:
            # pycaw >= 20230407
            from pycaw.utils import AudioUtilities
            from pycaw.api.audioclient import ISimpleAudioVolume
            from pycaw.api.endpointvolume import IAudioEndpointVolume
        except Exception:  # older layout
            from pycaw.pycaw import (  # type: ignore
                AudioUtilities,
                ISimpleAudioVolume,
                IAudioEndpointVolume,
            )
        COM_OK = True
    except Exception as exc:  # pragma: no cover
        print("[FATAL] Could not import pycaw/comtypes: %s" % exc)
        print("        pip install -r requirements.txt")
        raise
else:
    COMError = OSError  # type: ignore

VERSION = "1.0.0"
SERVICE_NAME = "remote-volume-mixer"
BASE_DIR = Path(__file__).resolve().parent
CONFIG_PATH = BASE_DIR / "config.json"
TOKENS_PATH = BASE_DIR / "tokens.json"

MASTER_ID = "master"

# --------------------------------------------------------------------------
# Logging  ->  [SERVER] Started   /   [AUDIO] Discord volume changed: 45%
# --------------------------------------------------------------------------
class TagFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        tag = getattr(record, "tag", "SERVER")
        ts = time.strftime("%H:%M:%S")
        return "%s [%s] %s" % (ts, tag, record.getMessage())


log = logging.getLogger("rvm")


def _setup_logging(debug: bool) -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(TagFormatter())
    log.handlers[:] = [handler]
    log.setLevel(logging.DEBUG if debug else logging.INFO)
    log.propagate = False


def L(tag: str, msg: str, *args: Any) -> None:
    log.info(msg % args if args else msg, extra={"tag": tag})


def D(tag: str, msg: str, *args: Any) -> None:
    log.debug(msg % args if args else msg, extra={"tag": tag})


def W(tag: str, msg: str, *args: Any) -> None:
    log.warning(msg % args if args else msg, extra={"tag": tag})


# --------------------------------------------------------------------------
# Config
# --------------------------------------------------------------------------
DEFAULT_CONFIG: Dict[str, Any] = {
    "host": "0.0.0.0",
    "port": 8765,
    "poll_interval_ms": 400,       # how often session volumes are re-read
    "enumerate_interval_ms": 1500,  # how often the session list is rebuilt
    "discovery_enabled": True,
    "discovery_port": 8766,
    "require_pairing": False,       # LAN-only by default -> pairing optional
    "pairing_code": None,           # null -> generated at every start
    "icons_enabled": True,
    "icon_size": 64,
    "debug": False,
}


def load_config() -> Dict[str, Any]:
    cfg = dict(DEFAULT_CONFIG)
    if CONFIG_PATH.exists():
        try:
            user = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
            if isinstance(user, dict):
                cfg.update({k: v for k, v in user.items() if k in DEFAULT_CONFIG})
        except Exception as exc:
            W("CONFIG", "config.json is invalid (%s) - using defaults", exc)
    else:
        try:
            CONFIG_PATH.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
        except Exception:
            pass
    return cfg


# --------------------------------------------------------------------------
# Networking helpers
# --------------------------------------------------------------------------
def get_lan_ip() -> str:
    """Best effort detection of the LAN address actually used for routing."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))          # no packet is sent for UDP connect
        return s.getsockname()[0]
    except Exception:
        try:
            return socket.gethostbyname(socket.gethostname())
        except Exception:
            return "127.0.0.1"
    finally:
        s.close()


# --------------------------------------------------------------------------
# Icon extraction (executable -> PNG), cached by exe path
# --------------------------------------------------------------------------
class IconCache:
    def __init__(self, size: int = 64, enabled: bool = True) -> None:
        self.size = size
        self.enabled = enabled
        self._by_path: Dict[str, Optional[bytes]] = {}
        self._lock = threading.Lock()
        self._available = False
        if enabled and IS_WINDOWS:
            try:
                import win32gui  # noqa: F401
                import win32ui   # noqa: F401
                from PIL import Image  # noqa: F401
                self._available = True
            except Exception as exc:
                D("ICON", "icon extraction unavailable (%s) - generic fallback", exc)

    @property
    def available(self) -> bool:
        return self._available

    def get(self, exe_path: Optional[str]) -> Optional[bytes]:
        if not (self.enabled and self._available and exe_path):
            return None
        key = exe_path.lower()
        with self._lock:
            if key in self._by_path:
                return self._by_path[key]
        png = None
        try:
            png = self._extract(exe_path)
        except Exception as exc:
            D("ICON", "extract failed for %s: %s", exe_path, exc)
        with self._lock:
            self._by_path[key] = png
        return png

    def _extract(self, exe_path: str) -> Optional[bytes]:
        import io
        import win32gui
        import win32ui
        import win32con
        from PIL import Image

        path = exe_path
        if path.startswith("@"):            # resource style path, unsupported
            return None
        if "," in path and not os.path.exists(path):
            path = path.split(",")[0]
        if not os.path.exists(path):
            return None

        large, small = win32gui.ExtractIconEx(path, 0)
        handles = list(large) + list(small)
        if not handles:
            return None
        hicon = handles[0]
        size = self.size
        hdc_screen = None
        try:
            hdc_screen = win32ui.CreateDCFromHandle(win32gui.GetDC(0))
            hbmp = win32ui.CreateBitmap()
            hbmp.CreateCompatibleBitmap(hdc_screen, size, size)
            hdc_mem = hdc_screen.CreateCompatibleDC()
            hdc_mem.SelectObject(hbmp)
            win32gui.DrawIconEx(
                hdc_mem.GetHandleOutput(), 0, 0, hicon, size, size, 0, None,
                win32con.DI_NORMAL,
            )
            raw = hbmp.GetBitmapBits(True)
            img = Image.frombuffer("RGBA", (size, size), raw, "raw", "BGRA", 0, 1)
            # Some icons come back with a fully zeroed alpha channel; in that
            # case treat black as transparent-ish and force the rest opaque.
            alpha = img.getchannel("A")
            if alpha.getextrema() == (0, 0):
                rgb = img.convert("RGB")
                px = rgb.load()
                img = Image.new("RGBA", (size, size))
                out = img.load()
                for y in range(size):
                    for x in range(size):
                        r, g, b = px[x, y]
                        out[x, y] = (r, g, b, 0 if (r + g + b) == 0 else 255)
            buf = io.BytesIO()
            img.save(buf, format="PNG", optimize=True)
            return buf.getvalue()
        finally:
            for h in handles:
                try:
                    win32gui.DestroyIcon(h)
                except Exception:
                    pass
            try:
                if hdc_screen is not None:
                    win32gui.ReleaseDC(0, hdc_screen.GetSafeHdc())
            except Exception:
                pass


# --------------------------------------------------------------------------
# Session model
# --------------------------------------------------------------------------
PRETTY_NAMES = {
    "chrome.exe": "Google Chrome",
    "msedge.exe": "Microsoft Edge",
    "firefox.exe": "Firefox",
    "discord.exe": "Discord",
    "spotify.exe": "Spotify",
    "steam.exe": "Steam",
    "steamwebhelper.exe": "Steam",
    "vlc.exe": "VLC",
    "telegram.exe": "Telegram",
    "whatsapp.exe": "WhatsApp",
    "obs64.exe": "OBS Studio",
    "audiodg.exe": "Windows Audio Device Graph",
    "explorer.exe": "Windows Explorer",
}


def prettify(process_name: str) -> str:
    key = (process_name or "").lower()
    if key in PRETTY_NAMES:
        return PRETTY_NAMES[key]
    base = os.path.splitext(process_name or "Unknown")[0]
    base = base.replace("_", " ").replace("-", " ").strip()
    if base and base.islower():
        base = base.title()
    return base or "Unknown"


class SessionEntry:
    """One live Windows Audio Session (there can be several per process)."""

    __slots__ = (
        "id", "pid", "process_name", "display_name", "exe_path",
        "volume", "muted", "session", "simple", "instance_id",
        "last_active", "premute_volume", "dead",
    )

    def __init__(self) -> None:
        self.id: str = ""
        self.pid: int = 0
        self.process_name: str = ""
        self.display_name: str = ""
        self.exe_path: Optional[str] = None
        self.volume: int = 0
        self.muted: bool = False
        self.session: Any = None
        self.simple: Any = None
        self.instance_id: str = ""
        self.last_active: float = time.time()
        self.premute_volume: int = 50
        self.dead: bool = False

    def to_dict(self, icon_url: Optional[str]) -> Dict[str, Any]:
        return {
            "id": self.id,
            "pid": self.pid,
            "process_name": self.process_name,
            "display_name": self.display_name,
            "volume": self.volume,
            "muted": self.muted,
            "icon": icon_url,
            "last_active": int(self.last_active * 1000),
        }


def _sid(instance_identifier: str, pid: int) -> str:
    h = hashlib.sha1(instance_identifier.encode("utf-8", "ignore")).hexdigest()[:10]
    return "%d-%s" % (pid, h)


# --------------------------------------------------------------------------
# Audio engine  (the ONLY thread that touches COM)
# --------------------------------------------------------------------------
class AudioEngine(threading.Thread):
    def __init__(self, cfg: Dict[str, Any], icons: IconCache) -> None:
        super().__init__(name="AudioEngine", daemon=True)
        self.cfg = cfg
        self.icons = icons
        self._cmd: "queue.Queue[Tuple[Callable[[], Any], Future]]" = queue.Queue()
        self._stop = threading.Event()
        self._sessions: Dict[str, SessionEntry] = {}
        self._snapshot: List[Dict[str, Any]] = []
        self._snapshot_lock = threading.Lock()
        self._master: Dict[str, Any] = {"volume": 0, "muted": False}
        self._endpoint: Any = None
        self.event_sink: Optional[Callable[[Dict[str, Any]], None]] = None
        self.ready = threading.Event()
        self._icon_pngs: Dict[str, bytes] = {}   # session id -> png
        self.audio_ok = False

    # -------------------------- public (thread safe) ----------------------
    def submit(self, fn: Callable[[], Any], timeout: float = 4.0) -> Any:
        if self._stop.is_set():
            raise RuntimeError("engine stopped")
        fut: Future = Future()
        self._cmd.put((fn, fut))
        return fut.result(timeout=timeout)

    def stop(self) -> None:
        self._stop.set()

    def snapshot(self) -> List[Dict[str, Any]]:
        with self._snapshot_lock:
            return list(self._snapshot)

    def master(self) -> Dict[str, Any]:
        with self._snapshot_lock:
            return dict(self._master)

    def icon_png(self, session_id: str) -> Optional[bytes]:
        return self._icon_pngs.get(session_id)

    # commands ------------------------------------------------------------
    def set_volume(self, sid: str, volume: int) -> Dict[str, Any]:
        return self.submit(lambda: self._set_volume(sid, volume))

    def set_mute(self, sid: str, muted: bool) -> Dict[str, Any]:
        return self.submit(lambda: self._set_mute(sid, muted))

    def force_refresh(self) -> List[Dict[str, Any]]:
        return self.submit(lambda: self._refresh_sessions(force_emit=False) or self.snapshot())

    # -------------------------- thread body -------------------------------
    def run(self) -> None:
        comtypes.CoInitialize()
        try:
            self._init_endpoint()
            try:
                self._refresh_sessions(force_emit=False)
                self.audio_ok = True
            except Exception as exc:
                W("AUDIO", "initial enumeration failed: %s", exc)
            self.ready.set()
            L("AUDIO", "Applications detected: %d", len(self._sessions))
            self._loop()
        finally:
            self._sessions.clear()
            self._endpoint = None
            comtypes.CoUninitialize()
            D("AUDIO", "engine thread stopped")

    def _loop(self) -> None:
        poll = max(100, int(self.cfg["poll_interval_ms"])) / 1000.0
        enum = max(300, int(self.cfg["enumerate_interval_ms"])) / 1000.0
        next_poll = time.monotonic() + poll
        next_enum = time.monotonic() + enum
        while not self._stop.is_set():
            # 1) commands first, they are latency sensitive
            try:
                fn, fut = self._cmd.get(timeout=0.04)
            except queue.Empty:
                pass
            else:
                try:
                    fut.set_result(fn())
                except Exception as exc:            # never kill the thread
                    fut.set_exception(exc)
            now = time.monotonic()
            # 2) cheap value diff
            if now >= next_poll:
                next_poll = now + poll
                try:
                    self._poll_values()
                except Exception as exc:
                    D("AUDIO", "poll error: %s", exc)
            # 3) session list changes (app opened / closed)
            if now >= next_enum:
                next_enum = now + enum
                try:
                    self._refresh_sessions(force_emit=True)
                except Exception as exc:
                    D("AUDIO", "enumerate error: %s", exc)

    # -------------------------- internals ---------------------------------
    def _init_endpoint(self) -> None:
        try:
            speakers = AudioUtilities.GetSpeakers()
            iface = speakers.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
            self._endpoint = ctypes.cast(iface, ctypes.POINTER(IAudioEndpointVolume))
            vol = int(round(self._endpoint.GetMasterVolumeLevelScalar() * 100))
            mute = bool(self._endpoint.GetMute())
            with self._snapshot_lock:
                self._master = {"volume": vol, "muted": mute}
            L("AUDIO", "Default output device ready (master %d%%)", vol)
        except Exception as exc:
            self._endpoint = None
            W("AUDIO", "no default audio endpoint (%s) - master volume disabled", exc)

    def _emit(self, msg: Dict[str, Any]) -> None:
        sink = self.event_sink
        if sink is not None:
            try:
                sink(msg)
            except Exception as exc:
                D("WS", "event sink error: %s", exc)

    def _rebuild_snapshot(self) -> None:
        apps = []
        for e in sorted(self._sessions.values(), key=lambda s: -s.last_active):
            icon = "/api/icon/%s" % e.id if e.id in self._icon_pngs else None
            apps.append(e.to_dict(icon))
        with self._snapshot_lock:
            self._snapshot = apps

    def _refresh_sessions(self, force_emit: bool = True) -> None:
        """Enumerate the real Windows audio sessions and diff against cache."""
        try:
            live = AudioUtilities.GetAllSessions()
        except Exception as exc:
            if self.audio_ok:
                W("AUDIO", "GetAllSessions failed: %s", exc)
            return

        seen: Dict[str, SessionEntry] = {}
        added: List[str] = []
        for s in live:
            try:
                simple = s.SimpleAudioVolume
                if simple is None:
                    continue
                try:
                    inst = s._ctl.GetSessionInstanceIdentifier()  # unique per session
                except Exception:
                    inst = ""
                pid = int(getattr(s, "ProcessId", 0) or 0)
                proc = s.Process
                if proc is not None:
                    pname = proc.name()
                else:
                    pname = "System Sounds" if pid == 0 else "Unknown"
                if not inst:
                    inst = "%s|%s" % (pid, pname)
                sid = _sid(inst, pid)
                if sid in self._sessions:
                    e = self._sessions[sid]
                    e.session, e.simple, e.dead = s, simple, False
                else:
                    e = SessionEntry()
                    e.id = sid
                    e.instance_id = inst
                    e.pid = pid
                    e.process_name = pname
                    e.session = s
                    e.simple = simple
                    disp = ""
                    try:
                        disp = (s.DisplayName or "").strip()
                    except Exception:
                        disp = ""
                    if not disp or disp.startswith("@"):
                        disp = prettify(pname) if pid else "System Sounds"
                    e.display_name = disp
                    e.exe_path = self._exe_path(proc)
                    e.volume = int(round(simple.GetMasterVolume() * 100))
                    e.muted = bool(simple.GetMute())
                    e.premute_volume = e.volume if e.volume > 0 else 50
                    self._load_icon(e)
                    added.append(e.display_name)
                seen[sid] = e
            except COMError:
                continue
            except Exception as exc:
                D("AUDIO", "skipping a session: %s", exc)
                continue

        removed = [self._sessions[k].display_name for k in self._sessions if k not in seen]
        changed = bool(added or removed)
        if changed:
            for k in list(self._sessions.keys()):
                if k not in seen:
                    self._icon_pngs.pop(k, None)
            self._sessions = seen
            self._rebuild_snapshot()
            for name in added:
                L("AUDIO", "Session opened: %s", name)
            for name in removed:
                L("AUDIO", "Session closed: %s", name)
            L("AUDIO", "Applications detected: %d", len(self._sessions))
            if force_emit:
                self._emit({"type": "apps_updated", "apps": self.snapshot()})
        else:
            self._sessions = seen

    @staticmethod
    def _exe_path(proc: Any) -> Optional[str]:
        if proc is None:
            return None
        try:
            return proc.exe()
        except Exception:
            return None

    def _load_icon(self, e: SessionEntry) -> None:
        if not self.icons.enabled:
            return
        png = self.icons.get(e.exe_path)
        if png:
            self._icon_pngs[e.id] = png

    def _poll_values(self) -> None:
        events: List[Dict[str, Any]] = []
        dead: List[str] = []
        for sid, e in list(self._sessions.items()):
            try:
                vol = int(round(e.simple.GetMasterVolume() * 100))
                mute = bool(e.simple.GetMute())
            except COMError:
                dead.append(sid)
                continue
            except Exception:
                dead.append(sid)
                continue
            if vol != e.volume or mute != e.muted:
                if vol != e.volume:
                    L("AUDIO", "%s volume changed: %d%%", e.display_name, vol)
                if mute != e.muted:
                    L("AUDIO", "%s mute changed: %s", e.display_name, mute)
                e.volume, e.muted = vol, mute
                e.last_active = time.time()
                if vol > 0:
                    e.premute_volume = vol
                events.append({
                    "type": "volume_update", "id": sid,
                    "volume": vol, "muted": mute,
                })
        if self._endpoint is not None:
            try:
                mv = int(round(self._endpoint.GetMasterVolumeLevelScalar() * 100))
                mm = bool(self._endpoint.GetMute())
                cur = self.master()
                if mv != cur["volume"] or mm != cur["muted"]:
                    with self._snapshot_lock:
                        self._master = {"volume": mv, "muted": mm}
                    events.append({
                        "type": "volume_update", "id": MASTER_ID,
                        "volume": mv, "muted": mm,
                    })
            except Exception:
                pass
        if events:
            self._rebuild_snapshot()
            for ev in events:
                self._emit(ev)
        if dead:
            for sid in dead:
                self._sessions.pop(sid, None)
                self._icon_pngs.pop(sid, None)
            self._rebuild_snapshot()
            self._emit({"type": "apps_updated", "apps": self.snapshot()})

    # ---- actual writes ---------------------------------------------------
    def _set_volume(self, sid: str, volume: int) -> Dict[str, Any]:
        volume = max(0, min(100, int(volume)))
        if sid == MASTER_ID:
            if self._endpoint is None:
                raise LookupError("no default audio endpoint")
            self._endpoint.SetMasterVolumeLevelScalar(volume / 100.0, None)
            with self._snapshot_lock:
                self._master["volume"] = volume
            D("AUDIO", "Master volume set: %d%%", volume)
            return {"id": MASTER_ID, "volume": volume, "muted": self.master()["muted"]}
        e = self._sessions.get(sid)
        if e is None:
            raise LookupError("unknown session id %s" % sid)
        if e.pid and not psutil.pid_exists(e.pid):
            self._sessions.pop(sid, None)
            raise LookupError("process %d no longer exists" % e.pid)
        try:
            e.simple.SetMasterVolume(volume / 100.0, None)
        except COMError as exc:
            self._sessions.pop(sid, None)
            self._rebuild_snapshot()
            raise LookupError("audio session gone (%s)" % exc)
        e.volume = volume
        e.last_active = time.time()
        if volume > 0:
            e.premute_volume = volume
        self._rebuild_snapshot()
        D("AUDIO", "%s volume set: %d%%", e.display_name, volume)
        return {"id": sid, "volume": volume, "muted": e.muted}

    def _set_mute(self, sid: str, muted: bool) -> Dict[str, Any]:
        muted = bool(muted)
        if sid == MASTER_ID:
            if self._endpoint is None:
                raise LookupError("no default audio endpoint")
            self._endpoint.SetMute(1 if muted else 0, None)
            with self._snapshot_lock:
                self._master["muted"] = muted
            return {"id": MASTER_ID, "volume": self.master()["volume"], "muted": muted}
        e = self._sessions.get(sid)
        if e is None:
            raise LookupError("unknown session id %s" % sid)
        try:
            if muted:
                if e.volume > 0:
                    e.premute_volume = e.volume
                e.simple.SetMute(1, None)
            else:
                e.simple.SetMute(0, None)
                # restore the remembered level if the app was left at 0
                if int(round(e.simple.GetMasterVolume() * 100)) == 0 and e.premute_volume > 0:
                    e.simple.SetMasterVolume(e.premute_volume / 100.0, None)
                    e.volume = e.premute_volume
        except COMError as exc:
            self._sessions.pop(sid, None)
            self._rebuild_snapshot()
            raise LookupError("audio session gone (%s)" % exc)
        e.muted = muted
        e.last_active = time.time()
        self._rebuild_snapshot()
        D("AUDIO", "%s mute set: %s", e.display_name, muted)
        return {"id": sid, "volume": e.volume, "muted": muted}


# --------------------------------------------------------------------------
# Pairing / tokens
# --------------------------------------------------------------------------
class PairingStore:
    def __init__(self, cfg: Dict[str, Any]) -> None:
        self.required = bool(cfg.get("require_pairing"))
        self.code = str(cfg.get("pairing_code") or "").strip() or None
        if self.required and not self.code:
            self.code = "%06d" % (secrets.randbits(32) % 1000000)
        self._tokens: Dict[str, str] = {}
        self._lock = threading.Lock()
        self._load()

    def _load(self) -> None:
        if TOKENS_PATH.exists():
            try:
                data = json.loads(TOKENS_PATH.read_text(encoding="utf-8"))
                if isinstance(data, dict):
                    self._tokens = {str(k): str(v) for k, v in data.items()}
            except Exception:
                self._tokens = {}

    def _save(self) -> None:
        try:
            TOKENS_PATH.write_text(json.dumps(self._tokens, indent=2), encoding="utf-8")
        except Exception as exc:
            D("AUTH", "could not persist tokens: %s", exc)

    def check_token(self, token: Optional[str]) -> bool:
        if not self.required:
            return True
        if not token:
            return False
        with self._lock:
            return token in self._tokens

    def pair(self, code: str, client_name: str) -> Optional[str]:
        if not self.required:
            return None
        if not code or code.strip() != self.code:
            return None
        token = secrets.token_urlsafe(24)
        with self._lock:
            self._tokens[token] = client_name or "android"
            self._save()
        L("AUTH", "Paired new client: %s", client_name or "android")
        return token


# --------------------------------------------------------------------------
# WebSocket connection manager
# --------------------------------------------------------------------------
class ConnectionManager:
    def __init__(self) -> None:
        self._clients: Dict[WebSocket, Dict[str, Any]] = {}
        self._lock = asyncio.Lock()

    @property
    def count(self) -> int:
        return len(self._clients)

    def peers(self) -> List[Dict[str, Any]]:
        return [
            {"name": meta.get("name", "?"), "ip": meta.get("ip", "?"),
             "since": meta.get("since", 0), "authenticated": meta.get("auth", False)}
            for meta in self._clients.values()
        ]

    async def add(self, ws: WebSocket, meta: Dict[str, Any]) -> None:
        async with self._lock:
            self._clients[ws] = meta

    async def remove(self, ws: WebSocket) -> None:
        async with self._lock:
            self._clients.pop(ws, None)

    async def broadcast(self, msg: Dict[str, Any], skip: Optional[WebSocket] = None) -> None:
        payload = json.dumps(msg, separators=(",", ":"))
        dead: List[WebSocket] = []
        for ws, meta in list(self._clients.items()):
            if ws is skip or not meta.get("auth"):
                continue
            try:
                await ws.send_text(payload)
            except Exception:
                dead.append(ws)
        for ws in dead:
            await self.remove(ws)


# --------------------------------------------------------------------------
# UDP discovery responder + announcer
# --------------------------------------------------------------------------
DISCOVERY_PROBE = b"RVMX_DISCOVER"


class DiscoveryService(threading.Thread):
    def __init__(self, port: int, http_port: int, pairing: PairingStore) -> None:
        super().__init__(name="Discovery", daemon=True)
        self.port = port
        self.http_port = http_port
        self.pairing = pairing
        self._stop = threading.Event()

    def payload(self) -> bytes:
        return json.dumps({
            "service": SERVICE_NAME,
            "name": socket.gethostname().upper(),
            "ip": get_lan_ip(),
            "port": self.http_port,
            "ws": "/ws",
            "requires_pairing": self.pairing.required,
            "version": VERSION,
        }, separators=(",", ":")).encode("utf-8")

    def stop(self) -> None:
        self._stop.set()

    def run(self) -> None:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.bind(("0.0.0.0", self.port))
            sock.settimeout(1.0)
        except Exception as exc:
            W("DISCOVERY", "could not start (%s)", exc)
            return
        L("DISCOVERY", "UDP responder listening on %d", self.port)
        last_announce = 0.0
        while not self._stop.is_set():
            try:
                data, addr = sock.recvfrom(1024)
                if DISCOVERY_PROBE in data or SERVICE_NAME.encode() in data:
                    sock.sendto(self.payload(), addr)
                    D("DISCOVERY", "probe from %s:%d answered", addr[0], addr[1])
            except socket.timeout:
                pass
            except Exception as exc:
                D("DISCOVERY", "loop error: %s", exc)
            now = time.monotonic()
            if now - last_announce > 5.0:       # passive announce, very cheap
                last_announce = now
                try:
                    sock.sendto(self.payload(), ("255.255.255.255", self.port))
                except Exception:
                    pass
        try:
            sock.close()
        except Exception:
            pass


# --------------------------------------------------------------------------
# FastAPI application
# --------------------------------------------------------------------------
def build_app(cfg: Dict[str, Any], engine: AudioEngine, pairing: PairingStore,
              lan_ip: str) -> FastAPI:
    app = FastAPI(
        title="Remote Volume Mixer Server",
        version=VERSION,
        description="LAN WebSocket bridge between an Android client and the "
                    "Windows Core Audio session mixer.",
    )
    manager = ConnectionManager()
    app.state.manager = manager
    app.state.started_at = time.time()

    # ---- bridge: engine thread -> asyncio broadcast -----------------------
    loop_holder: Dict[str, Any] = {"loop": None}

    def sink(msg: Dict[str, Any]) -> None:
        loop = loop_holder["loop"]
        if loop is None:
            return
        try:
            asyncio.run_coroutine_threadsafe(manager.broadcast(msg), loop)
        except Exception:
            pass

    @app.on_event("startup")
    async def _startup() -> None:
        loop_holder["loop"] = asyncio.get_running_loop()
        engine.event_sink = sink

    @app.on_event("shutdown")
    async def _shutdown() -> None:
        engine.event_sink = None

    # ---- HTTP debug API ---------------------------------------------------
    @app.get("/", response_class=HTMLResponse)
    async def root() -> str:
        apps = engine.snapshot()
        rows = "".join(
            "<tr><td>%s</td><td>%s</td><td>%d</td><td>%d%%</td><td>%s</td></tr>"
            % (a["display_name"], a["process_name"], a["pid"], a["volume"],
               "muted" if a["muted"] else "")
            for a in apps
        ) or "<tr><td colspan=5>no audio session right now</td></tr>"
        return """<!doctype html><html><head><meta charset=utf-8>
<title>Remote Volume Mixer Server</title>
<style>body{background:#0d0f14;color:#e7ebf3;font:14px/1.5 -apple-system,Segoe UI,sans-serif;padding:40px}
h1{font-weight:600;letter-spacing:-.5px}code{background:#1a1f2b;padding:2px 6px;border-radius:6px}
table{border-collapse:collapse;margin-top:16px;width:100%%;max-width:720px}
td,th{border-bottom:1px solid #222836;padding:8px 10px;text-align:left}
.ok{color:#4ade80}a{color:#7c9cff}</style></head><body>
<h1>Remote Volume Mixer <span class=ok>&#9679; online</span></h1>
<p>WebSocket: <code>ws://%s:%d/ws</code> &nbsp; Clients: <b>%d</b></p>
<p><a href="/api/status">/api/status</a> &middot; <a href="/api/apps">/api/apps</a> &middot;
<a href="/docs">/docs</a></p>
<table><tr><th>App</th><th>Process</th><th>PID</th><th>Volume</th><th></th></tr>%s</table>
</body></html>""" % (lan_ip, cfg["port"], manager.count, rows)

    @app.get("/api/status")
    async def status() -> Dict[str, Any]:
        return {
            "status": "online",
            "ip": lan_ip,
            "port": int(cfg["port"]),
            "clients": manager.count,
            "version": VERSION,
            "hostname": socket.gethostname().upper(),
            "requires_pairing": pairing.required,
            "audio_available": engine.audio_ok,
            "icons_available": engine.icons.available,
            "sessions": len(engine.snapshot()),
            "uptime_s": int(time.time() - app.state.started_at),
            "peers": manager.peers(),
        }

    @app.get("/api/apps")
    async def apps() -> Dict[str, Any]:
        return {"apps": engine.snapshot(), "master": engine.master()}

    @app.get("/api/icon/{session_id}")
    async def icon(session_id: str) -> Response:
        png = engine.icon_png(session_id)
        if not png:
            return Response(status_code=404, content=b"")
        return Response(content=png, media_type="image/png",
                        headers={"Cache-Control": "public, max-age=86400"})

    # ---- WebSocket -------------------------------------------------------
    @app.websocket("/ws")
    async def ws_endpoint(ws: WebSocket) -> None:
        await ws.accept()
        peer = ws.client.host if ws.client else "?"
        meta = {"name": "unknown", "ip": peer, "since": int(time.time() * 1000),
                "auth": not pairing.required}
        await manager.add(ws, meta)
        L("WS", "Client connected (%s)%s", peer,
          "" if not pairing.required else " - pairing required")

        async def send(msg: Dict[str, Any]) -> None:
            await ws.send_text(json.dumps(msg, separators=(",", ":")))

        async def send_apps(kind: str = "apps") -> None:
            await send({"type": kind, "apps": engine.snapshot(),
                        "master": engine.master()})

        try:
            await send({
                "type": "hello",
                "server": SERVICE_NAME,
                "version": VERSION,
                "hostname": socket.gethostname().upper(),
                "requires_pairing": pairing.required,
                "protocol": 1,
            })
            while True:
                raw = await ws.receive_text()
                try:
                    msg = json.loads(raw)
                    if not isinstance(msg, dict):
                        raise ValueError("not an object")
                except Exception:
                    await send({"type": "error", "code": "bad_json",
                                "message": "payload is not valid JSON"})
                    continue
                mtype = str(msg.get("type", ""))
                D("WS", "<- %s %s", peer, raw[:200])

                # ---------------- auth handshake ----------------
                if mtype == "auth":
                    token = msg.get("token")
                    name = str(msg.get("name") or "android")
                    meta["name"] = name
                    if pairing.check_token(token):
                        meta["auth"] = True
                        await send({"type": "auth_ok", "token": token})
                        await send_apps()
                    else:
                        await send({"type": "auth_required"})
                    continue

                if mtype == "pair":
                    token = pairing.pair(str(msg.get("code") or ""),
                                         str(msg.get("name") or "android"))
                    if token:
                        meta["auth"] = True
                        await send({"type": "paired", "token": token})
                        await send_apps()
                    else:
                        await send({"type": "pair_failed",
                                    "message": "wrong pairing code"})
                    continue

                if not meta["auth"]:
                    await send({"type": "auth_required"})
                    continue

                # ---------------- normal protocol ----------------
                if mtype == "get_apps":
                    await send_apps()

                elif mtype == "ping":
                    await send({"type": "pong", "t": msg.get("t"),
                                "server_time": int(time.time() * 1000)})

                elif mtype in ("set_volume", "set_mute"):
                    sid = str(msg.get("id") or "")
                    try:
                        if mtype == "set_volume":
                            res = await asyncio.to_thread(
                                engine.set_volume, sid, int(msg.get("volume", 0)))
                            ack = {"type": "volume_changed", "id": sid,
                                   "volume": res["volume"]}
                        else:
                            res = await asyncio.to_thread(
                                engine.set_mute, sid, bool(msg.get("muted")))
                            ack = {"type": "mute_changed", "id": sid,
                                   "muted": res["muted"]}
                        await send(ack)
                        # keep every other phone in sync immediately
                        await manager.broadcast({
                            "type": "volume_update", "id": sid,
                            "volume": res["volume"], "muted": res["muted"],
                        }, skip=ws)
                    except LookupError as exc:
                        await send({"type": "error", "code": "session_gone",
                                    "id": sid, "message": str(exc)})
                        await send_apps("apps_updated")
                    except Exception as exc:
                        W("AUDIO", "%s failed: %s", mtype, exc)
                        await send({"type": "error", "code": "audio_error",
                                    "id": sid, "message": str(exc)})

                elif mtype == "get_icon":
                    sid = str(msg.get("id") or "")
                    png = engine.icon_png(sid)
                    await send({
                        "type": "icon", "id": sid,
                        "png_base64": base64.b64encode(png).decode() if png else None,
                    })

                elif mtype == "refresh":
                    await asyncio.to_thread(engine.force_refresh)
                    await send_apps("apps_updated")

                else:
                    await send({"type": "error", "code": "unknown_type",
                                "message": "unsupported message type '%s'" % mtype})

        except WebSocketDisconnect:
            pass
        except Exception as exc:
            D("WS", "client error: %s", exc)
        finally:
            await manager.remove(ws)
            L("WS", "Client disconnected (%s)", peer)

    return app


# --------------------------------------------------------------------------
# Banner + main
# --------------------------------------------------------------------------
def banner(lan_ip: str, port: int, pairing: PairingStore) -> None:
    line = "=" * 40
    print("")
    print(line)
    print(" REMOTE VOLUME MIXER SERVER")
    print(line)
    print("")
    print(" Server: ONLINE")
    print("")
    print(" Local address:")
    print("   http://127.0.0.1:%d" % port)
    print("")
    print(" LAN address:")
    print("   http://%s:%d" % (lan_ip, port))
    print("")
    print(" WebSocket:")
    print("   ws://%s:%d/ws" % (lan_ip, port))
    print("")
    print(" Port:")
    print("   %d" % port)
    if pairing.required:
        print("")
        print(" PAIRING CODE:")
        print("")
        print("   %s" % pairing.code)
    print("")
    print(" Waiting for Android client...")
    print("")
    print(line)
    print("")


def main() -> int:
    ap = argparse.ArgumentParser(description="Remote Volume Mixer - Windows server")
    ap.add_argument("--port", type=int, help="TCP port (default 8765)")
    ap.add_argument("--host", help="bind address (default 0.0.0.0)")
    ap.add_argument("--debug", action="store_true", help="verbose logging")
    ap.add_argument("--pairing", action="store_true", help="force PIN pairing on")
    ap.add_argument("--no-discovery", action="store_true", help="disable UDP discovery")
    ap.add_argument("--no-icons", action="store_true", help="disable icon extraction")
    args = ap.parse_args()

    cfg = load_config()
    if args.port:
        cfg["port"] = args.port
    if args.host:
        cfg["host"] = args.host
    if args.debug:
        cfg["debug"] = True
    if args.pairing:
        cfg["require_pairing"] = True
    if args.no_discovery:
        cfg["discovery_enabled"] = False
    if args.no_icons:
        cfg["icons_enabled"] = False

    _setup_logging(bool(cfg["debug"]))

    if not IS_WINDOWS:
        print("[FATAL] This server controls the Windows audio mixer and must run "
              "on Windows 10/11.")
        return 2

    L("SERVER", "Starting Remote Volume Mixer %s (python %s)",
      VERSION, sys.version.split()[0])
    lan_ip = get_lan_ip()
    L("NETWORK", "LAN IP: %s", lan_ip)

    icons = IconCache(int(cfg["icon_size"]), bool(cfg["icons_enabled"]))
    if cfg["icons_enabled"] and not icons.available:
        W("ICON", "pywin32/Pillow missing - clients will use generic icons")

    engine = AudioEngine(cfg, icons)
    engine.start()
    if not engine.ready.wait(timeout=10):
        W("AUDIO", "engine slow to start - continuing anyway")

    pairing = PairingStore(cfg)
    discovery: Optional[DiscoveryService] = None
    if cfg["discovery_enabled"]:
        discovery = DiscoveryService(int(cfg["discovery_port"]), int(cfg["port"]), pairing)
        discovery.start()

    app = build_app(cfg, engine, pairing, lan_ip)
    banner(lan_ip, int(cfg["port"]), pairing)
    L("SERVER", "Started")

    config = uvicorn.Config(
        app,
        host=str(cfg["host"]),
        port=int(cfg["port"]),
        log_level="debug" if cfg["debug"] else "warning",
        access_log=bool(cfg["debug"]),
        ws_ping_interval=20,
        ws_ping_timeout=20,
    )
    server = uvicorn.Server(config)
    try:
        server.run()
    except KeyboardInterrupt:
        pass
    finally:
        L("SERVER", "Shutting down")
        if discovery:
            discovery.stop()
        engine.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())

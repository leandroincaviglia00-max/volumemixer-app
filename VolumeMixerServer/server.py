import comtypes
import json
import socket

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware

from pycaw.pycaw import AudioUtilities


app = FastAPI()


# ============================================================
# CORS
# ============================================================

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ============================================================
# GET PC IP
# ============================================================

def get_local_ip():

    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))

        ip = s.getsockname()[0]

        s.close()

        return ip

    except Exception:
        return "127.0.0.1"


# ============================================================
# GET WINDOWS AUDIO APPLICATIONS
# ============================================================

def get_apps():

    comtypes.CoInitialize()

    try:

        sessions = AudioUtilities.GetAllSessions()

        apps = []

        for index, session in enumerate(sessions):

            process = session.Process

            if process is None:
                continue

            try:

                audio = session.SimpleAudioVolume

                apps.append({
                    "id": index,
                    "name": process.name(),
                    "pid": process.pid,
                    "volume": round(
                        audio.GetMasterVolume() * 100
                    ),
                    "muted": bool(
                        audio.GetMute()
                    )
                })

            except Exception as e:

                print(
                    "Errore sessione audio:",
                    e
                )

        return apps

    finally:

        comtypes.CoUninitialize()


# ============================================================
# HTTP TEST
# ============================================================

@app.get("/")
def root():

    return {
        "status": "online",
        "server": "Volume Mixer Server",
        "ip": get_local_ip(),
        "port": 8765
    }


# ============================================================
# HTTP APPS
# ============================================================

@app.get("/api/apps")
def api_apps():

    return get_apps()


# ============================================================
# WEBSOCKET
# ============================================================

@app.websocket("/")
async def websocket_endpoint(websocket: WebSocket):

    await websocket.accept()

    print("")
    print("========================================")
    print("📱 WebSocket client connesso!")
    print("========================================")

    try:

        while True:

            message = await websocket.receive_text()

            print("")
            print("📨 CLIENT → SERVER")
            print(message)

            try:

                data = json.loads(message)

                cmd = data.get("cmd")

                print("🔎 Comando:", cmd)


                # ====================================================
                # UI CONNECTED
                # ====================================================

                if cmd == "UIConnected":

                    response = {
                        "cmd": "UIConnected",
                        "payload": {
                            "success": True
                        }
                    }

                    await websocket.send_text(
                        json.dumps(response)
                    )

                    print(
                        "📤 SERVER → CLIENT"
                    )

                    print(response)


                # ====================================================
                # GET APPLICATIONS
                # ====================================================

                elif cmd == "GetApplications":

                    applications = get_apps()

                    response = {
                        "cmd": "GetApplications",
                        "payload": {
                            "applications": applications
                        }
                    }

                    await websocket.send_text(
                        json.dumps(response)
                    )

                    print(
                        f"📤 Inviate {len(applications)} applicazioni"
                    )


                # ====================================================
                # UNKNOWN COMMAND
                # ====================================================

                else:

                    print(
                        "⚠️ Comando non ancora implementato:",
                        cmd
                    )

                    response = {
                        "cmd": cmd,
                        "payload": {
                            "success": True
                        }
                    }

                    await websocket.send_text(
                        json.dumps(response)
                    )


            except Exception as e:

                print(
                    "❌ Errore elaborazione messaggio:",
                    e
                )


    except WebSocketDisconnect:

        print("")
        print("📱 WebSocket client disconnesso")


    except Exception as e:

        print(
            "❌ Errore WebSocket:",
            e
        )

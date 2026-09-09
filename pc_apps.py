import os
import subprocess
from typing import Any, Dict, List, Optional

SLEEP_MIN_MINUTES = 1
SLEEP_MAX_MINUTES = 1440

PC_APPS: List[Dict[str, Any]] = [
    {"id": "youtube", "label": "▶ YouTube", "cmd": ["xdg-open", "https://youtube.com"], "destructive": False},
    {"id": "vscode", "label": "💻 VS Code", "cmd": ["code"], "destructive": False},
    {"id": "max", "label": "🎬 Max", "cmd": ["xdg-open", "https://play.max.com"], "destructive": False},
    {"id": "caffeine", "label": "☕ Caffeine", "cmd": ["caffeine"], "destructive": False},
    {"id": "lock", "label": "🔒 Bloqueio", "cmd": ["xdg-screensaver", "lock"], "destructive": True},
    {"id": "sleep", "label": "💤 Sleep", "cmd": None, "destructive": True, "kind": "sleep"},
]

_INDEX = {app["id"]: app for app in PC_APPS}


def list_apps() -> List[Dict[str, Any]]:
    return [{"id": a["id"], "label": a["label"], "destructive": a["destructive"]} for a in PC_APPS]


def get_app(app_id: str) -> Optional[Dict[str, Any]]:
    return _INDEX.get(app_id)


def run_app(app_id: str) -> Dict[str, Any]:
    app = _INDEX.get(app_id)
    if app is None:
        return {"ok": False, "code": "UNKNOWN_APP", "message": f"App desconhecido: {app_id}"}
    if app.get("kind") == "sleep":
        return {"ok": False, "code": "USE_SLEEP_ENDPOINT", "message": "Use POST /api/m/pc/sleep com minutes."}
    cmd = app.get("cmd")
    if not cmd:
        return {"ok": False, "code": "NO_COMMAND", "message": f"App sem comando: {app_id}"}
    try:
        subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    except FileNotFoundError:
        return {"ok": False, "code": "NOT_FOUND", "message": f"Comando não encontrado: {cmd[0]}"}
    except Exception as exc:
        return {"ok": False, "code": "RUN_FAILED", "message": str(exc)}
    return {"ok": True, "label": app["label"], "command": " ".join(cmd)}


def schedule_sleep(minutes: Any) -> Dict[str, Any]:
    if isinstance(minutes, bool) or not isinstance(minutes, int):
        return {"ok": False, "code": "BAD_MINUTES", "message": "minutes deve ser inteiro."}
    if minutes < SLEEP_MIN_MINUTES or minutes > SLEEP_MAX_MINUTES:
        return {"ok": False, "code": "BAD_MINUTES", "message": f"minutes deve estar entre {SLEEP_MIN_MINUTES} e {SLEEP_MAX_MINUTES}."}
    cmd = ["shutdown", "-h", f"+{minutes}"]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
    except FileNotFoundError:
        return {"ok": False, "code": "NOT_FOUND", "message": "Comando shutdown não encontrado."}
    except Exception as exc:
        return {"ok": False, "code": "SLEEP_FAILED", "message": str(exc)}
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip()[:200]
        return {"ok": False, "code": "SLEEP_DENIED", "message": f"Sem permissão para agendar desligamento: {detail}"}
    return {"ok": True, "label": "💤 Sleep", "command": " ".join(cmd), "minutes": minutes}

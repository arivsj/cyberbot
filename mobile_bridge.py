import argparse
import io
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

import httpx
from flask import Blueprint, Response, g, jsonify, request, send_file, stream_with_context

import db
import log
import ollama_utils
import pc_apps
import sysmon
import mobile_auth as auth

try:
    import security
except Exception as exc:
    print(f"[mobile] security indisponível: {exc}", file=sys.stderr)
    security = None

try:
    import cleanup
except Exception as exc:
    print(f"[mobile] cleanup indisponível: {exc}", file=sys.stderr)
    cleanup = None

try:
    import youtube as youtube_module
except Exception as exc:
    print(f"[mobile] youtube indisponível: {exc}", file=sys.stderr)
    youtube_module = None

OLLAMA_URL = "http://localhost:11434"
DEFAULT_MODEL = "gemma4"
VERSION = "1.0.0"
DRIVE_DIR = db.DRIVE_DIR
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MAX_UPLOAD_MB = int(os.environ.get("MOBILE_MAX_UPLOAD_MB", "200"))
CHAT_TIMEOUT = 120.0
CONVERSATION_TTL = 7200.0
MAX_CONVERSATIONS = 20

_public_paths = {"/api/m/pair"}
_conversations: Dict[str, Dict[str, Any]] = {}
_conv_lock = threading.RLock()
_youtube_jobs: Dict[str, Dict[str, Any]] = {}
_youtube_lock = threading.RLock()

bp = Blueprint("mobile", __name__, url_prefix="/api/m")


# ─── Helpers ─────────────────────────────────────────────

def _error(code: str, message: str, status: int = 400, retry_after: Optional[int] = None):
    response = jsonify({"error": {"code": code, "message": message}})
    response.status_code = status
    if retry_after is not None:
        response.headers["Retry-After"] = str(int(retry_after))
    return response


def _body() -> Dict[str, Any]:
    data = request.get_json(silent=True)
    return data if isinstance(data, dict) else {}


def _is_loopback(addr: Optional[str]) -> bool:
    return bool(addr) and (addr.startswith("127.") or addr in ("::1", "localhost"))


def _ip_allowed(addr: Optional[str]) -> bool:
    if not addr:
        return False
    if _is_loopback(addr):
        return True
    try:
        import ipaddress
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return False
    return ip.is_private or ip.is_link_local or ip in ipaddress.ip_network("100.64.0.0/10")


def _lan_ip() -> Optional[str]:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.settimeout(0.2)
            sock.connect(("8.8.8.8", 80))
            return sock.getsockname()[0]
    except Exception:
        return None


def _tailnet_ip() -> Optional[str]:
    try:
        out = subprocess.run(["ip", "-4", "-o", "addr", "show"], capture_output=True, text=True, timeout=3).stdout
    except Exception:
        return None
    for line in out.splitlines():
        parts = line.split()
        if len(parts) < 4:
            continue
        addr = parts[3].split("/")[0]
        if addr.startswith("100."):
            return addr
    return None


def _uptime_seconds() -> int:
    try:
        with open("/proc/uptime") as handle:
            return int(float(handle.read().split()[0]))
    except Exception:
        return 0


def _bot_process() -> Optional[int]:
    try:
        out = subprocess.run(["pgrep", "-f", "python3.*bot.py"], capture_output=True, text=True, timeout=5).stdout
    except Exception:
        return None
    pids = [int(p) for p in out.split() if p.isdigit()]
    return pids[0] if pids else None


def _ollama_running() -> bool:
    try:
        return httpx.get(f"{OLLAMA_URL}/api/tags", timeout=3).status_code == 200
    except Exception:
        return False


def _model() -> str:
    try:
        return db.get_setting("model", DEFAULT_MODEL) or DEFAULT_MODEL
    except Exception:
        return DEFAULT_MODEL


def _context_info(model: str) -> Optional[Dict[str, Any]]:
    if not _ollama_running():
        return None
    try:
        return ollama_utils.get_context_info(model)
    except Exception:
        return None


def _flat_sysmon() -> Dict[str, Any]:
    data = sysmon.collect() or {}
    ram = data.get("ram") or {}
    gpu = data.get("gpu") or {}
    gpu_pct = gpu.get("vram_pct") if gpu.get("present") else None
    return {
        "cpu": float(data.get("cpu") or 0),
        "ram": float(ram.get("pct") or 0),
        "gpu": float(gpu_pct or 0),
        "temperature": float(data.get("cpu_temp") or 0),
        "ram_total_mb": int(ram.get("total") or 0),
        "ram_used_mb": int(ram.get("used") or 0),
        "gpu_present": bool(gpu.get("present")),
    }


def _idempotency_key() -> str:
    return (request.headers.get("Idempotency-Key") or "").strip()


def _pin_header() -> Optional[str]:
    return (request.headers.get("X-Action-PIN") or "").strip() or None


def _require_pin():
    ok, code, retry_after = auth.verify_pin(_pin_header())
    if ok:
        return None
    messages = {
        "PIN_NOT_SET": "Nenhum PIN definido. Defina no PC com: python3 mobile_bridge.py --set-pin",
        "PIN_REQUIRED": "PIN obrigatório para esta ação.",
        "PIN_INVALID": "PIN incorreto.",
        "LOCKED": "Muitas tentativas de PIN. Aguarde para tentar novamente.",
    }
    status = 423 if code == "LOCKED" else 403
    return _error(code or "PIN_REQUIRED", messages.get(code, "PIN inválido."), status, retry_after)


def _safe_name(name: str) -> str:
    name = os.path.basename((name or "arquivo").strip()) or "arquivo"
    name = re.sub(r"[^A-Za-z0-9._\- ]+", "_", name).strip(" .") or "arquivo"
    return name[:200]


def _conversation(cid: str) -> List[Dict[str, str]]:
    now = time.time()
    with _conv_lock:
        for key in [k for k, v in _conversations.items() if now - v["ts"] > CONVERSATION_TTL]:
            _conversations.pop(key, None)
        entry = _conversations.get(cid)
        if entry is None:
            if len(_conversations) >= MAX_CONVERSATIONS:
                oldest = min(_conversations.items(), key=lambda kv: kv[1]["ts"])[0]
                _conversations.pop(oldest, None)
            entry = {"ts": now, "messages": []}
            _conversations[cid] = entry
        entry["ts"] = now
        return entry["messages"]


def _chat(messages: List[Dict[str, str]], model: str, stream: bool = False):
    payload = {
        "model": model,
        "messages": messages,
        "options": ollama_utils.get_chat_options(model),
        "stream": stream,
    }
    if stream:
        return httpx.stream("POST", f"{OLLAMA_URL}/api/chat", json=payload, timeout=CHAT_TIMEOUT)
    return httpx.post(f"{OLLAMA_URL}/api/chat", json=payload, timeout=CHAT_TIMEOUT)


# ─── Guarda de autenticação ──────────────────────────────

@bp.before_request
def _guard():
    path = request.path.rstrip("/") or "/"
    if request.method == "OPTIONS":
        return None
    if path == "/api/m/health":
        if not _is_loopback(request.remote_addr):
            return _error("IP_NOT_ALLOWED", "Health só em loopback.", 403)
        return None
    if path == "/api/m/pair/new":
        if not _is_loopback(request.remote_addr):
            return _error("IP_NOT_ALLOWED", "Geração de código só localmente no PC.", 403)
        return None
    if path == "/api/m/pin" and _is_loopback(request.remote_addr):
        return None
    if path.startswith("/api/m/admin/"):
        if not _is_loopback(request.remote_addr):
            return _error("IP_NOT_ALLOWED", "Rotas admin só localmente no PC.", 403)
        return None
    if path in _public_paths and request.method == "POST":
        if not _ip_allowed(request.remote_addr):
            return _error("IP_NOT_ALLOWED", "Origem não permitida.", 403)
        return None
    if not _ip_allowed(request.remote_addr):
        return _error("IP_NOT_ALLOWED", "Origem não permitida.", 403)
    header = request.headers.get("Authorization") or ""
    token = header[7:].strip() if header.lower().startswith("bearer ") else ""
    device = auth.authorize_token(token, request.remote_addr, request.path)
    if device is None:
        return _error("INVALID_TOKEN", "Token ausente, inválido ou revogado.", 401)
    allowed, retry_after = auth.rate_limit_ok(device["id"])
    if not allowed:
        return _error("RATE_LIMITED", "Muitas requisições.", 429, retry_after)
    g.device = device
    return None


# ─── Health / pareamento ─────────────────────────────────

@bp.get("/health")
def health():
    return jsonify({"ok": True, "version": VERSION, "service": "cyberbot-mobile"})


@bp.get("/pair/new")
def pair_new():
    ttl = request.args.get("ttl", type=int) or auth.PAIR_CODE_TTL
    result = auth.generate_pair_code(ttl)
    if not result.get("ok"):
        return _error(result.get("code", "PAIR_LOCKED"), "Pareamento bloqueado.", 423, result.get("retry_after"))
    host = request.host
    return jsonify({
        "code": result["code"],
        "expires_in": result["expires_in"],
        "endpoints": _endpoint_urls(host),
        "pc_name": socket.gethostname(),
    })


@bp.post("/pair")
def pair():
    data = _body()
    result = auth.pair_device(
        code=str(data.get("code", "")),
        device_name=str(data.get("device_name", "Android")),
        app_version=str(data.get("app_version", "")),
        pubkey=data.get("device_pubkey"),
        remote_ip=request.remote_addr,
    )
    if not result.get("ok"):
        code = result.get("code") or "PAIR_FAILED"
        messages = {
            "PAIR_CODE_EXPIRED": "Código expirado. Gere um novo código no PC (aba Configurar P2P).",
            "PAIR_CODE_INVALID": "Código incorreto. Confira os 6 dígitos mostrados no PC.",
            "PAIR_TOO_MANY_ATTEMPTS": "Muitas tentativas erradas. Aguarde e gere um novo código.",
            "DEVICE_LIMIT": f"Limite de {auth.MAX_DEVICES} dispositivos atingido. Revogue um no desktop.",
            "PAIR_LOCKED": "Pareamento temporariamente bloqueado. Aguarde alguns minutos.",
        }
        status = 423 if code in ("PAIR_TOO_MANY_ATTEMPTS", "PAIR_LOCKED") else 401
        return _error(code, messages.get(code, "Pareamento recusado."), status, result.get("retry_after"))
    return jsonify({
        "device_id": result["device_id"],
        "token": result["token"],
        "pc_name": socket.gethostname(),
        "transport_hint": {"direct": _endpoint_urls(request.host), "relay": None},
        "pin_set": auth.pin_is_set(),
        "iroh": _iroh_hint(),
    })


def _endpoint_urls(host: str) -> List[str]:
    """Endereços que o app deve usar, na ordem de preferência.

    O host da própria requisição vem PRIMEIRO: é o endereço que o celular
    comprovadamente alcançou (o app salva o primeiro da lista). Os demais IPs
    detectados entram como alternativa para outras redes.
    """
    urls: List[str] = []
    host_ip = host.split(":")[0] if host else ""
    if host and not _is_loopback(host_ip):
        urls.append(f"http://{host}")
    port = host.split(":")[-1] if host and ":" in host else "5055"
    for ip in (_lan_ip(), _tailnet_ip()):
        if not ip:
            continue
        candidate = f"http://{ip}:{port}"
        if candidate not in urls:
            urls.append(candidate)
    return urls


def _gateway_info() -> Dict[str, Any]:
    path = os.path.join(BASE_DIR, "state", "gateway.json")
    info: Dict[str, Any] = {"port": int(os.environ.get("MOBILE_PORT", "5055")), "hosts": []}
    if os.path.exists(path):
        try:
            with open(path) as handle:
                stored = json.load(handle)
            if isinstance(stored, dict):
                info.update({k: v for k, v in stored.items() if k in ("port", "hosts", "started_at")})
        except Exception:
            pass
    return info


def _iroh_hint() -> Optional[Dict[str, Any]]:
    path = os.path.join(BASE_DIR, "state", "iroh_endpoint.json")
    if not os.path.exists(path):
        return None
    try:
        with open(path) as handle:
            data = json.load(handle)
        return {"endpoint_id": data.get("endpoint_id"), "ticket": data.get("ticket"), "alpn": "cyberbot/1"}
    except Exception:
        return None


# ─── Status / telemetria ─────────────────────────────────

@bp.get("/status")
def status():
    model = _model()
    running = _ollama_running()
    return jsonify({
        "pc": {
            "hostname": socket.gethostname(),
            "uptime_s": _uptime_seconds(),
            "tailnet_ip": _tailnet_ip(),
            "lan_ip": _lan_ip(),
        },
        "bot": {"running": _bot_process() is not None, "pid": _bot_process()},
        "ollama": {"running": running, "model": model if running else None,
                   "context": _context_info(model) if running else None},
        "transport": {"server_time": datetime.now(timezone.utc).replace(microsecond=0).isoformat(), "version": VERSION},
    })


@bp.get("/sysmon")
def sysmon_endpoint():
    return jsonify(_flat_sysmon())


@bp.get("/sysmon/history")
def sysmon_history():
    limit = request.args.get("limit", default=30, type=int)
    return jsonify(db.list_sysmon_history(max(1, min(limit or 30, 500))))


# ─── Chat ────────────────────────────────────────────────

@bp.post("/chat")
def chat():
    data = _body()
    message = str(data.get("message", "")).strip()
    if not message:
        return _error("BAD_REQUEST", "message é obrigatório.")
    cid = str(data.get("conversation_id") or uuid.uuid4().hex)
    model = str(data.get("model") or _model())
    key = _idempotency_key()
    cached = auth.idempotency_get(key)
    if cached is not None:
        return jsonify(cached)
    if not _ollama_running():
        return _error("UPSTREAM_TIMEOUT", "Ollama não está rodando.", 504)
    history = _conversation(cid)
    history.append({"role": "user", "content": message})
    started = time.time()
    try:
        response = _chat(history[-20:], model)
        response.raise_for_status()
        reply = response.json().get("message", {}).get("content", "")
    except Exception as exc:
        history.pop()
        return _error("UPSTREAM_TIMEOUT", f"Falha ao falar com o Ollama: {exc}", 504)
    history.append({"role": "assistant", "content": reply})
    payload = {
        "reply": reply,
        "model": model,
        "elapsed_ms": int((time.time() - started) * 1000),
        "conversation_id": cid,
    }
    auth.idempotency_put(key, payload)
    return jsonify(payload)


@bp.post("/chat/stream")
def chat_stream():
    data = _body()
    message = str(data.get("message", "")).strip()
    if not message:
        return _error("BAD_REQUEST", "message é obrigatório.")
    cid = str(data.get("conversation_id") or uuid.uuid4().hex)
    model = str(data.get("model") or _model())
    history = _conversation(cid)
    history.append({"role": "user", "content": message})

    def generate():
        buffer: List[str] = []
        started = time.time()
        try:
            with _chat(history[-20:], model, stream=True) as response:
                for line in response.iter_lines():
                    if not line:
                        continue
                    try:
                        chunk = json.loads(line)
                    except ValueError:
                        continue
                    piece = chunk.get("message", {}).get("content", "")
                    if piece:
                        buffer.append(piece)
                        yield f"event: delta\ndata: {json.dumps({'text': piece})}\n\n"
        except Exception as exc:
            yield f"event: error\ndata: {json.dumps({'message': str(exc)})}\n\n"
            return
        history.append({"role": "assistant", "content": "".join(buffer)})
        yield f"event: done\ndata: {json.dumps({'elapsed_ms': int((time.time() - started) * 1000)})}\n\n"

    return Response(stream_with_context(generate()), mimetype="text/event-stream",
                    headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


# ─── Bot ─────────────────────────────────────────────────

@bp.post("/bot/start")
def bot_start():
    if _bot_process() is not None:
        return _error("CONFLICT", "Bot já está rodando.", 409)
    script = os.path.join(BASE_DIR, "bot.py")
    try:
        subprocess.Popen([sys.executable, script], cwd=BASE_DIR, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, start_new_session=True)
    except Exception as exc:
        return _error("INTERNAL", str(exc), 500)
    return jsonify({"status": "started"})


@bp.post("/bot/stop")
def bot_stop():
    denied = _require_pin()
    if denied is not None:
        return denied
    killed = False
    try:
        out = subprocess.run(["pgrep", "-f", "python3.*bot.py"], capture_output=True, text=True, timeout=5).stdout
        for pid in out.split():
            if pid.isdigit():
                os.kill(int(pid), 15)
                killed = True
    except Exception as exc:
        return _error("INTERNAL", str(exc), 500)
    if not killed:
        return _error("CONFLICT", "Bot não está rodando.", 409)
    return jsonify({"status": "stopped"})


@bp.post("/bot/send")
def bot_send():
    data = _body()
    chat_id = data.get("chat_id")
    text = str(data.get("text", "")).strip()
    if not chat_id or not text:
        return _error("BAD_REQUEST", "chat_id e text são obrigatórios.")
    token = os.environ.get("TELEGRAM_TOKEN") or ""
    if not token:
        return _error("INTERNAL", "TELEGRAM_TOKEN não configurado.", 500)
    try:
        response = httpx.post(f"https://api.telegram.org/bot{token}/sendMessage",
                              json={"chat_id": chat_id, "text": text}, timeout=10)
    except Exception as exc:
        return _error("INTERNAL", str(exc), 500)
    if response.status_code != 200:
        return _error("INTERNAL", f"Telegram API: {response.status_code}", 502)
    return jsonify({"status": "sent"})


# ─── PC Apps ─────────────────────────────────────────────

@bp.get("/pc/apps")
def pc_apps_list():
    return jsonify(pc_apps.list_apps())


@bp.post("/pc/run")
def pc_apps_run():
    data = _body()
    app_id = str(data.get("id", "")).strip()
    app = pc_apps.get_app(app_id)
    if app is None:
        return _error("NOT_FOUND", f"App desconhecido: {app_id}", 404)
    if app.get("destructive"):
        denied = _require_pin()
        if denied is not None:
            return denied
    key = _idempotency_key()
    cached = auth.idempotency_get(key)
    if cached is not None:
        return jsonify(cached)
    result = pc_apps.run_app(app_id)
    if not result.get("ok"):
        return _error(result.get("code", "RUN_FAILED"), result.get("message", "Falha ao executar."), 400)
    payload = {"status": "started", "label": result.get("label"), "command": result.get("command"), "at": None}
    auth.idempotency_put(key, payload)
    return jsonify(payload)


@bp.post("/pc/sleep")
def pc_sleep():
    denied = _require_pin()
    if denied is not None:
        return denied
    data = _body()
    key = _idempotency_key()
    cached = auth.idempotency_get(key)
    if cached is not None:
        return jsonify(cached)
    result = pc_apps.schedule_sleep(data.get("minutes"))
    if not result.get("ok"):
        return _error(result.get("code", "SLEEP_FAILED"), result.get("message", "Falha ao agendar."), 400)
    payload = {
        "status": "scheduled",
        "label": result.get("label"),
        "command": result.get("command"),
        "at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
    }
    auth.idempotency_put(key, payload)
    return jsonify(payload)


# ─── Drive ───────────────────────────────────────────────

@bp.get("/drive/folders")
def drive_folders():
    parent = request.args.get("parent", type=int)
    return jsonify(db.listar_pastas(parent_id=parent))


@bp.get("/drive/files")
def drive_files():
    folder = request.args.get("folder", type=int)
    files = db.listar_arquivos(folder_id=folder)
    for item in files:
        item["absolute_path"] = item.get("file_path") or None
    return jsonify(files)


@bp.post("/drive/upload")
def drive_upload():
    if "file" not in request.files:
        return _error("BAD_REQUEST", "Arquivo ausente (campo 'file').")
    upload = request.files["file"]
    if not upload.filename:
        return _error("BAD_REQUEST", "Nome de arquivo vazio.")
    folder_raw = request.form.get("folder_id", "")
    folder_id = int(folder_raw) if str(folder_raw).isdigit() else None
    name = _safe_name(upload.filename)
    limit = MAX_UPLOAD_MB * 1024 * 1024
    data = upload.read(limit + 1)
    if len(data) > limit:
        return _error("BAD_REQUEST", f"Arquivo maior que {MAX_UPLOAD_MB} MB.", 413)
    os.makedirs(DRIVE_DIR, exist_ok=True)
    fid = db.inserir_arquivo(name=name, folder_id=folder_id, file_path="", file_size=len(data),
                             mime_type=upload.mimetype or "application/octet-stream", caption="via app")
    final_path = os.path.join(DRIVE_DIR, f"{fid}_{name}")
    with open(final_path, "wb") as handle:
        handle.write(data)
    db.update_arquivo_path(fid, final_path)
    return jsonify({"id": fid, "name": name, "size": len(data), "mime": upload.mimetype or "application/octet-stream"})


@bp.get("/drive/download/<int:file_id>")
def drive_download(file_id: int):
    item = db.get_arquivo(file_id)
    if not item:
        return _error("NOT_FOUND", "Arquivo não encontrado.", 404)
    path = item.get("file_path") or ""
    if not path or not os.path.exists(path):
        return _error("NOT_FOUND", "Arquivo ausente no disco.", 404)
    return send_file(path, as_attachment=True, download_name=item.get("name") or os.path.basename(path))


@bp.delete("/drive/files/<int:file_id>")
def drive_delete(file_id: int):
    denied = _require_pin()
    if denied is not None:
        return denied
    item = db.get_arquivo(file_id)
    if not item:
        return _error("NOT_FOUND", "Arquivo não encontrado.", 404)
    path = item.get("file_path") or ""
    if path and os.path.exists(path):
        try:
            os.remove(path)
        except OSError:
            pass
    db.deletar_arquivo(file_id)
    return jsonify({"status": "deleted"})


# ─── Finanças ────────────────────────────────────────────

@bp.get("/financas")
def financas_list():
    return jsonify(db.listar(mes=request.args.get("mes")))


@bp.get("/financas/resumo")
def financas_resumo():
    mes = request.args.get("mes")
    return jsonify({
        "resumo": db.resumo_mes(mes=mes),
        "categorias": db.gastos_por_categoria(mes=mes),
        "contas": db.gastos_por_conta(mes=mes),
        "diario": db.gastos_por_dia(mes=mes),
        "meses": db.meses_disponiveis(),
    })


@bp.post("/financas")
def financas_add():
    data = _body()
    for field in ("categoria", "conta", "valor"):
        if data.get(field) in (None, ""):
            return _error("BAD_REQUEST", f"{field} é obrigatório.")
    try:
        valor = float(data["valor"])
    except (TypeError, ValueError):
        return _error("BAD_REQUEST", "valor deve ser numérico.")
    db.inserir(str(data["categoria"]), str(data["conta"]), valor, str(data.get("descricao", "")))
    return jsonify({"status": "ok"})


# ─── Security ────────────────────────────────────────────

@bp.get("/security/run")
def security_run():
    if security is None:
        return _error("INTERNAL", "Módulo de segurança indisponível.", 500)
    try:
        return jsonify(security.run_all())
    except Exception as exc:
        return _error("INTERNAL", str(exc), 500)


@bp.get("/security/<check>")
def security_check(check: str):
    if security is None:
        return _error("INTERNAL", "Módulo de segurança indisponível.", 500)
    funcs = {
        "connections": security.check_connections, "ssh": security.check_ssh,
        "integrity": security.check_integrity, "persistence": security.check_persistence,
        "processes": security.check_processes, "ports": security.check_ports,
        "firewall": security.check_firewall, "fail2ban": security.check_fail2ban,
        "sudo": security.check_sudo, "updates": security.check_updates,
        "services": security.check_services, "users": security.check_users,
    }
    func = funcs.get(check)
    if func is None:
        return _error("NOT_FOUND", f"Verificação desconhecida: {check}", 404)
    try:
        return jsonify(func())
    except Exception as exc:
        return _error("INTERNAL", str(exc), 500)


@bp.get("/security/report")
def security_report():
    if security is None:
        return _error("INTERNAL", "Módulo de segurança indisponível.", 500)
    try:
        data = security.run_all()
        lines: List[str] = []
        alerts: List[str] = []
        for name, result in data.items():
            meta = result.get("meta", {})
            result_alerts = result.get("alerts", [])
            attentions = result.get("attentions", [])
            lines.append(f"{name}: status={result.get('status')}, alerts={len(result_alerts)}, attentions={len(attentions)}")
            for item in result_alerts[:3]:
                lines.append(f"  alerta: {item}")
                alerts.append(f"[{meta.get('cmd', name)}] {item}")
            for item in attentions[:3]:
                lines.append(f"  atencao: {item}")
        prompt = ("Você é um analista de segurança Linux. Analise o relatório abaixo item por item, "
                  "de forma concisa, em português, em no máximo 4 parágrafos.\n\nRELATÓRIO:\n" + "\n".join(lines))
        try:
            response = httpx.post(f"{OLLAMA_URL}/api/chat", json={
                "model": _model(), "messages": [{"role": "user", "content": prompt}],
                "options": ollama_utils.get_chat_options(_model()), "stream": False,
            }, timeout=90)
            analysis = response.json().get("message", {}).get("content", "Erro na análise.") if response.status_code == 200 else "IA indisponível"
        except Exception as exc:
            analysis = f"IA indisponível: {exc}"
        db.save_report(bool(alerts), len(alerts), analysis, "\n".join(f"- {a}" for a in alerts) or "Nenhum alerta.",
                       source="mobile")
        return jsonify({"report": data, "ia_analysis": analysis})
    except Exception as exc:
        return _error("INTERNAL", str(exc), 500)


@bp.get("/reports")
def reports_list():
    return jsonify(db.list_reports(20))


@bp.get("/reports/<int:report_id>")
def reports_get(report_id: int):
    item = db.get_report(report_id)
    if not item:
        return _error("NOT_FOUND", "Relatório não encontrado.", 404)
    return jsonify(item)


# ─── Cleanup ─────────────────────────────────────────────

@bp.get("/cleanup/tasks")
def cleanup_tasks():
    if cleanup is None:
        return _error("INTERNAL", "Módulo de cleanup indisponível.", 500)
    return jsonify([
        {"id": t["id"], "name": t["name"], "desc": t["desc"], "needs_sudo": t["needs_sudo"], "safe": t["safe"]}
        for t in cleanup.TASKS
    ])


@bp.post("/cleanup/run")
def cleanup_run():
    if cleanup is None:
        return _error("INTERNAL", "Módulo de cleanup indisponível.", 500)
    denied = _require_pin()
    if denied is not None:
        return denied
    data = _body()
    task_id = data.get("task")
    if task_id:
        task = next((t for t in cleanup.TASKS if t["id"] == task_id), None)
        if task is None:
            return _error("NOT_FOUND", f"Tarefa desconhecida: {task_id}", 404)
        if task.get("needs_sudo"):
            return _error("SUDO_REQUIRED", "Esta tarefa exige sudo. Execute pelo desktop.", 403)
        return jsonify(cleanup.run_task(task_id))
    if data.get("mode") == "safe":
        return jsonify(cleanup.run_safe())
    return _error("BAD_REQUEST", "Informe task ou mode=safe (run_all não é permitido pelo app).")


# ─── Trilha Rede ─────────────────────────────────────────

@bp.get("/trilha/projects")
def trilha_projects():
    projects = db.listar_projetos()
    for project in projects:
        path = project.get("project_path")
        project["absolute_path"] = os.path.abspath(path) if path else None
    return jsonify(projects)


# ─── YouTube ─────────────────────────────────────────────

@bp.get("/youtube/jobs")
def youtube_jobs():
    with _youtube_lock:
        return jsonify(list(_youtube_jobs.values()))


@bp.post("/youtube/download")
def youtube_download():
    if youtube_module is None:
        return _error("INTERNAL", "Módulo de YouTube indisponível (yt-dlp).", 500)
    data = _body()
    url = str(data.get("url", "")).strip()
    fmt = str(data.get("format", "mp4")).strip().lower()
    if not youtube_module.is_youtube_url(url):
        return _error("BAD_REQUEST", "Link do YouTube inválido.")
    if fmt not in ("mp4", "mp3"):
        return _error("BAD_REQUEST", "format deve ser mp4 ou mp3.")
    job_id = "yt_" + secrets.token_hex(4)
    with _youtube_lock:
        _youtube_jobs[job_id] = {
            "job_id": job_id, "state": "running", "url": url, "format": fmt,
            "title": None, "file_id": None, "name": None, "error": None,
            "started_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        }
    threading.Thread(target=_run_youtube_job, args=(job_id, url, fmt), daemon=True).start()
    return jsonify({"job_id": job_id, "state": "running"})


def _run_youtube_job(job_id: str, url: str, fmt: str) -> None:
    tmp_dir = os.path.join(DRIVE_DIR, f"__yt_{job_id}")
    try:
        os.makedirs(tmp_dir, exist_ok=True)
        folder_id = youtube_module.get_youtube_folder_id()
        name, path, size, title = youtube_module.download(url, fmt, tmp_dir)
        if not path or not os.path.exists(path):
            raise RuntimeError("download não produziu arquivo")
        mime = "audio/mpeg" if fmt == "mp3" else "video/mp4"
        fid = db.inserir_arquivo(name=os.path.basename(path), folder_id=folder_id, file_path="",
                                 file_size=size, mime_type=mime, caption="via app")
        final_path = os.path.join(DRIVE_DIR, f"{fid}_{os.path.basename(path)}")
        shutil.move(path, final_path)
        db.update_arquivo_path(fid, final_path)
        with _youtube_lock:
            _youtube_jobs[job_id].update({"state": "done", "title": title, "file_id": fid,
                                          "name": os.path.basename(final_path)})
    except Exception as exc:
        with _youtube_lock:
            _youtube_jobs[job_id].update({"state": "error", "error": str(exc)[:300]})
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)


# ─── Eventos ─────────────────────────────────────────────

@bp.get("/events")
def events():
    def generate():
        last = None
        while True:
            try:
                snapshot = {
                    "bot": _bot_process() is not None,
                    "ollama": _ollama_running(),
                    "cpu": _flat_sysmon().get("cpu"),
                    "ram": _flat_sysmon().get("ram"),
                }
            except Exception:
                snapshot = {"bot": False, "ollama": False}
            if snapshot != last:
                last = snapshot
                yield f"event: status\ndata: {json.dumps(snapshot)}\n\n"
            else:
                yield ": ping\n\n"
            time.sleep(5)

    return Response(stream_with_context(generate()), mimetype="text/event-stream",
                    headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


# ─── Dispositivos / PIN ──────────────────────────────────

@bp.get("/devices")
def devices_list():
    return jsonify(auth.list_devices())


@bp.post("/devices/<device_id>/revoke")
def devices_revoke(device_id: str):
    denied = _require_pin()
    if denied is not None:
        return denied
    if not auth.revoke_device(device_id):
        return _error("NOT_FOUND", "Dispositivo não encontrado.", 404)
    return jsonify({"status": "revoked"})


@bp.delete("/devices/<device_id>")
def devices_delete(device_id: str):
    denied = _require_pin()
    if denied is not None:
        return denied
    if not auth.delete_device(device_id):
        return _error("NOT_FOUND", "Dispositivo não encontrado.", 404)
    return jsonify({"status": "deleted"})


# ─── Admin (somente loopback — usado pelo desktop) ───────

@bp.get("/admin/status")
def admin_status():
    gateway = _gateway_info()
    port = gateway.get("port") or 5055
    endpoints: List[str] = []
    for ip in (_lan_ip(), _tailnet_ip()):
        if ip:
            endpoints.append(f"http://{ip}:{port}")
    for host in gateway.get("hosts") or []:
        host = str(host)
        if not host or host.startswith("0.0.0.0") or _is_loopback(host.split(":")[0]):
            continue
        candidate = f"http://{host}" if ":" in host else f"http://{host}:{port}"
        if candidate not in endpoints:
            endpoints.append(candidate)
    devices = auth.list_devices()
    return jsonify({
        "pin_set": auth.pin_is_set(),
        "port": port,
        "lan_endpoints": endpoints,
        "iroh": _iroh_hint(),
        "devices_count": len([d for d in devices if not d.get("revoked")]),
        "devices": devices,
    })


@bp.get("/admin/devices")
def admin_devices():
    return jsonify(auth.list_devices())


@bp.post("/admin/devices/<device_id>/revoke")
def admin_revoke(device_id: str):
    if not auth.revoke_device(device_id):
        return _error("NOT_FOUND", "Dispositivo não encontrado.", 404)
    return jsonify({"status": "revoked"})


@bp.post("/pin")
def pin_update():
    data = _body()
    new_pin = str(data.get("new_pin", ""))
    current = data.get("current_pin")
    require_current = not _is_loopback(request.remote_addr)
    result = auth.set_pin(new_pin, current_pin=str(current) if current is not None else None,
                          require_current=require_current)
    if not result.get("ok"):
        status = 423 if result.get("code") == "LOCKED" else 403
        return _error(result.get("code", "BAD_PIN"), result.get("message", "PIN inválido."), status, result.get("retry_after"))
    return jsonify({"status": "ok"})


# ─── Registro / CLI ──────────────────────────────────────

def register(app) -> None:
    app.register_blueprint(bp)


def _cli(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="CyberBot Mobile Bridge (lado PC)")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--pair", action="store_true", help="gera código de pareamento")
    group.add_argument("--devices", action="store_true", help="lista dispositivos pareados")
    group.add_argument("--revoke", metavar="DEVICE_ID", help="revoga um dispositivo")
    group.add_argument("--set-pin", action="store_true", help="define o PIN localmente")
    group.add_argument("--selftest", action="store_true", help="roda os testes de aceite")
    group.add_argument("--serve", action="store_true", help="sobe o gateway LAN (equivalente a mobile_gateway.py)")
    parser.add_argument("--ttl", type=int, default=auth.PAIR_CODE_TTL)
    parser.add_argument("--port", type=int, default=5055)
    args = parser.parse_args(argv)

    db.init()

    if args.pair:
        result = auth.generate_pair_code(args.ttl)
        if not result.get("ok"):
            print(f"ERRO: {result.get('code')}")
            return 1
        host = f"{_lan_ip() or '127.0.0.1'}:{args.port}"
        print(f"Código: {result['code']}  (válido por {result['expires_in']}s)")
        print(f"No app, use o endpoint: http://{host}")
        return 0
    if args.devices:
        for device in auth.list_devices():
            state = "revogado" if device["revoked"] else "ativo"
            print(f"{device['id']}  {device['name']:<20} {state:<9} último acesso: {device['last_seen'] or '-'}")
        return 0
    if args.revoke:
        print("revogado" if auth.revoke_device(args.revoke) else "não encontrado")
        return 0
    if args.set_pin:
        import getpass
        pin = getpass.getpass("Novo PIN (6 dígitos): ")
        result = auth.set_pin(pin, require_current=False)
        print("PIN definido." if result.get("ok") else f"ERRO: {result.get('message')}")
        return 0 if result.get("ok") else 1
    if args.serve:
        import mobile_gateway
        return mobile_gateway.main(["--port", str(args.port)])
    return run_selftest()


def run_selftest() -> int:
    import mobile_selftest
    return mobile_selftest.run()


if __name__ == "__main__":
    raise SystemExit(_cli())

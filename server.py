import os
import sys
import time
import signal
import subprocess
import threading
from dotenv import load_dotenv

load_dotenv()

import httpx
from flask import Flask, jsonify, request
from flask_cors import CORS
import db
import ollama_utils

app = Flask(__name__)
CORS(app)

bot_process = None
BOT_SCRIPT = os.path.join(os.path.dirname(__file__), "bot.py")
OLLAMA_URL = "http://localhost:11434"
MODELO_PADRAO = "gemma4"

def ollama_rodando():
    try:
        r = httpx.get(f"{OLLAMA_URL}/api/tags", timeout=3)
        return r.status_code == 200
    except:
        return False

def modelo_disponivel():
    try:
        r = httpx.get(f"{OLLAMA_URL}/api/tags", timeout=5)
        if r.status_code != 200:
            return False
        nomes = [m["name"] for m in r.json().get("models", [])]
        return any(MODELO_PADRAO in n for n in nomes)
    except:
        return False

def iniciar_ollama():
    print("Ollama não está rodando. Iniciando...")
    subprocess.Popen(
        ["ollama", "serve"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    for i in range(30):
        if ollama_rodando():
            print("Ollama iniciado com sucesso!")
            return True
        time.sleep(1)
    print("ERRO: Não foi possível iniciar o Ollama")
    return False

def baixar_modelo():
    print(f"Modelo {MODELO_PADRAO} não encontrado. Baixando...")
    process = subprocess.Popen(
        ["ollama", "pull", MODELO_PADRAO],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    for line in process.stdout:
        line = line.strip()
        if line:
            print(f"  {line}")
    process.wait()
    if process.returncode == 0:
        print(f"Modelo {MODELO_PADRAO} baixado!")
        return True
    print(f"ERRO ao baixar {MODELO_PADRAO}")
    return False

def ensure_ollama():
    print()
    print("=" * 50)
    print("Verificando Ollama...")
    print("=" * 50)

    if not ollama_rodando():
        if not iniciar_ollama():
            print("AVISO: Continue manualmente com: ollama serve")
    else:
        print("Ollama está rodando!")

    if ollama_rodando() and not modelo_disponivel():
        baixar_modelo()

    if ollama_rodando():
        print("Ambiente Ollama pronto!")
    print("=" * 50)
    print()

@app.route("/api/status")
def status():
    global bot_process
    running = bot_process is not None and bot_process.poll() is None
    ctx_info = ollama_utils.get_context_info(MODELO_PADRAO) if ollama_rodando() else None
    return jsonify({
        "bot": running,
        "ollama": ollama_rodando(),
        "modelo": MODELO_PADRAO if ollama_rodando() else None,
        "contexto": ctx_info,
    })

@app.route("/api/bot/start", methods=["POST"])
def start_bot():
    global bot_process
    if bot_process and bot_process.poll() is None:
        return jsonify({"error": "Bot já está rodando"}), 400
    if not ollama_rodando():
        return jsonify({"error": "Ollama não está rodando"}), 400
    bot_process = subprocess.Popen(
        [sys.executable, BOT_SCRIPT],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return jsonify({"status": "started"})

@app.route("/api/bot/stop", methods=["POST"])
def stop_bot():
    global bot_process
    if bot_process and bot_process.poll() is None:
        _kill_bot()
        return jsonify({"status": "stopped"})
    return jsonify({"error": "Bot não está rodando"}), 400

def _kill_bot():
    global bot_process
    try:
        result = subprocess.run(
            ["pgrep", "-f", "python3.*bot.py"],
            capture_output=True, text=True, timeout=5,
        )
        for pid in result.stdout.strip().split():
            if pid:
                try:
                    os.kill(int(pid), signal.SIGKILL)
                except (OSError, ValueError):
                    pass
    except Exception:
        pass
    if bot_process and bot_process.poll() is None:
        bot_process.send_signal(signal.SIGTERM)
        try:
            bot_process.wait(timeout=5)
        except Exception:
            bot_process.kill()
            bot_process.wait()
        bot_process = None
    try:
        subprocess.run(["pkill", "-9", "ollama"], capture_output=True, timeout=5)
    except:
        pass

def _start_bot():
    global bot_process
    if not ollama_rodando():
        ensure_ollama()
    if ollama_rodando():
        bot_process = subprocess.Popen(
            [sys.executable, BOT_SCRIPT],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

@app.route("/api/cache/clear", methods=["POST"])
def clear_cache():
    _kill_bot()
    _start_bot()
    return jsonify({"status": "cache cleared, bot restarted"})

def _mes_arg():
    return request.args.get("mes")

@app.route("/api/financas")
def listar_financas():
    return jsonify(db.listar(mes=_mes_arg()))

@app.route("/api/financas/resumo")
def resumo_financas():
    mes = _mes_arg()
    return jsonify({
        "resumo": db.resumo_mes(mes=mes),
        "categorias": db.gastos_por_categoria(mes=mes),
        "contas": db.gastos_por_conta(mes=mes),
        "diario": db.gastos_por_dia(mes=mes),
        "meses": db.meses_disponiveis(),
    })

@app.route("/api/financas", methods=["POST"])
def adicionar_financa():
    data = request.get_json()
    db.inserir(
        data["categoria"],
        data["conta"],
        float(data["valor"]),
        data.get("descricao", ""),
    )
    return jsonify({"status": "ok"})

# ─── Drive API ────────────────────────────────────────────

@app.route("/api/drive/folders")
def list_folders():
    parent = request.args.get("parent")
    parent_id = int(parent) if parent else None
    return jsonify(db.listar_pastas(parent_id=parent_id))

@app.route("/api/drive/folders", methods=["POST"])
def create_folder():
    data = request.get_json()
    fid = db.criar_pasta(data["name"], data.get("parent_id"))
    return jsonify({"id": fid, "status": "ok"})

@app.route("/api/drive/folders/<int:folder_id>")
def get_folder(folder_id):
    folder = db.get_pasta(folder_id)
    if not folder:
        return jsonify({"error": "not found"}), 404
    return jsonify(folder)

@app.route("/api/drive/files")
def list_files():
    folder = request.args.get("folder")
    folder_id = int(folder) if folder else None
    files = db.listar_arquivos(folder_id=folder_id)
    base = os.path.dirname(os.path.abspath(__file__))
    for f in files:
        if f.get("file_path"):
            f["absolute_path"] = os.path.join(base, f["file_path"])
        else:
            f["absolute_path"] = None
    return jsonify(files)

@app.route("/api/drive/files/<int:file_id>")
def get_file(file_id):
    f = db.get_arquivo(file_id)
    if not f:
        return jsonify({"error": "not found"}), 404
    base = os.path.dirname(os.path.abspath(__file__))
    if f.get("file_path"):
        f["absolute_path"] = os.path.join(base, f["file_path"])
    else:
        f["absolute_path"] = None
    return jsonify(f)

@app.route("/api/drive/files/<int:file_id>", methods=["DELETE"])
def delete_file(file_id):
    db.deletar_arquivo(file_id)
    return jsonify({"status": "ok"})

@app.route("/api/drive/stats")
def drive_stats():
    return jsonify(db.drive_stats())

# ─── Trilha Rede API ──────────────────────────────────────

import trilha_rede

def _add_trilha_absolute(proj):
    if proj.get("project_path"):
        proj["absolute_path"] = os.path.abspath(proj["project_path"])
    else:
        proj["absolute_path"] = None
    return proj

@app.route("/api/trilha/projects")
def list_trilha_projects():
    projetos = db.listar_projetos()
    for p in projetos:
        _add_trilha_absolute(p)
    return jsonify(projetos)

@app.route("/api/trilha/projects/<int:proj_id>")
def get_trilha_project(proj_id):
    proj = db.get_projeto(proj_id)
    if not proj:
        return jsonify({"error": "not found"}), 404
    return jsonify(_add_trilha_absolute(proj))

@app.route("/api/trilha/logs/<int:proj_id>")
def get_trilha_logs(proj_id):
    proj = db.get_projeto(proj_id)
    if not proj:
        return jsonify({"error": "not found"}), 404
    logs = trilha_rede.get_logs(proj["project_path"], 100)
    return jsonify({"logs": logs})

BOT_TOKEN = os.environ.get("TELEGRAM_TOKEN") or ""
TELEGRAM_API = "https://api.telegram.org/bot"

@app.route("/api/bot/send", methods=["POST"])
def bot_send():
    data = request.get_json()
    chat_id = data.get("chat_id")
    text = data.get("text", "").strip()
    if not chat_id or not text:
        return jsonify({"error": "chat_id and text required"}), 400
    try:
        r = httpx.post(
            f"{TELEGRAM_API}{BOT_TOKEN}/sendMessage",
            json={"chat_id": chat_id, "text": text},
            timeout=10,
        )
        if r.status_code == 200:
            return jsonify({"status": "sent", "result": r.json().get("result", {})})
        return jsonify({"error": f"Telegram API error: {r.status_code}", "detail": r.text}), 502
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route("/api/bot/send", methods=["OPTIONS"])
def bot_send_preflight():
    return jsonify({})

# ─── Whitelist API ───────────────────────────────────────

@app.route("/api/whitelist")
def whitelist_list():
    return jsonify(db.whitelist_list())

@app.route("/api/whitelist", methods=["POST"])
def whitelist_add():
    data = request.get_json()
    uid = db.whitelist_add(
        user_id=data.get("user_id", 0),
        username=data.get("username", ""),
        label=data.get("label", ""),
    )
    return jsonify({"id": uid, "status": "ok"})

@app.route("/api/whitelist/<int:entry_id>", methods=["DELETE"])
def whitelist_remove(entry_id):
    db.whitelist_remove(entry_id)
    return jsonify({"status": "ok"})

@app.route("/api/whitelist/<int:entry_id>", methods=["POST"])
def whitelist_remove_post(entry_id):
    db.whitelist_remove(entry_id)
    return jsonify({"status": "ok"})

@app.route("/api/whitelist/check")
def whitelist_check():
    uid = request.args.get("user_id", type=int)
    username = request.args.get("username", "")
    ok = db.whitelist_check(uid, username) if uid else True
    return jsonify({"allowed": ok})

# ─── Logs API ────────────────────────────────────────────

import log as log_module
import sysmon
try:
    import security
except Exception as e:
    print(f"[security] Erro ao carregar módulo: {e}", file=sys.stderr)
    security = None

@app.route("/api/logs/today")
def logs_today():
    return jsonify(log_module.get_today_entries())

@app.route("/api/logs/stats")
def logs_stats():
    return jsonify(log_module.get_log_stats())

@app.route("/api/logs/blocked")
def logs_blocked():
    return jsonify(log_module.get_blocked_entries())

@app.route("/api/logs/dates")
def logs_dates():
    return jsonify(log_module.list_dates())

@app.route("/api/logs/path")
def logs_path():
    date = request.args.get("date", "")
    if date:
        fname = f"access_{date}.jsonl"
        fpath = os.path.join(log_module.LOG_DIR, fname)
        if os.path.exists(fpath):
            return jsonify({"path": os.path.abspath(fpath)})
    return jsonify({"path": os.path.abspath(log_module.LOG_DIR)})

@app.route("/api/logs/date/<date_str>")
def logs_by_date(date_str):
    return jsonify(log_module.get_entries_by_date(date_str))

@app.route("/api/logs/users")
def logs_users():
    return jsonify(log_module.get_all_users())

# ─── Security API ──────────────────────────────────────

@app.route("/api/sysmon")
def sysmon_endpoint():
    return jsonify(sysmon.collect())

@app.route("/api/sysmon/history")
def sysmon_history():
    return jsonify(db.list_sysmon_history(30))

@app.route("/api/security/init")
def security_init():
    if security is None:
        return jsonify({"error": "security module not loaded"}), 500
    try:
        security.init_baseline()
        return jsonify({"status": "ok"})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route("/api/security/run")
def security_run():
    if security is None:
        return jsonify({"error": "security module not loaded"}), 500
    try:
        return jsonify(security.run_all())
    except Exception as e:
        return jsonify({"error": str(e)}), 500

SEC_CHECKS_LIST = ["connections", "ssh", "integrity", "persistence", "processes", "ports"]
SEC_LABELS = {"connections": "🌐 Conexões", "ssh": "🔑 SSH", "integrity": "📁 Integridade", "persistence": "⏱ Persistência", "processes": "⚙ Processos", "ports": "🚪 Portas"}

@app.route("/api/security/report")
def security_report():
    if security is None:
        return jsonify({"error": "security module not loaded"}), 500
    try:
        data = security.run_all()
        alerts = []
        for name in SEC_CHECKS_LIST:
            for a in data.get(name, {}).get("alerts", []):
                meta = data.get(name, {}).get("meta", {})
                alerts.append(f"[{meta.get('cmd', name)}] {a}")
        summary = "\n".join(f"- {a}" for a in alerts) if alerts else "Nenhum alerta encontrado."
        lines_prompt = []
        for name in SEC_CHECKS_LIST:
            r2 = data.get(name, {})
            st = r2.get("status", "ok")
            meta = r2.get("meta", {})
            al = r2.get("alerts", [])
            at = r2.get("attentions", [])
            lines_prompt.append(f"{name}: status={st}, alerts={len(al)}, attentions={len(at)}")
            for a in al[:3]:
                lines_prompt.append(f"  alerta: {a}")
            for a in at[:3]:
                lines_prompt.append(f"  atencao: {a}")
            if not al and not at:
                lines_prompt.append(f"  tudo ok")
        full_report = "\n".join(lines_prompt)
        prompt = (
            "Você é um analista de segurança Linux. Analise o relatório abaixo verificando cada item "
            "individualmente (conexões de saída, SSH, integridade de arquivos, persistência, processos, portas). "
            "Explique o resultado de cada teste de forma concisa em português. "
            "Se houver portas como 631 em localhost, cite como atenção de baixo risco, não como alerta grave. "
            "Responda em até 4 parágrafos no máximo.\n\n"
            f"RELATÓRIO:\n{full_report}"
        )
        try:
            r = httpx.post(
                f"{OLLAMA_URL}/api/chat",
                json={"model": MODELO_PADRAO, "messages": [{"role": "user", "content": prompt}], "options": ollama_utils.get_chat_options(MODELO_PADRAO), "stream": False},
                timeout=60,
            )
            ia = r.json().get("message", {}).get("content", "Erro na análise.") if r.status_code == 200 else "IA indisponível"
        except Exception as e:
            ia = f"IA indisponível: {e}"
        has_risk = len(alerts) > 0
        db.save_report(has_risk, len(alerts), ia, summary, source="desktop")
        return jsonify({"report": data, "ia_analysis": ia})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route("/api/reports")
def list_reports():
    return jsonify(db.list_reports(20))

@app.route("/api/reports/<int:report_id>")
def get_report(report_id):
    r = db.get_report(report_id)
    if not r:
        return jsonify({"error": "not found"}), 404
    return jsonify(r)

@app.route("/api/reports/<int:report_id>", methods=["DELETE"])
def delete_report(report_id):
    db.delete_report(report_id)
    return jsonify({"status": "ok"})

@app.route("/api/reports/<int:report_id>", methods=["POST"])
def delete_report_post(report_id):
    db.delete_report(report_id)
    return jsonify({"status": "ok"})

@app.route("/api/security/<check>")
def security_check(check):
    if security is None:
        return jsonify({"status": "erro", "alerts": [], "meta": {"cmd": "erro", "desc": "Módulo de segurança não carregado", "risk": "erro"}})
    funcs = {
        "connections": security.check_connections,
        "ssh": security.check_ssh,
        "integrity": security.check_integrity,
        "persistence": security.check_persistence,
        "processes": security.check_processes,
        "ports": security.check_ports,
    }
    fn = funcs.get(check)
    if not fn:
        return jsonify({"status": "erro", "alerts": [], "meta": {"cmd": "", "desc": "Verificação desconhecida", "risk": ""}})
    try:
        return jsonify(fn())
    except Exception as e:
        return jsonify({"status": "erro", "alerts": [f"Erro: {e}"], "meta": {"cmd": "erro", "desc": str(e), "risk": "erro"}})

# ─── Model API ──────────────────────────────────────────

@app.route("/api/model")
def get_model():
    return jsonify({"model": MODELO_PADRAO})

@app.route("/api/model", methods=["POST"])
def set_model():
    global MODELO_PADRAO
    data = request.get_json()
    model = data.get("model", "").strip()
    if model:
        MODELO_PADRAO = model
        ollama_utils.clear_cache()
        db.set_setting("model", model)
        return jsonify({"status": "ok", "model": model})
    return jsonify({"error": "model name required"}), 400

@app.route("/api/context")
def context_info():
    return jsonify(ollama_utils.get_context_info(MODELO_PADRAO))

@app.route("/api/context", methods=["POST"])
def refresh_context():
    ollama_utils.clear_cache()
    return jsonify(ollama_utils.get_context_info(MODELO_PADRAO))

@app.route("/api/models")
def list_models():
    try:
        r = httpx.get(f"{OLLAMA_URL}/api/tags", timeout=5)
        if r.status_code == 200:
            models = [m["name"] for m in r.json().get("models", [])]
            return jsonify({"models": models})
    except:
        pass
    return jsonify({"models": []})

# ─── Periodic cache clear ────────────────────────────────

def periodic_cache_clear():
    _kill_bot()
    _start_bot()
    print("[cache] Limpeza periódica de cache executada")
    threading.Timer(7200, periodic_cache_clear).start()

if __name__ == "__main__":
    db.init()
    if security:
        try:
            security.init_baseline()
        except Exception as e:
            print(f"[security] Erro ao iniciar baseline: {e}", file=sys.stderr)
    MODELO_PADRAO = db.get_setting("model", "gemma4")
    ensure_ollama()
    _start_bot()
    threading.Timer(7200, periodic_cache_clear).start()
    port = int(os.environ.get("PORT", 5000))
    print(f"API rodando em http://localhost:{port}")
    app.run(host="127.0.0.1", port=port, debug=False, threaded=True)

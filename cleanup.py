import os
import subprocess
import shutil

SUDO_PASSWORD = ""

def set_password(pw):
    global SUDO_PASSWORD
    SUDO_PASSWORD = pw

def _run(cmd, timeout=120):
    if SUDO_PASSWORD and cmd.startswith("sudo "):
        cmd = f"echo {SUDO_PASSWORD} | sudo -S " + cmd[5:]
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip()[:500], r.stderr.strip()[:500], r.returncode
    except subprocess.TimeoutExpired:
        return "", "comando excedeu tempo limite", -1
    except Exception as e:
        return "", str(e), -1

TASKS = [
    {
        "id": "apt_cache",
        "name": "🧹 Cache APT",
        "desc": "Remove pacotes órfãos — executa apt autoremove --dry-run antes pra você ver o que será removido, depois confirma",
        "cmd": "sudo apt autoremove -y && sudo apt autoclean -y",
        "needs_sudo": True,
        "safe": True,
    },
    {
        "id": "journal",
        "name": "📋 Logs do sistema",
        "desc": "Limpa logs do journalctl com mais de 7 dias",
        "cmd": "sudo journalctl --vacuum-time=7d",
        "needs_sudo": True,
        "safe": True,
    },
    {
        "id": "user_cache",
        "name": "🗃 Cache do usuário",
        "desc": "Limpa ~/.cache (recriado automaticamente pelos apps)",
        "cmd": "rm -rf ~/.cache/*",
        "needs_sudo": False,
        "safe": True,
    },
    {
        "id": "trash",
        "name": "🗑 Lixeira",
        "desc": "Esvazia a lixeira do usuário — irreversível",
        "cmd": "rm -rf ~/.local/share/Trash/* 2>/dev/null || true",
        "needs_sudo": False,
        "safe": True,
    },
    {
        "id": "pip_cache",
        "name": "📦 Cache pip",
        "desc": "Limpa cache de pacotes Python",
        "cmd": "pip3 cache purge 2>/dev/null || pip cache purge 2>/dev/null || true",
        "needs_sudo": False,
        "safe": True,
    },
    {
        "id": "npm_cache",
        "name": "📦 Cache npm",
        "desc": "Limpa cache do npm",
        "cmd": "npm cache clean --force 2>/dev/null || true",
        "needs_sudo": False,
        "safe": True,
    },
    {
        "id": "snap_revisions",
        "name": "🔧 Snap antigos",
        "desc": "Remove revisões antigas de snaps — irreversível, não dá pra voltar versão",
        "cmd": "snap list --all 2>/dev/null | awk '/disabled/{print $1\" --revision=\"$3}' | xargs -r -L1 sudo snap remove 2>/dev/null || true",
        "needs_sudo": True,
        "safe": True,
    },
    {
        "id": "drop_caches",
        "name": "💨 Memória cache",
        "desc": "Limpa caches de memória RAM (pagecache + dentries) — kernel recria sob demanda",
        "cmd": "sync && sudo sh -c 'echo 3 > /proc/sys/vm/drop_caches'",
        "needs_sudo": True,
        "safe": True,
    },
    {
        "id": "temp_files",
        "name": "🌡 Arquivos temporários",
        "desc": "⚠️ PERIGOSO: remove /tmp — pode quebrar apps com sockets/locks em execução. Pop!_OS já limpa /tmp no boot, evite rodar com servidor ativo",
        "cmd": "sudo rm -rf /tmp/*",
        "needs_sudo": True,
        "safe": False,
    },
]

def preview_task(task_id):
    for t in TASKS:
        if t["id"] == task_id and t["id"] == "apt_cache":
            stdout, stderr, code = _run("sudo apt --dry-run autoremove 2>/dev/null | grep -E '^Remv|^Inst|^Conf' | head -30")
            if not stdout:
                return {"id": task_id, "preview": "Nenhum pacote a ser removido.", "packages": []}
            pkgs = [line.strip() for line in stdout.split("\n") if line.strip()]
            return {"id": task_id, "preview": "\n".join(pkgs), "packages": pkgs}
        if t["id"] == task_id:
            return {"id": task_id, "preview": "Preview não disponível para esta tarefa.", "packages": []}
    return {"id": task_id, "preview": "Tarefa não encontrada", "packages": []}

def run_task(task_id):
    for t in TASKS:
        if t["id"] == task_id:
            stdout, stderr, code = _run(t["cmd"])
            ok = code == 0 and "erro" not in stderr.lower()
            if not ok and "a password is required" in stderr.lower():
                return {"id": task_id, "ok": False, "stdout": "", "stderr": "🔒 Senha sudo necessária. Configure no Desktop > Limpeza > botão 🔑.", "exit": code}
            return {"id": task_id, "ok": ok, "stdout": stdout[:300], "stderr": stderr[:300], "exit": code}
    return {"id": task_id, "ok": False, "stdout": "", "stderr": "tarefa não encontrada", "exit": -1}

def run_all():
    results = []
    for t in TASKS:
        r = run_task(t["id"])
        results.append(r)
    return results

def run_safe():
    results = []
    for t in TASKS:
        if t["safe"]:
            r = run_task(t["id"])
            results.append(r)
    return results

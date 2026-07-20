import os
import subprocess
import shutil

def _run(cmd, timeout=120):
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
        "desc": "Remove pacotes órfãos e limpa cache do apt",
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
        "desc": "Limpa ~/.cache (recriado automaticamente)",
        "cmd": "rm -rf ~/.cache/*",
        "needs_sudo": False,
        "safe": True,
    },
    {
        "id": "trash",
        "name": "🗑 Lixeira",
        "desc": "Esvazia a lixeira do usuário",
        "cmd": "rm -rf ~/.local/share/Trash/*",
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
        "desc": "Remove revisões antigas de snaps",
        "cmd": "snap list --all 2>/dev/null | awk '/disabled/{print $1\" --revision=\"$3}' | xargs -r -L1 sudo snap remove 2>/dev/null || true",
        "needs_sudo": True,
        "safe": True,
    },
    {
        "id": "old_kernels",
        "name": "🐧 Kernels antigos",
        "desc": "Remove kernels antigos (mantém os 2 mais recentes)",
        "cmd": "sudo apt --purge autoremove -y",
        "needs_sudo": True,
        "safe": True,
    },
    {
        "id": "drop_caches",
        "name": "💨 Memória cache",
        "desc": "Limpa caches de memória RAM (pagecache + dentries)",
        "cmd": "sync && sudo sh -c 'echo 3 > /proc/sys/vm/drop_caches'",
        "needs_sudo": True,
        "safe": True,
    },
    {
        "id": "temp_files",
        "name": "🌡 Arquivos temporários",
        "desc": "Remove arquivos em /tmp (pode afetar processos em execução)",
        "cmd": "sudo rm -rf /tmp/*",
        "needs_sudo": True,
        "safe": False,
    },
]

def run_task(task_id):
    for t in TASKS:
        if t["id"] == task_id:
            stdout, stderr, code = _run(t["cmd"])
            ok = code == 0 and "erro" not in stderr.lower()
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

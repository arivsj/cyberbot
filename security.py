import os
import hashlib
import subprocess
import json
import db

ALERT_PORTS = {"4444", "1337"}
SUSPICIOUS_NAMES = {"[kworkerds]", "[kworker]", "systemd-network", "crond", "[syslogd]"}
SAFE_PORTS = {22, 80, 443, 53, 5432, 3306, 631, 11434}
BASELINE_FILES = ["/etc/passwd", "/etc/sudoers", "/bin/ls", "/bin/ps"]
BASELINE_KEY = "file_baseline"

def _run(cmd, timeout=5):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip(), r.stderr.strip(), r.returncode
    except subprocess.TimeoutExpired:
        return "", "timeout", -1
    except Exception as e:
        return "", str(e), -1

CHECKS_META = {
    "connections": {
        "cmd": "ss -tupn | grep ESTAB",
        "desc": "Conexões TCP estabelecidas — verifica portas suspeitas (4444, 1337) e IPs públicos em processos anormais (bash, python, nc, perl).",
        "risk": "Exfiltração de dados ou backdoor",
    },
    "ssh": {
        "cmd": "lastb / last / journalctl",
        "desc": "Tentativas de login SSH — lista falhas e sucessos recentes para detectar brute force.",
        "risk": "Ataque de força bruta ou acesso não autorizado",
    },
    "integrity": {
        "cmd": "md5sum /etc/passwd /etc/sudoers /bin/ls /bin/ps",
        "desc": "Integridade de arquivos críticos — compara hashes MD5 atuais com o baseline salvo.",
        "risk": "Arquivo de sistema modificado (rootkit ou backdoor)",
    },
    "persistence": {
        "cmd": "crontab -l / systemctl list-timers",
        "desc": "Persistência — verifica cron jobs e timers systemd em busca de scripts em /tmp ou /dev/shm.",
        "risk": "Persistência maliciosa no sistema",
    },
    "processes": {
        "cmd": "ps aux --sort=-%cpu",
        "desc": "Processos com maior uso de CPU — detecta nomes falsos ([kworkerds], systemd-network) e CPU acima de 200%.",
        "risk": "Processo malicioso disfarçado ou minerador",
    },
    "ports": {
        "cmd": "ss -tulpn | grep LISTEN",
        "desc": "Portas escutando em interfaces não-loopback — alerta portas fora do padrão (≠22,80,443,53,5432,3306).",
        "risk": "Serviço não autorizado exposto na rede",
    },
}

def check_connections():
    out, err, code = _run("ss -tupn 2>/dev/null | grep ESTAB")
    if code != 0 or not out:
        return {"status": "ok", "entries": [], "count": 0, "alerts": [], "meta": CHECKS_META["connections"]}
    alerts = []
    entries = []
    for line in out.split("\n"):
        parts = line.strip().split()
        if len(parts) < 6:
            continue
        remote = parts[4] if len(parts) > 4 else ""
        proc = parts[6] if len(parts) > 6 else parts[5] if len(parts) > 5 else ""
        entries.append({"remote": remote, "proc": proc})
        ip_port = remote.rsplit(":", 1)
        port = ip_port[1] if len(ip_port) > 1 else ""
        ip = ip_port[0] if len(ip_port) > 1 else remote
        pid_info = proc.split("users:(")[-1].rstrip(")") if "users:" in proc else proc
        if port in ALERT_PORTS:
            alerts.append(f"Conexão para porta {port} ({remote}) - {pid_info}")
        elif not ip.startswith(("10.", "172.16.", "192.168.", "127.")):
            pid_name = pid_info.split(",")[0].strip('"') if "," in pid_info else pid_info
            if any(p in pid_name.lower() for p in ["bash", "python", "nc", "perl"]):
                alerts.append(f"Conexão pública {remote} via {pid_name}")
    return {"status": "alerta" if alerts else "ok", "entries": entries, "count": len(entries), "alerts": alerts, "meta": CHECKS_META["connections"]}

def check_ssh():
    fails, _, _ = _run("lastb -a 2>/dev/null | head -5")
    success, _, _ = _run("last -a 2>/dev/null | head -5")
    if not fails and not success:
        fails, _, _ = _run("journalctl -u sshd -n 10 --no-pager 2>/dev/null | grep -i 'Failed password' | tail -5")
        success, _, _ = _run("journalctl -u sshd -n 10 --no-pager 2>/dev/null | grep -i 'Accepted' | tail -5")
    fail_lines = [l for l in fails.split("\n") if l.strip()] if fails else []
    succ_lines = [l for l in success.split("\n") if l.strip()] if success else []
    return {
        "status": "alerta" if fail_lines else "ok",
        "fail_count": len(fail_lines),
        "success_count": len(succ_lines),
        "fails": fail_lines[:5],
        "success": succ_lines[:5],
        "meta": CHECKS_META["ssh"],
    }

def _md5(path):
    try:
        with open(path, "rb") as f:
            return hashlib.md5(f.read()).hexdigest()
    except:
        return None

def _load_baseline():
    raw = db.get_setting(BASELINE_KEY, "{}")
    try:
        return json.loads(raw)
    except:
        return {}

def _save_baseline(baseline):
    db.set_setting(BASELINE_KEY, json.dumps(baseline))

def init_baseline():
    baseline = _load_baseline()
    changed = False
    for path in BASELINE_FILES:
        if path not in baseline:
            h = _md5(path)
            if h:
                baseline[path] = h
                changed = True
    if changed:
        _save_baseline(baseline)

def check_integrity():
    baseline = _load_baseline()
    alerts = []
    results = []
    for path in BASELINE_FILES:
        current = _md5(path)
        stored = baseline.get(path)
        if current is None:
            results.append({"file": path, "status": "erro", "detail": "não encontrado"})
            continue
        if stored is None:
            baseline[path] = current
            _save_baseline(baseline)
            results.append({"file": path, "status": "ok", "detail": "baseline criado"})
            continue
        if current != stored:
            alerts.append(f"{path} modificado!")
            results.append({"file": path, "status": "modificado", "detail": f"hash diff"})
        else:
            results.append({"file": path, "status": "ok", "detail": "ok"})
    return {"status": "alerta" if alerts else "ok", "results": results, "alerts": alerts, "meta": CHECKS_META["integrity"]}

def check_persistence():
    out, _, _ = _run("crontab -l 2>/dev/null | grep -v '^#'")
    alerts = []
    cron_entries = []
    if out:
        for line in out.split("\n"):
            line = line.strip()
            if line:
                cron_entries.append(line)
                if "/tmp/" in line or "/dev/shm/" in line:
                    alerts.append(f"Cron suspeito: {line[:80]}")
    timers, _, _ = _run("systemctl list-timers --all --no-legend 2>/dev/null | head -10")
    timer_list = [t.strip() for t in timers.split("\n") if t.strip()][:10] if timers else []
    return {
        "status": "alerta" if alerts else "ok",
        "cron_entries": cron_entries,
        "alerts": alerts,
        "timers": timer_list,
        "meta": CHECKS_META["persistence"],
    }

def check_processes():
    out, _, _ = _run("ps aux --sort=-%cpu 2>/dev/null | head -6")
    alerts = []
    processes = []
    if not out:
        return {"status": "erro", "entries": [], "alerts": [], "count": 0}
    lines = out.split("\n")[1:]  # skip header
    for line in lines:
        parts = line.strip().split()
        if len(parts) < 11:
            continue
        try:
            cpu = float(parts[2])
        except:
            cpu = 0
        name = parts[10] if len(parts) > 10 else ""
        full = " ".join(parts[10:]) if len(parts) > 11 else name
        processes.append({"name": name, "cpu": cpu, "full": full})
        if name in SUSPICIOUS_NAMES:
            alerts.append(f"Processo suspeito: {full} (CPU: {cpu}%)")
        elif cpu > 200:
            alerts.append(f"CPU excessiva: {full[:60]} ({cpu}%)")
    return {"status": "alerta" if alerts else "ok", "entries": processes, "alerts": alerts, "count": len(processes), "meta": CHECKS_META["processes"]}

def check_ports():
    out, _, _ = _run("ss -tulpn 2>/dev/null | grep LISTEN")
    alerts = []
    attentions = []
    ports = []
    own_ports = []
    if not out:
        return {"status": "ok", "entries": [], "alerts": [], "attentions": [], "count": 0, "meta": CHECKS_META["ports"]}
    for line in out.split("\n"):
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) < 5:
            continue
        addr = parts[4]
        proc = parts[6] if len(parts) > 6 else ""
        try:
            port = int(addr.rsplit(":", 1)[1])
        except:
            continue
        ports.append({"addr": addr, "port": port, "proc": proc})
        if port == 5000:
            own_ports.append(f"Porta {port} ({addr}) — próprio app (servidor CyberBot)")
            continue
        if port not in SAFE_PORTS:
            if "127.0.0.1" in addr or "::1" in addr:
                attentions.append(f"Porta {port} ({addr}) - {proc} (apenas localhost)")
            else:
                alerts.append(f"Porta {port} aberta ({addr}) - {proc}")
    status = "alerta" if alerts else "atencao" if attentions else "ok"
    return {"status": status, "entries": ports, "alerts": alerts, "attentions": attentions, "own_ports": own_ports, "count": len(ports), "meta": CHECKS_META["ports"]}

def run_all():
    return {
        "connections": check_connections(),
        "ssh": check_ssh(),
        "integrity": check_integrity(),
        "persistence": check_persistence(),
        "processes": check_processes(),
        "ports": check_ports(),
    }

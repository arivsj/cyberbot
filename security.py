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
    "firewall": {
        "cmd": "ufw status / iptables -L",
        "desc": "Status do firewall — verifica se UFW está ativo e se há regras de bloqueio configuradas.",
        "risk": "Firewall desativado — todas as portas expostas",
    },
    "fail2ban": {
        "cmd": "fail2ban-client status",
        "desc": "Status do Fail2ban — verifica se o serviço está rodando, jails ativos e IPs banidos.",
        "risk": "Sem proteção contra brute force",
    },
    "sudo": {
        "cmd": "journalctl -u sudo / grep sudo /var/log/auth.log",
        "desc": "Uso do sudo — monitora comandos sudo recentes e tentativas falhas de autenticação.",
        "risk": "Uso abusivo de sudo ou tentativa de escalação de privilégio",
    },
    "updates": {
        "cmd": "apt list --upgradable 2>/dev/null | grep -i security",
        "desc": "Atualizações de segurança pendentes — pacotes com correções disponíveis.",
        "risk": "Sistema desatualizado com CVEs conhecidas",
    },
    "services": {
        "cmd": "systemctl --failed",
        "desc": "Serviços systemd com falha — serviços que não iniciaram ou crasharam.",
        "risk": "Indicador de ataque ou má configuração",
    },
    "users": {
        "cmd": "awk -F: '$3==0' /etc/passwd / awk -F: '$2==\"\"' /etc/shadow",
        "desc": "Contas de usuário — verifica se há contas sem senha ou com UID 0 fora do root.",
        "risk": "Backdoor ou escalação de privilégio via conta não protegida",
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

def check_firewall():
    ufw, _, _ = _run("ufw status 2>/dev/null")
    alerts = []
    if "Status: active" in ufw:
        return {"status": "ok", "detail": "UFW ativo", "alerts": [], "meta": CHECKS_META["firewall"]}
    if ufw and "Status: inactive" in ufw:
        alerts.append("UFW está inativo. Ative com: sudo ufw enable")
        return {"status": "alerta", "detail": "UFW inativo", "alerts": alerts, "meta": CHECKS_META["firewall"]}
    out, _, _ = _run("iptables -L -n 2>/dev/null | head -5")
    if "Chain INPUT" in out:
        return {"status": "ok", "detail": "iptables presente", "alerts": [], "meta": CHECKS_META["firewall"]}
    alerts.append("Nenhum firewall detectado (UFW ou iptables)")
    return {"status": "alerta", "detail": "sem firewall", "alerts": alerts, "meta": CHECKS_META["firewall"]}

def check_fail2ban():
    out, _, code = _run("fail2ban-client status 2>/dev/null")
    alerts = []
    if code != 0 or not out:
        alerts.append("Fail2ban não está rodando ou não instalado")
        return {"status": "alerta", "jails": [], "banned": 0, "alerts": alerts, "meta": CHECKS_META["fail2ban"]}
    jails = []
    banned = 0
    for line in out.split("\n"):
        if "Jail list" in line:
            jails = [j.strip() for j in line.split(":")[-1].strip().split(",") if j.strip()]
        if "Banned" in line:
            try:
                banned = int(line.split(":")[-1].strip())
            except:
                pass
    if not jails:
        alerts.append("Fail2ban rodando mas sem jails ativos")
    return {"status": "atencao" if not jails else "ok", "jails": jails, "banned": banned, "alerts": alerts, "meta": CHECKS_META["fail2ban"]}

def check_sudo():
    out, _, _ = _run("journalctl -u sudo -n 20 --no-pager 2>/dev/null | tail -15")
    alerts = []
    entries = []
    if not out:
        out, _, _ = _run("grep -a 'sudo' /var/log/auth.log 2>/dev/null | tail -15")
    if not out:
        return {"status": "ok", "entries": [], "count": 0, "alerts": [], "meta": CHECKS_META["sudo"]}
    for line in out.split("\n"):
        line = line.strip()
        if not line:
            continue
        entries.append(line)
        if "FAILED" in line.upper() or "incorrect" in line.lower() or "authentication failure" in line.lower():
            alerts.append(f"Tentativa falha de sudo: {line[:120]}")
    return {"status": "alerta" if alerts else "ok", "entries": entries, "count": len(entries), "alerts": alerts[:5], "meta": CHECKS_META["sudo"]}

def check_updates():
    out, _, code = _run("apt list --upgradable 2>/dev/null | grep -i security | head -20")
    alerts = []
    security_pkgs = []
    if not out:
        out2, _, _ = _run("apt list --upgradable 2>/dev/null | tail -n +2 | head -30")
        if not out2:
            return {"status": "ok", "packages": [], "count": 0, "alerts": [], "meta": CHECKS_META["updates"]}
        return {"status": "ok", "packages": [], "count": 0, "alerts": [], "detail": "Atualizações disponíveis (não críticas)", "meta": CHECKS_META["updates"]}
    for line in out.split("\n"):
        line = line.strip()
        if line:
            security_pkgs.append(line)
    if security_pkgs:
        alerts.append(f"{len(security_pkgs)} pacote(s) de segurança pendente(s)")
        for p in security_pkgs[:5]:
            alerts.append(f"  {p}")
    return {"status": "alerta" if security_pkgs else "ok", "packages": security_pkgs, "count": len(security_pkgs), "alerts": alerts, "meta": CHECKS_META["updates"]}

def check_services():
    out, _, _ = _run("systemctl --failed --no-legend 2>/dev/null | head -15")
    alerts = []
    failed = []
    if not out:
        return {"status": "ok", "failed": [], "count": 0, "alerts": [], "meta": CHECKS_META["services"]}
    for line in out.split("\n"):
        line = line.strip()
        if line:
            parts = line.split()
            if parts:
                name = parts[0]
                failed.append(line)
                alerts.append(f"Serviço com falha: {name}")
    return {"status": "alerta" if failed else "ok", "failed": failed, "count": len(failed), "alerts": alerts, "meta": CHECKS_META["services"]}

def check_users():
    uid0, _, _ = _run("awk -F: '($3 == 0) {print}' /etc/passwd 2>/dev/null")
    empty_pw, _, _ = _run("awk -F: '($2 == \"\") {print}' /etc/shadow 2>/dev/null")
    alerts = []
    attentions = []
    uid0_list = [l.strip() for l in uid0.split("\n") if l.strip()] if uid0 else []
    empty_list = [l.strip() for l in empty_pw.split("\n") if l.strip()] if empty_pw else []
    for u in uid0_list:
        if "root" not in u.split(":")[0]:
            alerts.append(f"Conta não-root com UID 0: {u.split(':')[0]}")
    for u in empty_list:
        alerts.append(f"Conta sem senha: {u.split(':')[0]}")
    if not uid0_list and not empty_list:
        attentions.append("Verificar se há contas desativadas ou suspeitas")
    return {"status": "alerta" if alerts else "ok", "uid0": uid0_list, "empty_password": empty_list, "alerts": alerts, "attentions": attentions if not alerts else [], "meta": CHECKS_META["users"]}

def run_all():
    return {
        "connections": check_connections(),
        "ssh": check_ssh(),
        "integrity": check_integrity(),
        "persistence": check_persistence(),
        "processes": check_processes(),
        "ports": check_ports(),
        "firewall": check_firewall(),
        "fail2ban": check_fail2ban(),
        "sudo": check_sudo(),
        "updates": check_updates(),
        "services": check_services(),
        "users": check_users(),
    }

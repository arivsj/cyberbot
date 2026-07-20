import subprocess
import re
import db

def _run(cmd, timeout=5):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip(), r.stderr.strip(), r.returncode
    except:
        return "", "", -1

def get_cpu_usage():
    out, _, _ = _run("top -bn1 | grep 'Cpu(s)' | awk '{print $2 + $4}'")
    if out:
        try: return min(round(float(out), 1), 100)
        except: pass
    out, _, _ = _run("ps aux | awk 'NR>1 {s+=$3} END {print s}'")
    if out:
        try: return min(round(float(out), 1), 100)
        except: pass
    return 0

def get_cpu_temp():
    for path in [
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/hwmon/hwmon0/temp1_input",
    ]:
        out, _, _ = _run(f"cat {path} 2>/dev/null")
        if out:
            try:
                v = int(out.strip())
                if v > 0: return round(v / 1000, 1)
            except: pass
    out, _, _ = _run("for f in /sys/class/hwmon/hwmon*/temp*_input; do cat \"$f\" 2>/dev/null && break; done")
    if out:
        try:
            v = int(out.strip().split("\n")[0])
            if v > 0: return round(v / 1000, 1)
        except: pass
    out, _, _ = _run("sensors 2>/dev/null | grep -i 'core\\|temp1' | head -1 | awk '{print $2}' | tr -d '+°C'")
    if out:
        try: return float(out)
        except: pass
    return 0

def get_ram():
    out, _, _ = _run("free -m | grep Mem")
    if out:
        parts = out.split()
        if len(parts) >= 3:
            try:
                total = float(parts[1])
                used = float(parts[2])
                pct = round(used / total * 100, 1) if total > 0 else 0
                return {"total": int(total), "used": int(used), "pct": pct}
            except: pass
    return {"total": 0, "used": 0, "pct": 0}

def get_gpu():
    out, _, _ = _run("nvidia-smi --query-gpu=memory.used,memory.total,temperature.gpu --format=csv,noheader,nounits 2>/dev/null")
    if out:
        parts = out.strip().split(",")
        if len(parts) >= 3:
            try:
                vram_used = int(parts[0].strip())
                vram_total = int(parts[1].strip())
                temp = int(parts[2].strip())
                pct = round(vram_used / vram_total * 100, 1) if vram_total > 0 else 0
                return {"vram_used": vram_used, "vram_total": vram_total, "vram_pct": pct, "temp": temp, "present": True}
            except: pass
    return {"present": False}

def collect():
    cpu = get_cpu_usage()
    temp = get_cpu_temp()
    ram = get_ram()
    gpu = get_gpu()
    data = {
        "cpu": cpu,
        "cpu_temp": temp,
        "ram": ram,
        "gpu": gpu,
    }
    try:
        db.save_sysmon(data)
    except:
        pass
    return data

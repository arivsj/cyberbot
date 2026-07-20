import os
import json
from datetime import datetime

LOG_DIR = os.path.join(os.path.dirname(__file__), "logs")

def _ensure_dir():
    os.makedirs(LOG_DIR, exist_ok=True)

def _today_path():
    return os.path.join(LOG_DIR, f"access_{datetime.now().strftime('%Y-%m-%d')}.jsonl")

def log_access(entry: dict):
    _ensure_dir()
    path = _today_path()
    entry["ts"] = datetime.now().isoformat(sep=" ", timespec="seconds")
    with open(path, "a") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")

def _parse_log(path):
    entries = []
    if not os.path.exists(path):
        return entries
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    entries.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
    return entries

def get_today_entries():
    return _parse_log(_today_path())

def get_log_stats():
    _ensure_dir()
    total_size = 0
    today_size = 0
    today_path = _today_path()
    for fname in os.listdir(LOG_DIR):
        fpath = os.path.join(LOG_DIR, fname)
        if os.path.isfile(fpath):
            sz = os.path.getsize(fpath)
            total_size += sz
            if fpath == today_path:
                today_size = sz
    today = get_today_entries()
    by_type = {}
    by_hour = {}
    for e in today:
        t = e.get("type", "unknown")
        by_type[t] = by_type.get(t, 0) + 1
        try:
            hour = e["ts"].split(" ")[1].split(":")[0]
            by_hour[hour] = by_hour.get(hour, 0) + 1
        except (IndexError, KeyError):
            pass
    hours = [{"hour": h, "count": by_hour[h]} for h in sorted(by_hour)]
    blocked_count = sum(1 for e in today if e.get("type") == "blocked")
    return {
        "total_entries": len(today),
        "blocked": blocked_count,
        "by_type": [{"type": k, "count": v} for k, v in sorted(by_type.items())],
        "by_hour": hours,
        "total_log_size": total_size,
        "today_size": today_size,
    }

def get_blocked_entries():
    return [e for e in get_today_entries() if e.get("type") == "blocked"]

def list_dates():
    _ensure_dir()
    dates = set()
    for fname in os.listdir(LOG_DIR):
        if fname.startswith("access_") and fname.endswith(".jsonl"):
            date = fname.replace("access_", "").replace(".jsonl", "")
            try:
                datetime.strptime(date, "%Y-%m-%d")
                dates.add(date)
            except ValueError:
                pass
    return sorted(dates, reverse=True)

def get_entries_by_date(date_str):
    path = os.path.join(LOG_DIR, f"access_{date_str}.jsonl")
    return _parse_log(path)

def get_all_users():
    users = {}
    for fname in sorted(os.listdir(LOG_DIR), reverse=True):
        if fname.startswith("access_") and fname.endswith(".jsonl"):
            for e in _parse_log(os.path.join(LOG_DIR, fname)):
                uid = e.get("user_id")
                if not uid:
                    continue
                ts = e.get("ts", "")
                if uid in users:
                    if ts > users[uid].get("last_seen", ""):
                        users[uid]["last_seen"] = ts
                        users[uid]["last_type"] = e.get("type", "")
                        users[uid]["last_summary"] = (e.get("summary") or "")[:80]
                else:
                    users[uid] = {
                        "user_id": uid,
                        "username": e.get("username"),
                        "first_name": e.get("first_name"),
                        "last_name": e.get("last_name"),
                        "chat_id": e.get("chat_id"),
                        "last_seen": ts,
                        "last_type": e.get("type", ""),
                        "last_summary": (e.get("summary") or "")[:80],
                    }
    return sorted(users.values(), key=lambda u: u.get("last_seen") or "", reverse=True)

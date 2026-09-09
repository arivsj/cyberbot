import hashlib
import hmac
import json
import secrets
import threading
import time
from collections import deque
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

import db
import log

STATE_KEY = "mobile_state"
PAIR_KEY = "mobile_pair"
PIN_FAIL_KEY = "mobile_pin_fail"
MAX_DEVICES = 10
PAIR_CODE_TTL = 120
PAIR_MAX_ATTEMPTS = 5
PAIR_LOCK_SECONDS = 600
PIN_MAX_ATTEMPTS = 5
PIN_LOCK_SECONDS = 900
RATE_LIMIT_PER_MIN = 120
RATE_LIMIT_BURST = 10
IDEMPOTENCY_TTL = 600
SCRYPT_N = 2 ** 14
SCRYPT_R = 8
SCRYPT_P = 1

_lock = threading.RLock()
_rate: Dict[str, deque] = {}
_idem: Dict[str, Tuple[float, Any]] = {}


def _hash_pair_code(code: str) -> str:
    return hashlib.sha256(("pair:" + str(code)).encode()).hexdigest()


def _load_json_setting(key: str, default: Dict[str, Any]) -> Dict[str, Any]:
    raw = db.get_setting(key, "")
    if not raw:
        return dict(default)
    try:
        value = json.loads(raw)
    except (ValueError, TypeError):
        return dict(default)
    return value if isinstance(value, dict) else dict(default)


def _save_json_setting(key: str, value: Dict[str, Any]) -> None:
    db.set_setting(key, json.dumps(value))


def _now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _empty_state() -> Dict[str, Any]:
    return {"version": 1, "pin": None, "devices": []}


def _load() -> Dict[str, Any]:
    raw = db.get_setting(STATE_KEY, "")
    if not raw:
        return _empty_state()
    try:
        state = json.loads(raw)
    except (ValueError, TypeError):
        return _empty_state()
    if not isinstance(state, dict):
        return _empty_state()
    state.setdefault("version", 1)
    state.setdefault("pin", None)
    state.setdefault("devices", [])
    return state


def _save(state: Dict[str, Any]) -> None:
    db.set_setting(STATE_KEY, json.dumps(state, ensure_ascii=False))


def _hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def _scrypt(password: str, salt: bytes) -> bytes:
    return hashlib.scrypt(password.encode(), salt=salt, n=SCRYPT_N, r=SCRYPT_R, p=SCRYPT_P, dklen=32)


def _audit(event: str, **fields: Any) -> None:
    entry = {"type": "mobile", "event": event}
    entry.update({k: v for k, v in fields.items() if v is not None})
    try:
        log.log_access(entry)
    except Exception:
        pass


# ─── Pareamento ──────────────────────────────────────────

def generate_pair_code(ttl: int = PAIR_CODE_TTL) -> Dict[str, Any]:
    with _lock:
        now = time.time()
        state = _load_json_setting(PAIR_KEY, {"code_hash": None, "expires_at": 0.0,
                                              "fail_count": 0, "until": 0.0})
        if float(state.get("until") or 0) > now:
            return {"ok": False, "code": "PAIR_LOCKED",
                    "retry_after": int(float(state["until"]) - now) + 1}
        code = f"{secrets.randbelow(1_000_000):06d}"
        state.update({"code_hash": _hash_pair_code(code), "expires_at": now + max(30, int(ttl)),
                      "fail_count": 0})
        _save_json_setting(PAIR_KEY, state)
        _audit("pair_code_created", ttl=ttl)
        return {"ok": True, "code": code, "expires_in": max(30, int(ttl))}


def _check_pair_code(code: str) -> Tuple[bool, Optional[str], Optional[int]]:
    with _lock:
        now = time.time()
        state = _load_json_setting(PAIR_KEY, {"code_hash": None, "expires_at": 0.0,
                                              "fail_count": 0, "until": 0.0})
        if float(state.get("until") or 0) > now:
            return False, "PAIR_TOO_MANY_ATTEMPTS", int(float(state["until"]) - now) + 1
        expected = state.get("code_hash")
        expires_at = float(state.get("expires_at") or 0)
        if not expected or expires_at < now:
            state.update({"code_hash": None, "expires_at": 0.0})
            _save_json_setting(PAIR_KEY, state)
            return False, "PAIR_CODE_EXPIRED", None
        if not hmac.compare_digest(_hash_pair_code(code), str(expected)):
            state["fail_count"] = int(state.get("fail_count") or 0) + 1
            if state["fail_count"] >= PAIR_MAX_ATTEMPTS:
                state.update({"until": now + PAIR_LOCK_SECONDS, "fail_count": 0, "code_hash": None})
                _save_json_setting(PAIR_KEY, state)
                return False, "PAIR_TOO_MANY_ATTEMPTS", PAIR_LOCK_SECONDS
            _save_json_setting(PAIR_KEY, state)
            return False, "PAIR_CODE_INVALID", None
        state.update({"code_hash": None, "expires_at": 0.0, "fail_count": 0})
        _save_json_setting(PAIR_KEY, state)
        return True, None, None


def pair_device(code: str, device_name: str, app_version: str = "", pubkey: Optional[str] = None,
                remote_ip: Optional[str] = None) -> Dict[str, Any]:
    ok, error_code, retry_after = _check_pair_code(code)
    if not ok:
        _audit("pair_failed", code=error_code, ip=remote_ip)
        return {"ok": False, "code": error_code, "retry_after": retry_after}

    with _lock:
        state = _load()
        active = [d for d in state["devices"] if not d.get("revoked")]
        if len(active) >= MAX_DEVICES:
            return {"ok": False, "code": "DEVICE_LIMIT", "retry_after": None}
        token = secrets.token_urlsafe(32)
        device_id = "dev_" + secrets.token_hex(4)
        state["devices"].append({
            "id": device_id,
            "name": (device_name or "Android")[:64],
            "token_hash": _hash_token(token),
            "pubkey": pubkey,
            "endpoint_id": None,
            "created_at": _now_iso(),
            "last_seen": None,
            "last_ip": remote_ip,
            "last_path": None,
            "app_version": (app_version or "")[:32],
            "revoked": False,
        })
        _save(state)
    _audit("paired", device_id=device_id, name=device_name, ip=remote_ip)
    return {"ok": True, "device_id": device_id, "token": token}


# ─── Dispositivos ────────────────────────────────────────

def list_devices() -> List[Dict[str, Any]]:
    with _lock:
        state = _load()
        return [
            {
                "id": d["id"],
                "name": d.get("name"),
                "created_at": d.get("created_at"),
                "last_seen": d.get("last_seen"),
                "last_ip": d.get("last_ip"),
                "revoked": bool(d.get("revoked")),
            }
            for d in state["devices"]
        ]


def revoke_device(device_id: str) -> bool:
    with _lock:
        state = _load()
        found = False
        for d in state["devices"]:
            if d["id"] == device_id:
                d["revoked"] = True
                found = True
        if found:
            _save(state)
    if found:
        _audit("device_revoked", device_id=device_id)
    return found


def delete_device(device_id: str) -> bool:
    with _lock:
        state = _load()
        before = len(state["devices"])
        state["devices"] = [d for d in state["devices"] if d["id"] != device_id]
        changed = len(state["devices"]) != before
        if changed:
            _save(state)
    if changed:
        _audit("device_deleted", device_id=device_id)
    return changed


def _find_by_token(token: str) -> Optional[Dict[str, Any]]:
    if not token:
        return None
    digest = _hash_token(token)
    with _lock:
        state = _load()
        for d in state["devices"]:
            if d.get("revoked"):
                continue
            if hmac.compare_digest(d.get("token_hash", ""), digest):
                return d
    return None


def authorize_token(token: str, remote_ip: Optional[str] = None, path: Optional[str] = None) -> Optional[Dict[str, Any]]:
    device = _find_by_token(token)
    if device is None:
        return None
    with _lock:
        state = _load()
        for d in state["devices"]:
            if d["id"] == device["id"]:
                d["last_seen"] = _now_iso()
                d["last_ip"] = remote_ip or d.get("last_ip")
                d["last_path"] = path or d.get("last_path")
        _save(state)
    return device


def authorize_endpoint(endpoint_id: str) -> Optional[Dict[str, Any]]:
    if not endpoint_id:
        return None
    with _lock:
        state = _load()
        for d in state["devices"]:
            if d.get("revoked"):
                continue
            if d.get("endpoint_id") and hmac.compare_digest(d["endpoint_id"], endpoint_id):
                return d
    return None


def bind_endpoint(device_id: str, endpoint_id: str) -> bool:
    with _lock:
        state = _load()
        for d in state["devices"]:
            if d["id"] == device_id:
                d["endpoint_id"] = endpoint_id
                _save(state)
                _audit("endpoint_bound", device_id=device_id, endpoint_id=endpoint_id)
                return True
    return False


def get_device(device_id: str) -> Optional[Dict[str, Any]]:
    with _lock:
        state = _load()
        for d in state["devices"]:
            if d["id"] == device_id:
                return d
    return None


# ─── PIN ─────────────────────────────────────────────────

def pin_is_set() -> bool:
    with _lock:
        return bool((_load().get("pin") or {}).get("hash"))


def _verify_pin_hash(pin: str, stored: Dict[str, Any]) -> bool:
    try:
        salt = bytes.fromhex(stored["salt"])
        expected = bytes.fromhex(stored["hash"])
    except (KeyError, ValueError):
        return False
    return hmac.compare_digest(_scrypt(pin, salt), expected)


def verify_pin(pin: Optional[str]) -> Tuple[bool, Optional[str], Optional[int]]:
    with _lock:
        state = _load()
        stored = state.get("pin")
        if not stored or not stored.get("hash"):
            return False, "PIN_NOT_SET", None
        now = time.time()
        fails = _load_json_setting(PIN_FAIL_KEY, {"count": 0, "until": 0.0})
        if float(fails.get("until") or 0) > now:
            return False, "LOCKED", int(float(fails["until"]) - now) + 1
        if not pin:
            return False, "PIN_REQUIRED", None
        if not _verify_pin_hash(str(pin), stored):
            fails["count"] = int(fails.get("count") or 0) + 1
            if fails["count"] >= PIN_MAX_ATTEMPTS:
                fails.update({"until": now + PIN_LOCK_SECONDS, "count": 0})
                _save_json_setting(PIN_FAIL_KEY, fails)
                _audit("pin_locked")
                return False, "LOCKED", PIN_LOCK_SECONDS
            _save_json_setting(PIN_FAIL_KEY, fails)
            return False, "PIN_INVALID", None
        _save_json_setting(PIN_FAIL_KEY, {"count": 0, "until": 0.0})
        return True, None, None


def set_pin(new_pin: str, current_pin: Optional[str] = None, require_current: bool = True) -> Dict[str, Any]:
    if not isinstance(new_pin, str) or not new_pin.isdigit() or len(new_pin) != 6:
        return {"ok": False, "code": "BAD_PIN", "message": "O PIN deve ter exatamente 6 dígitos."}
    with _lock:
        state = _load()
        stored = state.get("pin")
        if stored and stored.get("hash"):
            if require_current:
                ok, error_code, retry_after = verify_pin(current_pin)
                if not ok:
                    return {"ok": False, "code": error_code, "retry_after": retry_after}
        elif require_current:
            return {"ok": False, "code": "PIN_NOT_SET", "message": "Nenhum PIN definido. Defina localmente com: python3 mobile_bridge.py --set-pin"}
        salt = secrets.token_bytes(16)
        state["pin"] = {
            "algo": "scrypt", "n": SCRYPT_N, "r": SCRYPT_R, "p": SCRYPT_P,
            "salt": salt.hex(), "hash": _scrypt(new_pin, salt).hex(), "set_at": _now_iso(),
        }
        _save(state)
    _audit("pin_changed")
    return {"ok": True}


# ─── Rate limit / idempotência ───────────────────────────

def rate_limit_ok(device_id: str) -> Tuple[bool, Optional[int]]:
    now = time.time()
    with _lock:
        window = _rate.setdefault(device_id, deque())
        while window and now - window[0] > 60.0:
            window.popleft()
        if len(window) >= RATE_LIMIT_PER_MIN:
            return False, max(1, int(60.0 - (now - window[0])) + 1)
        recent = sum(1 for ts in window if now - ts <= 1.0)
        if recent >= RATE_LIMIT_BURST:
            return False, 1
        window.append(now)
        return True, None


def idempotency_get(key: str) -> Optional[Any]:
    if not key:
        return None
    now = time.time()
    with _lock:
        entry = _idem.get(key)
        if not entry:
            return None
        expiry, payload = entry
        if expiry < now:
            _idem.pop(key, None)
            return None
        return payload


def idempotency_put(key: str, payload: Any) -> None:
    if not key:
        return
    now = time.time()
    with _lock:
        for stale in [k for k, (exp, _) in _idem.items() if exp < now]:
            _idem.pop(stale, None)
        _idem[key] = (now + IDEMPOTENCY_TTL, payload)

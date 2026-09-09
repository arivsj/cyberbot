import io
import json
import os
import shutil
import tempfile
from typing import Any, Dict, List, Tuple

PASS = "PASS"
FAIL = "FAIL"


def _prepare_environment(tmp: str) -> None:
    import db
    import log

    db.DB_PATH = os.path.join(tmp, "selftest.db")
    db.DRIVE_DIR = os.path.join(tmp, "drive")
    os.makedirs(db.DRIVE_DIR, exist_ok=True)
    log.log_access = lambda entry: None


def run() -> int:
    tmp = tempfile.mkdtemp(prefix="cyberbot-selftest-")
    results: List[Tuple[str, bool, str]] = []

    try:
        _prepare_environment(tmp)
        import db
        import mobile_auth as auth
        import mobile_bridge
        import pc_apps
        from flask import Flask

        mobile_bridge.DRIVE_DIR = db.DRIVE_DIR
        db.init()

        # O selftest roda in-process e é muito mais rápido que uso real:
        # desliga o rate limit por padrão e o testa isoladamente em S23.
        original_limits = (auth.RATE_LIMIT_PER_MIN, auth.RATE_LIMIT_BURST)
        auth.RATE_LIMIT_PER_MIN = 100000
        auth.RATE_LIMIT_BURST = 100000

        app = Flask("selftest")
        mobile_bridge.register(app)
        client = app.test_client()

        def check(name: str, condition: bool, detail: str = "") -> None:
            results.append((name, bool(condition), detail))

        # ── S1: health só em loopback
        response = client.get("/api/m/health")
        check("S1 health loopback", response.status_code == 200, f"status={response.status_code}")

        # ── S2: rota protegida sem token
        response = client.get("/api/m/status")
        check("S2 status sem token -> 401", response.status_code == 401,
              f"status={response.status_code}")

        # ── S3: token inválido
        response = client.get("/api/m/status", headers={"Authorization": "Bearer nao-existe"})
        check("S3 token inválido -> 401", response.status_code == 401, f"status={response.status_code}")

        # ── S4/S5: código de pareamento
        response = client.get("/api/m/pair/new")
        code = (response.get_json() or {}).get("code", "")
        check("S4 pair/new gera código", response.status_code == 200 and len(code) == 6, f"code={code}")

        response = client.post("/api/m/pair", json={"code": "000000", "device_name": "selftest",
                                                    "app_version": "test"})
        check("S5 código errado -> 401", response.status_code == 401, f"status={response.status_code}")

        response = client.post("/api/m/pair", json={"code": code, "device_name": "selftest",
                                                    "app_version": "test"})
        payload = response.get_json() or {}
        token = payload.get("token", "")
        device_id = payload.get("device_id", "")
        check("S6 pareamento ok", response.status_code == 200 and bool(token) and bool(device_id),
              f"status={response.status_code}")
        check("S6b contrato do app", all(k in payload for k in
              ("device_id", "token", "pc_name", "transport_hint", "pin_set")),
              f"keys={sorted(payload.keys())}")

        response = client.post("/api/m/pair", json={"code": code, "device_name": "selftest",
                                                    "app_version": "test"})
        check("S7 código é uso único", response.status_code == 401, f"status={response.status_code}")

        auth_header = {"Authorization": f"Bearer {token}"}

        # ── S8: status com token
        response = client.get("/api/m/status", headers=auth_header)
        body = response.get_json() or {}
        check("S8 status 200", response.status_code == 200, f"status={response.status_code}")
        check("S8b shape do status", all(k in body for k in ("pc", "bot", "ollama", "transport"))
              and "hostname" in body.get("pc", {}), f"keys={sorted(body.keys())}")

        # ── S9: sysmon achatado
        response = client.get("/api/m/sysmon", headers=auth_header)
        body = response.get_json() or {}
        check("S9 sysmon 200", response.status_code == 200, f"status={response.status_code}")
        check("S9b sysmon numérico", all(isinstance(body.get(k), (int, float)) for k in
              ("cpu", "ram", "gpu", "temperature")), f"body={body}")

        # ── S10: pc/apps
        response = client.get("/api/m/pc/apps", headers=auth_header)
        apps = response.get_json() or []
        check("S10 pc/apps", response.status_code == 200 and isinstance(apps, list) and len(apps) >= 4,
              f"n={len(apps)}")
        check("S10b shape do app", all(set(a.keys()) == {"id", "label", "destructive"} for a in apps),
              f"first={apps[0] if apps else None}")

        # ── S11: app desconhecido
        response = client.post("/api/m/pc/run", json={"id": "nao-existe"}, headers=auth_header)
        check("S11 app desconhecido -> 404", response.status_code == 404, f"status={response.status_code}")

        # ── S12: ação destrutiva sem PIN definido
        response = client.post("/api/m/pc/run", json={"id": "lock"}, headers=auth_header)
        check("S12 lock sem PIN -> 403", response.status_code == 403, f"status={response.status_code}")

        # ── S13: sleep com valores inválidos
        response = client.post("/api/m/pc/sleep", json={"minutes": 0}, headers=auth_header)
        check("S13 sleep 0 -> 400/403", response.status_code in (400, 403), f"status={response.status_code}")
        response = client.post("/api/m/pc/sleep", json={"minutes": "90; rm -rf /"}, headers=auth_header)
        check("S13b sleep string maliciosa", response.status_code in (400, 403), f"status={response.status_code}")
        response = client.post("/api/m/pc/sleep", json={"minutes": 99999}, headers=auth_header)
        check("S13c sleep fora do range", response.status_code in (400, 403), f"status={response.status_code}")

        # ── S14: definir PIN (loopback)
        response = client.post("/api/m/pin", json={"new_pin": "123456"})
        check("S14 set PIN loopback", response.status_code == 200, f"status={response.status_code}")
        response = client.post("/api/m/pin", json={"new_pin": "123"})
        check("S14b PIN inválido -> 403", response.status_code == 403, f"status={response.status_code}")

        # ── S15: PIN obrigatório / inválido / lockout
        response = client.post("/api/m/pc/run", json={"id": "lock"}, headers=auth_header)
        check("S15 lock sem PIN -> 403 PIN_REQUIRED", response.status_code == 403,
              f"status={response.status_code} code={(response.get_json() or {}).get('error', {}).get('code')}")
        response = client.post("/api/m/pc/run", json={"id": "lock"},
                               headers={**auth_header, "X-Action-PIN": "000000"})
        check("S15b PIN errado -> 403", response.status_code == 403, f"status={response.status_code}")
        locked = False
        for _ in range(5):
            response = client.post("/api/m/pc/run", json={"id": "lock"},
                                   headers={**auth_header, "X-Action-PIN": "000000"})
            if response.status_code == 423:
                locked = True
        check("S15c lockout após 5 erros -> 423", locked, f"last={response.status_code}")
        auth._save_json_setting(auth.PIN_FAIL_KEY, {"count": 0, "until": 0.0})

        # ── S16: rota fora de /api/m não existe no gateway
        response = client.get("/api/financas", headers=auth_header)
        check("S16 gateway não expõe /api/*", response.status_code == 404, f"status={response.status_code}")

        # ── S17: upload sanitiza nome
        data = {"file": (io.BytesIO(b"conteudo"), "../../etc/passwd"), "folder_id": ""}
        response = client.post("/api/m/drive/upload", data=data, headers=auth_header,
                               content_type="multipart/form-data")
        body = response.get_json() or {}
        stored = body.get("name", "")
        check("S17 upload 200", response.status_code == 200, f"status={response.status_code}")
        check("S17b nome sanitizado", "/" not in stored and ".." not in stored, f"name={stored}")
        file_id = body.get("id")
        if file_id:
            record = db.get_arquivo(file_id)
            path = (record or {}).get("file_path", "")
            check("S17c arquivo dentro do drive", os.path.abspath(path).startswith(os.path.abspath(db.DRIVE_DIR)),
                  f"path={path}")
            response = client.get(f"/api/m/drive/download/{file_id}", headers=auth_header)
            check("S17d download 200", response.status_code == 200, f"status={response.status_code}")

        # ── S18: finanças
        response = client.post("/api/m/financas", json={"categoria": "selftest", "conta": "selftest",
                                                        "valor": 1.23, "descricao": "teste"},
                               headers=auth_header)
        check("S18 finanças POST", response.status_code == 200, f"status={response.status_code}")
        response = client.get("/api/m/financas/resumo", headers=auth_header)
        body = response.get_json() or {}
        check("S18b resumo", response.status_code == 200 and "resumo" in body, f"keys={sorted(body.keys())}")

        # ── S19: cleanup bloqueia sudo
        response = client.get("/api/m/cleanup/tasks", headers=auth_header)
        tasks = response.get_json()
        tasks = tasks if isinstance(tasks, list) else []
        sudo_task = next((t for t in tasks if t.get("needs_sudo")), None)
        check("S19 cleanup/tasks", response.status_code == 200 and bool(tasks), f"n={len(tasks)}")
        if sudo_task:
            response = client.post("/api/m/cleanup/run", json={"task": sudo_task["id"]},
                                   headers={**auth_header, "X-Action-PIN": "123456"})
            check("S19b tarefa sudo bloqueada", response.status_code == 403, f"status={response.status_code}")

        # ── S20: security
        response = client.get("/api/m/security/ports", headers=auth_header)
        check("S20 security check", response.status_code == 200, f"status={response.status_code}")
        response = client.get("/api/m/security/inexistente", headers=auth_header)
        check("S20b check desconhecido -> 404", response.status_code == 404, f"status={response.status_code}")

        # ── S21: devices
        response = client.get("/api/m/devices", headers=auth_header)
        devices = response.get_json() or []
        check("S21 devices", response.status_code == 200 and any(d["id"] == device_id for d in devices),
              f"n={len(devices)}")
        check("S21b shape do device", all(set(d.keys()) == {"id", "name", "created_at", "last_seen",
              "last_ip", "revoked"} for d in devices), f"first={devices[0] if devices else None}")

        # ── S22: revogação invalida o token
        response = client.post(f"/api/m/devices/{device_id}/revoke",
                               headers={**auth_header, "X-Action-PIN": "123456"})
        check("S22 revoke", response.status_code == 200, f"status={response.status_code}")
        response = client.get("/api/m/status", headers=auth_header)
        check("S22b token revogado -> 401", response.status_code == 401, f"status={response.status_code}")

        # ── S25: rotas admin (desktop) só em loopback
        response = client.get("/api/m/admin/status")
        admin = response.get_json() or {}
        check("S25 admin/status loopback", response.status_code == 200, f"status={response.status_code}")
        check("S25b shape do admin/status", all(k in admin for k in
              ("pin_set", "port", "lan_endpoints", "devices_count", "devices")),
              f"keys={sorted(admin.keys())}")
        check("S25c admin lista dispositivos", isinstance(admin.get("devices"), list), "")
        response = client.get("/api/m/admin/status", environ_base={"REMOTE_ADDR": "8.8.8.8"})
        check("S25d admin fora de loopback -> 403", response.status_code == 403,
              f"status={response.status_code}")
        response = client.get("/api/m/admin/devices", environ_base={"REMOTE_ADDR": "8.8.8.8"})
        check("S25e admin/devices fora de loopback -> 403", response.status_code == 403,
              f"status={response.status_code}")
        response = client.get("/api/m/admin/status", headers=auth_header,
                              environ_base={"REMOTE_ADDR": "10.0.0.50"})
        check("S25g admin com token remoto -> 403", response.status_code == 403,
              f"status={response.status_code}")
        response = client.post("/api/m/admin/devices/dev_inexistente/revoke")
        check("S25f revoke inexistente -> 404", response.status_code == 404, f"status={response.status_code}")

        # ── S26: sessão de pareamento é compartilhada entre processos (persistida)
        generated = auth.generate_pair_code(300)
        stored = db.get_setting(auth.PAIR_KEY, "")
        check("S26 código persistido no banco", bool(stored) and "code_hash" in stored, f"stored={stored[:60]}")
        check("S26b código não fica em texto puro", generated["code"] not in stored, "")
        response = client.post("/api/m/pair", json={"code": generated["code"], "device_name": "crossproc",
                                                    "app_version": "test"})
        check("S26c pareamento usando o código persistido", response.status_code == 200,
              f"status={response.status_code}")
        # limpa o dispositivo criado por S26
        created = (response.get_json() or {}).get("device_id")
        if created:
            auth.delete_device(created)
        response = client.post("/api/m/pair", json={"code": generated["code"], "device_name": "reuse",
                                                    "app_version": "test"})
        check("S26d código não pode ser reusado", response.status_code == 401, f"status={response.status_code}")

        # ── S23: rate limit (isolado)
        code2 = (client.get("/api/m/pair/new").get_json() or {}).get("code", "")
        payload2 = (client.post("/api/m/pair", json={"code": code2, "device_name": "ratelimit",
                                                     "app_version": "test"}).get_json() or {})
        header2 = {"Authorization": f"Bearer {payload2.get('token', '')}"}
        auth.RATE_LIMIT_PER_MIN, auth.RATE_LIMIT_BURST = 5, 5
        auth._rate.clear()
        got_429 = False
        retry_after = None
        for _ in range(20):
            response = client.get("/api/m/status", headers=header2)
            if response.status_code == 429:
                got_429 = True
                retry_after = response.headers.get("Retry-After")
                break
        auth.RATE_LIMIT_PER_MIN, auth.RATE_LIMIT_BURST = original_limits
        auth._rate.clear()
        check("S23 rate limit -> 429", got_429, "")
        check("S23b header Retry-After", bool(retry_after), f"retry={retry_after}")

        # ── S24: pc_apps não aceita comando arbitrário
        check("S24 sleep só inteiro", pc_apps.schedule_sleep(True).get("ok") is False, "")
        check("S24b run_app valida id", pc_apps.run_app("../evil").get("ok") is False, "")

    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    failures = [r for r in results if not r[1]]
    for name, ok, detail in results:
        print(f"[{PASS if ok else FAIL}] {name}" + (f"  ({detail})" if detail and not ok else ""))
    print()
    print(f"{len(results) - len(failures)}/{len(results)} testes passaram")
    if failures:
        print("FALHAS:")
        for name, _, detail in failures:
            print(f"  - {name} {detail}")
        return 1
    return 0

import argparse
import json
import os
import socket
import sys
import threading
import time
from typing import List

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PILIBS = os.path.join(BASE_DIR, "pylibs")
if os.path.isdir(PILIBS) and PILIBS not in sys.path:
    sys.path.insert(0, PILIBS)

from flask import Flask
from werkzeug.serving import make_server

import db
import mobile_bridge

DEFAULT_PORT = int(os.environ.get("MOBILE_PORT", "5055"))


def _lan_ips() -> List[str]:
    ips: List[str] = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.settimeout(0.2)
            sock.connect(("8.8.8.8", 80))
            ips.append(sock.getsockname()[0])
    except Exception:
        pass
    try:
        import subprocess
        out = subprocess.run(["ip", "-4", "-o", "addr", "show"], capture_output=True, text=True, timeout=3).stdout
        for line in out.splitlines():
            parts = line.split()
            if len(parts) < 4:
                continue
            addr = parts[3].split("/")[0]
            if addr.startswith("100.") or addr.startswith("10.") or addr.startswith("192.168."):
                if addr not in ips:
                    ips.append(addr)
    except Exception:
        pass
    return ips


def _start_mdns(port: int):
    """Publica _cyberbot._tcp.local para o app achar o PC mesmo quando o IP muda."""
    try:
        from zeroconf import ServiceInfo, Zeroconf
    except ImportError:
        print("[mdns] zeroconf ausente — descoberta automática desativada "
              "(pip3 install --target ./pylibs zeroconf)", file=sys.stderr)
        return None

    hostname = socket.gethostname()
    service_type = "_cyberbot._tcp.local."
    service_name = f"{hostname} CyberBot.{service_type}"
    zc = Zeroconf()
    state = {"addresses": None}

    def _build(addresses):
        return ServiceInfo(
            service_type,
            service_name,
            addresses=addresses or None,
            port=port,
            properties={"name": hostname, "path": "/api/m", "version": mobile_bridge.VERSION},
            server=f"{hostname}.local.",
        )

    addresses = [socket.inet_aton(ip) for ip in _lan_ips()]
    info = _build(addresses)
    try:
        zc.register_service(info)
        state["addresses"] = sorted(addresses)
        print(f"[mdns] anunciando {service_name} na porta {port} em {_lan_ips()}")
    except Exception as exc:
        print(f"[mdns] falha ao anunciar: {exc}", file=sys.stderr)
        zc.close()
        return None

    def _watch():
        while True:
            time.sleep(20)
            try:
                current = sorted(socket.inet_aton(ip) for ip in _lan_ips())
                if current != state["addresses"]:
                    zc.update_service(_build(current))
                    state["addresses"] = current
                    print(f"[mdns] endereços atualizados: {_lan_ips()}")
                    try:
                        with open(os.path.join(mobile_bridge.BASE_DIR, "state", "gateway.json"), "w") as handle:
                            json.dump({"port": port, "hosts": _lan_ips(),
                                       "started_at": time.strftime("%Y-%m-%dT%H:%M:%S")}, handle, indent=2)
                    except Exception:
                        pass
            except Exception:
                pass

    threading.Thread(target=_watch, daemon=True).start()
    return zc


def build_app() -> Flask:
    app = Flask("cyberbot-mobile-gateway")
    app.config["MAX_CONTENT_LENGTH"] = mobile_bridge.MAX_UPLOAD_MB * 1024 * 1024 + (1 << 20)
    mobile_bridge.register(app)
    return app


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Gateway LAN do CyberBot Mobile (expõe SOMENTE /api/m/*)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--bind", default="0.0.0.0",
                        help="endereço de escuta (padrão 0.0.0.0 = todas as interfaces; "
                             "o middleware de origem só aceita IPs privados)")
    parser.add_argument("--host", action="append", default=[], help="IP extra para escutar (repetível)")
    parser.add_argument("--no-loopback", action="store_true", help="não escutar em 127.0.0.1")
    args = parser.parse_args(argv)

    db.init()
    app = build_app()

    hosts: List[str] = [args.bind]
    if args.bind != "0.0.0.0" and not args.no_loopback:
        hosts.append("127.0.0.1")
    for host in args.host:
        if host not in hosts:
            hosts.append(host)
    detected = _lan_ips()

    servers: List[tuple] = []
    bound_hosts: List[str] = []
    for host in hosts:
        try:
            server = make_server(host, args.port, app, threaded=True)
        except OSError as exc:
            print(f"[gateway] não foi possível escutar em {host}:{args.port} ({exc})", file=sys.stderr)
            continue
        threading.Thread(target=server.serve_forever, daemon=True).start()
        servers.append((host, server))
        bound_hosts.extend(detected or [host])
        print(f"[gateway] ouvindo em http://{host}:{args.port}/api/m/")

    _start_mdns(args.port)

    state_dir = os.path.join(mobile_bridge.BASE_DIR, "state")
    os.makedirs(state_dir, exist_ok=True)
    with open(os.path.join(state_dir, "gateway.json"), "w") as handle:
        json.dump({"port": args.port, "hosts": bound_hosts,
                   "started_at": time.strftime("%Y-%m-%dT%H:%M:%S")}, handle, indent=2)

    if not servers:
        print("[gateway] nenhum listener ativo", file=sys.stderr)
        return 1

    lan = _lan_ips()
    if lan:
        print()
        print("=" * 62)
        print("  Endpoint para o app (tela de pareamento):")
        for ip in lan:
            print(f"      http://{ip}:{args.port}")
        print("  Gere o código com: python3 mobile_bridge.py --pair")
        print("=" * 62)
        print()

    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        for _, server in servers:
            server.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

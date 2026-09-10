import argparse
import asyncio
import json
import os
import stat
import struct
import sys
import time
from typing import Any, Dict, Optional, Tuple

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PILIBS = os.path.join(BASE_DIR, "pylibs")
if os.path.isdir(PILIBS) and PILIBS not in sys.path:
    sys.path.insert(0, PILIBS)

try:
    import httpx
except ImportError:
    print("httpx ausente: pip3 install httpx", file=sys.stderr)
    raise

try:
    import iroh
except ImportError:
    print("Módulo 'iroh' ausente. Instale com:", file=sys.stderr)
    print(f"  pip3 install --target {PILIBS} iroh", file=sys.stderr)
    raise SystemExit(2)

STATE_DIR = os.path.join(BASE_DIR, "state")
KEY_PATH = os.path.join(STATE_DIR, "iroh_secret.key")
INFO_PATH = os.path.join(STATE_DIR, "iroh_endpoint.json")

ALPN_MAIN = b"cyberbot/1"
ALPN_PAIR = b"cyberbot/pair/1"
PROTO_VERSION = 1
MAX_FRAME = 1 << 20
CHUNK = 64 * 1024
DEFAULT_APIS = ("http://127.0.0.1:5055", "http://127.0.0.1:5000")
RELAY_WAIT = float(os.environ.get("IROH_RELAY_WAIT", "25"))
REFRESH_INTERVAL = float(os.environ.get("IROH_REFRESH_INTERVAL", "20"))


def _log(message: str) -> None:
    print(f"[iroh {time.strftime('%H:%M:%S')}] {message}", flush=True)


def load_or_create_key() -> bytes:
    os.makedirs(STATE_DIR, exist_ok=True)
    if os.path.exists(KEY_PATH):
        mode = stat.S_IMODE(os.stat(KEY_PATH).st_mode)
        if mode & 0o077:
            raise SystemExit(f"ERRO: {KEY_PATH} tem modo {oct(mode)}; esperado 0600. Corrija com: chmod 600 {KEY_PATH}")
        with open(KEY_PATH, "rb") as handle:
            key = handle.read()
        if len(key) != 32:
            raise SystemExit(f"ERRO: {KEY_PATH} deve conter 32 bytes (tem {len(key)}).")
        return key
    key = iroh.SecretKey.generate().to_bytes()
    fd = os.open(KEY_PATH, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "wb") as handle:
        handle.write(key)
    _log(f"identidade criada em {KEY_PATH} (modo 0600)")
    return key


def _write_info(endpoint_id: str, ticket: str, addr: Any = None) -> None:
    os.makedirs(STATE_DIR, exist_ok=True)
    payload: Dict[str, Any] = {"endpoint_id": endpoint_id, "ticket": ticket, "updated_at": time.time()}
    if addr is not None:
        try:
            relay = addr.relay_url()
            payload["relay_url"] = relay
            payload["direct_addresses"] = list(addr.direct_addresses())
            payload["online"] = bool(relay)
        except Exception:
            pass
    tmp = INFO_PATH + ".tmp"
    with open(tmp, "w") as handle:
        json.dump(payload, handle, indent=2)
    os.replace(tmp, INFO_PATH)


def _read_info() -> Optional[Dict[str, Any]]:
    if not os.path.exists(INFO_PATH):
        return None
    try:
        with open(INFO_PATH) as handle:
            return json.load(handle)
    except Exception:
        return None


async def wait_for_relay(endpoint, timeout: float = RELAY_WAIT) -> bool:
    """Espera o endpoint ganhar um home relay.

    Sem relay o ticket só carrega endereços da LAN: o app funciona em casa e
    falha no 4G. O relay é o que permite furar o NAT de fora da rede local.
    """
    deadline = time.monotonic() + max(1.0, timeout)
    while time.monotonic() < deadline:
        try:
            if endpoint.addr().relay_url():
                return True
        except Exception:
            pass
        await asyncio.sleep(0.5)
    return False


async def bind_endpoint(wait_relay: bool = True):
    key = load_or_create_key()
    endpoint = await iroh.Endpoint.bind(iroh.EndpointOptions(
        secret_key=key,
        alpns=[ALPN_MAIN, ALPN_PAIR],
    ))
    endpoint_id = str(endpoint.id())
    if wait_relay:
        if await wait_for_relay(endpoint):
            _log("relay conectado — o app alcança o PC de fora da rede (4G)")
        else:
            _log(f"AVISO: sem relay após {RELAY_WAIT:.0f}s — o ticket só terá endereços da LAN")
    addr = endpoint.addr()
    ticket = str(iroh.EndpointTicket.from_addr(addr))
    _write_info(endpoint_id, ticket, addr)
    return endpoint, endpoint_id, ticket


async def refresh_ticket(endpoint, endpoint_id: str, current: str) -> None:
    """Republica o ticket quando relay/endereços mudam (o app lê state/iroh_endpoint.json)."""
    last = current
    while True:
        await asyncio.sleep(max(5.0, REFRESH_INTERVAL))
        try:
            addr = endpoint.addr()
            ticket = str(iroh.EndpointTicket.from_addr(addr))
        except Exception as exc:
            _log(f"refresh do ticket falhou: {type(exc).__name__}: {exc}")
            continue
        if ticket != last:
            _write_info(endpoint_id, ticket, addr)
            last = ticket
            _log("ticket atualizado (endereços/relay mudaram)")


async def read_frame(recv) -> Dict[str, Any]:
    header = await recv.read_exact(4)
    size = struct.unpack(">I", bytes(header))[0]
    if size <= 0 or size > MAX_FRAME:
        raise ValueError("FRAME_TOO_LARGE")
    return json.loads(bytes(await recv.read_exact(size)).decode("utf-8"))


async def write_frame(send, payload: Dict[str, Any]) -> None:
    blob = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    await send.write_all(struct.pack(">I", len(blob)) + blob)


def _resolve_api(preferred: Optional[str]) -> Optional[str]:
    candidates = [preferred] if preferred else list(DEFAULT_APIS)
    for base in candidates:
        if not base:
            continue
        try:
            response = httpx.get(f"{base}/api/m/health", timeout=2)
            if response.status_code == 200:
                return base
        except Exception:
            continue
    return None


async def _forward(api_base: str, frame: Dict[str, Any]) -> Tuple[int, Dict[str, Any]]:
    method = str(frame.get("method", "GET")).upper()
    path = str(frame.get("path", ""))
    if not path.startswith("/api/m/"):
        return 404, {"error": {"code": "NOT_FOUND", "message": "rota inválida"}}
    headers = {k: v for k, v in (frame.get("headers") or {}).items()
               if k.lower() not in ("host", "content-length", "connection")}
    async with httpx.AsyncClient(timeout=httpx.Timeout(120.0, connect=5.0)) as client:
        try:
            response = await client.request(method, f"{api_base}{path}",
                                            params=frame.get("query") or {},
                                            json=frame.get("body"),
                                            headers=headers)
        except Exception as exc:
            return 503, {"error": {"code": "API_DOWN", "message": str(exc)}}
    try:
        body = response.json()
    except Exception:
        body = {"raw": response.text[:4096]}
    return response.status_code, body


async def handle_connection(conn, api_base: Optional[str]) -> None:
    """Atende a conexão QUIC inteira: uma conexão serve VÁRIOS requests.

    O app reaproveita a conexão entre requisições; antes o nó respondia um único
    stream e derrubava a conexão, obrigando um reconectar (caro no 4G) em cada request.
    """
    remote = str(conn.remote_id())
    while True:
        try:
            bi = await conn.accept_bi()
        except Exception:
            break
        asyncio.create_task(serve_stream(conn, remote, bi, api_base))


async def serve_stream(conn, remote: str, bi, api_base: Optional[str]) -> None:
    recv, send = bi.recv(), bi.send()
    try:
        frame = await read_frame(recv)
    except Exception:
        conn.close(1, b"bad-frame")
        return

    # O app (kotlinx.serialization) NÃO envia campos iguais ao default, então "v" costuma
    # vir ausente mesmo num cliente atualizado. Ausente = versão 1, não "atualize o app".
    try:
        version = int(frame.get("v", PROTO_VERSION))
    except (TypeError, ValueError):
        version = PROTO_VERSION
    if version != PROTO_VERSION:
        await write_frame(send, {"v": PROTO_VERSION, "id": frame.get("id"), "status": 426,
                                 "body": {"error": {"code": "PROTOCOL_VERSION", "message": "atualize o app"}}})
        await send.finish()
        return

    base = api_base or _resolve_api(None)
    if base is None:
        await write_frame(send, {"v": PROTO_VERSION, "id": frame.get("id"), "status": 503,
                                 "body": {"error": {"code": "API_DOWN",
                                                    "message": "API local indisponível (rode mobile_gateway.py)"}}})
        await send.finish()
        return

    status, body = await _forward(base, frame)
    await write_frame(send, {"v": PROTO_VERSION, "id": frame.get("id"), "status": status,
                             "headers": {"content-type": "application/json"}, "body": body})
    await send.finish()
    _log(f"{remote[:16]}… {frame.get('method')} {frame.get('path')} -> {status}")


async def serve(api_base: Optional[str]) -> None:
    iroh.iroh_ffi.uniffi_set_event_loop(asyncio.get_running_loop())
    endpoint, endpoint_id, ticket = await bind_endpoint()
    resolved = _resolve_api(api_base)
    asyncio.create_task(refresh_ticket(endpoint, endpoint_id, ticket))
    relay = endpoint.addr().relay_url() or "SEM RELAY (só rede local)"
    print()
    print("=" * 66)
    print(f"  EndpointId : {endpoint_id}")
    print(f"  Ticket     : {ticket}")
    print(f"  Relay      : {relay}")
    print(f"  API local  : {resolved or 'INDISPONÍVEL — rode: python3 mobile_gateway.py'}")
    print("=" * 66)
    print()
    while True:
        incoming = await endpoint.accept_next()
        if incoming is None:
            break
        # Cada conexão em sua própria task: se um handshake travar, o laço
        # continua aceitando. Sem isso o nó fica surdo depois da primeira
        # conexão (o accept() preso bloqueia todos os clientes seguintes).
        asyncio.create_task(accept_incoming(incoming, resolved))


async def accept_incoming(incoming, api_base: Optional[str]) -> None:
    try:
        accepting = await incoming.accept()
        conn = await accepting.connect()
    except Exception as exc:
        _log(f"falha ao aceitar conexão: {type(exc).__name__}: {exc!r}")
        return
    try:
        await handle_connection(conn, api_base)
    except Exception as exc:
        _log(f"erro ao atender conexão: {type(exc).__name__}: {exc!r}")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="CyberBot Iroh Node (lado PC)")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--init", action="store_true", help="cria identidade e mostra EndpointId/ticket")
    group.add_argument("--status", action="store_true", help="mostra identidade e estado da API")
    group.add_argument("--serve", action="store_true", help="roda o nó (padrão)")
    parser.add_argument("--api", default=None, help="URL da API local (padrão: auto 5055/5000)")
    args = parser.parse_args(argv)

    if args.status:
        info = _read_info()
        if not info:
            print("Nenhuma identidade criada. Rode: python3 iroh_node.py --init")
            return 1
        age = time.time() - float(info.get("updated_at") or 0)
        relay = info.get("relay_url") or "SEM RELAY (o app só alcança o PC na mesma rede)"
        print(f"EndpointId : {info.get('endpoint_id')}")
        print(f"Ticket     : {info.get('ticket')}")
        print(f"Relay      : {relay}")
        print(f"Atualizado : há {age:.0f}s")
        print(f"API local  : {_resolve_api(args.api) or 'INDISPONÍVEL'}")
        return 0

    if args.init:
        async def run_init():
            iroh.iroh_ffi.uniffi_set_event_loop(asyncio.get_running_loop())
            endpoint, endpoint_id, ticket = await bind_endpoint()
            print(f"EndpointId : {endpoint_id}")
            print(f"Ticket     : {ticket}")
            print(f"Relay      : {endpoint.addr().relay_url() or 'SEM RELAY'}")
            await endpoint.close()
        asyncio.run(run_init())
        return 0

    try:
        asyncio.run(serve(args.api))
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

import asyncio, json, os, struct, sys
BASE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(BASE, "pylibs"))
import iroh

ALPN = b"cyberbot/1"
MAX_FRAME = 1 << 20


async def read_frame(recv):
    head = await recv.read_exact(4)
    n = struct.unpack(">I", bytes(head))[0]
    return json.loads(bytes(await recv.read_exact(n)).decode())


async def write_frame(send, obj):
    blob = json.dumps(obj, separators=(",", ":")).encode()
    await send.write_all(struct.pack(">I", len(blob)) + blob)


async def call(ep, addr, req):
    conn = await ep.connect(addr, ALPN)
    bi = await conn.open_bi()
    await write_frame(bi.send(), req)
    await bi.send().finish()
    resp = await read_frame(bi.recv())
    conn.close(0, b"ok")
    return resp


async def main():
    iroh.iroh_ffi.uniffi_set_event_loop(asyncio.get_running_loop())
    with open(os.path.join(BASE, "state", "iroh_endpoint.json")) as f:
        info = json.load(f)
    ticket = iroh.EndpointTicket.from_string(info["ticket"])
    addr = ticket.endpoint_addr()
    ep = await iroh.Endpoint.bind(iroh.EndpointOptions(alpns=[ALPN]))

    r1 = await call(ep, addr, {"v": 1, "id": "t1", "method": "GET", "path": "/api/m/pair/new"})
    print("1) pair/new ->", r1["status"], str(r1["body"])[:80])
    code = r1["body"]["code"]

    r2 = await call(ep, addr, {"v": 1, "id": "t2", "method": "POST", "path": "/api/m/pair",
                               "body": {"code": code, "device_name": "iroh-e2e", "app_version": "test"}})
    print("2) pair     ->", r2["status"])
    token = r2["body"]["token"]
    device_id = r2["body"]["device_id"]
    print("   device   ->", device_id, "| hint:", r2["body"]["transport_hint"])

    r3 = await call(ep, addr, {"v": 1, "id": "t3", "method": "GET", "path": "/api/m/status",
                               "headers": {"Authorization": "Bearer " + token}})
    print("3) status   ->", r3["status"], "| host:", r3["body"]["pc"]["hostname"],
          "| lan:", r3["body"]["pc"]["lan_ip"], "| bot:", r3["body"]["bot"]["running"])

    r4 = await call(ep, addr, {"v": 1, "id": "t4", "method": "GET", "path": "/api/m/sysmon",
                               "headers": {"Authorization": "Bearer " + token}})
    print("4) sysmon   ->", r4["status"], r4["body"])

    r5 = await call(ep, addr, {"v": 1, "id": "t5", "method": "GET", "path": "/api/m/pc/apps",
                               "headers": {"Authorization": "Bearer " + token}})
    print("5) pc/apps  ->", r5["status"], [a["id"] for a in r5["body"]])

    r6 = await call(ep, addr, {"v": 1, "id": "t6", "method": "GET", "path": "/api/m/status"})
    print("6) sem token->", r6["status"], r6["body"]["error"]["code"])

    r7 = await call(ep, addr, {"v": 1, "id": "t7", "method": "GET", "path": "/api/financas",
                               "headers": {"Authorization": "Bearer " + token}})
    print("7) rota fora->", r7["status"], r7["body"]["error"]["code"])

    await ep.close()
    print("DEVICE_TO_REVOKE=" + device_id)


asyncio.run(main())

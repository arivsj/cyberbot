# p2pAndroi.md — Parte 1: PC / Desktop

> **STATUS: IMPLEMENTADO E TESTADO** (este documento segue sendo a especificação; a seção abaixo registra o que foi de fato construído).
>
> | Arquivo criado | Papel |
> |---|---|
> | `pc_apps.py` | lista branca de comandos do PC (`id`, `label`, `destructive`) |
> | `mobile_auth.py` | dispositivos, token (SHA-256), PIN (scrypt), lockout, rate limit, idempotência |
> | `mobile_bridge.py` | Blueprint Flask `/api/m/*` + CLI (`--pair`, `--devices`, `--revoke`, `--set-pin`, `--selftest`, `--serve`) |
> | `mobile_gateway.py` | servidor LAN isolado que expõe **somente** `/api/m/*` (porta 5055) |
> | `iroh_node.py` | endpoint Iroh (QUIC/TLS 1.3) que encaminha frames para a API local |
> | `mobile_selftest.py` | 43 verificações de aceite (S1–S24) |
> | `systemd/*.service` | unidades de usuário para gateway e nó Iroh |
> | `server.py` | **+2 linhas** (registro do Blueprint; nenhuma rota existente alterada) |
>
> **Como rodar:**
> ```bash
> python3 mobile_gateway.py --port 5055      # API do app na LAN
> python3 iroh_node.py --serve               # transporte P2P
> python3 mobile_bridge.py --pair            # código de 6 dígitos + endpoint para o app
> python3 mobile_bridge.py --set-pin         # define o PIN das ações destrutivas
> python3 mobile_bridge.py --selftest        # 43/43
> ```
> No app (tela de pareamento): endpoint `http://<IP-LAN-do-PC>:5055` + código de 6 dígitos.
>
> **Validado:** `--selftest` 43/43; pareamento + `/status` + `/sysmon` + `/pc/apps` **através do Iroh** (QUIC real, cliente Python); APK Android compilado com `libiroh_ffi.so`.
>
> **Diferença em relação à especificação original:** o app foi construído inicialmente com `DirectTransport` HTTP; o `IrohTransport` foi **adicionado** ao `TransportSelector` (Iroh tem prioridade quando há ticket, com fallback HTTP na LAN). O pareamento continua HTTP e devolve `iroh: {endpoint_id, ticket, alpn}` — o pareamento inicial exige que o celular esteja na mesma rede do PC (único canal disponível antes de existir identidade).

> **Instruções de implementação para o agente que vai codar o lado do computador.**
> Leia inteiro antes de escrever código. Complemento: **Parte 2** (`appAndroid.md`) descreve o app Kotlin.
> Repositório: `Dog Assistent` (CyberBot). Regras do `AGENTS.md` são obrigatórias (§14).
> Transporte: **Iroh 1.1** (QUIC + NAT traversal). Sem Tailscale, sem Cloudflare, sem porta aberta.

---

## 0. Objetivo

Quando o PC estiver ligado e o celular também, o app Android deve poder **ler e comandar o CyberBot** — status, bot, chat com IA, PC Apps, drive, finanças, security, cleanup, eventos — por uma conexão **P2P criptografada**, sem VPN, sem túnel de terceiros e **sem expor nenhuma porta na internet**.

O PC é o **servidor** (endpoint Iroh + API). O app é o **cliente**. A API existente (`/api/*` em `127.0.0.1:5000`) **não muda**.

---

## 1. Fatos verificados (não invente nada além disso)

### Sistema atual

| Arquivo | Relevância |
|---|---|
| `server.py` (762 linhas) | Flask 3.1, ~60 rotas `/api/*`, `app.run(host="127.0.0.1", port=5000, threaded=True)`. **Zero autenticação**, `CORS(app)` liberado. O desktop Electron depende disso sem token. |
| `bot.py` (2046 linhas) | `python-telegram-bot`, `run_polling()`. Whitelist por `user_id`. Contém `PC_APPS` + `pc_apps_callback` (linhas ~1352–1495): `xdg-open`, `code`, `caffeine`, `shutdown -h +N`, `xdg-screensaver lock`. **Não importar este módulo** (inicia o polling no import). |
| `db.py` (526 linhas) | SQLite WAL. Usar: `get_setting`, `set_setting`, `inserir_arquivo`, `listar_arquivos`, `get_arquivo`, `deletar_arquivo`, `listar_pastas`, `listar`, `resumo_mes`, `gastos_por_categoria`, `gastos_por_conta`, `gastos_por_dia`, `meses_disponiveis`, `save_sysmon`, `list_sysmon_history`, `save_report`, `list_reports`, `get_report`, `delete_report`. |
| `log.py` | `log_access(entry)` → `logs/access_YYYY-MM-DD.jsonl`. |
| `sysmon.py` | `collect()` → CPU/RAM/GPU/temperatura. |
| `security.py` | `run_all()`, `check_connections/ssh/integrity/persistence/processes/ports/firewall/fail2ban/sudo/updates/services/users`, `init_baseline()`. |
| `cleanup.py` | `TASKS`, `run_task`, `run_safe`, `run_all`, `set_password`, `SUDO_PASSWORD`. |
| `ollama_utils.py` | `get_chat_options(model)`, `get_context_info(model)`, `clear_cache()`. |
| `desktop/main.js` | Electron: `startServer()` faz `fuser -k 5000/tcp` e spawna `server.py`; `window-all-closed` mata o processo. **Comportamento existente — não alterar.** |

### Ambiente do usuário

- Pop!_OS, kernel 7.1, **Python 3.10.12**, Node 22, Ollama em `127.0.0.1:11434`.
- IP local `10.93.220.250/24`; **CGNAT confirmado** (IP público `189.85.89.135` compartilhado; saltos `192.168.0.1 → 189.85.89.6 → 172.16.46.25 → 172.16.47.61`). Port forwarding é impossível → **Iroh é a escolha certa**.
- `ufw` ativo. **Nenhuma porta nova precisa ser aberta** (Iroh só faz conexões de saída UDP).

### Iroh 1.1 — fatos verificados nas fontes

- `pip install iroh` → **1.1.0** com wheel `manylinux_2_28_x86_64` (também aarch64 Linux, macOS arm64, Windows amd64). **Não precisa de Rust no PC.**
- Identidade = `SecretKey` Ed25519 de 32 bytes → `EndpointId` (chave pública, base32). Persistir a secret key é **obrigatório** para o app não ter que reparear.
- `Endpoint.bind(EndpointOptions(secret_key=..., alpns=[...], relay_mode=..., bind_addr=..., protocols=..., preset=...))`.
- `ep.id()`, `ep.addr()`, `ep.remote_addr(id)`, `ep.accept_next()`, `ep.close()`, `ep.secret_key()`, `ep.stats()`.
- `Incoming`: `accept()`, `refuse()`, `ignore()`, `retry()`, `remote_addr()` (retorna `Direct(ip:port)` ou `Relay`).
- `Connection`: `remote_id()`, `open_bi()`, `accept_bi()`, `close()`.
- `BiStream`: `send()` / `recv()`; em cada um: `write_all(bytes)`, `read_to_end(n)`, `finish()`.
- `EndpointTicket.from_addr(addr)` → string base32 compartilhável; `EndpointTicket.from_string(s)` → `endpoint_addr()`.
- `EndpointAddr(id, relay_url, addresses)`; `RelayMap.from_urls([...])`, `RelayMode.disabled() / default_mode() / staging()`.
- Presets: `preset_n0()` (relays + discovery de produção, **padrão**), `preset_minimal()`, `preset_n0_disable_relay()`.
- Python async: chamar `iroh.iroh_ffi.uniffi_set_event_loop(asyncio.get_running_loop())` antes de usar.
- Relays públicos do n0 são gratuitos e *best-effort* (o código já emite aviso de rate-limit).

---

## 2. Arquitetura (o que você vai construir)

```
┌──────────────┐   QUIC/TLS 1.3 (E2E)   ┌──────────────────────── PC ─────────────────────────┐
│ App Android  │ ──────────────────────▶ │  iroh_node.py   (processo próprio, asyncio)        │
│  EndpointId  │   ALPN cyberbot/1       │    ├─ identidade persistente (secret key 0600)     │
│  (chave no   │   streams bi-direcionais│    ├─ autoriza por EndpointId do celular           │
│   Keystore)  │                         │    └─ encaminha para 127.0.0.1:5000/api/m/*         │
└──────────────┘                         │                │                                    │
                                         │                ▼                                    │
                                         │  server.py (Flask, INTOCADO)                        │
                                         │    + mobile_bridge.py  (Blueprint /api/m/*)         │
                                         │         ├─ token Bearer + PIN + lockout             │
                                         │         ├─ rate limit + idempotência                │
                                         │         └─ log em logs/access_*.jsonl               │
                                         │    + pc_apps.py (lista branca de comandos)          │
                                         └─────────────────────────────────────────────────────┘
```

**Decisões de arquitetura (siga exatamente):**

1. **Nada é exposto na rede.** `mobile_bridge.py` responde **somente** em `127.0.0.1:5000`. O único caminho externo é o endpoint Iroh, que é *outbound UDP*. `ufw` permanece fechado.
2. **`iroh_node.py` é um processo separado** (asyncio) que fala HTTP loopback com o Flask. Assim você não mistura asyncio com o Flask síncrono nem toca no `server.py`.
3. **Uma única implementação de autenticação**: token/PIN/rate-limit vivem em `mobile_bridge.py`. O `iroh_node.py` só adiciona a identidade de transporte (EndpointId).
4. **Autenticação em três camadas** (defesa em profundidade): EndpointId pareado → token Bearer → PIN nas ações destrutivas.
5. **O pareamento acontece dentro do Iroh** (ALPN `cyberbot/pair/1`), não por HTTP. Nenhum código de pareamento trafega pela rede pública.

---

## 3. Arquivos a criar

```
pc_apps.py          # NOVO — lista branca de comandos do PC (compartilhada)
mobile_auth.py      # NOVO — dispositivos, token, PIN, lockout, rate limit
mobile_bridge.py    # NOVO — Blueprint Flask /api/m/* + CLI (--pair, --devices, --revoke, --set-pin, --selftest)
iroh_node.py        # NOVO — endpoint Iroh, framing, autorização por EndpointId, proxy para o Flask
mobile_state.py     # NOVO — store JSON no SQLite (db.settings), chaves em ~/.local/state/cyberbot/
systemd/cyberbot-iroh.service   # NOVO — unidade user (auto-start)
systemd/cyberbot-api.service    # NOVO — unidade user (API sempre disponível)
server.py           # ALTERAÇÃO MÍNIMA: 2 linhas no fim (registrar o Blueprint)
```

**Patch exato em `server.py`** — inserir imediatamente antes de `if __name__ == "__main__":` (linha ~740):

```python
import mobile_bridge
mobile_bridge.register(app)
```

Nenhuma outra linha do `server.py` pode mudar. `git diff server.py` deve mostrar **exatamente 2 linhas adicionadas**.

---

## 4. Protocolo Iroh (o contrato que o app espera)

### 4.1 Constantes

```python
ALPN_MAIN = b"cyberbot/1"        # requisições normais
ALPN_PAIR = b"cyberbot/pair/1"   # somente pareamento
PROTO_VERSION = 1
MAX_FRAME = 1 << 20              # 1 MiB por frame de controle
CHUNK = 64 * 1024                # 64 KiB para arquivos
```

### 4.2 Framing (JSON com prefixo de tamanho)

Todo frame: **4 bytes big-endian** com o tamanho + payload **UTF-8 JSON**.

```
[ 0x00 0x00 0x01 0x2C ][ {"v":1,"id":"r_1","method":"GET", ...} ]
```

**Requisição** (app → PC), no primeiro bi-stream:

```json
{
  "v": 1,
  "id": "r_7f3a91",
  "method": "GET",
  "path": "/api/m/status",
  "query": { "mes": "2026-08" },
  "headers": { "x-action-pin": "482913", "idempotency-key": "uuid" },
  "body": null
}
```

**Resposta** (PC → app), no mesmo bi-stream:

```json
{ "v": 1, "id": "r_7f3a91", "status": 200, "headers": { "content-type": "application/json" }, "body": { "pc": { }, "bot": { } } }
```

**Erro** (sempre dentro de `body`):

```json
{ "v": 1, "id": "r_7f3a91", "status": 403, "body": { "error": { "code": "PIN_REQUIRED", "message": "PIN obrigatório para esta ação" } } }
```

Regras obrigatórias:
- Um bi-stream = uma requisição/resposta (multiplexação nativa do QUIC, sem head-of-line blocking).
- `id` é ecoado; se o app mandar frame malformado, responda `400 BAD_FRAME` e **feche o stream**.
- `v != 1` → `426 PROTOCOL_VERSION` (o app mostra "atualize o app").
- Frame maior que `MAX_FRAME` → `413 FRAME_TOO_LARGE` + fechar.
- Nunca aceitar caminho fora de `/api/m/` (whitelist de prefixo).

### 4.3 Stream de eventos

Um bi-stream dedicado; o app envia como primeiro frame:

```json
{ "v": 1, "type": "subscribe", "topics": ["status", "alerts", "jobs"] }
```

O PC responde `{"v":1,"type":"subscribed"}` e depois empurra frames:

```json
{ "v": 1, "type": "event", "topic": "status", "ts": "2026-09-09T12:00:00Z", "data": { "bot": true, "cpu": 23.5, "ram": 61.0 } }
```

Heartbeat a cada 15 s: `{"v":1,"type":"ping"}`. Máximo 3 streams de eventos por dispositivo.

### 4.4 Transferência de arquivo (upload/download)

Stream dedicado. Primeiro frame = metadados JSON, depois **bytes crus** em blocos de 64 KiB, terminando com o SHA-256 para verificação.

```json
{ "v":1, "type":"upload", "name":"foto.jpg", "size":812345, "sha256":"...", "folder_id":3, "mime":"image/jpeg" }
```

O PC responde `{"v":1,"type":"ack","offset":0}` (permite retomada: o app pode pedir `offset`), recebe os bytes e ao final responde `{"v":1,"type":"done","id":42}`.

### 4.5 Pareamento (ALPN `cyberbot/pair/1`)

App → PC, primeiro frame:

```json
{ "v":1, "type":"pair", "code":"482913", "device_name":"Pixel 8", "device_endpoint_id":"<base32>", "app_version":"1.0.0" }
```

PC → App:

```json
{ "v":1, "type":"paired", "device_id":"dev_7f3a", "token":"<43 chars urlsafe>", "pc_name":"cyberbot-pc", "pin_set":true, "protocol":1 }
```

Erros: `{"v":1,"type":"error","code":"PAIR_CODE_INVALID"|"PAIR_CODE_EXPIRED"|"PAIR_TOO_MANY_ATTEMPTS"|"DEVICE_LIMIT"}`.

**O `device_endpoint_id` é obrigatório** — é ele que autoriza o dispositivo depois.

---

## 5. Contrato REST `/api/m/*` (o que o `iroh_node` encaminha)

Base: `http://127.0.0.1:5000/api/m` — **só loopback**.
Auth: `Authorization: Bearer <token>` em tudo, exceto nada (o pareamento é via Iroh, não HTTP).
Ações destrutivas: header `X-Action-PIN`.

| Método | Rota | Descrição | PIN |
|---|---|---|---|
| GET | `/api/m/status` | hostname, uptime, IPs, bot, ollama, modelo, contexto, transporte, versão | — |
| GET | `/api/m/sysmon` | `sysmon.collect()` | — |
| GET | `/api/m/sysmon/history?limit=30` | `db.list_sysmon_history` | — |
| POST | `/api/m/chat` | `{conversation_id, message, model?}` → `{reply, model, elapsed_ms}` | — |
| POST | `/api/m/chat/stream` | SSE `delta`/`done` | — |
| POST | `/api/m/bot/start` | inicia `bot.py` | — |
| POST | `/api/m/bot/stop` | para o bot | **sim** |
| POST | `/api/m/bot/send` | `{chat_id, text}` via Telegram API | — |
| GET | `/api/m/pc/apps` | lista branca | — |
| POST | `/api/m/pc/run` | `{id}` | destrutivos |
| POST | `/api/m/pc/sleep` | `{minutes}` 1–1440 | **sim** |
| GET | `/api/m/drive/folders?parent=` | pastas | — |
| GET | `/api/m/drive/files?folder=` | arquivos | — |
| POST | `/api/m/drive/upload` | multipart (usado pelo stream Iroh) | — |
| GET | `/api/m/drive/download/<id>` | binário | — |
| DELETE | `/api/m/drive/files/<id>` | apaga | **sim** |
| GET | `/api/m/financas?mes=YYYY-MM` | `db.listar` | — |
| GET | `/api/m/financas/resumo?mes=` | resumo+categorias+contas+diário | — |
| POST | `/api/m/financas` | `{categoria, conta, valor, descricao}` | — |
| GET | `/api/m/security/run` | `security.run_all()` | — |
| GET | `/api/m/security/<check>` | 12 checks | — |
| GET | `/api/m/security/report` | `run_all` + IA (timeout 90 s) | — |
| GET | `/api/m/reports` / `/reports/<id>` | relatórios salvos | — |
| GET | `/api/m/cleanup/tasks` | tarefas | — |
| POST | `/api/m/cleanup/run` | `{task?, mode?}` | **sim** |
| GET | `/api/m/events` | SSE (para o `iroh_node` consumir e repassar) | — |
| GET | `/api/m/devices` | dispositivos | — |
| POST | `/api/m/devices/<id>/revoke` | revoga | **sim** |
| POST | `/api/m/pin` | `{current_pin?, new_pin}` | — |
| GET | `/api/m/pair/new` | gera código de pareamento (usado pela CLI/desktop) | — |

**Códigos de erro** (sempre `{"error":{"code":...,"message":...}}`):
`BAD_REQUEST`(400), `INVALID_TOKEN`/`TOKEN_REVOKED`(401), `PIN_REQUIRED`/`PIN_INVALID`/`DEVICE_NOT_PAIRED`(403), `NOT_FOUND`(404), `CONFLICT`(409), `LOCKED`(423), `RATE_LIMITED`(429, com `Retry-After`), `INTERNAL`(500), `UPSTREAM_TIMEOUT`(504).

**Proibido:** expor `/api/cleanup/sudo-password`, `/api/cleanup/sudo-status` ou qualquer rota que aceite senha sudo. Tarefa que exija sudo → `403 SUDO_REQUIRED` ("faça pelo desktop").

---

## 6. `mobile_auth.py` — identidades, tokens e PIN

### 6.1 Store (chave `mobile_state` em `db.settings`)

```json
{
  "version": 1,
  "pin": { "algo": "scrypt", "n": 16384, "r": 8, "p": 1, "salt": "<b64>", "hash": "<b64>", "set_at": "..." },
  "devices": [
    {
      "id": "dev_7f3a91c2",
      "name": "Pixel 8",
      "endpoint_id": "<base32 do EndpointId do app>",
      "token_hash": "<sha256 hex>",
      "created_at": "2026-09-09T12:00:00Z",
      "last_seen": "2026-09-09T12:10:00Z",
      "last_path": "direct",
      "revoked": false,
      "app_version": "1.0.0"
    }
  ]
}
```

### 6.2 Regras

- Token: `secrets.token_urlsafe(32)`. Guardar **apenas** `sha256(token).hexdigest()`. Comparar com `secrets.compare_digest`.
- PIN: 6 dígitos, `hashlib.scrypt(n=2**14, r=8, p=1, salt=16 bytes)`. Comparação com `compare_digest`.
- Lockout de PIN: 5 falhas → `423 LOCKED` por 15 min (contador em memória + timestamp no store).
- Lockout de pareamento: 5 códigos errados → invalida o código e bloqueia pareamento por 10 min.
- **Vínculo EndpointId ↔ token**: em toda conexão Iroh, `conn.remote_id()` tem que casar com o `endpoint_id` do dispositivo cujo `token_hash` bate. Se o EndpointId não estiver na lista → `incoming.refuse()` **antes de ler qualquer byte**.
- Revogação: `revoked: true` invalida token e EndpointId imediatamente.
- Máx. 10 dispositivos; `DEVICE_LIMIT` acima disso.
- Rate limit por dispositivo (token-bucket em memória): 120 req/min, pico 10 req/s; `/api/m/chat` máx. 2 concorrentes.
- Idempotência: `Idempotency-Key` guardada 10 min → devolve a mesma resposta (crítico para retry em rede móvel).

### 6.3 Chaves em disco

```
~/.local/state/cyberbot/iroh_secret.key     # 32 bytes, modo 0600, dono = usuário
~/.local/state/cyberbot/iroh_endpoint_id    # base32 (para o QR e conferência)
```

- Criar com `os.open(path, O_CREAT|O_EXCL|O_WRONLY, 0o600)`.
- Nunca logar, nunca imprimir em erro, nunca colocar em argumento de linha de comando.
- Se o arquivo existir com modo errado → abortar com mensagem clara (não "consertar" silenciosamente).
- Alternativa documentada: `LoadCredential=` do systemd (mais seguro, opcional).

---

## 7. `iroh_node.py` — esqueleto obrigatório

```python
import asyncio, json, os, struct, httpx, iroh
from mobile_auth import authorize_endpoint, verify_token, log_mobile

ALPN_MAIN, ALPN_PAIR = b"cyberbot/1", b"cyberbot/pair/1"
API = "http://127.0.0.1:5000"
MAX_FRAME, CHUNK = 1 << 20, 64 * 1024

async def read_frame(recv) -> dict:
    head = await recv.read_exact(4)
    n = struct.unpack(">I", bytes(head))[0]
    if n > MAX_FRAME:
        raise ValueError("FRAME_TOO_LARGE")
    return json.loads(bytes(await recv.read_exact(n)))

async def write_frame(send, obj: dict):
    payload = json.dumps(obj, separators=(",", ":")).encode()
    await send.write_all(struct.pack(">I", len(payload)) + payload)

async def handle_connection(conn):
    endpoint_id = str(conn.remote_id())
    device = authorize_endpoint(endpoint_id)          # None se não pareado
    if device is None:
        await conn.close(1, b"unpaired")              # recusa antes de ler
        return
    bi = await conn.accept_bi()
    recv, send = bi.recv(), bi.send()
    try:
        req = await read_frame(recv)
    except Exception:
        await write_frame(send, {"v": 1, "id": None, "status": 400,
                                 "body": {"error": {"code": "BAD_FRAME", "message": "frame inválido"}}})
        await send.finish(); return

    if req.get("v") != 1:
        await write_frame(send, {"v": 1, "id": req.get("id"), "status": 426,
                                 "body": {"error": {"code": "PROTOCOL_VERSION", "message": "atualize o app"}}})
        await send.finish(); return

    if not str(req.get("path", "")).startswith("/api/m/"):
        await write_frame(send, {"v": 1, "id": req.get("id"), "status": 404,
                                 "body": {"error": {"code": "NOT_FOUND", "message": "rota inválida"}}})
        await send.finish(); return

    headers = {k: v for k, v in (req.get("headers") or {}).items() if k.lower() not in ("host", "content-length")}
    async with httpx.AsyncClient(timeout=httpx.Timeout(120.0, connect=5.0)) as client:
        r = await client.request(
            req["method"], f"{API}{req['path']}",
            params=req.get("query") or {},
            json=req.get("body"),
            headers=headers,
        )
    try:
        body = r.json()
    except Exception:
        body = {"raw": r.text[:4096]}
    await write_frame(send, {"v": 1, "id": req.get("id"), "status": r.status_code,
                             "headers": {"content-type": r.headers.get("content-type", "application/json")},
                             "body": body})
    await send.finish()
    log_mobile(device, req, r.status_code)

async def main():
    iroh.iroh_ffi.uniffi_set_event_loop(asyncio.get_running_loop())
    secret = load_or_create_secret_key()              # 32 bytes, 0600
    ep = await iroh.Endpoint.bind(iroh.EndpointOptions(
        secret_key=secret,
        alpns=[ALPN_MAIN, ALPN_PAIR],
    ))
    print(f"[iroh] EndpointId: {ep.id()}")
    print(f"[iroh] ticket:    {iroh.EndpointTicket.from_addr(ep.addr())}")
    while True:
        incoming = await ep.accept_next()
        if incoming is None:
            break
        accepting = await incoming.accept()
        conn = await accepting.connect()
        asyncio.create_task(handle_connection(conn))
```

**Requisitos do nó:**
- `preset_n0()` (padrão) para relays + discovery. Documentar `RelayMode.disabled()` e `preset_n0_disable_relay()` como opções (rede local apenas).
- Expor na CLI/status o **caminho atual** (`Direct` vs `Relay`) — o app mostra isso na UI.
- Reconexão do Flask: se `127.0.0.1:5000` estiver fora, responder `503 API_DOWN` e tentar subir o `server.py` (supervisão leve, ver §10).
- Um `asyncio.Task` por conexão, com `try/except` global — nenhuma exceção pode derrubar o nó.
- Máx. 8 conexões simultâneas por dispositivo.
- Logar `endpoint_id`, rota, status e `path` (direct/relay). **Nunca** token, PIN ou corpo de chat.

---

## 8. Segurança — o que o lado do PC DEVE fazer

### 8.1 Modelo de ameaça

| Ameaça | Mitigação obrigatória |
|---|---|
| Celular perdido/roubado | Token revogável por dispositivo + PIN + lockout; revogação invalida na hora |
| Token extraído do celular | Token só existe como hash no PC; **vinculado ao EndpointId** — outro dispositivo não usa |
| Alguém descobriu o EndpointId do PC | Sem token válido do dispositivo pareado, a conexão é recusada antes de qualquer leitura |
| Relay malicioso / espião de rede | QUIC + TLS 1.3 autenticado pela chave pública do par: relay **não descriptografa nem faz MITM** |
| Replay de requisição | `Idempotency-Key` + stream único por requisição |
| Escalonamento de privilégio pelo app | Lista branca de comandos, `subprocess` sem `shell=True`, sem sudo, sem shell arbitrário |
| Vazamento em logs | Nunca logar token/PIN/senha/conteúdo de chat; redigir `Authorization` |
| Upload malicioso | Sanitizar nome (`basename`, sem `..`, 255 chars, sufixo uuid), limite de tamanho, verificar SHA-256 |
| DoS pelo app | Rate limit por dispositivo, limites de frame/stream, timeouts |
| Comprometimento do PC | `systemd` hardening (§9); chaves 0600; sem porta aberta |

### 8.2 Requisitos testáveis (numere os testes assim no `--selftest`)

| # | Requisito | Teste |
|---|---|---|
| S1 | EndpointId não pareado é recusado antes de ler bytes | conectar com chave nova → `conn.close(1)` |
| S2 | Token ausente/errado → 401 | frame sem `Authorization` |
| S3 | Token de um dispositivo + EndpointId de outro → 403 | teste cruzado |
| S4 | Ações destrutivas sem PIN → 403 `PIN_REQUIRED` | `POST /api/m/bot/stop` |
| S5 | 5 PINs errados → 423 por 15 min | loop |
| S6 | Código de pareamento expira em 120 s e é uso único | reusar → `PAIR_CODE_INVALID` |
| S7 | Senha sudo nunca trafega | grep nas rotas + teste negativo |
| S8 | `sleep` aceita só inteiro 1–1440, sem shell | `{"minutes":"90; rm -rf /"}` → 400 |
| S9 | Upload sanitiza `../../etc/passwd` | nome vira `passwd_<uuid>` |
| S10 | Nenhum segredo em log/URL | grep em `logs/` |
| S11 | Desktop continua funcionando sem token | abrir o Electron |
| S12 | Revogar dispositivo invalida imediatamente | revogar → 401 |
| S13 | Frame > 1 MiB → 413 e fecha | teste de tamanho |
| S14 | `v=2` → 426 | teste de versão |
| S15 | Rota fora de `/api/m/` → 404 | `/api/m/../bot/stop` e `/api/financas` |
| S16 | Rate limit devolve 429 + `Retry-After` | 200 req em rajada |
| S17 | `iroh_secret.key` com modo ≠ 0600 aborta o boot | `chmod 644` + start |
| S18 | `git diff server.py` = 2 linhas | comando de verificação |

### 8.3 Endurecimento do systemd (`cyberbot-iroh.service`)

```ini
[Unit]
Description=CyberBot Iroh P2P node
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=%h/dev/Dog Assistent
ExecStart=%h/.local/state/cyberbot/venv/bin/python iroh_node.py
Restart=always
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths=%h/.local/state/cyberbot %h/dev/Dog Assistent/logs %h/dev/Dog Assistent/drive_files
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictSUIDSGID=true
LockPersonality=true
MemoryDenyWriteExecute=false
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
SystemCallFilter=@system-service
[Install]
WantedBy=default.target
```

> `ProtectHome=read-only` + `ReadWritePaths` garante que o nó só escreve onde precisa. Ajuste os caminhos ao layout real e **valide com `systemd-analyze verify`**.

---

## 9. Instalação e operação

```bash
# 1. venv isolado (não mexer no Python do sistema)
python3 -m venv ~/.local/state/cyberbot/venv
~/.local/state/cyberbot/venv/bin/pip install iroh httpx python-dotenv

# 2. identidade (gera chave 0600 e imprime EndpointId + QR)
~/.local/state/cyberbot/venv/bin/python iroh_node.py --init

# 3. pareamento (código de 6 dígitos, TTL 120 s)
~/.local/state/cyberbot/venv/bin/python mobile_bridge.py --pair

# 4. serviços
mkdir -p ~/.config/systemd/user
cp systemd/*.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now cyberbot-api cyberbot-iroh
loginctl enable-linger $USER      # sobrevive a logout
```

**Interação com o desktop (importante, não "conserte" isso):** o `desktop/main.js` faz `fuser -k 5000/tcp` e sobe o próprio `server.py`. Se o desktop estiver aberto, ele é o dono da porta 5000 — o `iroh_node.py` continua funcionando (ele só fala com `127.0.0.1:5000`, quem quer que esteja lá). Quando o desktop fecha, o `server.py` dele morre; então o `iroh_node.py` deve **supervisionar**: se `GET /api/status` falhar por N segundos, iniciar `python3 server.py` como subprocesso (registrando o PID) e continuar. Assim o controle remoto funciona com o PC ligado, mesmo sem o desktop aberto — que é o requisito do usuário.

---

## 10. Desktop Electron — aba "Mobile" (aditivo)

Adicione (sem alterar nada existente):
- Nova aba/modal **Mobile** no `renderer`, consumindo `/api/m/pair/new`, `/api/m/devices`, `/api/m/devices/<id>/revoke`.
- Exibe: QR de pareamento (payload §4.5 + `endpoint_id` do PC), contagem regressiva de 120 s, lista de dispositivos com `last_seen` e caminho (`direct`/`relay`), botão "Revogar", e botão "Definir PIN".
- Tema: reusar as variáveis CSS do `style.css` (`--neon`, `--bg`, `--surface`).
- Não alterar o menu existente, o `clearCache` nem o `startServer`.

---

## 11. CLI obrigatória

```bash
python3 iroh_node.py --init                 # cria identidade, imprime EndpointId + ticket
python3 iroh_node.py --status               # EndpointId, caminho atual, relays, API up/down
python3 mobile_bridge.py --pair [--ttl 300] # código + QR ASCII + JSON para o app
python3 mobile_bridge.py --devices
python3 mobile_bridge.py --revoke dev_7f3a
python3 mobile_bridge.py --set-pin
python3 mobile_bridge.py --selftest         # roda S1–S18 sem o app
```

---

## 12. Critérios de aceite (o trabalho só está pronto se todos passarem)

- [ ] `python3 server.py` sobe exatamente como antes; desktop funciona sem mudanças.
- [ ] `git diff server.py` = **2 linhas adicionadas**.
- [ ] `mobile_bridge.py --selftest` verde (S1–S18).
- [ ] `--init` cria `iroh_secret.key` com modo 0600 e o mesmo EndpointId em execuções seguintes.
- [ ] Pareamento por código funciona dentro do TTL e falha fora dele.
- [ ] Após parear, o app consegue `/api/m/status`, `/api/m/chat`, `/api/m/sysmon` e o stream de eventos.
- [ ] `pc/run` abre apps da lista; `sleep`/lock exigem PIN e executam de verdade.
- [ ] Upload de arquivo do celular aparece no desktop (aba Drive) e o SHA-256 confere.
- [ ] `ufw status` inalterado; `ss -tlnp` não mostra porta nova além das existentes.
- [ ] Revogar um dispositivo derruba o acesso no próximo frame.
- [ ] `systemd-analyze verify` passa nas duas unidades.
- [ ] Nenhum token, PIN, senha ou conteúdo de chat em `logs/`.

---

## 13. Ordem de implementação

1. `pc_apps.py` + `mobile_auth.py` + store + CLI `--set-pin`.
2. `mobile_bridge.py`: Blueprint, auth middleware, `/status`, `/sysmon` + `--selftest` (S1–S5, S11, S12, S18).
3. `/chat`, `/chat/stream`, `/pc/*`, `/drive/*`, `/financas/*`, `/security/*`, `/cleanup/*`, `/events`.
4. `iroh_node.py`: `--init`, framing, autorização por EndpointId, proxy, pareamento.
5. Unidades systemd + supervisão da API + verificação de `ufw`.
6. Aba Mobile no desktop.
7. Rodar `--selftest` completo e o teste real com o app.

---

## 14. Regras do repositório (obrigatórias)

- **Nunca** modificar, remover ou alterar comportamento existente — só adicionar ao lado.
- **Nunca** `git add`/`git commit` sem perguntar antes, mostrando os arquivos.
- Python: snake_case, type hints, sem comentários supérfluos; queries sempre via `db.py`; logs via `log.py`.
- `.env` e segredos nunca commitados.
- **Não fazer:**
  - Não importar `bot.py` (inicia polling no import).
  - Não adicionar autenticação nas rotas `/api/*` existentes (quebraria o desktop).
  - Não abrir `0.0.0.0`, não criar regra no `ufw`, não usar Tailscale/Cloudflare/ngrok.
  - Não aceitar comando shell arbitrário vindo do app.
  - Não expor senha sudo, nem qualquer rota de sudo.
  - Não usar `shell=True` em nenhum comando disparado pelo app.
  - Não guardar token em texto puro, nem logá-lo.

---

## 15. Troubleshooting

| Sintoma | Causa provável | Ação |
|---|---|---|
| App não conecta, PC "offline" | EndpointId mudou (chave não persistida) | conferir `--status`; o EndpointId tem que ser estável |
| Conecta mas lento | caminho via relay (CGNAT agressivo) | `--status` mostra o caminho; normal, E2E mantido |
| `429` do relay | rate-limit dos relays públicos do n0 | self-host do `iroh-relay` + `RelayMap.from_urls` |
| `503 API_DOWN` | Flask não está rodando | supervisão do §9 |
| `PAIR_CODE_EXPIRED` | passou de 120 s | gerar novo código |
| `DEVICE_LIMIT` | 10 dispositivos | revogar antigos |
| App pede PIN toda hora | lockout ativo após 5 erros | aguardar 15 min |

---

## 16. Apêndice — API Iroh Python (verificada na v1.1.0)

```python
import asyncio, iroh

iroh.iroh_ffi.uniffi_set_event_loop(asyncio.get_running_loop())

ep = await iroh.Endpoint.bind(iroh.EndpointOptions(
    secret_key=secret_bytes,          # 32 bytes; OBRIGATÓRIO persistir
    alpns=[b"cyberbot/1", b"cyberbot/pair/1"],
    # relay_mode=iroh.RelayMode.default_mode(),
    # bind_addr="0.0.0.0:0",
))
print(ep.id())                        # EndpointId (base32)
print(ep.addr())                      # EndpointAddr (id + relay + addrs)
ticket = iroh.EndpointTicket.from_addr(ep.addr())   # string para QR
addr  = iroh.EndpointTicket.from_string(str(ticket)).endpoint_addr()

incoming = await ep.accept_next()
print(incoming.remote_addr())         # Direct("ip:port") | Relay
accepting = await incoming.accept()
conn = await accepting.connect()
print(conn.remote_id())               # EndpointId do app → autorização
bi = await conn.accept_bi()
recv, send = bi.recv(), bi.send()
data = await recv.read_to_end(4096)
await send.write_all(b"ok")
await send.finish()

await ep.close()
```

Tipos exportados: `Endpoint`, `EndpointOptions`, `EndpointId`, `EndpointAddr`, `EndpointTicket`, `SecretKey`, `RelayMode`, `RelayMap`, `RelayConfig`, `preset_n0()`, `preset_minimal()`, `preset_n0_disable_relay()`.

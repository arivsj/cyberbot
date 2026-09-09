# copilot.md — CyberBot Mobile: app Android nativo + bridge P2P

> **Documento de especificação para implementação por agente de código.**
> Leia inteiro antes de escrever qualquer linha. Nada aqui está implementado ainda.
> Repositório alvo: `Dog Assistent` (CyberBot). Regras do `AGENTS.md` são obrigatórias e estão repetidas na §12.

---

## 0. Resumo executivo

Objetivo: **app Android nativo** que controla o CyberBot (e o PC) sempre que celular e computador estiverem ligados, usando um transporte **P2P** (WireGuard/Tailscale) ou, como alternativa, um **relay E2E** (WebSocket/MQTT cifrado na aplicação).

Entrega em duas partes:

| Parte | Onde vive | Linguagem |
|---|---|---|
| **A. Bridge no PC** | `mobile_bridge.py` + `pc_apps.py` no repositório Python | Python 3.10 / Flask |
| **B. App Android** | novo diretório `mobile/` | Kotlin 2.x / Jetpack Compose |

A parte A é **pré-requisito** da B. Implemente A primeiro, valide com `curl`, depois B.

---

## 1. Fatos verificados do sistema atual (não invente nada além disso)

- `server.py`: Flask 3.1, ~60 rotas em `/api/*`, roda em `127.0.0.1:5000` (`app.run(host="127.0.0.1", port=5000, threaded=True)`). **Zero autenticação. CORS liberado com `CORS(app)`.**
- `bot.py`: 2046 linhas, `python-telegram-bot` com `app.run_polling()`. Whitelist por `user_id` via `db.whitelist_check`. Contém o menu **PC Apps** (`PC_APPS` e `pc_apps_callback`, linhas ~1352–1495) com `xdg-open`, `code`, `caffeine`, `shutdown -h +N`, `xdg-screensaver lock`.
- `db.py`: SQLite WAL. Funções úteis já existentes: `get_setting(key, default)`, `set_setting(key, value)`, `inserir_arquivo(name, folder_id, file_path, file_size, mime_type, telegram_file_id, caption)`, `listar_arquivos`, `get_arquivo`, `deletar_arquivo`, `whitelist_*`, `save_sysmon`, `list_sysmon_history`.
- `log.py`: `log_access(entry)` grava JSONL em `logs/access_YYYY-MM-DD.jsonl`.
- `sysmon.py`: `collect()` retorna dict de CPU/RAM/GPU/temperatura.
- `security.py`: `run_all()` e 12 checagens individuais; `init_baseline()`.
- `cleanup.py`: `TASKS`, `run_task`, `run_safe`, `run_all`, `set_password`, `SUDO_PASSWORD` (senha sudo **em memória**).
- `ollama_utils.py`: `get_chat_options(model)`, `get_context_info(model)`, `clear_cache()`.
- `plugin_loader.py`: plugins só registram **comandos do Telegram**; o campo `routes` existe na classe `Plugin` mas **nunca é ligado ao Flask**.
- `desktop/`: Electron sobe `server.py` como subprocesso e chama a API via `preload.js` (`fetch` em `127.0.0.1:5000`). **O desktop depende da API sem autenticação.**
- **Não existe rota de chat livre com a IA.** Só `/api/rag/chat` e `/api/websearch/chat`.
- Rede do usuário: IP local `10.93.220.250/24`, IP público `189.85.89.135`, **CGNAT confirmado** (saltos `192.168.0.1 → 189.85.89.6 → 172.16.46.25 → 172.16.47.61`). `ufw` ativo. Sem `tailscale`/`wg`/`zerotier` instalados.
- Máquina: Pop!_OS, kernel 7.1, Python 3.10.12, Node 22, Ollama em `127.0.0.1:11434`. **Sem JDK, sem Android SDK, sem Flutter.**

---

## 2. Arquitetura alvo

```
┌──────────────────────────┐         transporte         ┌─────────────────────────────┐
│  App Android (mobile/)   │  ───────────────────────▶  │  PC (Pop!_OS)               │
│  Kotlin + Compose        │                            │                             │
│  ┌────────────────────┐  │   A) Tailscale: HTTP direto │  mobile_bridge.py (Flask    │
│  │ Transport (interface)│ │      http://100.x.y.z:5000 │  Blueprint, /api/m/*)       │
│  │  DirectTransport    │ │   B) Relay: WSS + AES-GCM  │    ├─ auth (token + PIN)    │
│  │  RelayTransport     │ │      E2E, relay cego       │    ├─ allowlist de IP       │
│  └────────────────────┘  │                            │    ├─ rate limit / lockout  │
└──────────────────────────┘                            │    └─ log em logs/*.jsonl   │
                                                        │           │                 │
                                                        │           ▼                 │
                                                        │  server.py / db.py / bot.py │
                                                        │  (INTOCADOS)                │
                                                        └─────────────────────────────┘
```

Princípio central: **a API existente não muda**. Todo acesso do celular passa por `/api/m/*`, com autenticação própria. O desktop continua usando `/api/*` sem token, em `127.0.0.1`.

---

## 3. Decisão de transporte: implemente os dois atrás de uma interface

O app **não deve** ser acoplado a um transporte. Escreva uma interface e duas implementações.

```kotlin
interface Transport {
    suspend fun request(call: ApiCall): ApiResult        // unário (request/response)
    fun events(): Flow<CyberEvent>                        // stream (SSE ou WS)
    suspend fun probe(): TransportHealth                  // latency, path, reachable
}

class DirectTransport(                                   // opção A: Tailscale / LAN
    private val baseUrl: String,                         // http://100.x.y.z:5000 ou http://10.93.220.250:5000
    private val tokenProvider: suspend () -> String,
) : Transport

class RelayTransport(                                    // opção B: relay cego E2E
    private val wsUrl: String,                           // wss://relay.exemplo.com/v1/ws
    private val deviceKeyPair: X25519KeyPair,
    private val peerPublicKey: X25519PublicKey,          // chave do PC (vem no QR de pareamento)
    private val tokenProvider: suspend () -> String,
) : Transport

enum class TransportMode { AUTO, DIRECT_ONLY, RELAY_ONLY }
```

`AUTO` (padrão): tenta `DirectTransport` (tailnet, depois LAN por mDNS); se `probe()` falhar em < 2 s, cai para `RelayTransport`. Exponha o caminho ativo na UI (`direto` / `relay`) e um botão "forçar direto".

---

## 4. Contrato da API do PC (bridge) — implemente exatamente assim

Base: `http://<host>:5000/api/m` (porta configurável por `PORT`).
Autenticação: `Authorization: Bearer <token>` em **todas** as rotas, exceto `/api/m/pair`.
Rotas destrutivas exigem adicionalmente o header `X-Action-PIN: <pin>`.

Formato de erro único:

```json
{ "error": { "code": "PIN_REQUIRED", "message": "PIN obrigatório para esta ação" } }
```

| HTTP | code | quando |
|---|---|---|
| 400 | `BAD_REQUEST` | payload inválido |
| 401 | `INVALID_TOKEN` / `TOKEN_REVOKED` | token ausente, inválido ou revogado |
| 403 | `PIN_REQUIRED` / `PIN_INVALID` / `IP_NOT_ALLOWED` | ação destrutiva sem PIN correto, ou origem fora da allowlist |
| 404 | `NOT_FOUND` | recurso inexistente |
| 409 | `CONFLICT` | ex.: bot já rodando |
| 423 | `LOCKED` | lockout após tentativas de PIN |
| 429 | `RATE_LIMITED` | com header `Retry-After` (segundos) |
| 500 | `INTERNAL` | exceção no PC |
| 504 | `UPSTREAM_TIMEOUT` | Ollama/IA demorou além do timeout |

### 4.1 Pareamento

```
POST /api/m/pair
body: { "code": "482913", "device_name": "Pixel 8", "device_pubkey": "<base64 X25519, opcional>", "app_version": "1.0.0" }
200 → { "device_id": "dev_7f3a...", "token": "<43 chars urlsafe base64>", "pc_name": "cyberbot-pc",
        "transport_hint": { "direct": ["http://100.101.102.103:5000", "http://10.93.220.250:5000"], "relay": null },
        "pin_set": true }
```

Regras obrigatórias:
- O código de 6 dígitos é gerado **no PC** (desktop ou CLI `python3 mobile_bridge.py --pair`), TTL **120 s**, **uso único**, máx. **5 tentativas** antes de invalidar.
- Comparação com `secrets.compare_digest`. Nunca logar o código nem o token.
- Máx. 10 dispositivos pareados; `POST /api/m/devices/<id>/revoke` revoga.

### 4.2 Status e monitor

```
GET  /api/m/status
200 → { "pc": { "hostname": "cyberbot-pc", "uptime_s": 91234, "tailnet_ip": "100.101.102.103", "lan_ip": "10.93.220.250" },
        "bot": { "running": true, "pid": 1234 },
        "ollama": { "running": true, "model": "gemma4", "context": { ...ollama_utils.get_context_info() } },
        "transport": { "server_time": "2026-09-09T12:00:00Z", "version": "1.0.0" } }

GET  /api/m/sysmon
200 → resultado direto de sysmon.collect()
GET  /api/m/sysmon/history?limit=30
200 → db.list_sysmon_history(limit)
```

### 4.3 Chat com a IA (rota nova — não existe hoje)

```
POST /api/m/chat
body: { "conversation_id": "c_abc", "message": "resuma minhas finanças de agosto", "model": "gemma4?" }
200 → { "reply": "...", "model": "gemma4", "elapsed_ms": 4200, "conversation_id": "c_abc" }

POST /api/m/chat/stream           # text/event-stream
event: delta   data: {"text":"..."}
event: done    data: {"elapsed_ms":4200}
```

- Chama `POST http://localhost:11434/api/chat` com `options=ollama_utils.get_chat_options(model)`.
- Histórico por `conversation_id` em memória (dict com TTL 2 h, máx. 20 conversas). Não escrever no banco.
- Timeout 120 s. Se Ollama não responde, `504 UPSTREAM_TIMEOUT`.
- **Não** reaproveite `/api/rag/chat` nem `/api/websearch/chat` (eles injetam contexto e mudam o comportamento).

### 4.4 Controle do bot

```
POST /api/m/bot/start   → { "status": "started" }            # 409 se já rodando
POST /api/m/bot/stop    → { "status": "stopped" }            # requer X-Action-PIN
POST /api/m/bot/send    body: { "chat_id": 123, "text": "..." }  # reusa a lógica existente via httpx
```

### 4.5 PC Apps (hoje só existe no bot)

```
GET  /api/m/pc/apps
200 → [ { "id": "youtube", "label": "▶ YouTube", "destructive": false },
        { "id": "vscode",  "label": "💻 VS Code", "destructive": false },
        { "id": "max",     "label": "🎬 Max",     "destructive": false },
        { "id": "caffeine","label": "☕ Caffeine","destructive": false },
        { "id": "lock",    "label": "🔒 Bloqueio","destructive": true  },
        { "id": "sleep",   "label": "💤 Sleep",   "destructive": true  } ]

POST /api/m/pc/run      body: { "id": "vscode" }
200 → { "status": "started", "label": "💻 VS Code" }

POST /api/m/pc/sleep    body: { "minutes": 90 }   # requer X-Action-PIN
200 → { "status": "scheduled", "command": "shutdown -h +90", "at": "2026-09-09T13:30:00Z" }
```

- Comandos **hardcoded** em `pc_apps.py`. **Nunca** aceitar comando arbitrário do app.
- `shutdown` só via `minutes` inteiro, faixa 1–1440, e executado como `subprocess.Popen(["shutdown","-h",f"+{minutes}"])` (lista, sem `shell=True`).
- `lock` → `subprocess.run(["xdg-screensaver","lock"], timeout=5)`.

### 4.6 Drive (upload/download não existem hoje)

```
GET  /api/m/drive/folders?parent=<id>   → db.listar_pastas(parent_id)
GET  /api/m/drive/files?folder=<id>     → db.listar_arquivos(folder_id)
POST /api/m/drive/upload                multipart/form-data: file=<binário>, folder_id=<int|"">
200 → { "id": 42, "name": "foto.jpg", "size": 812345, "mime": "image/jpeg" }
GET  /api/m/drive/download/<file_id>    → stream binário com Content-Disposition
DELETE /api/m/drive/files/<file_id>     → requer X-Action-PIN
```

- Salvar em `drive_files/` com nome saneado (`os.path.basename`, remover `..`, limite 255 chars) e sufixo `uuid4().hex[:8]` para evitar colisão.
- Limite de upload configurável (`MOBILE_MAX_UPLOAD_MB`, padrão 200) e `413` acima disso.
- Registrar via `db.inserir_arquivo(...)` para aparecer no desktop.

### 4.7 Finanças, segurança, limpeza

```
GET  /api/m/financas?mes=YYYY-MM          → db.listar(mes)
GET  /api/m/financas/resumo?mes=YYYY-MM   → mesmo payload de /api/financas/resumo
POST /api/m/financas                      body: {categoria, conta, valor, descricao}
GET  /api/m/security/run                  → security.run_all()
GET  /api/m/security/<check>              → uma checagem
GET  /api/m/security/report               → roda run_all + análise IA (timeout 90 s)
GET  /api/m/cleanup/tasks                 → metadados das tarefas
POST /api/m/cleanup/run                   body: {task?, mode?} — requer X-Action-PIN
```

**Proibido:** expor `/api/cleanup/sudo-password`, `/api/cleanup/sudo-status` ou qualquer rota que aceite senha sudo pelo app. Se uma tarefa exigir sudo, retorne `403 SUDO_REQUIRED` com instrução para fazer no desktop.

### 4.8 Eventos em tempo real (SSE)

```
GET /api/m/events        (text/event-stream, heartbeat a cada 15 s)
event: status   data: {"bot":true,"ollama":true,"cpu":23.5,"ram":61.0}
event: alert    data: {"source":"security","severity":"high","message":"porta 22 exposta"}
event: job      data: {"id":"j_1","kind":"youtube","state":"done","detail":"video.mp4"}
```

- Um `threading.Queue` por cliente; máx. 5 clientes simultâneos; `werkzeug` threaded já cobre.
- Push opcional no Android: se o app registrar um `push_token`, o bridge pode disparar via FCM **ou** `ntfy` (escolha por `MOBILE_PUSH_BACKEND=none|ntfy|fcm`; `none` é o padrão).
- Nunca incluir token, caminho absoluto de arquivo sensível ou conteúdo de mensagem no push.

### 4.9 Dispositivos

```
GET    /api/m/devices                 → [ {id, name, created_at, last_seen, last_ip, revoked} ]
POST   /api/m/devices/<id>/revoke     → requer X-Action-PIN
DELETE /api/m/devices/<id>            → requer X-Action-PIN
POST   /api/m/pin                     body: {current_pin?, new_pin} — troca de PIN
```

---

## 5. Backend PC — `mobile_bridge.py` (requisitos de implementação)

### 5.1 Arquivos

```
pc_apps.py          # NOVO — registro compartilhado de apps do PC (sem importar bot.py)
mobile_bridge.py    # NOVO — Blueprint Flask + auth + rotas /api/m/*
server.py           # ALTERAÇÃO MÍNIMA: 2 linhas no fim, antes de if __name__
```

Patch exato em `server.py` (aditivo, no final do arquivo, antes do bloco `if __name__ == "__main__":`):

```python
import mobile_bridge
mobile_bridge.register(app)
```

Nenhuma rota, função ou comportamento existente pode ser alterado. Não mexer em `CORS(app)`, `app.run(...)`, `_kill_bot`, `periodic_cache_clear` nem no `MODELO_PADRAO`.

### 5.2 Bind e allowlist de origem

- `MOBILE_BIND` (env): `127.0.0.1` (padrão), `tailnet`, `lan` ou IP literal. `tailnet` resolve para o IP `100.x.y.z` da interface `tailscale0`; se a interface não existir, o bridge **não** liga e loga aviso.
- Se `MOBILE_BIND != 127.0.0.1`, o `app.run` do `server.py` continua em `127.0.0.1` e o bridge sobe um **segundo listener** (`werkzeug.serving.make_server`) numa thread, no IP escolhido. Assim o desktop não fica exposto.
- Middleware `before_request` do Blueprint: rejeita `403 IP_NOT_ALLOWED` se `request.remote_addr` não estiver em `127.0.0.0/8`, `100.64.0.0/10` (tailnet), `10.0.0.0/8`, `192.168.0.0/16`, `172.16.0.0/12`.
- **Nunca** `0.0.0.0` em interface pública. Documentar no `--help` do CLI.

### 5.3 Armazenamento de credenciais (via `db.py`, chave `mobile_devices`)

```json
{
  "version": 1,
  "pin_hash": "scrypt$n=16384,r=8,p=1$<salt_b64>$<hash_b64>",
  "devices": [
    { "id": "dev_7f3a", "name": "Pixel 8", "token_hash": "<sha256 hex>", "pubkey": "<b64|null>",
      "created_at": "2026-09-09T12:00:00Z", "last_seen": "2026-09-09T12:10:00Z", "last_ip": "100.64.1.2",
      "revoked": false }
  ]
}
```

- Token: `secrets.token_urlsafe(32)`; guardar **apenas** o SHA-256. Comparação por hash + `compare_digest`.
- PIN: 6 dígitos, `hashlib.scrypt` (n=2^14, r=8, p=1, 16 bytes de salt). Lockout: 5 falhas → `423` por 15 min (contador em memória + `last_seen`).
- Escrita com `db.set_setting("mobile_devices", json.dumps(...))`. Leitura por `db.get_setting`.

### 5.4 Log e auditoria

Toda requisição `/api/m/*` chama `log.log_access({...})` com `type: "mobile"`, `device_id`, `route`, `method`, `status`, `ip`, `duration_ms`. **Nunca** logar token, PIN, corpo de mensagem de chat ou senha.

### 5.5 Rate limiting

Token-bucket em memória por `device_id`: 120 req/min e 10 req/s de pico. `/api/m/pair`: 5 tentativas/5 min por IP. `/api/m/chat`: máx. 2 concorrentes por dispositivo.

### 5.6 CLI de administração

```bash
python3 mobile_bridge.py --pair            # gera código de 6 dígitos (TTL 120s) e imprime QR ASCII + JSON
python3 mobile_bridge.py --pair --ttl 300
python3 mobile_bridge.py --devices         # lista dispositivos
python3 mobile_bridge.py --revoke dev_7f3a
python3 mobile_bridge.py --set-pin
python3 mobile_bridge.py --qr              # QR com URL base + fingerprint da chave (para RelayTransport)
python3 mobile_bridge.py --selftest        # roda os testes de aceite §11 sem o app
```

---

## 6. App Android — estrutura e stack

### 6.1 Stack obrigatória

| Item | Escolha |
|---|---|
| Linguagem | Kotlin 2.x (sem Java) |
| UI | Jetpack Compose + Material 3 (tema cyberpunk: `--neon`, `--bg`, `--surface` equivalentes em `ColorScheme`) |
| minSdk / targetSdk | 26 / mais recente estável |
| Build | Gradle Kotlin DSL + `libs.versions.toml` (version catalog); AGP 8.x |
| DI | Hilt |
| Rede | Ktor Client 3.x (engine OkHttp), `kotlinx.serialization`, `HttpTimeout`, retry exponencial |
| Assíncrono | Coroutines + Flow |
| Persistência | DataStore (Proto ou Preferences) + **Google Tink** para segredos — `EncryptedSharedPreferences` está **deprecado**, não usar |
| Imagens | Coil |
| Background | WorkManager (comandos offline, sync) + Foreground Service `dataSync` para o stream SSE |
| Testes | JUnit5 + Turbine + MockWebServer + Compose UI Test |

Não fixe versões de biblioteca "de memória": no momento da implementação consulte a versão estável atual e registre no version catalog.

### 6.2 Estrutura de pacotes

```
mobile/
├── settings.gradle.kts
├── gradle/libs.versions.toml
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/cyberbot/mobile/
            ├── CyberBotApp.kt                 # @HiltAndroidApp
            ├── MainActivity.kt
            ├── core/
            │   ├── crypto/                    # X25519, HKDF, AES-GCM, Tink keyset
            │   ├── storage/                   # DataStore, SecureStore (token/PIN)
            │   ├── net/                       # Ktor client, interceptors, ApiCall/ApiResult
            │   ├── transport/                 # Transport, DirectTransport, RelayTransport, TransportSelector
            │   └── model/                     # DTOs serializáveis
            ├── data/
            │   ├── api/                       # MobileApi (endpoints /api/m/*)
            │   ├── repo/                      # StatusRepository, ChatRepository, DriveRepository, ...
            │   └── local/                     # DAO/cache
            ├── domain/                        # use cases (ToggleBot, RunPcApp, SendChat, UploadFile...)
            ├── ui/
            │   ├── theme/                     # cores, tipografia, tema cyberpunk
            │   ├── nav/                       # NavHost + rotas
            │   ├── pairing/                   # PairingScreen, PairingViewModel
            │   ├── dashboard/                 # status cards, CPU/RAM/GPU, bot toggle
            │   ├── chat/                      # ChatScreen (streaming), ChatViewModel
            │   ├── pcapps/                    # PC Apps grid
            │   ├── drive/                     # navegador + upload/download
            │   ├── finance/                   # resumo + nova transação
            │   ├── security/                  # 12 checks + relatório IA
            │   ├── cleanup/                   # tarefas (PIN)
            │   ├── devices/                   # gerenciar dispositivos
            │   └── settings/                  # transporte, PIN, tema, notificações
            ├── service/                       # EventStreamService (foreground, SSE)
            ├── work/                          # CommandQueueWorker, SyncWorker
            └── widget/                        # Tile + Widget (lock PC, toggle bot)
```

### 6.3 Telas (Compose) e comportamento

1. **Pairing** — campo de código de 6 dígitos ou leitura de QR (`journeyapps:zxing-android-embedded` ou CameraX+ML Kit). Mostra `pc_name` e os endpoints descobertos. `FLAG_SECURE` nesta tela.
2. **Dashboard** — cards: bot (ligar/desligar com confirmação), Ollama/modelo, CPU/RAM/GPU/temp em tempo real via `/api/m/events`, IPs (tailnet/LAN), indicador de transporte (`direto`/`relay`) e latência, botão "PC Apps", atalhos.
3. **Chat** — bolhas estilo cyberpunk, streaming de `/api/m/chat/stream`, seletor de modelo, histórico local por conversa, indicador de "IA pensando" (o modelo local é lento: mostre tempo decorrido).
4. **PC Apps** — grid de botões; `sleep`/lock pedem PIN + confirmação.
5. **Drive** — breadcrumbs, lista com tipo/tamanho, upload via `ActivityResultContracts.GetContent` (multipart, progresso), download com `DownloadManager`, preview de imagem/vídeo/áudio (reusar ideia do desktop).
6. **Finanças** — resumo do mês, gráfico (Vico ou Compose Canvas), inserir transação.
7. **Security** — 12 cards com status/alerts/attentions, gráfico, gerar relatório IA (aviso de lentidão), histórico de relatórios.
8. **Cleanup** — tarefas com badge "sudo" desabilitadas no app (ver §4.7), executar sem sudo requer PIN.
9. **Devices/Settings** — transporte (AUTO/direto/relay), endpoints, PIN, notificações, revogar dispositivos, apagar dados locais.

### 6.4 Detalhes de implementação que evitam retrabalho

- `ApiResult`: `Success(body, elapsedMs)` | `HttpError(code, apiCode, message)` | `NetworkError(cause)`. Mapeie `401` para "desparear e voltar à tela de pairing"; `423` para tela de lockout com contador.
- Idempotência: gere `Idempotency-Key` (UUID) em `POST` de chat/upload/cleanup; o bridge guarda 10 min e devolve a mesma resposta.
- Reconexão SSE: backoff 1s→2s→4s→…→60s, `Last-Event-ID` quando disponível; FGS com notificação silenciosa e `stopSelf` se o usuário desligar.
- `AUTO` só faz probe no resume do app e a cada 5 min (não a cada request).
- Cache local do último `/api/m/status` para a tela abrir instantânea (mostrar "dados de há X min").
- `FLAG_SECURE` em Pairing e no diálogo de PIN.
- Nunca persistir o token em `SharedPreferences` comum, `logcat`, `ClipboardManager` ou backup (`android:allowBackup="false"` / `dataExtractionRules`).

---

## 7. Transporte A — Tailscale (recomendado)

Passos no PC:

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up --hostname=cyberbot-pc --accept-routes=false
tailscale status            # anote o IP 100.x.y.z e se o caminho é "direct" ou "relay"
sudo tailscale cert cyberbot-pc.<tailnet>.ts.net   # certificado TLS válido (habilita HTTPS)
```

Passos no celular: instalar o app oficial Tailscale, logar na **mesma tailnet**, ativar a VPN.

ACL mínima (policy file), princípio do menor privilégio:

```jsonc
{
  "tagOwners": { "tag:pc": ["autogroup:admin"], "tag:phone": ["autogroup:admin"] },
  "acls": [
    // celular só alcança a porta 5000 do PC
    { "action": "accept", "src": ["tag:phone"], "dst": ["tag:pc:5000"] }
    // nada mais. sem porta 11434, sem SSH, sem ICMP.
  ]
}
```

Endurecimento obrigatório: **device approval** ativado, **key expiry** 90 dias, **Tailscale Lock (tailnet lock)** ativado se disponível, SSO com 2FA, e um device "phone" separado por aparelho.

No app: `DirectTransport("http://cyberbot-pc.<tailnet>.ts.net:5000")` (ou IP `100.x`); TLS com o certificado de `tailscale cert` (confie na CA da Tailscale ou fixe o fingerprint).

Detecção de LAN: quando o celular está no mesmo Wi-Fi, tente `http://10.93.220.250:5000` (ou descoberta mDNS `_cyberbot._tcp`) **antes** da tailnet — latência mínima.

---

## 8. Transporte B — Relay E2E (alternativa)

Use **somente** se o requisito "nenhum terceiro no controle" pesar mais que o esforço. Você estará reimplementando o plano de dados do Tailscale.

Topologia: PC e celular abrem WebSocket **de saída** para um relay (VPS). O relay roteia por `to`, sem descriptografar nada.

Envelope (JSON sobre WSS):

```json
{ "v": 1, "id": "m_8f21", "ts": "2026-09-09T12:00:00Z", "from": "dev_7f3a", "to": "pc_cyberbot",
  "counter": 1042, "nonce": "<12 bytes b64>", "alg": "X25519-HKDF-SHA256+A256GCM",
  "ciphertext": "<b64>" }
```

- Chaves: X25519 por dispositivo, trocadas no pareamento (QR contém pubkey do PC + URL do relay + `pairing_token` de uso único). HKDF-SHA256 → chave de sessão; AES-256-GCM por mensagem.
- Anti-replay: `counter` monotônico por par de dispositivos + janela de `ts` de ±120 s; PC guarda o último counter visto.
- Handshake do WS: assinatura Ed25519 do `challenge` do relay com a chave do dispositivo (o relay autentica, não decifra).
- Arquivos: frames de 256 KiB com `seq`/`total` e SHA-256 do arquivo; retomada por offset.
- O relay é **stateless**: sem fila offline, sem retenção; se o PC estiver offline, `502` do relay e o app enfileira no WorkManager.
- Sem TLS o relay é inútil — `wss://` obrigatório, e ainda assim o payload é cifrado na aplicação (defesa em profundidade contra relay comprometido).

Comparação de risco: um relay comprometido **não** lê o tráfego (E2E), mas pode fazer DoS, correlação de metadados e replay (mitigado por counter/ts). Um coordination server comprometido (Tailscale) não lê tráfego e não descriptografa DERP, mas pode tentar inserir um dispositivo na tailnet — mitigado por ACL restritiva + device approval + tailnet lock + o token do próprio bridge.

---

## 9. Modelo de segurança (requisitos testáveis)

| # | Requisito | Como testar |
|---|---|---|
| S1 | Nenhuma rota `/api/m/*` responde sem `Bearer` válido | `curl` sem header → 401 |
| S2 | Token guardado só como SHA-256 no banco | inspecionar `db.settings` |
| S3 | `/api/m/pair` expira em 120 s e é uso único | reusar código → 401 |
| S4 | Origem fora da allowlist → 403 | `curl` de IP externo |
| S5 | Ações destrutivas exigem PIN | `POST /api/m/bot/stop` sem header → 403 |
| S6 | 5 PINs errados → 423 por 15 min | loop de testes |
| S7 | Senha sudo nunca trafega pelo app | grep nas rotas + teste negativo |
| S8 | `shutdown` só aceita 1–1440 min, sem shell | teste com `"90; rm -rf /"` → 400 |
| S9 | Upload sanitiza nome e respeita limite | `../../etc/passwd` → nome saneado |
| S10 | Token não aparece em log nem em URL | grep em `logs/` |
| S11 | Desktop continua funcionando sem token | abrir o app Electron |
| S12 | Revogar dispositivo invalida o token imediatamente | revogar → 401 |
| S13 | Relay não consegue decifrar | capturar tráfego no relay → só ciphertext |
| S14 | Replay rejeitado | reenviar envelope com mesmo `counter` → 409 |

---

## 10. Ordem de implementação (faça nesta sequência)

1. `pc_apps.py` (registry + `run_app`) + `mobile_bridge.py` com auth, `/pair`, `/status`, `/sysmon`.
2. `--selftest` cobrindo S1–S5, S12 (sem app).
3. `/chat` + `/chat/stream`, `/pc/*`, `/drive/*`, `/financas/*`, `/security/*`, `/cleanup/*`, `/events`.
4. Projeto Android: Gradle + Hilt + Ktor + tema; `PairingScreen` funcional.
5. `DirectTransport` + Dashboard + Chat.
6. PC Apps, Drive, Finanças, Security.
7. `RelayTransport` + `EventStreamService` + notificações.
8. Widget/Tile, testes de UI, empacotamento assinado.

---

## 11. Critérios de aceite (o trabalho só está pronto se todos passarem)

- [ ] `python3 server.py` sobe igual a antes; desktop Electron funciona sem nenhuma mudança de comportamento.
- [ ] `git diff server.py` mostra **apenas** as 2 linhas de registro do Blueprint.
- [ ] `python3 mobile_bridge.py --selftest` verde (S1–S14).
- [ ] Com Tailscale ativo e o celular na tailnet, o app abre o Dashboard em < 2 s e mostra CPU/RAM/GPU atualizando.
- [ ] Chat responde via Ollama local e o app mostra o tempo de resposta.
- [ ] "Bloqueio de tela" e "Sleep" exigem PIN e realmente executam no PC.
- [ ] Upload de foto do celular aparece no desktop (aba Drive) imediatamente.
- [ ] Se a tailnet cair, o app cai para relay (ou mostra "PC offline") sem travar nem vazar erro cru.
- [ ] Revogar o dispositivo derruba o app para a tela de pareamento no próximo request.
- [ ] Nenhuma senha, token ou PIN em log, URL ou `logcat`.

---

## 12. Regras do repositório (obrigatórias)

Do `AGENTS.md`:
- **Nunca** modificar, remover ou alterar comportamento de funcionalidade existente. Só adicionar ao lado.
- **Nunca** `git add`/`git commit` sem perguntar antes ao usuário, mostrando os arquivos.
- Comentários supérfluos não; Python snake_case com type hints; Kotlin/JS camelCase.
- Queries no banco sempre via `db.py`.
- Tema cyberpunk com variáveis de cor consistentes com `desktop/renderer/style.css`.
- `.env` nunca commitado; segredos do bridge ficam no banco/SQLite, não em arquivo texto.

**Não fazer:**
- Não adicionar autenticação nas rotas `/api/*` existentes (quebraria o desktop).
- Não importar `bot.py` de dentro do bridge (ele inicia polling do Telegram no import).
- Não expor `0.0.0.0` na interface pública nem abrir porta no roteador (CGNAT).
- Não aceitar comando shell arbitrário do app.
- Não usar o Telegram como transporte do app.
- Não usar `EncryptedSharedPreferences` (deprecado) nem `SharedPreferences` comum para segredos.

---

## 13. Riscos conhecidos

| Risco | Impacto | Mitigação |
|---|---|---|
| CGNAT agressivo → tráfego sempre via DERP | Latência 30–80 ms | Aceitável para controle; `tailscale status` mostra o caminho; Headscale/VPS se incomodar |
| Android só permite **uma VPN ativa** | Tailscale conflita com VPN de trabalho | `AUTO` com fallback relay; avisar o usuário na UI |
| Ollama lento (modelo local) | Chat com 10–60 s | Streaming + indicador de tempo + timeout 120 s |
| PC dorme/hiberna | App parece offline | Wake-on-LAN opcional (fase futura), `/api/m/status` com `last_seen` |
| `security/report` roda IA e demora | Timeout no app | Timeout 90 s + botão assíncrono com "gerar relatório" |
| Duplicação da lista `PC_APPS` (bot vs `pc_apps.py`) | Divergência | Comentar no `pc_apps.py` que `bot.py` mantém a dele; refatorar só se o usuário autorizar |
| Perda do celular | Acesso ao PC | Revogação por device + PIN + tailnet key expiry + device approval |

---

## 14. Glossário

- **DERP** — relay criptografado da Tailscale; encaminha pacotes WireGuard sem poder decifrá-los.
- **Hole punching** — técnica de NAT traversal que abre o caminho UDP direto entre dois peers.
- **CGNAT** — NAT do provedor; impede port forwarding, exige traversal ou relay.
- **E2E** — criptografia ponta a ponta; intermediários só veem ciphertext.
- **FGS** — Foreground Service do Android; necessário para manter o stream de eventos vivo.
- **BluePrint (Flask)** — grupo de rotas registrável sem alterar o app Flask existente.

# CyberBot — Viabilidade de App Android via P2P

> Documento de análise técnica. Nada do sistema atual foi alterado.
> Data da verificação: inspeção do repositório + sondagem de rede na máquina do usuário.

## 1. Diagnóstico do sistema atual

| Camada | Arquivo | O que faz |
|---|---|---|
| Bot Telegram | `bot.py` (2046 linhas) | Chat com Ollama, finanças, drive, YouTube, PC Apps, security, cleanup, RAG, web search, plugins |
| API REST | `server.py` (762 linhas) | ~60 rotas Flask, **sem autenticação**, CORS liberado |
| Desktop | `desktop/` (Electron) | Sobe o `server.py` como subprocesso e consome a API via `preload.js` |
| Banco | `db.py` | SQLite WAL; tabela `settings` chave/valor (bom lugar para token) |
| Plugins | `plugin_loader.py` | Plugins adicionam **comandos do Telegram**; o campo `routes` existe mas **nunca é ligado ao Flask** |

Estado da rede na máquina:

- API escutando **somente em `127.0.0.1:5000`** (`app.run(host="127.0.0.1")`) — hoje é inalcançável de qualquer outro dispositivo.
- Ollama em `127.0.0.1:11434`.
- IP local `10.93.220.250/24`; IP público `189.85.89.135`.
- **CGNAT confirmado**: `tracepath` mostra saltos em `192.168.0.1` → `189.85.89.6` → `172.16.46.25` → `172.16.47.61`. Ou seja, o "IP público" é compartilhado pela operadora.
- `ufw` ativo; sem `tailscale`, `zerotier`, `wg` instalados.
- Sem JDK/Android SDK/Flutter instalados (relevante para a decisão de stack do app).

## 2. O que já é controlável hoje (remotamente)

Via API REST (tudo que o desktop usa): status, start/stop do bot, finanças, drive (listar/criar/excluir), trilha rede, whitelist, logs, security (12 checks + relatório IA), sysmon, cleanup (inclui senha sudo em memória), RAG, web search, plugins, troca de modelo.

Via bot Telegram: os mesmos itens + **PC Apps** (abrir YouTube/VS Code/Max/Caffeine, `sleep`, `lock`) — hoje **exclusivo do bot**, não existe rota HTTP para isso.

## 3. Lacunas para um app de verdade

1. **Sem autenticação.** Nenhuma rota valida token. `CORS(app)` aceita qualquer origem. Expor isso na rede hoje = acesso total ao PC (inclusive `cleanup` com sudo e `pc_apps`).
2. **Sem rota de chat direto com a IA.** Só existem `/api/rag/chat` e `/api/websearch/chat`; não há `/api/chat` genérico (o chat livre só existe no Telegram).
3. **PC Apps não é API.** Está embutido em `pc_apps_callback` no `bot.py`.
4. **Sem tempo real.** Nada de WebSocket/SSE — o app teria que fazer polling de `/api/status` e `/api/sysmon`.
5. **Sem upload/download binário no Drive.** `/api/rag/upload` existe; o drive só grava arquivo pelo bot.
6. **Sem notificações push** para eventos (alerta de security, bot caiu, download terminou).

## 4. O P2P é viável? Sim — mas não por porta aberta

**Restrição dura:** com CGNAT, abrir porta no roteador não resolve. Não existe caminho *inbound* direto do celular para o PC. Qualquer solução tem que ser **NAT traversal (hole punching)** ou **relay**.

Comparação das opções:

| Opção | P2P real | Funciona atrás de CGNAT | Infra extra | Android | Esforço |
|---|---|---|---|---|---|
| **Tailscale** (WireGuard + DERP) | Sim (direto quando possível, relay quando não) | Sim | Nenhuma (coordination server da Tailscale; pode self-hostar com Headscale) | App oficial pronto | **Baixo** |
| Headscale self-hosted | Sim | Sim | VPS (~US$5) | App oficial (troca de URL) | Médio |
| WireGuard manual | Sim | **Não** | Port forward (impossível com CGNAT) + DDNS | App oficial | Não viável aqui |
| WebRTC DataChannel + signaling/STUN/TURN | Sim | Sim | Servidor de signaling + TURN | Bibliotecas boas, mas app próprio | Alto |
| libp2p | Sim | Sim (com relays) | Bootstrap/relay nodes | Suporte Android fraco | Alto |
| MQTT/WebSocket relay | Não (relay) | Sim | Broker (VPS ou público) | Fácil | Médio |
| Cloudflare Tunnel + Access | Não (relay) | Sim | Nenhuma (conta CF) | Fácil | Baixo |
| Telegram (já existe) | Não | Sim | Nenhuma | Bot API no app | Baixo, mas limitado |

**Recomendação: Tailscale.** É literalmente WireGuard P2P com hole punching e fallback para relay DERP quando o CGNAT é agressivo — atende o pedido "sempre que os dois estiverem ligados". Dá nome fixo via MagicDNS (`pc.tailnet.ts.net`) e **certificado TLS válido** (`tailscale cert`), o que habilita HTTPS e Web Push no app.

Ponto de honestidade: neste link CGNAT o tráfego pode cair no relay DERP em vez de ir direto. Latência típica 30–80 ms, criptografia fim-a-fim mantida, suficiente para controle remoto. Tailscale mostra o caminho em `tailscale status` (`direct` vs `relay`).

## 5. Arquitetura proposta

```
[ App Android ]  --WireGuard/Tailscale-->  [ PC: tailnet IP 100.x.y.z ]
        |                                          |
        | HTTP(S) + Bearer token                   +--> mobile_bridge.py (NOVO, Blueprint Flask)
        |                                          |      - /api/m/*  (status, chat, pc-apps, upload)
        +-- Web Push (opcional)                    |      - token em db.settings, rate-limit, log em logs/
                                                   +--> server.py / db.py / bot.py  (INTOCADOS)
```

Regras respeitadas do `AGENTS.md`:

- `mobile_bridge.py` é **módulo novo**, importado com 2 linhas no fim do `server.py` (adicionar ao lado, sem alterar nenhuma rota existente).
- `bot.py`, `db.py`, `sysmon.py`, fluxo do Telegram e do desktop permanecem intactos.
- Escrita no banco via `db.py` (reuso de `get_setting`/`set_setting`).
- Log de acessos do app no mesmo `logs/access_*.jsonl` via `log.py`.

### Segurança mínima obrigatória (não negociável)

1. Bind **apenas no IP da tailnet** (ou 127.0.0.1 + proxy), nunca `0.0.0.0` na interface pública.
2. **Bearer token** gerado no PC, guardado em `db.settings`, comparado com `secrets.compare_digest`.
3. `CORS` restrito ao próprio app (nada de `*`).
4. Escopo por rota: rotas destrutivas (`cleanup/run`, `bot/stop`, `drive/files DELETE`) exigem um segundo fator (PIN) — hoje o desktop chama sem nenhuma barreira.
5. **Não expor** `/api/cleanup/sudo-password` no app: senha sudo nunca sai do desktop.

## 6. Roadmap em fases

| Fase | Entrega | Esforço |
|---|---|---|
| 0 | Instalar Tailscale no PC e no celular, confirmar acesso a `http://<tailnet-ip>:5000/api/status` | ~30 min |
| 1 | `mobile_bridge.py`: token, `/api/m/status`, `/api/m/chat` (Ollama direto), `/api/m/pc/apps` + `/api/m/pc/run`, `/api/m/drive/upload|download`, `/api/m/sysmon`, log | 1–2 dias |
| 2 | Cliente PWA (HTML/CSS/JS, reaproveita o tema cyberpunk do desktop), instalável na tela inicial | 2–4 dias |
| 3 | Notificações (Web Push com VAPID + `tailscale cert` para HTTPS) e atalhos rápidos | 1–2 dias |
| 4 | (Opcional) App nativo Kotlin/Compose com foreground service, widget, tarefas em background | 1–2 semanas |

**Por que PWA primeiro:** não há JDK/Android SDK na máquina (verificado), o build nativo exige ~5–8 GB de toolchain e o PWA instala em 1 clique no Chrome, já resolve controle/manipulação e valida toda a API antes de investir em Kotlin.

## 7. Riscos e mitigações

| Risco | Mitigação |
|---|---|
| CGNAT força relay → latência maior | Aceitável para controle; testar `tailscale status`; se virar problema, avaliar Headscale + `--advertise-* ` |
| API sem auth exposta | Token obrigatório + bind restrito (Fase 1) |
| Roubo do celular com tailnet ativa | Chave da tailnet expirável + PIN nas rotas destrutivas + revogar device no admin Tailscale |
| Dependência de terceiro (Tailscale) | Caminho de migração para Headscale self-hosted sem mudar o app |
| `pc_apps` executa comandos arbitrários | Lista branca fixa (a atual `PC_APPS`), nunca `shell` livre vindo do app |

## 8. Conclusão

**Viável.** O sistema já tem a peça mais difícil pronta — uma API REST completa que faz tudo. O que falta é (a) transporte P2P, (b) autenticação, (c) meia dúzia de rotas que hoje só existem no bot. Com Tailscale + um módulo novo de ~300 linhas + um PWA, o objetivo "PC ligado e celular ligado ⇒ controlo o software pelo app" é atingido sem tocar em nenhum fluxo existente.

---

# Anexo A — Tailscale vs Relay: comparação aprofundada (foco em segurança)

Comparação pedida entre a **opção 1 (Tailscale)** e a **opção 4 (relay MQTT/WebSocket)**.
Premissa de ouro: **transporte seguro não é autorização.** Em qualquer das opções, o PC precisa de token + PIN próprios. Transporte protege o *caminho*; o bridge protege o *comando*.

## A.1 Opção 1 — Tailscale (WireGuard P2P + DERP)

**Como funciona:** cada dispositivo recebe um IP na faixa `100.64.0.0/10`. O plano de dados é WireGuard (handshake Noise IK), criptografia ponta a ponta entre os dois dispositivos. Um servidor de coordenação (control plane) distribui chaves públicas e ACLs — **ele não vê o tráfego**. Quando o hole punching UDP falha (CGNAT agressivo, como o seu), os pacotes já cifrados passam por um relay DERP, que também não consegue descriptografar.

### Prós

| Ganho | Detalhe |
|---|---|
| P2P real | Conexão direta UDP quando o NAT permite; na LAN é direto e local (latência ~1 ms) |
| Funciona em CGNAT | Hole punching + fallback DERP — seu caso |
| E2E por padrão | WireGuard entre os dispositivos; relay cego |
| Zero código de rede | Você escreve app e bridge, não protocolo de transporte |
| Identidade forte | SSO + 2FA, device approval, key expiry, tailnet lock |
| ACL por porta | O celular só alcança `tag:pc:5000`; nada de SSH, Ollama ou 11434 |
| Nome estável + TLS | MagicDNS (`pc.tailnet.ts.net`) e `tailscale cert` → HTTPS real, habilita Web Push |
| Multi-dispositivo | Notebook, celular, tablet, sem trabalho extra |
| Grátis no plano pessoal | Uso pessoal não paga |

### Contras

| Custo | Detalhe |
|---|---|
| Terceiro no controle da *identidade* | Conta Tailscale comprometida = atacante pode tentar entrar na tailnet |
| Metadados visíveis | O control plane sabe quais dispositivos existem e quando se conectam (não o conteúdo) |
| App VPN no Android | Android só permite **uma VPN ativa** — conflita com VPN corporativa |
| Bateria | VPN persistente consome bateria; mitigável com conexão sob demanda |
| Sem P2P garantido | No seu CGNAT pode cair em DERP: +30–80 ms e dependência do relay da Tailscale |
| Lock-in suave | Migrar depois = Headscale (mesmo protocolo, servidor próprio) |

### Ameaças e mitigações

| Ameaça | Mitigação |
|---|---|
| Control plane comprometido adiciona device hostil | ACL restritiva por porta + **device approval** + **tailnet lock** + token do bridge (o atacante na tailnet ainda precisa do token) |
| Dispositivo perdido/roubado | Key expiry 90 dias, revogar device no admin, PIN nas ações destrutivas |
| Relay DERP lê tráfego | Impossível por design (WireGuard E2E); conferível com captura |
| IP do PC exposto a outros peers da tailnet | ACL `src: tag:phone` → `dst: tag:pc:5000` (allowlist, não "todos") |
| Token vazando do celular | Token por dispositivo, revogável, só hash no PC, DataStore+Tink |

## A.2 Opção 4 — Relay MQTT/WebSocket (não é P2P, é ponte)

**Como funciona:** PC e celular abrem conexão **de saída** para um broker/relay (VPS ou serviço público). O PC assina `cyberbot/cmd/#` e publica em `cyberbot/events`. O relay roteia por tópico ou por `to`. **O relay vê o payload em claro, a menos que você cifre na aplicação.**

### Prós

| Ganho | Detalhe |
|---|---|
| Funciona sempre | Atravessa CGNAT, NAT simétrico, firewall — sem traversal nenhum |
| Sem VPN no celular | Não conflita com outras VPNs; nada para o usuário ativar |
| Push e eventos nativos | Stream contínuo de eventos é o modelo natural do MQTT/WS |
| Multi-dispositivo e multi-PC fácil | Tópicos por device; fan-out trivial |
| Debug simples | `mosquitto_sub` mostra tudo; logs claros |
| Baixo consumo | Conexão persistente única, sem handshake por request |
| Controle total | Você é dono do relay (se self-hosted) |

### Contras

| Custo | Detalhe |
|---|---|
| **Não é P2P** | Todo tráfego passa pelo relay, inclusive quando celular e PC estão no mesmo Wi-Fi |
| Segurança 100% sua responsabilidade | Sem E2E de aplicação, o broker lê e pode **alterar** comandos ("bloquear o PC", "rodar cleanup") |
| Você reimplementa um protocolo | Correlação request/response, ordenação, reconexão, anti-replay, chunking de arquivo, presença |
| Sem acesso LAN rápido | Latência = RTT até o VPS em toda ação (20–60 ms), mesmo em casa |
| Relay é SPOF | VPS cai → tudo para; sem fila offline (ou você constrói uma) |
| Broker público = jamais | `test.mosquitto.org`, HiveMQ público etc. expõem seus comandos ao mundo |
| Limites de payload | MQTT não é feito para arquivos; precisa chunking e retomada |
| Custo mensal | VPS (~US$5) ou serviço gerenciado |

### Ameaças e mitigações

| Ameaça | Mitigação obrigatória |
|---|---|
| Broker lê comandos (inclui `shutdown`, cleanup) | **Cifrar na aplicação**: X25519 + HKDF + AES-256-GCM (ou libsodium sealed box). Sem isso, a opção é inaceitável |
| Broker forja/replay de comando | Counter monotônico + janela de timestamp + assinatura Ed25519 |
| Broker comprometido (root no VPS) | Não decifra (E2E), mas pode fazer DoS e correlação de metadados |
| Credencial do broker vazada | Credencial por dispositivo, ACL por tópico, TLS obrigatório, rotação |
| Replay de pacote capturado | Counter + `ts` ±120 s + cache dos últimos counters |
| Arquivo grande derruba o broker | Chunking 256 KiB + hash + retomada por offset |
| Terceiro entre celular e relay | `wss://` com certificado válido + pinning opcional |

## A.3 Veredito para o seu caso

| Critério | Tailscale | Relay E2E |
|---|---|---|
| Atende "PC e celular ligados ⇒ funciona"? | Sim | Sim |
| É P2P de verdade | **Sim** | Não |
| Criptografia ponta a ponta | Sim, nativa | Sim, **se você implementar** |
| Esforço de implementação | Baixo | Alto |
| Superfície de erro de segurança | Pequena (primitivas prontas) | Grande (você escreve o protocolo) |
| Independência de terceiros | Média (control plane; Headscale resolve) | Alta |
| Latência | Direta ou DERP 30–80 ms | VPS 20–60 ms sempre |
| Conflito de VPN no Android | Sim | Não |

**Recomendação:** Tailscale agora (entrega o objetivo em dias, com segurança superior à média e menos código seu), e — se a dependência incomodar — Headscale no VPS depois, **sem mudar o app** (mesmo protocolo, muda só a URL do coordination server). Relay E2E só se "nenhum terceiro no controle de identidade" for requisito duro; nesse caso o relay precisa de cifragem na aplicação, senão ele vira o elo mais fraco.

## A.4 Independente da escolha: o que não muda

1. Token Bearer por dispositivo (hash no PC) + PIN para ações destrutivas.
2. Bind restrito à tailnet/LAN + allowlist de IP de origem; nunca `0.0.0.0` público.
3. Lista branca de comandos (`pc_apps.py`); nunca shell arbitrário vindo do app.
4. Senha sudo **nunca** trafega pelo app.
5. Tudo logado em `logs/access_*.jsonl` com `device_id`, rota e IP.

O contrato detalhado do backend e o projeto do app nativo estão em `copilot.md`.


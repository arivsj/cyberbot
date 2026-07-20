# CyberBot — Guia para Agentes

## Visão Geral

Assistente pessoal composto por:
- **Telegram Bot** (`bot.py`) — chat com IA local via Ollama + funcionalidades (finanças, drive, YouTube, segurança, etc.)
- **API Flask** (`server.py`) — REST API que gerencia o bot e expõe dados para o desktop
- **Desktop Electron** (`desktop/`) — dashboard cyberpunk

## Stack

- Python 3.10+, Flask, python-telegram-bot, httpx, yt-dlp, sqlite3
- Node.js 18+, Electron
- Ollama (IA local)
- Banco: SQLite (`financas.db` — ignorado pelo git)

## Estrutura

```
├── bot.py                    # Telegram bot
├── server.py                 # API Flask (backend principal)
├── db.py                     # SQLite (transações, drive, projetos, whitelist, etc.)
├── security.py               # 6 verificações de segurança Linux
├── sysmon.py                 # Monitor de CPU/RAM/GPU/temperatura
├── log.py                    # Logging em JSONL
├── ollama_utils.py           # Otimização de contexto Ollama por VRAM
├── youtube.py                # Download YouTube (yt-dlp)
├── trilha_rede.py            # Geração de código com opencode
├── requirements.txt
├── desktop/
│   ├── main.js               # Electron main process
│   ├── preload.js             # Ponte segura renderer ↔ main
│   ├── package.json
│   └── renderer/
│       ├── index.html
│       ├── app.js            # Lógica do frontend
│       └── style.css          # Tema cyberpunk
└── AGENTS.md
```

## Como Rodar

```bash
# Servidor (sobe API + bot + verifica Ollama)
python3 server.py

# Ou só o bot
TELEGRAM_TOKEN="seu_token" python3 bot.py

# Desktop
cd desktop && npm start
```

## Dados Sensíveis — NÃO COMMITAR

- **Token do Telegram** — lido de `os.environ.get("TELEGRAM_TOKEN")` com fallback para `.env`
- `.env` — nunca commitar (está no `.gitignore`)
- `financas.db` — banco local ignorado
- `drive_files/` — arquivos enviados pelo bot, ignorados
- `logs/` — logs de acesso, ignorados
- `trilha_projetos/` — projetos gerados localmente, ignorados
- `desktop/node_modules/` — dependências, ignoradas

## API REST (Flask — localhost:5000)

| Rota | Método | Descrição |
|------|--------|-----------|
| `/api/status` | GET | Status do bot, Ollama, contexto |
| `/api/bot/start\|stop` | POST | Controla o bot |
| `/api/bot/send` | POST | Envia mensagem pelo bot |
| `/api/financas` | GET/POST | Lista/cria transações |
| `/api/financas/resumo` | GET | Resumo com categorias, contas, diário |
| `/api/drive/folders` | GET/POST | Pastas do drive |
| `/api/drive/files` | GET | Arquivos do drive |
| `/api/drive/stats` | GET | Estatísticas do drive |
| `/api/trilha/projects` | GET | Projetos gerados |
| `/api/security/run` | GET | Varredura completa de segurança |
| `/api/security/report` | GET | Varredura + relatório com IA |
| `/api/security/<check>` | GET | Verificação individual |
| `/api/reports` | GET | Relatórios de segurança salvos |
| `/api/sysmon` | GET | Monitor do sistema |
| `/api/sysmon/history` | GET | Histórico do monitor |
| `/api/whitelist` | GET/POST | Lista/adição de permissão |
| `/api/logs/*` | GET | Logs de acesso e estatísticas |
| `/api/models` | GET | Modelos disponíveis no Ollama |
| `/api/model` | GET/POST | Modelo ativo |
| `/api/context` | GET/POST | Informações de contexto |
| `/api/cache/clear` | POST | Limpa cache e reinicia bot |

## Bot Telegram — Comandos

| Comando | Descrição |
|---------|-----------|
| `/start` | Mensagem inicial |
| `/menu` | Menu principal com todas as funcionalidades |
| `/clear` | Limpa histórico da conversa |
| `/model <nome>` | Troca modelo do Ollama |
| `/resumo <YYYY-MM>` | Resumo financeiro |
| `/codigo <descrição>` | Gera projeto com opencode |
| `/sysmon` | Monitor do sistema |
| `/cancel` | Cancela operação atual |

## Convenções de Código

- **Python**: sem comentários supérfluos, snake_case, type hints quando aplicável
- **JavaScript**: sem comentários supérfluos, camelCase, async/await
- **CSS**: tema cyberpunk com variáveis CSS (`--neon`, `--bg`, `--surface`, etc.)
- **Markdown** no bot: usar `parse_mode="Markdown"`
- **Banco**: todas as queries via `db.py` (conexão WAL)
- **Logs**: toda interação do bot passar por `_log_update()`

## Desktop — Módulos

- **Dashboard**: status, finanças, drive, usuários, logs, whitelist
- **Finanças**: gráficos por categoria/conta com filtro de mês
- **Drive**: navegador de pastas com breadcrumbs
- **Trilha Rede**: lista projetos (clique → abre pasta)
- **Security**: cards individuais, gráfico, relatório com IA, histórico
- **Sidebar**: monitor do sistema (CPU, RAM, GPU)
- **Botão**: iniciar/parar bot
- **Modais**: trocar modelo, enviar mensagem, relatório, bloqueados

# CyberBot

Um assistente pessoal integrado com **Telegram Bot + Ollama (IA local)** + **Dashboard Desktop (Electron)** com visual cyberpunk.

## Funcionalidades

### 🤖 Bot do Telegram

- **Chat com IA** — Converse com modelos locais do Ollama (texto, imagens e áudios)
- **💰 Finanças** — Registre despesas com categoria/conta/valor, veja resumos e gráficos por mês
- **📁 Drive** — Gerencie arquivos e pastas: upload, download, organização hierárquica
- **🎬 YouTube** — Baixe vídeos (MP4) ou áudios (MP3) e salve diretamente no Drive
- **📱 PC Apps** — Abra aplicativos no computador (VS Code, YouTube, Max, etc.), bloqueie a tela, agende desligamento
- **🛡️ Security** — Varredura de segurança do servidor Linux (6 verificações) com relatório gerado por IA
- **🧠 Trilha Rede** — Gere projetos completos com IA: melhora o prompt e usa opencode para criar o código
- **🖥 Monitor do Sistema** — CPU, RAM, GPU, temperatura via comando `/sysmon`
- **🔒 Whitelist** — Controle de acesso por lista de permissão
- **🔄 Troca de modelo** — Alterne entre modelos do Ollama dinamicamente

### 🖥 Dashboard Desktop (Electron)

- Interface cyberpunk com tema neon escuro
- **Dashboard** com status do Bot, Ollama, contexto, finanças, drive, usuários, logs
- **💰 Finanças** — Gráficos de despesas por categoria/conta, tabela de transações, filtro por mês
- **📁 Drive** — Navegador de pastas com breadcrumbs, clique para abrir arquivo no gerenciador
- **🧠 Trilha Rede** — Lista de projetos gerados, clique para abrir a pasta
- **🛡️ Security** — Cards individuais por verificação, botão "Rodar Todos", relatório com IA, histórico de relatórios
- **📡 Logs** — Visualizador de logs com seletor de data, gráfico por hora, acesso bloqueados
- **👥 Usuários** — Lista de usuários do Telegram com opção de enviar mensagem
- **🔒 Whitelist** — Gerenciamento visual da lista de permissão
- **🖥 Monitor** — CPU, RAM, GPU, temperaturas na sidebar com atualização automática
- **🤖 Controle do Bot** — Iniciar/parar o bot diretamente da interface
- **🔄 Cache automático** — Limpeza de cache a cada 30 min

### 🧩 Módulos Compartilhados

- `server.py` — API REST em Flask que gerencia bot, finanças, drive, trilha, segurança, logs e mais
- `db.py` — Banco SQLite com tabelas para transações, pastas, arquivos, projetos, whitelist, configurações, relatórios e monitor
- `security.py` — 6 verificações de segurança Linux: conexões, SSH, integridade de arquivos, persistência, processos e portas
- `sysmon.py` — Coleta de CPU, temperatura, RAM e GPU (NVIDIA)
- `log.py` — Logging em JSONL com estatísticas por hora/tipo
- `ollama_utils.py` — Otimização de contexto baseada na VRAM disponível
- `youtube.py` — Download de vídeos/áudios do YouTube via yt-dlp
- `trilha_rede.py` — Melhoria de prompt com IA + geração de código via opencode

## Estrutura

```
CyberBot/
├── bot.py              # Bot do Telegram
├── server.py           # API Flask (backend)
├── db.py               # Banco de dados SQLite
├── security.py         # Verificações de segurança
├── sysmon.py           # Monitor do sistema
├── log.py              # Sistema de logs
├── ollama_utils.py     # Utilitários Ollama
├── youtube.py          # Download YouTube
├── trilha_rede.py      # Geração de projetos com IA
├── requirements.txt
├── desktop/
│   ├── main.js         # Electron main process
│   ├── preload.js      # Preload seguro
│   ├── package.json
│   ├── renderer/
│   │   ├── index.html
│   │   ├── app.js
│   │   └── style.css
│   └── assets/img/
├── drive_files/        # Arquivos enviados pelo bot
├── trilha_projetos/    # Projetos gerados
└── logs/               # Logs de acesso JSONL
```

## Requisitos

- Python 3.10+
- [Ollama](https://ollama.ai) rodando com modelos baixados
- Node.js 18+ (para o desktop)
- ffmpeg (para áudios)

### Python

```bash
pip install -r requirements.txt
```

### Desktop

```bash
cd desktop && npm install
```

## Como usar

### 1. Iniciar o servidor

```bash
python3 server.py
```

O servidor inicia automaticamente o Ollama (se necessário), baixa o modelo padrão e sobe o bot.

### 2. Desktop

```bash
cd desktop && npm start
```

### 3. Bot avulso

```bash
TELEGRAM_TOKEN="seu_token" python3 bot.py
```

## Configuração

| Variável | Padrão | Descrição |
|----------|--------|-----------|
| `TELEGRAM_TOKEN` | (embutido) | Token do bot Telegram |
| `PORT` | `5000` | Porta do servidor Flask |

No Telegram, envie `/menu` para ver todas as funcionalidades disponíveis.

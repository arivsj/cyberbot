# DoGCyberAgent — Tela de bloqueio Matrix

Terceira leva de mudancas no app Android (`/home/aridev/AndroidStudioProjects/DoGCyberAgent`).
Aplicada com `bash android_patch/apply.sh`.

## O que entrou

### Tela de bloqueio (nada e montado antes de autenticar)
- `ui/lock/MatrixRain.kt` — motor da chuva de caracteres. As colunas ficam fora do
  Compose e o desenho le o tempo de um State: redesenha a cada quadro sem recompor.
- `ui/lock/ContagemGlifos.kt` — contagem regressiva desenhada com os mesmos glifos.
- `ui/lock/LockScreen.kt` — a tela: chuva, cartao central e estados.
- `ui/lock/LockViewModel.kt` — maquina de estados e persistencia do bloqueio.
- `core/security/AutenticacaoBiometrica.kt` — biometria ou senha do aparelho.

**Fluxo:** ao abrir (e ao voltar do segundo plano) aparece a chuva com um cartao
**RESTRICTED ACCESS**. Tocar abre o prompt do sistema (biometria ou PIN). Ao bater no
cartao, os caracteres param e soletram palavras de aviso na vertical (GO AWAY, DANGER,
GET OUT, STAY BACK, DENIED, TURN BACK). Errando, o cartao fica vermelho e uma contagem
regressiva de 30 s se forma no topo com os glifos que caem. Passando os 30 s sem entrar,
bloqueio de 1 hora e o cartao vermelho passa a dizer **USER BLOCKED** — sem informar
quanto tempo falta. Ao entrar, a chuva some do centro para as pontas e os cartoes
surgem de lados diferentes ate formar a Home.

### Incidente no PC, no Telegram e nas telas de Seguranca
- `db.py`: tabela `security_incidents` + `save_incident` / `list_incidents`.
- `server.py`: `POST /api/security/incident` (grava e avisa no Telegram para todos da
  whitelist) e `GET /api/security/incidents`.
- App: card "Incidentes de acesso" na tela Security.
- Desktop: mesmo painel na aba Security.

**Atencao:** o servidor precisa ser reiniciado para as rotas novas existirem. Enquanto
o processo antigo estiver na porta 5000, `/api/security/incidents` cai na rota
generica `/api/security/<check>` e responde "Verificacao desconhecida".

## Verificacao (medida, nao impressao)

- Palavra formada no cartao: mapa ASCII da faixa mostrou **S T A Y   B A C K** na
  coluna exata registrada pelo log (x=784).
- Contagem: matriz de celulas decodificada contra a tabela de digitos do codigo
  (`1111,0001,0001,1111,1000,1000,1111` = 2 e `1111,0001,0001,1111,0001,0001,1111` = 3)
  -> a tela mostrava **23**, coerente com 30 s menos o tempo decorrido.
- Bloqueio: cartao vermelho com **USER BLOCKED / ACCESS SUSPENDED / TRY AGAIN LATER**,
  sem mencao de tempo, e a chuva ainda formando palavras (o recorte pegou GET OUT).
- Desbloqueio com o PIN do aparelho levou a Home; voltar do segundo plano volta a pedir.
- Incidente: `POST` gravou e o `GET` devolveu a lista; a tela Security do app mostrou
  os registros.

## Nao foi tocado

GPU/VRAM, chat com IA, upload, transporte Iroh/rede e o fluxo de pareamento.

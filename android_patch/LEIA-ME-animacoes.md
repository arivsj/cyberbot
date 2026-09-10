# DoGCyberAgent — Animacoes de toque, tipografia e design

Segunda leva de mudancas no app Android (`/home/aridev/AndroidStudioProjects/DoGCyberAgent`).
Aplicada com `bash android_patch/apply.sh` (copia tudo de `android_patch/files/`).

## O que entrou

### 1. Fontes novas (tech / cyberpunk)
Tres familias embutidas em `app/src/main/res/font/` — instancias estaticas, nao variaveis,
para os pesos funcionarem tambem em API 26/27:

| Fonte | Papel | Licenca |
|---|---|---|
| **Orbitron** Medium/Bold | titulos de tela e marca | OFL |
| **Rajdhani** Medium/SemiBold/Bold | rotulos de secao, botoes, menu inferior | OFL |
| **JetBrains Mono** Regular/Medium/Bold | corpo de texto, dados e valores | OFL |

Definidas em `ui/theme/Type.kt`. Antes: SansSerif + Monospace genericos.

### 2. Animacao de toque (`ui/common/CyberInteractions.kt` — novo)
- `Modifier.cyberPress`: encolhe com mola enquanto o dedo esta pressionado. A leitura do
  estado fica dentro do `graphicsLayer`, entao redesenha a camada sem recompor o conteudo.
- `CyberButton`, `CyberTextButton`, `CyberOutlinedButton`, `CyberIconButton`, `CyberFab`:
  substitutos diretos dos componentes do Material (mesmos parametros), com contorno neon.
- `PressableCyberCard`: cartao clicavel que encolhe (0,97), acende a borda em neon e
  clareia o fundo. Sem onda de toque por cima do texto.

**Sem vibracao do aparelho** — nenhum `HapticFeedback` no app.

### 3. Design
- `ui/common/CyberWidgets.kt` (novo): `StatRow` (rotulo a esquerda / valor neon a direita /
  divisoria fina) e `EmptyState` (icone + texto). `corPorUso()` colore CPU/RAM/GPU/temp
  por faixa.
- `ui/theme/Theme.kt`: escala unica de cantos (`Shapes`). Antes o app misturava 12dp nos
  cartoes, 8dp nos baloes do chat e 18dp na barra inferior.
- Status/Telemetria: 16 linhas soltas de texto viraram `StatRow`.
- Menu inferior: rotulo em Rajdhani 11sp.
- `ui/nav/CyberBotNav.kt`: transicao de tela com deslize curto + fade.
- FAB do chat: era azul Material fixo `#1E88E5`, agora neon do tema.

## Verificacao

- `assembleDebug` e `testDebugUnitTest` passando.
- Toque medido pixel a pixel com o app rodando no emulador: cartao 312 -> 303 px de largura
  (escala 0,97), borda `(42,42,90)` -> `(3,233,229)` neon, fundo Surface -> Surface2, e
  volta ao repouso ao soltar.
- 17 telas recapturadas; nenhum texto cortado ou sobreposto.

## Arquivos tocados

- **novos**: `ui/common/CyberInteractions.kt`, `ui/common/CyberWidgets.kt`,
  `res/font/*.ttf` (8 arquivos)
- **reescritos**: `ui/theme/Type.kt`, `ui/theme/Theme.kt`
- **botoes trocados**: 18 telas (61 substituicoes)
- **cartoes clicaveis**: `home/HomeScreen.kt`, `drive/DriveScreen.kt`, `model/ModelScreen.kt`
- **design**: `dashboard/DashboardScreen.kt`, `finance/FinanceScreen.kt`,
  `trilha/TrilhaScreen.kt`, `plugins/PluginsScreen.kt`, `nav/CyberBotNav.kt`,
  `nav/CyberBottomBar.kt`, `chat/ChatScreen.kt`

## Nao foi tocado

GPU/VRAM (`sysmon.py`), chat com IA, upload de arquivos, transporte Iroh/rede, seguranca
e fluxo de pareamento. Tudo aqui e camada visual.

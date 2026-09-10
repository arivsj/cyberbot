# DoGCyberAgent — Animacoes das paginas do menu inferior

Quarta leva. Aplicada com `bash android_patch/apply.sh`.

## O que entrou

### Motor da chuva agora e compartilhado
`ui/lock/MatrixRain.kt` e `ContagemGlifos.kt` sairam para **`ui/matrix/`**, junto com o
novo `MatrixFundo.kt` — um pano de fundo reaproveitavel (so a chuva, sem cartao nem
contagem). A tela de bloqueio passou a importar de la.

### Chat — chuva como fundo permanente
`MatrixFundo(opacidade = 0.5f)` atras do conteudo, em verde Matrix. As bolhas de
mensagem ficaram com `surface` a 82% de opacidade para a chuva aparecer por tras sem
atrapalhar a leitura.

### PC Apps — loading de invasao
`ui/pcapps/InvasaoLoading.kt`: painel com titulo que sofre glitch, barra de progresso,
6 etapas que vao sendo marcadas (`[ ok ]`, `[ .. ]`, `[    ]`) e uma linha de
hexadecimal embaralhado. Aparece tanto ao abrir a tela quanto ao tocar em
"Recarregar lista" — e o mesmo `isLoading`.

O `PcAppsViewModel` ganhou um piso de `DURACAO_MINIMA_LOADING_MS = 1600` para a
animacao ser percebida mesmo quando a rede responde na hora.

### Status — preenchimento tipo terminal
`StatRow` ganhou `atrasoMs`: a linha entra depois do atraso e o **valor e digitado
caractere a caractere com cursor**. Novo helper `RevelarTerminal(atrasoMs)` para
revelar qualquer conteudo (usado tambem nos rotulos de secao e nos botoes).

No `DashboardScreen` o atraso e **global na tela** (`atrasoDaLinha`): os cartoes
aparecem vazios e o conteudo se preenche de cima para baixo, 95ms por linha.

## Verificacao (gravada e medida)

- **Chat**: chuva verde confirmada no fundo, atras das bolhas, bolhas legiveis.
- **PC Apps (carregando)**: quadro a 48% com `INVASAO EM CURSO`, `[ ok ] resolvendo
  host`, `[ ok ] varrendo portas`, `[ .. ] contornando firewall`, `[    ] lendo
  processos` e a linha `8078 CE96 F008 D4D5 481D F1AF D828 EA3A`.
- **PC Apps (atualizar)**: gravacao mostrou o verde subindo de 616 a 2893 pixels
  (etapas completando) e o ciano de 4110 a 8535 (barra), voltando a lista depois.
- **Status**: gravacao a 10fps mostrou o texto claro subindo de **0 a 850 pixels** e
  estabilizando — o preenchimento de cima para baixo.

## Nao foi tocado

GPU/VRAM, chat com IA (logica), upload, transporte Iroh/rede, seguranca e pareamento.
A chuva no chat roda enquanto a tela esta visivel; se incomodar a bateria, e so baixar
a opacidade ou desligar o quadro.

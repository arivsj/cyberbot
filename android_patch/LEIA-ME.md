# DoGCyberAgent — QR Code no pareamento + sair da conexão

Patch pronto para aplicar no app Android (`/home/aridev/AndroidStudioProjects/DoGCyberAgent`).

## O que entra

1. **Ler QR Code** — botão novo na tela de pareamento. Abre a câmera (CameraX + ZXing,
   sem depender do Google Play Services), lê o QR gerado no desktop e já pareia:
   código de 6 dígitos + endereço do PC + ticket Iroh vêm todos no QR.
2. **Sair da conexão (desparear)** — card "Conexão" no Hub, com confirmação. Apaga token
   e device ID e o app volta sozinho para a tela de pareamento (dá para parear de novo).
3. **Iroh mais resiliente no 4G**
   - o app reaproveita a conexão QUIC (antes abria uma conexão nova a cada requisição);
   - se cair, descarta o cache e tenta uma vez de novo;
   - se não houver ticket, conecta pelo EndpointId (descoberta do Iroh);
   - no Wi-Fi o caminho continua o de sempre; no celular o Iroh é tentado primeiro
     (antes o app gastava segundos tentando endereços que só existem em casa).

## Como aplicar

```bash
bash android_patch/apply.sh
```

O script guarda uma cópia dos arquivos originais em
`<projeto>/.backup-cyberbot-<data>` antes de substituir.

Depois, compile:

```bash
cd /home/aridev/AndroidStudioProjects/DoGCyberAgent
JAVA_HOME=/home/aridev/programs/android-studio/jbr ./gradlew assembleDebug
```

Ou simplesmente abra o Android Studio e rode **Build ▸ Make Project**.

### APK já compilado (opcional)

O patch foi compilado numa cópia do projeto dentro deste repositório, com a sua própria
debug keystore (SHA-256 `972d2b9a…725d`), então dá para instalar por cima da versão atual:

```
state/android_build/app/build/outputs/apk/debug/app-debug.apk
```

O build de verificação terminou com `BUILD SUCCESSFUL` (assembleDebug + testDebugUnitTest).

## Arquivos tocados

| Arquivo | Mudança |
|---|---|
| `gradle/libs.versions.toml` | versões CameraX 1.4.2 e ZXing 3.5.3 |
| `app/build.gradle.kts` | dependências camera-core/camera2/lifecycle/view + zxing core |
| `app/src/main/AndroidManifest.xml` | permissão de câmera (opcional, sem exigir hardware) |
| `core/model/PairingPayload.kt` | **novo** — parser do `cyberbot://pair?...` |
| `ui/pairing/QrScannerScreen.kt` | **novo** — tela da câmera com leitura de QR |
| `ui/pairing/PairingScreen.kt` | botão "Ler QR Code", aplica o payload e pareia |
| `ui/settings/SettingsScreen.kt` | card Conexão + "Sair da conexão (desparear)" |
| `core/storage/SettingsStorage.kt` | `clearPairing()` |
| `data/repo/Repositories.kt` | `unpair()` e `saveIrohHint()` |
| `core/transport/IrohTransport.kt` | reuso de conexão, retry e fallback por EndpointId |
| `core/transport/Transport.kt` | escolha do transporte ciente da rede (Wi-Fi × celular) |
| `ui/pairing/QrScannerScreen.kt` | dispara **um único** pareamento por leitura (compareAndSet) |
| `data/repo/Repositories.kt` | `prepareFromQr()`: guarda **todos** os endereços do QR como candidatos |
| `app/src/test/.../PairingPayloadTest.kt` | **novo** — 4 testes do parser (já passando) |

## Formato do QR Code

```
cyberbot://pair?v=1&c=<código 6 dígitos>&n=<nome do PC>&t=<ticket iroh>&i=<endpoint id>&d=<url LAN>
```

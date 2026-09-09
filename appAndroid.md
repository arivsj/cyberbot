# appAndroid.md — Parte 2: App Android nativo (Kotlin)

> **Instruções de implementação para o agente que vai codar o app.**
> Complemento obrigatório: **Parte 1** (`p2pAndroi.md`) — define o PC, o protocolo e a API `/api/m/*`.
> Transporte: **Iroh 1.1** via AAR oficial. Compose + MVVM/UDF + Clean Architecture + Hilt + StateFlow.

---

## 0. Escopo e premissas

**O que o app é:** cliente Android nativo que fala QUIC com o PC via Iroh e comanda o CyberBot (status, bot, chat IA, PC Apps, drive, finanças, security, cleanup, eventos).

**O que o app NÃO é:** não fala HTTP com o PC, não usa VPN, não passa por servidor de terceiros, não tem backend próprio.

**"MCCM"**: interpretado como **MVVM com fluxo de dados unidirecional (UDF)** — Model → ViewModel (StateFlow) → View (Compose), e View → Evento/Intent → ViewModel. Se a intenção era MVI, a estrutura já está pronta: basta renomear `Event` → `Intent`; o resto (estado imutável + reducer + StateFlow) é idêntico.

**Fatos verificados que você deve usar:**

| Fato | Consequência |
|---|---|
| `computer.iroh:iroh-android:1.1.0` está no **Maven Central** | **Não precisa de Rust, NDK ou cargo** — só a dependência Gradle |
| O AAR traz `libiroh_ffi.so` para `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` (54 MB no total) | Use `abiFilters` para não inflar o APK |
| AAR `minSdk = 24` | App com `minSdk 26` é compatível |
| Traz transitivamente `computer.iroh:iroh` + `net.java.dev.jna:jna:5.15.0@aar` + coroutines | Não adicione JNA manualmente (duplicaria classes) |
| `IrohAndroid.installAndroidContext(context)` é **obrigatório** antes de qualquer `Endpoint` | Chamar em `Application.onCreate()` |
| No binding Kotlin, `Endpoint.close()` foi renomeado para **`shutdown()`** | Não chame `close()` |
| `RecvStream.readExact(n)` / `read(sizeLimit)` / `readToEnd(sizeLimit)`; `SendStream.writeAll(bytes)` / `finish()` | Framing com prefixo de 4 bytes usa `readExact` |
| `Connection.remoteId()`, `stats()`, `paths()` | Mostrar caminho (direto/relay) e latência na UI |

---

## 1. Stack e bibliotecas

**Base:** Kotlin 2.x · JDK toolchain 17 · AGP 8.x · `compileSdk`/`targetSdk` = último estável · `minSdk 26` · Gradle Kotlin DSL + version catalog.

> Não fixe versões "de memória": no dia da implementação consulte a estável atual e registre no `libs.versions.toml`. As coordenadas abaixo são as corretas; as versões são o que você deve verificar.

### `gradle/libs.versions.toml` (modelo)

```toml
[versions]
kotlin = "2.x"
agp = "8.x"
composeBom = "2025.xx.xx"
hilt = "2.5x"
coroutines = "1.9+"
serialization = "1.7+"
iroh = "1.1.0"
datastore = "1.1+"
tink = "1.15+"
biometric = "1.1+"
camerax = "1.4+"
mlkitBarcode = "17.3+"
coil = "3.x"
work = "2.10+"
navigation = "2.8+"
lifecycle = "2.8+"
timber = "5.0.1"

[libraries]
# Iroh — transporte P2P (AAR com .so por ABI + JNA AAR + coroutines)
iroh-android = { module = "computer.iroh:iroh-android", version.ref = "iroh" }

# Compose
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material-icons = { module = "androidx.compose.material:material-icons-extended" }
activity-compose = { module = "androidx.activity:activity-compose", version = "1.9+" }
navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }

# Hilt
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version = "1.2+" }
hilt-work = { module = "androidx.hilt:hilt-work", version = "1.2+" }

# Coroutines / serializacao
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }

# Armazenamento seguro
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
tink-android = { module = "com.google.crypto.tink:tink-android", version.ref = "tink" }

# Seguranca / permissoes
biometric = { module = "androidx.biometric:biometric", version.ref = "biometric" }

# QR (pareamento)
camera-core = { module = "androidx.camera:camera-core", version.ref = "camerax" }
camera-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "camerax" }
camera-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camerax" }
camera-view = { module = "androidx.camera:camera-view", version.ref = "camerax" }
mlkit-barcode = { module = "com.google.mlkit:barcode-scanning", version.ref = "mlkitBarcode" }

# Imagens / background
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
work-runtime = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }

# Observabilidade
timber = { module = "com.jakewharton.timber:timber", version.ref = "timber" }
```

### `app/build.gradle.kts` (pontos críticos)

```kotlin
android {
    namespace = "com.cyberbot.mobile"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        targetSdk = 36
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }   // x86_64 = emulador
    }
    buildFeatures { compose = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { applicationIdSuffix = ".debug" }
    }
}

dependencies {
    implementation(libs.iroh.android)      // unico necessario para o P2P
    implementation(platform(libs.compose.bom))
    // ... demais dependencias do catalogo
}
```

### `proguard-rules.pro` (obrigatório — JNA + uniffi)

```proguard
# JNA
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keepclassmembers class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**

# Bindings iroh (uniffi gera classes chamadas por JNI/JNA)
-keep class computer.iroh.** { *; }
-keep class computer.iroh.IrohAndroid { *; }
-keepclassmembers class computer.iroh.** { *; }
```

> **Valide com build de release de verdade.** Falha de R8 aqui só aparece em release — rode `assembleRelease` + teste de fumaça no device/emulador antes de considerar pronto.

---

## 2. Arquitetura (Clean Architecture + MVVM/UDF)

### 2.1 Camadas e regra de dependência

```
presentation  ---> domain <--- data
   (Compose,        (models,      (repos impl,
    ViewModel,       interfaces,   transport Iroh,
    UiState)         use cases)    DTOs, stores)
```

- **`domain`** não conhece Android, Compose, Iroh nem JSON. Só Kotlin puro.
- **`data`** implementa as interfaces do `domain`. Toda a sujeira (Iroh, serialização, DataStore, Tink) mora aqui.
- **`presentation`** só fala com `domain` (use cases) e nunca com `data` diretamente.

### 2.2 Estrutura de pacotes (módulo único `:app`)

> Módulo único com fronteiras estritas é mais rápido e menos propenso a erro que multi-módulo nesta entrega. Se quiser multi-módulo depois, o mapeamento é: `core:model` ← `domain`, `core:transport` + `core:data` ← `data`, `feature:*` ← `presentation`.

```
app/src/main/java/com/cyberbot/mobile/
├── CyberBotApp.kt                      # @HiltAndroidApp + IrohAndroid.installAndroidContext
├── MainActivity.kt                     # setContent { CyberBotTheme { CyberBotNavHost() } }
├── di/
│   ├── AppModule.kt                    # DataStore, Tink, dispatchers, Json
│   ├── TransportModule.kt              # Endpoint, IrohTransport, repos
│   └── WorkModule.kt                   # HiltWorkerFactory
├── domain/
│   ├── model/                          # PcStatus, Sysmon, ChatMessage, DriveItem, FinancasResumo,
│   │                                   # SecurityCheck, CleanupTask, PcApp, DeviceInfo, TransportPath
│   ├── repository/                     # StatusRepository, ChatRepository, PcAppsRepository,
│   │                                   # DriveRepository, FinanceRepository, SecurityRepository,
│   │                                   # CleanupRepository, PairingRepository, DevicesRepository
│   └── usecase/                        # GetStatusUseCase, ToggleBotUseCase, SendChatUseCase,
│                                       # RunPcAppUseCase, UploadFileUseCase, PairUseCase, ...
├── data/
│   ├── transport/
│   │   ├── IrohTransport.kt            # conexao, framing, streams, reconexao
│   │   ├── IrohEndpointProvider.kt     # identidade persistente + bind do Endpoint
│   │   ├── Frame.kt                    # codec 4 bytes + JSON
│   │   ├── EventStream.kt              # assinatura de eventos + heartbeat
│   │   └── TransportError.kt           # sealed class de erros
│   ├── remote/dto/                     # @Serializable DTOs espelhando /api/m/*
│   ├── remote/MobileApi.kt             # request(method, path, query, headers, body)
│   ├── repository/                     # *RepositoryImpl
│   ├── local/
│   │   ├── SecureStore.kt              # Tink + Keystore (secret key Iroh, token)
│   │   ├── SettingsStore.kt            # DataStore (endpointId do PC, preferencias)
│   │   └── IdempotencyStore.kt         # chaves de idempotencia
│   └── mapper/                         # DTO <-> domain
├── presentation/
│   ├── theme/                          # cores cyberpunk, tipografia, shapes
│   ├── nav/                            # CyberBotNavHost, Rotas
│   ├── common/                         # UiState, ErrorBanner, PinDialog, BiometricGate
│   ├── pairing/                        # PairingScreen + ViewModel (codigo 6 digitos / QR)
│   ├── dashboard/                      # DashboardScreen + ViewModel
│   ├── chat/                           # ChatScreen + ViewModel
│   ├── pcapps/                         # PcAppsScreen + ViewModel
│   ├── drive/                          # DriveScreen + ViewModel
│   ├── finance/                        # FinanceScreen + ViewModel
│   ├── security/                       # SecurityScreen + ViewModel
│   ├── cleanup/                        # CleanupScreen + ViewModel
│   ├── devices/                        # DevicesScreen + ViewModel
│   └── settings/                       # SettingsScreen + ViewModel
├── service/
│   ├── EventStreamService.kt           # Foreground Service (dataSync) - opt-in
│   └── NotificationHelper.kt           # canais + notificacoes
└── work/
    └── CommandQueueWorker.kt           # fila offline (WorkManager) + idempotencia
```

### 2.3 Padrão de estado (UDF)

```kotlin
// presentation/common/UiState.kt
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Empty(val message: String) : UiState<Nothing>
    data class Error(val error: AppError) : UiState<Nothing>
}

// Erros que a UI sabe renderizar (mapeados de TransportError/HTTP)
sealed interface AppError {
    data object PcOffline : AppError
    data object Unpaired : AppError
    data object TokenRevoked : AppError
    data object PinRequired : AppError
    data class PinLocked(val retryAfterSec: Int) : AppError
    data object SudoRequired : AppError
    data class RateLimited(val retryAfterSec: Int) : AppError
    data class ProtocolMismatch(val minAppVersion: String) : AppError
    data class Unknown(val message: String) : AppError
}

// ViewModel - um unico fluxo de estado imutavel + efeitos one-shot
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getStatus: GetStatusUseCase,
    private val observeEvents: ObserveEventsUseCase,
    private val toggleBot: ToggleBotUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private val _effects = Channel<DashboardEffect>(Channel.BUFFERED)
    val effects: Flow<DashboardEffect> = _effects.receiveAsFlow()

    fun onEvent(event: DashboardEvent) { /* reduce -> _state.update { ... } */ }
}
```

Regras:
- Estado **imutável** (`data class`), atualizado só com `_state.update { it.copy(...) }`.
- ViewModel **nunca** expõe `MutableStateFlow`.
- Efeitos one-shot (navegar, snackbar) por `Channel`/`receiveAsFlow`, não dentro do estado.
- `StateFlow` + `collectAsStateWithLifecycle()` na UI.
- Use cases são `operator fun invoke(...)` com `suspend` ou `Flow`.
- Erro explícito (`AppError`): nunca deixe exceção crua chegar na UI.

---

## 3. Camada de transporte Iroh (o coração do app)

### 3.1 Inicialização (obrigatória)

```kotlin
@HiltAndroidApp
class CyberBotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        IrohAndroid.installAndroidContext(this)   // JNI/ndk_context - antes de qualquer Endpoint
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
    }
}
```

### 3.2 Identidade persistente

```kotlin
@Singleton
class IrohEndpointProvider @Inject constructor(
    private val secureStore: SecureStore,
) {
    private val mutex = Mutex()
    @Volatile private var endpoint: Endpoint? = null

    suspend fun endpoint(): Endpoint = mutex.withLock {
        endpoint?.let { return it }
        val secret = secureStore.irohSecretKey()          // 32 bytes; gera e guarda se ausente
        val ep = Endpoint.bind(
            EndpointOptions(
                secretKey = secret,                        // persistida: o EndpointId NUNCA muda
                alpns = listOf(ALPN_MAIN, ALPN_PAIR),
            )
        )
        endpoint = ep
        ep
    }

    suspend fun endpointId(): String = endpoint().id().toString()   // base32

    companion object {
        val ALPN_MAIN = "cyberbot/1".encodeToByteArray()
        val ALPN_PAIR = "cyberbot/pair/1".encodeToByteArray()
    }
}
```

**Regra de ouro:** a `SecretKey` é gerada **uma vez** (`SecretKey.generate().toBytes()`) e guardada cifrada. Se ela se perder, o EndpointId muda e o app precisa reparear. Nunca regenerar "para limpar estado".

### 3.3 Framing (espelha §4 da Parte 1)

```kotlin
private const val MAX_FRAME = 1 shl 20
private const val CHUNK = 64 * 1024

@Serializable
data class FrameRequest(
    val v: Int = 1,
    val id: String,
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: JsonElement? = null,
)

@Serializable
data class FrameResponse(
    val v: Int = 1,
    val id: String? = null,
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: JsonElement? = null,
)

private suspend fun SendStream.writeFrame(json: String) {
    val payload = json.encodeToByteArray()
    require(payload.size <= MAX_FRAME)
    val header = ByteBuffer.allocate(4).putInt(payload.size).array()
    writeAll(header + payload)
}

private suspend fun RecvStream.readFrame(): String {
    val header = readExact(4)
    val size = ByteBuffer.wrap(header).int
    if (size <= 0 || size > MAX_FRAME) throw TransportError.BadFrame(size)
    return readExact(size).decodeToString()
}
```

### 3.4 Chamada unária

```kotlin
@Singleton
class IrohTransport @Inject constructor(
    private val endpoints: IrohEndpointProvider,
    private val settings: SettingsStore,
    private val json: Json,
) {
    suspend fun call(req: FrameRequest): FrameResponse = withTimeout(120.seconds) {
        val ep = endpoints.endpoint()
        val pcAddr = settings.pcEndpointAddr() ?: throw TransportError.NotPaired
        val conn = ep.connect(pcAddr, ALPN_MAIN)          // QUIC + hole punching
        val bi = conn.openBi()
        bi.send().writeFrame(json.encodeToString(req))
        bi.send().finish()
        val resp = json.decodeFromString<FrameResponse>(bi.recv().readFrame())
        conn.close(0, "ok".encodeToByteArray())
        resp
    }
}
```

- Um `openBi()` por requisição (multiplexação do QUIC evita head-of-line blocking).
- Reutilize a `Connection` para rajadas (dashboard + sysmon) abrindo vários streams na mesma conexão.
- `stats()`/`paths()` alimentam o indicador de caminho/latência da UI.

### 3.5 Reconexão e mudança de rede

```kotlin
@Singleton
class ConnectivityWatcher @Inject constructor(@ApplicationContext ctx: Context) {
    fun changes(): Flow<Unit> = callbackFlow {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
        }
        cm.registerDefaultNetworkCallback(cb)
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.debounce(500).map { }
}
```

Backoff: 0,5 s → 1 s → 2 s → 4 s → 8 s → 30 s (máx.) com jitter de ±20%. Ao voltar a rede, reconectar imediatamente. O `Endpoint` fica vivo durante todo o processo — **não** faça `bind()` por requisição.

### 3.6 Pareamento

```kotlin
suspend fun pair(code: String, pcEndpointId: String, pcRelay: String?, deviceName: String): PairResult {
    val ep = endpoints.endpoint()
    val addr = EndpointAddr(EndpointId.fromString(pcEndpointId), pcRelay, emptyList())
    val conn = ep.connect(addr, ALPN_PAIR)
    val bi = conn.openBi()
    bi.send().writeFrame(json.encodeToString(
        PairRequest(code = code, deviceName = deviceName,
                    deviceEndpointId = ep.id().toString(), appVersion = BuildConfig.VERSION_NAME)
    ))
    val resp = json.decodeFromString<PairResponse>(bi.recv().readFrame())
    if (resp.type == "paired") {
        secureStore.saveToken(resp.token)                       // Tink + Keystore
        settings.savePcEndpointId(pcEndpointId, pcRelay)        // DataStore
    }
    return resp.toResult()
}
```

O QR do PC contém: `endpoint_id`, `relay` (opcional), `name`, `v`. O **código de 6 dígitos é digitado**, não vem no QR (o QR serve para evitar erro de digitação do EndpointId).

---

## 4. Telas (Compose)

Tema: fundo `#0a0a1a`, superfícies `#12122a`, neon ciano/magenta, tipografia mono para dados. Reuse a paleta do `desktop/renderer/style.css`.

| Tela | Conteúdo | Detalhes críticos |
|---|---|---|
| **Pairing** | Campo de 6 dígitos + botão "Ler QR" | `FLAG_SECURE`; mostra o `EndpointId` do próprio app; erro por código (`PAIR_CODE_EXPIRED`, `DEVICE_LIMIT`) |
| **Dashboard** | Cards: bot on/off, Ollama/modelo, CPU/RAM/GPU/temp, caminho (direto/relay) + latência, uptime | Atualização por stream de eventos; botão de pânico "Parar bot" com biometria; pull-to-refresh |
| **Chat** | Lista de mensagens + composer; seletor de modelo; tempo decorrido | Streaming por frames `delta`; cancelar em voo; histórico local por `conversation_id` |
| **PC Apps** | Grid de botões da lista branca | `lock`/`sleep` → `PinDialog` + biometria; `sleep` com seletor de minutos 1–1440 |
| **Drive** | Breadcrumbs, lista, upload/download | Upload via `ActivityResultContracts.GetContent` (sem permissão de storage); progresso; preview de imagem/vídeo/áudio |
| **Finanças** | Resumo do mês, gráfico por categoria/conta, inserir transação | Gráfico em Compose Canvas (sem dependência extra) |
| **Security** | 12 cards com status/alerts/attentions, gráfico, relatório IA, histórico | `security/report` é lento: botão assíncrono + progresso + timeout 90 s |
| **Cleanup** | Tarefas com badge "sudo" | Tarefas com sudo: botão **desabilitado** ("faça pelo desktop"); as demais pedem PIN |
| **Devices** | Dispositivos pareados, `last_seen`, revogar | Revogar pede biometria |
| **Settings** | EndpointId do PC, relay, tema, notificações, limpar dados | "Desparear" apaga token + EndpointId (mantém a SecretKey; só repareia) |

---

## 5. Segurança no Android

### 5.1 Armazenamento de segredos

| Segredo | Onde | Como |
|---|---|---|
| `SecretKey` do Iroh (32 bytes) | Tink keyset cifrado por chave do **Android Keystore** | `AndroidKeysetManager` + `Aead`; nunca em `SharedPreferences` comum |
| Token Bearer do PC | idem | idem |
| PIN | **não armazenar** por padrão | digitado a cada ação destrutiva; opcional: guardar cifrado, liberado só por biometria |

> `androidx.security:security-crypto` (`EncryptedSharedPreferences`) está **deprecado** — use **Tink** diretamente. `MODE_PRIVATE` sozinho não é proteção suficiente.

### 5.2 Requisitos de segurança (testáveis)

| # | Requisito | Como verificar |
|---|---|---|
| A1 | Nenhum segredo em `logcat` | grep por token/PIN nos logs de release |
| A2 | `allowBackup=false` + `dataExtractionRules` | `adb backup` não extrai nada |
| A3 | Pairing e PIN com `FLAG_SECURE` | screenshot/recents fica preto |
| A4 | `setFilterTouchesWhenObscured(true)` nas telas sensíveis | overlay não injeta toque |
| A5 | Ações destrutivas exigem biometria (`BIOMETRIC_STRONG` + `DEVICE_CREDENTIAL`) | teste manual |
| A6 | Token nunca em URL/query/Intent extras | inspeção de código + `dumpsys` |
| A7 | Nenhum componente `exported=true` além do launcher | `AndroidManifest.xml` |
| A8 | Cleartext **somente** na LAN para o pareamento; resto do tráfego via Iroh | `network_security_config.xml` + code review |
| A9 | R8 mantém JNA/iroh e release funciona | `assembleRelease` + smoke test |
| A10 | `401/TOKEN_REVOKED` → apaga token e volta ao pairing | teste com revogação no PC |
| A11 | Lockout de PIN espelhado (423 → bloqueia UI com contador) | teste |
| A12 | Sem analytics/crash SDK com payload | `./gradlew dependencies` |
| A13 | EndpointId do app estável entre reinícios | matar o app e conferir |
| A14 | "Desparear" limpa token e EndpointId do PC | teste |
| A15 | Upload sanitiza nome e valida SHA-256 | teste com nome malicioso |

### 5.3 Outros itens

- **Sem TLS/pinning:** o app não usa HTTP. O QUIC do Iroh autentica o PC pela chave pública (`EndpointId`), o que já impede MITM. Não invente camada TLS por cima.
- **Logs:** `Timber` só em debug; em release, nada. Nunca logar frames, tokens ou PIN.
- **Clipboard:** não copiar token; se copiar PIN, limpar depois.
- **Root/emulador:** detecção opcional, apenas aviso (não bloquear).
- **Criptografia de transporte:** E2E garantido pelo QUIC/Iroh; relay não descriptografa.

---

## 6. Permissões (Android)

### 6.1 Manifest — o mínimo necessário

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Rede: unico conjunto realmente essencial -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- Notificacoes (Android 13+) -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- Servico em primeiro plano para o stream de eventos (opt-in) -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

    <!-- Biometria para acoes destrutivas -->
    <uses-permission android:name="android.permission.USE_BIOMETRIC" />

    <!-- Camera: apenas para ler o QR de pareamento -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />

    <application
        android:name=".CyberBotApp"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="false"
        android:networkSecurityConfig="@xml/network_security_config"
        android:networkSecurityConfig="@xml/network_security_config"
        android:icon="@mipmap/ic_launcher"
        android:theme="@style/Theme.CyberBot">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.EventStreamService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
    </application>
</manifest>
```

### 6.2 Justificativa e fluxo de cada permissão

| Permissão | Por que | Quando pedir | Se negada |
|---|---|---|---|
| `INTERNET` | QUIC/Iroh | instalação | app inútil |
| `ACCESS_NETWORK_STATE` | detectar troca Wi-Fi↔4G para reconectar | instalação | reconexão mais lenta |
| `POST_NOTIFICATIONS` | avisos de alerta/job concluído | **no onboarding**, não no primeiro start | app funciona, sem notificações |
| `FOREGROUND_SERVICE(_DATA_SYNC)` | manter o stream de eventos vivo | quando o usuário liga "conexão contínua" | conexão só com o app aberto |
| `USE_BIOMETRIC` | proteger ações destrutivas | na primeira ação destrutiva | cai para PIN |
| `CAMERA` | ler o QR de pareamento | ao tocar "Ler QR" | digitar o EndpointId manualmente |

**Não pedir (e não declarar):**
- `READ_MEDIA_IMAGES`/`READ_EXTERNAL_STORAGE` → use **Photo Picker** (`PickVisualMedia`), sem permissão.
- `ACCESS_FINE_LOCATION`/`COARSE` → desnecessário (Iroh não precisa de scan Wi-Fi).
- `QUERY_ALL_PACKAGES` → nunca.
- `RECEIVE_BOOT_COMPLETED` → só se implementar auto-start; prefira não.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` → evite; use `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` com explicação.

### 6.3 Foreground Service — regras atuais

- Declare `android:foregroundServiceType="dataSync"` **e** a permissão correspondente.
- Android 14+: tipo declarado no manifest + justificativa no Play Console.
- Android 15+: `dataSync` tem **timeout** (ordem de horas/dia) — trate `onTimeout()` parando o serviço e avisando; não conte com FGS eterno.
- `startForegroundService()` + `startForeground(...)` em < 5 s, senão `ForegroundServiceDidNotStartInTimeException`.
- Notificação do FGS: canal silencioso com ação "Desconectar".

### 6.4 Rede

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
    <!-- O pareamento inicial com o PC é HTTP na LAN (único canal antes de existir
         identidade Iroh). O IP do PC muda por DHCP e a NSC não aceita faixas CIDR,
         por isso o base-config libera cleartext. Depois do pareamento o app usa
         IrohTransport (QUIC + TLS 1.3, ponta a ponta). -->
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

Nenhum domínio é acessado por HTTP — o app só fala QUIC com o PC. Não há exceção a declarar.

---

## 7. Background, notificações e fila offline

- **Stream de eventos** (`EventStreamService`, opt-in): abre um bi-stream de assinatura, heartbeat de 15 s, notifica `alerts` (security) e `jobs` concluídos.
- **WorkManager** para comandos com o PC offline: fila com `Idempotency-Key` + backoff exponencial + `NetworkType.CONNECTED`. Ações destrutivas **não** entram na fila.
- **Doze/App Standby:** o Endpoint morre com o processo; ao voltar, reconectar em < 1 s. Não tente manter conexão para sempre sem FGS.
- **Notificações:** canais separados (`alerts`, `jobs`, `service`), sem conteúdo sensível no texto.

---

## 8. Build, assinatura e CI

- `abiFilters` = `arm64-v8a` + `x86_64`. AAB para distribuição; `bundletool` para testar o split real.
- Assinatura: `signingConfigs.release` lendo `keystore.properties` (fora do git) ou variáveis de ambiente no CI.
- CI (GitHub Actions): `./gradlew ktlintCheck detekt testDebugUnitTest assembleRelease`; cache Gradle; artefato AAB.
- `ktlint`/`detekt` obrigatórios.
- Baseline profile opcional (ganho de startup).

---

## 9. Testes

| Tipo | Ferramenta | O que cobrir |
|---|---|---|
| Unit (domain) | JUnit5 + MockK | use cases, mapeamento de erro, lockout |
| Unit (data) | MockK + `runTest` | framing (encode/decode), retry, idempotência |
| Transporte | Fake `IrohTransport` (interface) | ViewModels com Loading/Success/Error |
| ViewModel | Turbine + `runTest` | sequência de `StateFlow` |
| UI | Compose UI Test | Pairing (código errado/expirado), PinDialog, estados de erro |
| Instrumentado | device/emulador | pareamento real + `/api/m/status` + upload |

**Framing tem teste obrigatório** (ponto mais frágil): round-trip, frame > 1 MiB, frame truncado, JSON inválido, `v=2`.

---

## 10. Critérios de aceite

- [ ] App instala, abre no Pairing, lê QR ou aceita código de 6 dígitos, e pareia.
- [ ] `EndpointId` do app é o mesmo depois de matar/abrir (identidade persistida).
- [ ] Dashboard mostra status e CPU/RAM/GPU atualizando sem polling agressivo.
- [ ] Chat responde com streaming e mostra tempo decorrido.
- [ ] "Bloqueio" e "Sleep" pedem PIN/biometria e executam no PC.
- [ ] Upload de foto do celular aparece na aba Drive do desktop.
- [ ] Matar o app e reabrir reconecta em < 2 s sem reparear.
- [ ] Trocar Wi-Fi → 4G reconecta sozinho.
- [ ] PC desligado → UI mostra "PC offline" (não trava, não vaza exceção).
- [ ] Revogar o dispositivo no PC → app volta ao Pairing no próximo request.
- [ ] `assembleRelease` (R8 ativo) roda e funciona no device.
- [ ] `logcat` de release não contém token, PIN nem conteúdo de mensagem.
- [ ] Nenhuma permissão além das listadas em §6.
- [ ] `minSdk 26` e `targetSdk` atual; abre em Android 10 e no mais recente.

---

## 11. Não fazer / armadilhas

- ❌ Não regenerar a `SecretKey` do Iroh (obriga a reparear).
- ❌ Não usar `Endpoint.close()` — no Kotlin é **`shutdown()`**.
- ❌ Não chamar `Endpoint.bind()` a cada requisição nem antes de `installAndroidContext`.
- ❌ Não adicionar JNA manualmente (o AAR já traz, com a exclusão correta do JAR).
- ❌ Não usar `EncryptedSharedPreferences` (deprecado) nem `SharedPreferences` comum para segredos.
- ❌ Não pedir permissão de storage/localização (Photo Picker resolve).
- ❌ Não colocar token em query string, deep link ou `Intent` extra.
- ❌ Não logar frames nem segredos.
- ❌ Não usar `runBlocking` na thread principal; nada de I/O em `Composable`.
- ❌ Não deixar `allowBackup=true` (o token iria para o backup do Google).
- ❌ Não confiar só no `EndpointId`: o PC exige token **e** PIN.
- ❌ Não reimplementar TLS/pinning por cima do Iroh.

---

## 12. Glossário

- **Endpoint** — objeto Iroh que representa este dispositivo na rede P2P.
- **EndpointId** — chave pública Ed25519 (base32) que identifica o dispositivo; o "endereço" do PC.
- **SecretKey** — chave privada de 32 bytes; persistida e secreta.
- **ALPN** — identificador do protocolo negociado no handshake QUIC (`cyberbot/1`, `cyberbot/pair/1`).
- **BiStream** — par de streams QUIC bidirecionais (um por requisição).
- **Hole punching** — técnica de NAT traversal; o relay só entra se falhar.
- **UDF** — fluxo unidirecional: evento → ViewModel → estado → UI.
- **FGS** — Foreground Service; mantém o stream vivo com notificação visível.
- **Tink** — biblioteca de criptografia do Google; cifra segredos com chave do Keystore.

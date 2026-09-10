package com.cyberbot.mobile.core.transport

import com.cyberbot.mobile.core.model.ApiErrorEnvelope
import com.cyberbot.mobile.core.model.CyberEvent
import com.cyberbot.mobile.core.model.TransportHealth
import com.cyberbot.mobile.core.net.ApiCall
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.core.storage.SecureStore
import com.cyberbot.mobile.core.storage.SettingsStorage
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.EndpointTicket
import computer.iroh.RecvStream
import computer.iroh.SecretKey
import io.ktor.http.HttpMethod
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class IrohFrameRequest(
    // SEM valor default de propósito: kotlinx.serialization não grava campos iguais ao
    // default, e sem "v" explícito o PC respondia 426 "atualize o app" em toda requisição Iroh.
    val v: Int,
    val id: String,
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: JsonElement? = null,
)

@Serializable
data class IrohFrameResponse(
    val v: Int = 1,
    val id: String? = null,
    val status: Int = 0,
    val headers: Map<String, String> = emptyMap(),
    val body: JsonElement? = null,
)

object IrohFraming {
    private const val MAX_FRAME = 1 shl 20

    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME) { "frame grande demais" }
        return ByteBuffer.allocate(4).putInt(payload.size).array() + payload
    }

    suspend fun decode(recv: RecvStream): ByteArray {
        val header = recv.readExact(4u)
        val size = ByteBuffer.wrap(header).int
        require(size in 1..MAX_FRAME) { "frame invalido: $size" }
        return recv.readExact(size.toUInt())
    }
}

@Singleton
class IrohEndpointProvider @Inject constructor(
    private val secureStore: SecureStore,
) {
    private val mutex = Mutex()

    @Volatile
    private var endpoint: Endpoint? = null

    suspend fun endpoint(): Endpoint = mutex.withLock {
        endpoint?.let { return it }
        val secret = secureStore.readIrohKey()
            ?: SecretKey.generate().toBytes().also { secureStore.writeIrohKey(it) }
        val bound = Endpoint.bind(
            EndpointOptions(
                secretKey = secret,
                alpns = listOf(ALPN_MAIN, ALPN_PAIR),
            )
        )
        endpoint = bound
        bound
    }

    suspend fun endpointId(): String = endpoint().id().toString()

    companion object {
        val ALPN_MAIN = "cyberbot/1".encodeToByteArray()
        val ALPN_PAIR = "cyberbot/pair/1".encodeToByteArray()
    }
}

private const val PROTOCOL_VERSION = 1
private const val REQUEST_TIMEOUT_MS = 180_000L

// Tentativa curta quando a conexão é reaproveitada: se o celular trocou de rede
// (4G -> 5G) sem o Android avisar, a conexão antiga fica "morta mas aberta" e o
// request ficava pendurado os 180s inteiros. Com isso ele cai em 20s e reconecta.
private const val ATTEMPT_TIMEOUT_MS = 20_000L
private const val PROBE_TIMEOUT_MS = 20_000L

// Conexão parada por mais que isso é descartada (mais barato reconectar do que arriscar).
private const val CONNECTION_IDLE_MS = 30_000L

@Singleton
class IrohTransport @Inject constructor(
    private val json: Json,
    private val endpointProvider: IrohEndpointProvider,
    private val settingsStorage: SettingsStorage,
    private val secureStore: SecureStore,
) : Transport {

    private val connectionMutex = Mutex()

    private class CachedConnection(val key: String, val connection: Connection) {
        var lastUsedAt: Long = System.currentTimeMillis()
    }

    @Volatile
    private var cachedConnection: CachedConnection? = null

    override suspend fun request(call: ApiCall): ApiResult<String> {
        val startedAt = System.currentTimeMillis()
        val repetivel = call.method == HttpMethod.Get || call.headers.containsKey("Idempotency-Key")
        val limite = if (cachedConnection != null && repetivel) ATTEMPT_TIMEOUT_MS else REQUEST_TIMEOUT_MS

        val primeira = tentativa(call, limite)
        primeira.getOrNull()?.let { return toResult(it, startedAt) }

        // Quase sempre é a conexão reaproveitada que morreu: descarta e tenta de novo.
        invalidateConnection()
        if (!repetivel) {
            return ApiResult.NetworkError(
                primeira.exceptionOrNull()
                    ?: IllegalStateException("Sem resposta do PC pelo Iroh.")
            )
        }

        val segunda = tentativa(call, REQUEST_TIMEOUT_MS)
        val resposta = segunda.getOrNull() ?: return timeoutFailure(segunda.exceptionOrNull())
        return toResult(resposta, startedAt)
    }

    private suspend fun tentativa(call: ApiCall, limiteMs: Long): Result<IrohFrameResponse> =
        try {
            Result.success(withTimeout(limiteMs) { perform(call) })
        } catch (cancel: CancellationException) {
            if (cancel !is TimeoutCancellationException) throw cancel
            Result.failure(cancel)
        } catch (error: Throwable) {
            Result.failure(error)
        }

    override fun events(): Flow<CyberEvent> = emptyFlow()

    override suspend fun probe(): TransportHealth {
        val startedAt = System.currentTimeMillis()
        val endpointId = settingsStorage.read().irohEndpointId
        // Teste rápido: sem isso um Iroh fora do ar deixava o app 3 minutos em "Pareando...".
        val response = withTimeoutOrNull(PROBE_TIMEOUT_MS) { request(ApiCall(path = "status")) }
            ?: ApiResult.NetworkError(
                IllegalStateException("Iroh sem resposta em ${PROBE_TIMEOUT_MS / 1000}s")
            )
        return when (response) {
            is ApiResult.Success -> TransportHealth(
                reachable = true,
                latencyMs = System.currentTimeMillis() - startedAt,
                path = "iroh",
                endpoint = endpointId,
            )

            is ApiResult.HttpError -> TransportHealth(
                reachable = true,
                latencyMs = System.currentTimeMillis() - startedAt,
                path = "iroh",
                endpoint = endpointId,
                reason = response.message,
            )

            is ApiResult.NetworkError -> TransportHealth(
                reachable = false,
                path = "iroh",
                endpoint = endpointId,
                reason = response.cause.message,
            )
        }
    }

    fun invalidateConnection() {
        cachedConnection = null
    }

    private suspend fun perform(call: ApiCall): IrohFrameResponse {
        val endpoint = endpointProvider.endpoint()
        val connection = connection(endpoint)
        val stream = connection.openBi()
        val request = IrohFrameRequest(
            v = PROTOCOL_VERSION,
            id = UUID.randomUUID().toString(),
            method = call.method.value,
            path = "/api/m/" + call.path.trimStart('/'),
            query = call.query,
            headers = buildMap {
                putAll(call.headers)
                if (call.authRequired) {
                    secureStore.readToken()?.takeIf { it.isNotBlank() }?.let {
                        put("Authorization", "Bearer $it")
                    }
                }
            },
            body = call.body?.let { json.parseToJsonElement(it) },
        )
        val send = stream.send()
        send.writeAll(
            IrohFraming.encode(
                json.encodeToString(IrohFrameRequest.serializer(), request).encodeToByteArray(),
            )
        )
        send.finish()
        return json.decodeFromString(
            IrohFrameResponse.serializer(),
            IrohFraming.decode(stream.recv()).decodeToString(),
        )
    }

    private suspend fun connection(endpoint: Endpoint): Connection {
        val target = targetAddress()
            ?: throw IllegalStateException("Sem ticket Iroh: pareie novamente.")
        val key = target.second

        reaproveitar(key)?.let { return it }

        return connectionMutex.withLock {
            reaproveitar(key)?.let { return it }
            val fresh = endpoint.connect(target.first, IrohEndpointProvider.ALPN_MAIN)
            cachedConnection = CachedConnection(key, fresh)
            fresh
        }
    }

    private fun reaproveitar(key: String): Connection? {
        val atual = cachedConnection ?: return null
        val ociosa = System.currentTimeMillis() - atual.lastUsedAt
        if (atual.key != key || ociosa >= CONNECTION_IDLE_MS || !isOpen(atual.connection)) return null
        atual.lastUsedAt = System.currentTimeMillis()
        return atual.connection
    }

    private fun isOpen(connection: Connection): Boolean =
        runCatching { connection.closeReason() == null }.getOrDefault(false)

    /**
     * Endereço do PC: usa o ticket quando existe (leva relay + endereços) e cai para o
     * EndpointId quando o ticket está vazio — nesse caso quem resolve é a descoberta do Iroh.
     */
    private suspend fun targetAddress(): Pair<EndpointAddr, String>? {
        val session = settingsStorage.read()
        val ticket = session.irohTicket.trim()
        if (ticket.isNotBlank()) {
            val addr = runCatching { EndpointTicket.fromString(ticket).endpointAddr() }.getOrNull()
            if (addr != null) return addr to ticket
        }
        val endpointId = session.irohEndpointId.trim()
        if (endpointId.isNotBlank()) {
            val id = runCatching { EndpointId.fromString(endpointId) }.getOrNull()
            if (id != null) return EndpointAddr(id, null, emptyList()) to "id:$endpointId"
        }
        return null
    }

    private fun toResult(response: IrohFrameResponse, startedAt: Long): ApiResult<String> {
        val elapsed = System.currentTimeMillis() - startedAt
        val payload = response.body?.let { json.encodeToString(JsonElement.serializer(), it) } ?: "{}"
        return if (response.status in 200..299) {
            ApiResult.Success(payload, elapsed)
        } else {
            val envelope = try {
                json.decodeFromString<ApiErrorEnvelope>(payload)
            } catch (_: Throwable) {
                null
            }
            ApiResult.HttpError(
                code = response.status,
                apiCode = envelope?.error?.code,
                message = envelope?.error?.message ?: "Falha HTTP " + response.status,
                retryAfterSeconds = response.headers["retry-after"]?.toLongOrNull(),
            )
        }
    }

    private fun timeoutFailure(error: Throwable?): ApiResult.NetworkError =
        ApiResult.NetworkError(
            IllegalStateException(
                "Tempo esgotado ao falar com o PC pelo Iroh. Verifique se o PC está ligado.",
                error,
            )
        )
}

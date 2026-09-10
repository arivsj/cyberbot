package com.cyberbot.mobile.core.transport

import com.cyberbot.mobile.core.model.CyberEvent
import com.cyberbot.mobile.core.model.TransportHealth
import com.cyberbot.mobile.core.model.TransportMode
import com.cyberbot.mobile.core.model.SessionSnapshot
import com.cyberbot.mobile.core.net.ApiCall
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.core.model.ApiErrorEnvelope
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.cyberbot.mobile.core.net.MdnsDiscovery
import com.cyberbot.mobile.core.storage.SecureStore
import com.cyberbot.mobile.core.storage.SettingsStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

interface Transport {
    suspend fun request(call: ApiCall): ApiResult<String>
    fun events(): Flow<CyberEvent>
    suspend fun probe(): TransportHealth
    suspend fun upload(
        path: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        fields: Map<String, String> = emptyMap(),
    ): ApiResult<String> = ApiResult.NetworkError(
        UnsupportedOperationException("Upload nao suportado neste transporte.")
    )
}

class DirectTransport(
    private val client: HttpClient,
    private val json: Json,
    private val baseUrl: String,
    private val tokenProvider: suspend () -> String?,
) : Transport {
    override suspend fun request(call: ApiCall): ApiResult<String> {
        if (baseUrl.isBlank()) {
            return ApiResult.NetworkError(IllegalStateException("Nenhum endpoint direto configurado."))
        }

        val startedAt = System.currentTimeMillis()
        return try {
            val response = client.request(buildUrl(call.path)) {
                method = call.method
                contentType(ContentType.Application.Json)
                call.query.forEach { (key, value) -> parameter(key, value) }
                call.headers.forEach { (key, value) -> header(key, value) }
                if (call.authRequired) {
                    tokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                }
                call.body?.let(::setBody)
            }
            val elapsed = System.currentTimeMillis() - startedAt
            val payload = response.bodyAsText()
            if (response.status.isSuccess()) {
                ApiResult.Success(payload, elapsed)
            } else {
                val envelope = try {
                    json.decodeFromString<ApiErrorEnvelope>(payload)
                } catch (_: SerializationException) {
                    null
                }
                ApiResult.HttpError(
                    code = response.status.value,
                    apiCode = envelope?.error?.code,
                    message = envelope?.error?.message ?: "Falha HTTP ${response.status.value}",
                    retryAfterSeconds = response.headers["Retry-After"]?.toLongOrNull(),
                )
            }
        } catch (error: HttpRequestTimeoutException) {
            ApiResult.NetworkError(error)
        } catch (error: SocketTimeoutException) {
            ApiResult.NetworkError(error)
        } catch (error: ConnectException) {
            ApiResult.NetworkError(error)
        } catch (error: UnknownHostException) {
            ApiResult.NetworkError(error)
        } catch (error: IOException) {
            ApiResult.NetworkError(error)
        }
    }

    override suspend fun upload(
        path: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        fields: Map<String, String>,
    ): ApiResult<String> {
        if (baseUrl.isBlank()) {
            return ApiResult.NetworkError(IllegalStateException("Nenhum endpoint direto configurado."))
        }
        val startedAt = System.currentTimeMillis()
        return try {
            val response = client.submitFormWithBinaryData(
                url = buildUrl(path),
                formData = formData {
                    fields.forEach { (key, value) -> append(key, value) }
                    append(
                        "file", bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, mimeType)
                            append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        },
                    )
                },
            ) {
                tokenProvider()?.takeIf { it.isNotBlank() }?.let {
                    header(HttpHeaders.Authorization, "Bearer $it")
                }
            }
            val payload = response.bodyAsText()
            val elapsed = System.currentTimeMillis() - startedAt
            if (response.status.isSuccess()) {
                ApiResult.Success(payload, elapsed)
            } else {
                val envelope = try {
                    json.decodeFromString<ApiErrorEnvelope>(payload)
                } catch (_: SerializationException) {
                    null
                }
                ApiResult.HttpError(response.status.value, envelope?.error?.code,
                    envelope?.error?.message ?: "Falha HTTP ${response.status.value}")
            }
        } catch (error: IOException) {
            ApiResult.NetworkError(error)
        } catch (error: Throwable) {
            ApiResult.NetworkError(error)
        }
    }

    override fun events(): Flow<CyberEvent> = emptyFlow()

    override suspend fun probe(): TransportHealth {
        val startedAt = System.currentTimeMillis()
        val response = request(ApiCall(path = "status"))
        return when (response) {
            is ApiResult.Success -> TransportHealth(
                reachable = true,
                latencyMs = System.currentTimeMillis() - startedAt,
                path = "direct",
                endpoint = baseUrl,
            )

            is ApiResult.HttpError -> TransportHealth(
                reachable = true,
                latencyMs = System.currentTimeMillis() - startedAt,
                path = "direct",
                endpoint = baseUrl,
                reason = response.message,
            )

            is ApiResult.NetworkError -> TransportHealth(
                reachable = false,
                path = "direct",
                endpoint = baseUrl,
                reason = response.cause.message,
            )
        }
    }

    private fun buildUrl(path: String): String = "${baseUrl.trimEnd('/')}/api/m/${path.trimStart('/')}"
}

class RelayTransport(
    private val wsUrl: String,
) : Transport {
    override suspend fun request(call: ApiCall): ApiResult<String> =
        ApiResult.NetworkError(UnsupportedOperationException("Relay E2E ainda nao foi implementado. URL: $wsUrl"))

    override fun events(): Flow<CyberEvent> = emptyFlow()

    override suspend fun probe(): TransportHealth =
        TransportHealth(
            reachable = false,
            path = "relay",
            endpoint = wsUrl,
            reason = "Relay E2E pendente.",
        )
}

data class SelectedTransport(
    val mode: TransportMode,
    val transport: Transport,
    val health: TransportHealth,
)

@Singleton
class TransportSelector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: HttpClient,
    private val json: Json,
    private val settingsStorage: SettingsStorage,
    private val secureStore: SecureStore,
    private val irohEndpointProvider: IrohEndpointProvider,
    private val mdnsDiscovery: MdnsDiscovery,
) {
    private var cachedSelection: Pair<Long, SelectedTransport>? = null

    private val probeClient: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 2500
            requestTimeoutMillis = 4000
            socketTimeoutMillis = 4000
        }
    }

    @Volatile
    private var tipoDeRedeAtual: String = ""

    init {
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = invalidate()
                override fun onLost(network: Network) = invalidate()

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    val tipo = tipoDeRede(capabilities)
                    if (tipo != tipoDeRedeAtual) {
                        tipoDeRedeAtual = tipo
                        invalidate()
                    }
                }
            })
        }
    }

    private fun tipoDeRede(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "celular"
        else -> "outro"
    }

    suspend fun select(forceRefresh: Boolean = false): SelectedTransport {
        val now = System.currentTimeMillis()
        cachedSelection?.takeIf { !forceRefresh && now - it.first < 60_000L }?.let { return it.second }

        val session = settingsStorage.read()
        val selection = when (session.transportMode) {
            TransportMode.DIRECT_ONLY -> createDirectSelection(session)
            TransportMode.RELAY_ONLY -> createRelaySelection(session)
            TransportMode.AUTO -> selectAuto(session)
        }

        cachedSelection = now to selection
        return selection
    }

    /**
     * Escolha automática de transporte.
     *
     * Na Wi-Fi/LAN o caminho direto continua sendo o primeiro. Fora da rede local (4G),
     * o Iroh vem primeiro: assim o app não perde segundos tentando endereços que só
     * existem em casa antes de cair para a conexão P2P.
     */
    private suspend fun selectAuto(session: SessionSnapshot): SelectedTransport {
        if (onLocalNetwork()) {
            val direct = createDirectSelection(session, allowMdns = true)
            if (direct.health.reachable) return direct
            val iroh = createIrohSelection(session)
            if (iroh != null && iroh.health.reachable) return iroh
            return if (session.relayUrl.isBlank()) direct else createRelaySelection(session)
        }

        val iroh = createIrohSelection(session)
        if (iroh != null && iroh.health.reachable) return iroh
        val direct = createDirectSelection(session, allowMdns = false)
        if (direct.health.reachable) return direct
        return if (session.relayUrl.isBlank()) direct else createRelaySelection(session)
    }

    /** true quando há Wi-Fi/Ethernet/VPN ativos (rede local ou Tailscale). */
    private fun onLocalNetwork(): Boolean {
        return try {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return true
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Throwable) {
            true
        }
    }

    suspend fun events(): Flow<CyberEvent> = select().transport.events()

    fun invalidate() {
        cachedSelection = null
    }

    private suspend fun createIrohSelection(session: SessionSnapshot): SelectedTransport? {
        if (session.irohTicket.isBlank()) return null
        val transport = IrohTransport(
            json = json,
            endpointProvider = irohEndpointProvider,
            settingsStorage = settingsStorage,
            secureStore = secureStore,
        )
        return SelectedTransport(
            mode = session.transportMode,
            transport = transport,
            health = transport.probe(),
        )
    }

    private suspend fun createDirectSelection(
        session: SessionSnapshot,
        allowMdns: Boolean = true,
    ): SelectedTransport {
        val saved = session.directBaseUrl.trim().trimEnd('/')
        val candidates = buildList {
            if (saved.isNotBlank()) add(saved)
            if (allowMdns) {
                mdnsDiscovery.discover().forEach { if (it.isNotBlank()) add(it.trimEnd('/')) }
            }
            session.directCandidates.forEach { if (it.isNotBlank()) add(it.trimEnd('/')) }
        }.distinct()

        var last: SelectedTransport? = null
        for (base in candidates) {
            val probe = DirectTransport(
                client = probeClient,
                json = json,
                baseUrl = base,
                tokenProvider = { secureStore.readToken() },
            )
            val health = probe.probe()
            val selected = SelectedTransport(
                mode = session.transportMode,
                transport = DirectTransport(
                    client = client,
                    json = json,
                    baseUrl = base,
                    tokenProvider = { secureStore.readToken() },
                ),
                health = health,
            )
            if (health.reachable) {
                if (base != saved) settingsStorage.updateDirectBaseUrl(base)
                return selected
            }
            last = selected
        }
        return last ?: SelectedTransport(
            mode = session.transportMode,
            transport = DirectTransport(
                client = client,
                json = json,
                baseUrl = saved,
                tokenProvider = { secureStore.readToken() },
            ),
            health = TransportHealth(reachable = false, path = "direct", endpoint = saved,
                reason = "PC nao encontrado na rede"),
        )
    }

    private fun createRelaySelection(session: SessionSnapshot): SelectedTransport {
        val transport = RelayTransport(session.relayUrl)
        return SelectedTransport(
            mode = session.transportMode,
            transport = transport,
            health = TransportHealth(
                reachable = false,
                path = "relay",
                endpoint = session.relayUrl,
                reason = "Relay indisponivel nesta etapa.",
            ),
        )
    }
}

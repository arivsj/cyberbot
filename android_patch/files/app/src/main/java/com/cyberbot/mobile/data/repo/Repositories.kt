package com.cyberbot.mobile.data.repo

import com.cyberbot.mobile.BuildConfig
import com.cyberbot.mobile.core.model.ActionResponse
import com.cyberbot.mobile.core.model.ChatRequest
import com.cyberbot.mobile.core.model.ChatResponse
import com.cyberbot.mobile.core.model.PairingRequest
import com.cyberbot.mobile.core.model.SessionSnapshot
import com.cyberbot.mobile.core.model.StatusResponse
import com.cyberbot.mobile.core.model.SysmonSnapshot
import com.cyberbot.mobile.core.model.TransportHealth
import com.cyberbot.mobile.core.model.TransportMode
import com.cyberbot.mobile.core.model.UpdatePinRequest
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.core.storage.SecureStore
import com.cyberbot.mobile.core.storage.SettingsStorage
import com.cyberbot.mobile.core.transport.TransportSelector
import com.cyberbot.mobile.data.api.MobileApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class SessionRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val mobileApi: MobileApi,
    private val settingsStorage: SettingsStorage,
    private val secureStore: SecureStore,
    private val transportSelector: TransportSelector,
) {
    val session: Flow<SessionSnapshot> = settingsStorage.session

    suspend fun pair(code: String, deviceName: String, directBaseUrl: String): ApiResult<Unit> {
        settingsStorage.updateDirectBaseUrl(directBaseUrl)
        transportSelector.invalidate()

        val fingerprint = runCatching {
            android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val result = mobileApi.pair(
            PairingRequest(
                code = code,
                device_name = deviceName,
                app_version = BuildConfig.VERSION_NAME,
                device_fingerprint = fingerprint,
            )
        )

        return when (result) {
            is ApiResult.Success -> {
                secureStore.writeToken(result.body.token)
                settingsStorage.updatePairing(
                    deviceId = result.body.device_id,
                    pcName = result.body.pc_name,
                    directBaseUrl = result.body.transport_hint.direct.firstOrNull() ?: directBaseUrl,
                    relayUrl = result.body.transport_hint.relay,
                    pinSet = result.body.pin_set,
                )
                settingsStorage.updateCandidates(result.body.transport_hint.direct)
                result.body.iroh?.let { hint ->
                    settingsStorage.updateIroh(
                        endpointId = hint.endpoint_id.orEmpty(),
                        ticket = hint.ticket.orEmpty(),
                    )
                }
                transportSelector.invalidate()
                ApiResult.Success(Unit, result.elapsedMs)
            }

            is ApiResult.HttpError -> result
            is ApiResult.NetworkError -> result
        }
    }

    suspend fun saveTransportMode(mode: TransportMode) {
        settingsStorage.updateTransportMode(mode)
        transportSelector.invalidate()
    }

    suspend fun saveDirectBaseUrl(value: String) {
        settingsStorage.updateDirectBaseUrl(value)
        transportSelector.invalidate()
    }

    suspend fun saveRelayUrl(value: String) {
        settingsStorage.updateRelayUrl(value)
        transportSelector.invalidate()
    }

    fun cachedPin(): String? = secureStore.readPin()

    fun saveCachedPin(value: String) {
        secureStore.writePin(value)
    }

    suspend fun clearAll() {
        secureStore.clearSecrets()
        settingsStorage.clear()
        transportSelector.invalidate()
    }

    /** Sai da conexão atual: apaga token/device ID e volta para a tela de pareamento. */
    suspend fun unpair() {
        secureStore.clearSecrets()
        settingsStorage.clearPairing()
        transportSelector.invalidate()
    }

    /** Guarda o que veio no QR Code (endereços + Iroh) antes de parear. */
    suspend fun prepareFromQr(directUrls: List<String>, endpointId: String? = null, ticket: String? = null) {
        val limpos = directUrls.map { it.trim() }.filter { it.startsWith("http") }
        if (limpos.isNotEmpty()) settingsStorage.updateCandidates(limpos)
        saveIrohHint(endpointId, ticket)
        transportSelector.invalidate()
    }

    /** Guarda o ticket Iroh vindo do QR Code quando a resposta de pareamento não trouxe um. */
    suspend fun saveIrohHint(endpointId: String?, ticket: String?) {
        val current = settingsStorage.read()
        val id = endpointId?.takeIf { it.isNotBlank() } ?: current.irohEndpointId
        val hint = ticket?.takeIf { it.isNotBlank() } ?: current.irohTicket
        if (id.isBlank() && hint.isBlank()) return
        settingsStorage.updateIroh(id, hint)
        transportSelector.invalidate()
    }
}

data class DashboardData(
    val session: SessionSnapshot,
    val status: StatusResponse? = null,
    val sysmon: SysmonSnapshot? = null,
    val transportHealth: TransportHealth? = null,
)

@Singleton
class StatusRepository @Inject constructor(
    private val mobileApi: MobileApi,
    private val settingsStorage: SettingsStorage,
    private val transportSelector: TransportSelector,
) {
    val session: Flow<SessionSnapshot> = settingsStorage.session

    suspend fun transportHealth(forceRefresh: Boolean = false): TransportHealth =
        transportSelector.select(forceRefresh = forceRefresh).health

    suspend fun getStatus(): ApiResult<StatusResponse> = mobileApi.getStatus()

    suspend fun getSysmon(): ApiResult<SysmonSnapshot> = mobileApi.getSysmon()
}

@Singleton
class ChatRepository @Inject constructor(
    private val mobileApi: MobileApi,
) {
    suspend fun sendMessage(
        conversationId: String,
        message: String,
        model: String,
        history: List<com.cyberbot.mobile.core.model.ChatHistoryItem>? = null,
    ): ApiResult<ChatResponse> =
        mobileApi.sendChat(
            ChatRequest(
                conversation_id = conversationId,
                message = message,
                model = model.takeIf { it.isNotBlank() },
                history = history,
            )
        )
}

@Singleton
class ChatHistoryRepository @Inject constructor(
    private val mobileApi: MobileApi,
) {
    suspend fun clear(conversationId: String?) = mobileApi.clearChat(conversationId)
}

@Singleton
class PcAppsRepository @Inject constructor(
    private val mobileApi: MobileApi,
) {
    suspend fun listApps() = mobileApi.listPcApps()

    suspend fun runApp(id: String, pin: String? = null) = mobileApi.runPcApp(id, pin)

    suspend fun scheduleSleep(minutes: Int, pin: String) = mobileApi.scheduleSleep(minutes, pin)
}

@Singleton
class DriveRepository @Inject constructor(
    private val mobileApi: MobileApi,
) {
    suspend fun listFolders(parent: Int?) = mobileApi.listFolders(parent)
    suspend fun listFiles(folder: Int?) = mobileApi.listFiles(folder)
    suspend fun upload(name: String, mime: String, bytes: ByteArray, folderId: Int?) =
        mobileApi.uploadFile(name, mime, bytes, folderId)
}

@Singleton
class FinanceRepository @Inject constructor(
    private val mobileApi: MobileApi,
) {
    suspend fun summary(mes: String?) = mobileApi.financeSummary(mes)
    suspend fun add(categoria: String, conta: String, valor: Double, descricao: String) =
        mobileApi.addFinance(categoria, conta, valor, descricao)
}

@Singleton
class SecurityRepository @Inject constructor(
    private val mobileApi: MobileApi,
) {
    suspend fun run() = mobileApi.securityRun()
    suspend fun report() = mobileApi.securityReport()
}

@Singleton
class CleanupRepository @Inject constructor(
    private val mobileApi: MobileApi,
) {
    suspend fun tasks() = mobileApi.cleanupTasks()
    suspend fun run(taskId: String?, mode: String?, pin: String) = mobileApi.cleanupRun(taskId, mode, pin)
}

@Singleton
class TrilhaRepository @Inject constructor(
    private val mobileApi: MobileApi,
) {
    suspend fun projects() = mobileApi.trilhaProjects()
}

@Singleton
class YoutubeRepository @Inject constructor(
    private val mobileApi: MobileApi,
) {
    suspend fun jobs() = mobileApi.youtubeJobs()
    suspend fun download(url: String, format: String) = mobileApi.youtubeDownload(url, format)
}

@Singleton
class DiagnosticsRepository @Inject constructor(
    private val mobileApi: MobileApi,
) {
    suspend fun history(limit: Int = 30) = mobileApi.getSysmonHistory(limit)
}

@Singleton
class RagRepository @Inject constructor(
    private val mobileApi: MobileApi,
) {
    suspend fun documents() = mobileApi.ragDocuments()
    suspend fun upload(name: String, mime: String, bytes: ByteArray) = mobileApi.ragUpload(name, mime, bytes)
    suspend fun ask(question: String) = mobileApi.ragChat(question)
    suspend fun remove(id: Int) = mobileApi.ragDelete(id)
}

@Singleton
class WebSearchRepository @Inject constructor(
    private val mobileApi: MobileApi,
) {
    suspend fun search(query: String) = mobileApi.webSearch(query)
    suspend fun ask(query: String) = mobileApi.webSearchChat(query)
}

@Singleton
class MediaRepository @Inject constructor(
    private val mobileApi: MobileApi,
) {
    suspend fun image(name: String, mime: String, bytes: ByteArray, caption: String, conversationId: String) =
        mobileApi.sendImage(name, mime, bytes, caption, conversationId)

    suspend fun video(name: String, mime: String, bytes: ByteArray) =
        mobileApi.sendVideo(name, mime, bytes)

    suspend fun audio(name: String, mime: String, bytes: ByteArray, caption: String, conversationId: String) =
        mobileApi.sendAudio(name, mime, bytes, caption, conversationId)

    suspend fun document(name: String, mime: String, bytes: ByteArray) =
        mobileApi.sendDocument(name, mime, bytes)
}

@Singleton
class PluginsRepository @Inject constructor(
    private val mobileApi: MobileApi,
) {
    suspend fun list() = mobileApi.plugins()
}

@Singleton
class ModelRepository @Inject constructor(
    private val mobileApi: MobileApi,
) {
    suspend fun available() = mobileApi.models()
    suspend fun current() = mobileApi.currentModel()
    suspend fun select(model: String) = mobileApi.setModel(model)
    suspend fun context() = mobileApi.contextInfo()
}

@Singleton
class DevicesRepository @Inject constructor(
    private val mobileApi: MobileApi,
) {
    suspend fun listDevices() = mobileApi.listDevices()
    suspend fun revoke(deviceId: String, pin: String) = mobileApi.revokeDevice(deviceId, pin)

    suspend fun updatePin(currentPin: String?, newPin: String): ApiResult<ActionResponse> =
        mobileApi.updatePin(
            UpdatePinRequest(
                current_pin = currentPin?.takeIf { it.isNotBlank() },
                new_pin = newPin,
            )
        )
}

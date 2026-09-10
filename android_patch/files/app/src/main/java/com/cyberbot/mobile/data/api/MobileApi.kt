package com.cyberbot.mobile.data.api

import com.cyberbot.mobile.core.model.ActionResponse
import com.cyberbot.mobile.core.model.ChatRequest
import com.cyberbot.mobile.core.model.ChatResponse
import com.cyberbot.mobile.core.model.CleanupTask
import com.cyberbot.mobile.core.model.CyberEvent
import com.cyberbot.mobile.core.model.DeviceInfo
import com.cyberbot.mobile.core.model.DocumentText
import com.cyberbot.mobile.core.model.DriveFile
import com.cyberbot.mobile.core.model.DriveFolder
import com.cyberbot.mobile.core.model.FinanceSummary
import com.cyberbot.mobile.core.model.AnswerResult
import com.cyberbot.mobile.core.model.AudioAnswer
import com.cyberbot.mobile.core.model.ImageAnalysis
import com.cyberbot.mobile.core.model.MediaUpload
import com.cyberbot.mobile.core.model.ContextInfo
import com.cyberbot.mobile.core.model.ModelInfo
import com.cyberbot.mobile.core.model.ModelsResponse
import com.cyberbot.mobile.core.model.PairingRequest
import com.cyberbot.mobile.core.model.PairingResponse
import com.cyberbot.mobile.core.model.PluginInfo
import com.cyberbot.mobile.core.model.RagDocument
import com.cyberbot.mobile.core.model.RagResult
import com.cyberbot.mobile.core.model.PcAppItem
import com.cyberbot.mobile.core.model.RunPcAppRequest
import com.cyberbot.mobile.core.model.SecurityIncident
import com.cyberbot.mobile.core.model.SecurityReport
import com.cyberbot.mobile.core.model.SecurityResult
import com.cyberbot.mobile.core.model.SleepPcRequest
import com.cyberbot.mobile.core.model.StatusResponse
import com.cyberbot.mobile.core.model.SysmonHistoryItem
import com.cyberbot.mobile.core.model.SysmonSnapshot
import com.cyberbot.mobile.core.model.TrilhaProject
import com.cyberbot.mobile.core.model.UpdatePinRequest
import com.cyberbot.mobile.core.model.UploadResult
import com.cyberbot.mobile.core.model.YoutubeJob
import com.cyberbot.mobile.core.net.ApiCall
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.core.transport.TransportSelector
import io.ktor.http.HttpMethod
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

interface MobileApi {
    suspend fun pair(request: PairingRequest): ApiResult<PairingResponse>
    suspend fun getStatus(): ApiResult<StatusResponse>
    suspend fun getSysmon(): ApiResult<SysmonSnapshot>
    suspend fun sendChat(request: ChatRequest): ApiResult<ChatResponse>
    suspend fun listPcApps(): ApiResult<List<PcAppItem>>
    suspend fun runPcApp(id: String, pin: String? = null): ApiResult<ActionResponse>
    suspend fun scheduleSleep(minutes: Int, pin: String): ApiResult<ActionResponse>
    suspend fun listDevices(): ApiResult<List<DeviceInfo>>
    suspend fun updatePin(request: UpdatePinRequest): ApiResult<ActionResponse>
    suspend fun getSysmonHistory(limit: Int = 30): ApiResult<List<SysmonHistoryItem>>
    suspend fun listFolders(parent: Int?): ApiResult<List<DriveFolder>>
    suspend fun listFiles(folder: Int?): ApiResult<List<DriveFile>>
    suspend fun uploadFile(fileName: String, mimeType: String, bytes: ByteArray, folderId: Int?): ApiResult<UploadResult>
    suspend fun financeSummary(mes: String?): ApiResult<FinanceSummary>
    suspend fun addFinance(categoria: String, conta: String, valor: Double, descricao: String): ApiResult<ActionResponse>
    suspend fun securityRun(): ApiResult<Map<String, SecurityResult>>
    suspend fun securityReport(): ApiResult<SecurityReport>
    suspend fun reportIncident(motivo: String, tentativas: Int, bloqueadoAte: String): ApiResult<ActionResponse>
    suspend fun securityIncidents(): ApiResult<List<SecurityIncident>>
    suspend fun cleanupTasks(): ApiResult<List<CleanupTask>>
    suspend fun cleanupRun(taskId: String?, mode: String?, pin: String): ApiResult<JsonElement>
    suspend fun trilhaProjects(): ApiResult<List<TrilhaProject>>
    suspend fun youtubeJobs(): ApiResult<List<YoutubeJob>>
    suspend fun youtubeDownload(url: String, format: String): ApiResult<YoutubeJob>
    suspend fun revokeDevice(deviceId: String, pin: String): ApiResult<ActionResponse>
    suspend fun ragDocuments(): ApiResult<List<RagDocument>>
    suspend fun ragUpload(fileName: String, mimeType: String, bytes: ByteArray): ApiResult<ActionResponse>
    suspend fun ragQuery(question: String): ApiResult<RagResult>
    suspend fun ragChat(question: String): ApiResult<AnswerResult>
    suspend fun ragDelete(documentId: Int): ApiResult<ActionResponse>
    suspend fun webSearch(query: String): ApiResult<RagResult>
    suspend fun webSearchChat(query: String): ApiResult<AnswerResult>
    suspend fun plugins(): ApiResult<List<PluginInfo>>
    suspend fun models(): ApiResult<ModelsResponse>
    suspend fun currentModel(): ApiResult<ModelInfo>
    suspend fun setModel(model: String): ApiResult<ModelInfo>
    suspend fun contextInfo(): ApiResult<ContextInfo>
    suspend fun clearChat(conversationId: String?): ApiResult<ActionResponse>
    suspend fun sendImage(fileName: String, mimeType: String, bytes: ByteArray, caption: String, conversationId: String): ApiResult<ImageAnalysis>
    suspend fun sendVideo(fileName: String, mimeType: String, bytes: ByteArray): ApiResult<MediaUpload>
    suspend fun sendAudio(fileName: String, mimeType: String, bytes: ByteArray, caption: String, conversationId: String): ApiResult<AudioAnswer>
    suspend fun sendDocument(fileName: String, mimeType: String, bytes: ByteArray): ApiResult<DocumentText>
    fun events(): Flow<CyberEvent>
}

@Singleton
class RealMobileApi @Inject constructor(
    private val json: Json,
    private val transportSelector: TransportSelector,
) : MobileApi {
    override suspend fun pair(request: PairingRequest): ApiResult<PairingResponse> {
        val selectedTransport = transportSelector.select(forceRefresh = true)
        return decode(
            selectedTransport.transport.request(
                ApiCall(
                    path = "pair",
                    method = HttpMethod.Post,
                    body = json.encodeToString(PairingRequest.serializer(), request),
                    authRequired = false,
                )
            )
        )
    }

    override suspend fun getStatus(): ApiResult<StatusResponse> =
        decode(request(path = "status"))

    override suspend fun getSysmon(): ApiResult<SysmonSnapshot> =
        decode(request(path = "sysmon"))

    override suspend fun sendChat(request: ChatRequest): ApiResult<ChatResponse> =
        decode(
            request(
                path = "chat",
                method = HttpMethod.Post,
                body = json.encodeToString(ChatRequest.serializer(), request),
                headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
            )
        )

    override suspend fun listPcApps(): ApiResult<List<PcAppItem>> =
        decodeList(request(path = "pc/apps"), ListSerializer(PcAppItem.serializer()))

    override suspend fun runPcApp(id: String, pin: String?): ApiResult<ActionResponse> =
        decode(
            request(
                path = "pc/run",
                method = HttpMethod.Post,
                body = json.encodeToString(
                    RunPcAppRequest.serializer(),
                    RunPcAppRequest(id = id),
                ),
                headers = buildMap {
                    put("Idempotency-Key", UUID.randomUUID().toString())
                    pin?.takeIf { it.isNotBlank() }?.let { put("X-Action-PIN", it) }
                },
            )
        )

    override suspend fun scheduleSleep(minutes: Int, pin: String): ApiResult<ActionResponse> =
        decode(
            request(
                path = "pc/sleep",
                method = HttpMethod.Post,
                body = json.encodeToString(
                    SleepPcRequest.serializer(),
                    SleepPcRequest(minutes = minutes),
                ),
                headers = mapOf(
                    "Idempotency-Key" to UUID.randomUUID().toString(),
                    "X-Action-PIN" to pin,
                ),
            )
        )

    override suspend fun listDevices(): ApiResult<List<DeviceInfo>> =
        decodeList(request(path = "devices"), ListSerializer(DeviceInfo.serializer()))

    override suspend fun updatePin(request: UpdatePinRequest): ApiResult<ActionResponse> =
        decode(
            this.request(
                path = "pin",
                method = HttpMethod.Post,
                body = json.encodeToString(UpdatePinRequest.serializer(), request),
                headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
            )
        )

    override suspend fun getSysmonHistory(limit: Int): ApiResult<List<SysmonHistoryItem>> =
        decodeList(request(path = "sysmon/history", query = mapOf("limit" to limit.toString())),
            ListSerializer(SysmonHistoryItem.serializer()))

    override suspend fun listFolders(parent: Int?): ApiResult<List<DriveFolder>> =
        decodeList(
            request(path = "drive/folders", query = parent?.let { mapOf("parent" to it.toString()) } ?: emptyMap()),
            ListSerializer(DriveFolder.serializer()),
        )

    override suspend fun listFiles(folder: Int?): ApiResult<List<DriveFile>> =
        decodeList(
            request(path = "drive/files", query = folder?.let { mapOf("folder" to it.toString()) } ?: emptyMap()),
            ListSerializer(DriveFile.serializer()),
        )

    override suspend fun uploadFile(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        folderId: Int?,
    ): ApiResult<UploadResult> {
        val fields = folderId?.let { mapOf("folder_id" to it.toString()) } ?: emptyMap()
        val selected = transportSelector.select()
        return decode(selected.transport.upload("drive/upload", fileName, mimeType, bytes, fields))
    }

    override suspend fun financeSummary(mes: String?): ApiResult<FinanceSummary> =
        decode(
            request(path = "financas/resumo", query = mes?.let { mapOf("mes" to it) } ?: emptyMap())
        )

    override suspend fun addFinance(
        categoria: String,
        conta: String,
        valor: Double,
        descricao: String,
    ): ApiResult<ActionResponse> =
        decode(
            request(
                path = "financas",
                method = HttpMethod.Post,
                body = json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("categoria", JsonPrimitive(categoria))
                        put("conta", JsonPrimitive(conta))
                        put("valor", JsonPrimitive(valor))
                        put("descricao", JsonPrimitive(descricao))
                    },
                ),
                headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
            )
        )

    override suspend fun securityRun(): ApiResult<Map<String, SecurityResult>> =
        decode(request(path = "security/run"))

    override suspend fun securityReport(): ApiResult<SecurityReport> =
        decode(request(path = "security/report"))

    override suspend fun securityIncidents(): ApiResult<List<SecurityIncident>> =
        decodeList(
            request(path = "security/incidents"),
            ListSerializer(SecurityIncident.serializer()),
        )

    override suspend fun reportIncident(
        motivo: String,
        tentativas: Int,
        bloqueadoAte: String,
    ): ApiResult<ActionResponse> =
        decode(
            postJson(
                "security/incident",
                buildJsonObject {
                    put("motivo", JsonPrimitive(motivo))
                    put("tentativas", JsonPrimitive(tentativas))
                    put("bloqueado_ate", JsonPrimitive(bloqueadoAte))
                },
            )
        )

    override suspend fun cleanupTasks(): ApiResult<List<CleanupTask>> =
        decodeList(request(path = "cleanup/tasks"), ListSerializer(CleanupTask.serializer()))

    override suspend fun cleanupRun(taskId: String?, mode: String?, pin: String): ApiResult<JsonElement> {
        val body = buildJsonObject {
            taskId?.let { put("task", JsonPrimitive(it)) }
            mode?.let { put("mode", JsonPrimitive(it)) }
        }
        val result = request(
            path = "cleanup/run",
            method = HttpMethod.Post,
            body = json.encodeToString(JsonObject.serializer(), body),
            headers = buildMap {
                put("Idempotency-Key", UUID.randomUUID().toString())
                if (pin.isNotBlank()) put("X-Action-PIN", pin)
            },
        )
        return when (result) {
            is ApiResult.Success -> try {
                ApiResult.Success(json.parseToJsonElement(result.body), result.elapsedMs)
            } catch (error: SerializationException) {
                ApiResult.NetworkError(IllegalStateException("Resposta invalida", error))
            }

            is ApiResult.HttpError -> result
            is ApiResult.NetworkError -> result
        }
    }

    override suspend fun trilhaProjects(): ApiResult<List<TrilhaProject>> =
        decodeList(request(path = "trilha/projects"), ListSerializer(TrilhaProject.serializer()))

    override suspend fun youtubeJobs(): ApiResult<List<YoutubeJob>> =
        decodeList(request(path = "youtube/jobs"), ListSerializer(YoutubeJob.serializer()))

    override suspend fun youtubeDownload(url: String, format: String): ApiResult<YoutubeJob> =
        decode(
            request(
                path = "youtube/download",
                method = HttpMethod.Post,
                body = json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("url", JsonPrimitive(url))
                        put("format", JsonPrimitive(format))
                    },
                ),
            )
        )

    override suspend fun revokeDevice(deviceId: String, pin: String): ApiResult<ActionResponse> =
        decode(
            request(
                path = "devices/$deviceId/revoke",
                method = HttpMethod.Post,
                headers = buildMap {
                    put("Idempotency-Key", UUID.randomUUID().toString())
                    if (pin.isNotBlank()) put("X-Action-PIN", pin)
                },
            )
        )

    override suspend fun ragDocuments(): ApiResult<List<RagDocument>> =
        decodeList(request(path = "rag/documents"), ListSerializer(RagDocument.serializer()))

    override suspend fun ragUpload(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ApiResult<ActionResponse> {
        val selected = transportSelector.select()
        return decode(selected.transport.upload("rag/upload", fileName, mimeType, bytes))
    }

    override suspend fun ragQuery(question: String): ApiResult<RagResult> =
        decode(postJson("rag/query", buildJsonObject { put("question", JsonPrimitive(question)) }))

    override suspend fun ragChat(question: String): ApiResult<AnswerResult> =
        decode(postJson("rag/chat", buildJsonObject { put("question", JsonPrimitive(question)) }))

    override suspend fun ragDelete(documentId: Int): ApiResult<ActionResponse> =
        decode(
            request(
                path = "rag/delete/$documentId",
                method = HttpMethod.Post,
                headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
            )
        )

    override suspend fun webSearch(query: String): ApiResult<RagResult> =
        decode(postJson("websearch/search", buildJsonObject { put("query", JsonPrimitive(query)) }))

    override suspend fun webSearchChat(query: String): ApiResult<AnswerResult> =
        decode(postJson("websearch/chat", buildJsonObject { put("query", JsonPrimitive(query)) }))

    override suspend fun plugins(): ApiResult<List<PluginInfo>> =
        decodeList(request(path = "plugins"), ListSerializer(PluginInfo.serializer()))

    override suspend fun models(): ApiResult<ModelsResponse> = decode(request(path = "models"))

    override suspend fun currentModel(): ApiResult<ModelInfo> = decode(request(path = "model"))

    override suspend fun setModel(model: String): ApiResult<ModelInfo> =
        decode(postJson("model", buildJsonObject { put("model", JsonPrimitive(model)) }))

    override suspend fun contextInfo(): ApiResult<ContextInfo> = decode(request(path = "context"))

    override suspend fun clearChat(conversationId: String?): ApiResult<ActionResponse> =
        decode(
            postJson(
                "chat/clear",
                buildJsonObject {
                    conversationId?.let { put("conversation_id", JsonPrimitive(it)) }
                },
            )
        )

    override suspend fun sendImage(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        caption: String,
        conversationId: String,
    ): ApiResult<ImageAnalysis> {
        val selected = transportSelector.select()
        return decode(
            selected.transport.upload(
                "media/image", fileName, mimeType, bytes,
                mapOf("caption" to caption, "conversation_id" to conversationId),
            )
        )
    }

    override suspend fun sendVideo(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ApiResult<MediaUpload> {
        val selected = transportSelector.select()
        return decode(selected.transport.upload("media/video", fileName, mimeType, bytes))
    }

    override suspend fun sendAudio(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        caption: String,
        conversationId: String,
    ): ApiResult<AudioAnswer> {
        val selected = transportSelector.select()
        return decode(
            selected.transport.upload(
                "media/audio", fileName, mimeType, bytes,
                mapOf("caption" to caption, "conversation_id" to conversationId),
            )
        )
    }

    override suspend fun sendDocument(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ApiResult<DocumentText> {
        val selected = transportSelector.select()
        return decode(selected.transport.upload("media/document", fileName, mimeType, bytes))
    }

    private suspend fun postJson(path: String, body: JsonObject): ApiResult<String> =
        request(
            path = path,
            method = HttpMethod.Post,
            body = json.encodeToString(JsonObject.serializer(), body),
            headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
        )

    override fun events(): Flow<CyberEvent> = emptyFlow()

    private suspend fun request(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        body: String? = null,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): ApiResult<String> {
        val selectedTransport = transportSelector.select()
        return selectedTransport.transport.request(
            ApiCall(
                path = path,
                method = method,
                body = body,
                query = query,
                headers = headers,
            )
        )
    }

    private inline fun <reified T> decode(result: ApiResult<String>): ApiResult<T> =
        when (result) {
            is ApiResult.Success -> {
                try {
                    ApiResult.Success(
                        body = json.decodeFromString<T>(result.body),
                        elapsedMs = result.elapsedMs,
                    )
                } catch (error: SerializationException) {
                    ApiResult.NetworkError(
                        IllegalStateException("Resposta invalida para ${T::class.simpleName}", error)
                    )
                }
            }

            is ApiResult.HttpError -> result
            is ApiResult.NetworkError -> result
        }

    private fun <T> decodeList(
        result: ApiResult<String>,
        serializer: kotlinx.serialization.KSerializer<List<T>>,
    ): ApiResult<List<T>> =
        when (result) {
            is ApiResult.Success -> {
                try {
                    ApiResult.Success(
                        body = json.decodeFromString(serializer, result.body),
                        elapsedMs = result.elapsedMs,
                    )
                } catch (error: SerializationException) {
                    ApiResult.NetworkError(IllegalStateException("Resposta invalida para lista", error))
                }
            }

            is ApiResult.HttpError -> result
            is ApiResult.NetworkError -> result
        }
}

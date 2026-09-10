package com.cyberbot.mobile.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PairingRequest(
    val code: String,
    val device_name: String,
    val device_pubkey: String? = null,
    val app_version: String,
    val device_fingerprint: String? = null,
)

@Serializable
data class TransportHint(
    val direct: List<String> = emptyList(),
    val relay: String? = null,
)

@Serializable
data class IrohHint(
    val endpoint_id: String? = null,
    val ticket: String? = null,
    val alpn: String? = null,
)

@Serializable
data class PairingResponse(
    val device_id: String,
    val token: String,
    val pc_name: String,
    val transport_hint: TransportHint = TransportHint(),
    val pin_set: Boolean = false,
    val iroh: IrohHint? = null,
)

@Serializable
data class PcInfo(
    val hostname: String = "",
    val uptime_s: Long = 0,
    val tailnet_ip: String? = null,
    val lan_ip: String? = null,
)

@Serializable
data class BotInfo(
    val running: Boolean = false,
    val pid: Long? = null,
)

@Serializable
data class OllamaInfo(
    val running: Boolean = false,
    val model: String? = null,
    val context: JsonObject? = null,
)

@Serializable
data class TransportStatusInfo(
    val server_time: String? = null,
    val version: String? = null,
)

@Serializable
data class StatusResponse(
    val pc: PcInfo = PcInfo(),
    val bot: BotInfo = BotInfo(),
    val ollama: OllamaInfo = OllamaInfo(),
    val transport: TransportStatusInfo = TransportStatusInfo(),
)

@Serializable
data class SysmonSnapshot(
    val cpu: Double? = null,
    val ram: Double? = null,
    val gpu: Double? = null,
    val temperature: Double? = null,
)

@Serializable
data class ChatHistoryItem(val role: String, val content: String)

@Serializable
data class ChatRequest(
    val conversation_id: String,
    val message: String,
    val model: String? = null,
    val history: List<ChatHistoryItem>? = null,
)

@Serializable
data class ChatResponse(
    val reply: String,
    val model: String,
    val elapsed_ms: Long,
    val conversation_id: String,
)

@Serializable
data class PcAppItem(
    val id: String,
    val label: String,
    val destructive: Boolean = false,
)

@Serializable
data class RunPcAppRequest(
    val id: String,
)

@Serializable
data class SleepPcRequest(
    val minutes: Int,
)

@Serializable
data class ActionResponse(
    val status: String,
    val label: String? = null,
    val command: String? = null,
    val at: String? = null,
)

@Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    val created_at: String? = null,
    val last_seen: String? = null,
    val last_ip: String? = null,
    val revoked: Boolean = false,
)

@Serializable
data class UpdatePinRequest(
    val current_pin: String? = null,
    val new_pin: String,
)

@Serializable
data class ApiErrorBody(
    val code: String,
    val message: String,
)

@Serializable
data class ApiErrorEnvelope(
    val error: ApiErrorBody,
)

@Serializable
data class SysmonHistoryItem(
    val id: Long = 0,
    val cpu: Double = 0.0,
    val cpu_temp: Double = 0.0,
    val ram_pct: Double = 0.0,
    val ram_used: Long = 0,
    val ram_total: Long = 0,
    val gpu_pct: Double = 0.0,
    val gpu_temp: Double = 0.0,
    val gpu_vram_used: Long = 0,
    val gpu_vram_total: Long = 0,
    val created_at: String? = null,
)

@Serializable
data class DriveFolder(val id: Int, val name: String = "", val parent_id: Int? = null)

@Serializable
data class DriveFile(
    val id: Int,
    val name: String = "",
    val file_size: Long = 0,
    val mime_type: String? = null,
    val folder_id: Int? = null,
    val file_path: String? = null,
)

@Serializable
data class UploadResult(val id: Int, val name: String = "", val size: Long = 0, val mime: String? = null)

@Serializable
data class FinanceTotals(
    val total_transacoes: Int = 0,
    val total_gasto: Double = 0.0,
    val media_gasto: Double = 0.0,
    val gasto_mes: Double = 0.0,
)

@Serializable
data class FinanceBucket(val name: String = "", val qtde: Int = 0, val total: Double = 0.0)

@Serializable
data class FinanceSummary(
    val resumo: FinanceTotals = FinanceTotals(),
    val categorias: List<JsonObject> = emptyList(),
    val contas: List<JsonObject> = emptyList(),
    val diario: List<JsonObject> = emptyList(),
    val meses: List<String> = emptyList(),
)

@Serializable
data class SecurityMeta(val cmd: String? = null, val desc: String? = null, val risk: String? = null)

@Serializable
data class SecurityResult(
    val status: String = "ok",
    val alerts: List<String> = emptyList(),
    val attentions: List<String> = emptyList(),
    val meta: SecurityMeta = SecurityMeta(),
)

@Serializable
data class SecurityReport(val report: Map<String, SecurityResult> = emptyMap(), val ia_analysis: String = "")

/** Tentativa de acesso barrada na tela de bloqueio do app. */
@Serializable
data class SecurityIncident(
    val id: Int = 0,
    val date: String = "",
    val motivo: String = "",
    val tentativas: Int = 0,
    val dispositivo: String = "",
    val bloqueado_ate: String = "",
    val notificado: Int = 0,
    val created_at: String = "",
)

@Serializable
data class CleanupTask(
    val id: String,
    val name: String = "",
    val desc: String = "",
    val needs_sudo: Boolean = false,
    val safe: Boolean = true,
)

@Serializable
data class CleanupResult(
    val id: String = "",
    val ok: Boolean = false,
    val stdout: String? = null,
    val stderr: String? = null,
    val exit: Int = 0,
)

@Serializable
data class TrilhaProject(
    val id: Int,
    val name: String = "",
    val status: String? = null,
    val project_path: String? = null,
    val absolute_path: String? = null,
    val original: String? = null,
    val created_at: String? = null,
)

@Serializable
data class YoutubeJob(
    val job_id: String,
    val state: String = "running",
    val title: String? = null,
    val url: String? = null,
    val format: String? = null,
    val error: String? = null,
    val file_id: Int? = null,
    val name: String? = null,
)

@Serializable
data class RagDocument(val id: Int, val name: String = "", val chunks: Int = 0, val created_at: String? = null)

@Serializable
data class RagResult(val context: String = "", val sources: List<String> = emptyList())

@Serializable
data class AnswerResult(val reply: String = "", val sources: List<String> = emptyList())

@Serializable
data class PluginInfo(val name: String, val description: String = "", val commands: List<String> = emptyList())

@Serializable
data class ModelsResponse(val models: List<String> = emptyList())

@Serializable
data class ModelInfo(val model: String = "", val status: String? = null)

@Serializable
data class ContextInfo(val model: String? = null, val max_context: Int = 0, val optimal_context: Int = 0)

@Serializable
data class ImageAnalysis(val reply: String = "", val model: String? = null)

@Serializable
data class DocumentText(
    val name: String = "",
    val file_id: Int = 0,
    val pages: Int = 0,
    val chars: Int = 0,
    val truncated: Boolean = false,
    val text: String = "",
)

@Serializable
data class SavedMessage(val role: String, val text: String, val elapsedMs: Long? = null)

@Serializable
data class SavedConversation(
    val id: String,
    val title: String,
    val conversationId: String,
    val model: String,
    val savedAt: Long,
    val messages: List<SavedMessage> = emptyList(),
)

@Serializable
data class MediaUpload(val file_id: Int = 0, val name: String = "", val size: Long = 0)

@Serializable
data class AudioAnswer(
    val reply: String = "",
    val model: String? = null,
    val audio_mime: String? = null,
    val audio_b64: String? = null,
    val elapsed_ms: Long = 0,
)

enum class TransportMode {
    AUTO,
    DIRECT_ONLY,
    RELAY_ONLY,
}

data class TransportHealth(
    val reachable: Boolean,
    val latencyMs: Long? = null,
    val path: String,
    val endpoint: String? = null,
    val reason: String? = null,
)

sealed interface CyberEvent {
    data class Status(
        val bot: Boolean,
        val ollama: Boolean,
        val cpu: Double? = null,
        val ram: Double? = null,
    ) : CyberEvent

    data class Alert(
        val source: String,
        val severity: String,
        val message: String,
    ) : CyberEvent

    data class Job(
        val id: String,
        val kind: String,
        val state: String,
        val detail: String? = null,
    ) : CyberEvent
}

data class SessionSnapshot(
    val deviceId: String? = null,
    val pcName: String? = null,
    val directBaseUrl: String = "http://10.93.220.250:5000",
    val relayUrl: String = "",
    val transportMode: TransportMode = TransportMode.AUTO,
    val pinSet: Boolean = false,
    val irohEndpointId: String = "",
    val irohTicket: String = "",
    val directCandidates: List<String> = emptyList(),
) {
    val isPaired: Boolean = !deviceId.isNullOrBlank()
}

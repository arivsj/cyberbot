package com.cyberbot.mobile.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cyberbot.mobile.ui.common.CyberFab
import com.cyberbot.mobile.ui.common.CyberIconButton
import com.cyberbot.mobile.ui.common.CyberTextButton
import com.cyberbot.mobile.core.model.ChatHistoryItem
import com.cyberbot.mobile.core.model.SavedConversation
import com.cyberbot.mobile.core.model.SavedMessage
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.core.storage.ChatStorage
import com.cyberbot.mobile.data.repo.ChatHistoryRepository
import com.cyberbot.mobile.data.repo.ChatRepository
import com.cyberbot.mobile.data.repo.MediaRepository
import com.cyberbot.mobile.data.repo.ModelRepository
import com.cyberbot.mobile.ui.common.CyberCard
import com.cyberbot.mobile.ui.common.ErrorPane
import com.cyberbot.mobile.ui.common.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessageUi(
    val id: String,
    val role: String,
    val text: String,
    val elapsedMs: Long? = null,
    val audio: ByteArray? = null,
)

enum class AttachmentKind { IMAGE, VIDEO, FILE, AUDIO }

data class PendingAttachment(
    val kind: AttachmentKind,
    val name: String,
    val mime: String,
    val bytes: ByteArray,
)

data class ChatUiState(
    val model: String = "gemma4",
    val draft: String = "",
    val sending: Boolean = false,
    val conversationId: String = "c_${UUID.randomUUID()}",
    val messages: List<ChatMessageUi> = emptyList(),
    val error: String? = null,
    val pending: PendingAttachment? = null,
    val savedId: String? = null,
    val replayHistory: Boolean = false,
    val saved: List<SavedConversation> = emptyList(),
    val sendingSince: Long? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val chatHistoryRepository: ChatHistoryRepository,
    private val modelRepository: ModelRepository,
    private val mediaRepository: MediaRepository,
    private val sessionStore: ChatSessionStore,
    private val chatStorage: ChatStorage,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            chatStorage.conversations.collect { list -> _uiState.update { it.copy(saved = list) } }
        }
        viewModelScope.launch {
            val restored = sessionStore.read()
            if (restored != null) {
                _uiState.update {
                    it.copy(
                        conversationId = restored.conversationId,
                        model = restored.model,
                        messages = restored.messages,
                    )
                }
            } else {
                chatStorage.activeConversationId()?.let { activeId ->
                    _uiState.update { it.copy(conversationId = activeId) }
                }
                when (val result = modelRepository.current()) {
                    is ApiResult.Success -> if (result.body.model.isNotBlank()) {
                        _uiState.update { it.copy(model = result.body.model) }
                        persist()
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun persist() {
        val state = _uiState.value
        viewModelScope.launch {
            chatStorage.setActiveConversationId(state.conversationId)
            sessionStore.write(
                ChatSessionStore.Snapshot(
                    conversationId = state.conversationId,
                    model = state.model,
                    messages = state.messages,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun markSending() {
        _uiState.update { it.copy(sending = true, error = null, sendingSince = System.currentTimeMillis()) }
    }

    private fun append(role: String, text: String, audio: ByteArray? = null) {
        _uiState.update {
            it.copy(
                sending = false,
                sendingSince = null,
                error = null,
                messages = it.messages + ChatMessageUi(
                    id = UUID.randomUUID().toString(),
                    role = role,
                    text = text,
                    audio = audio,
                ),
            )
        }
        persist()
    }

    fun updateDraft(value: String) {
        _uiState.update { it.copy(draft = value, error = null) }
    }

    fun updateModel(value: String) {
        _uiState.update { it.copy(model = value, error = null) }
        persist()
    }

    fun clearPending() = _uiState.update { it.copy(pending = null) }

    private fun readUri(uri: Uri, context: Context): Triple<String, String, ByteArray>? {
        return runCatching {
            val resolver = context.contentResolver
            val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: "arquivo"
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            Triple(name, mime, bytes)
        }.getOrNull()
    }

    fun attach(uri: Uri, context: Context, kind: AttachmentKind) {
        val data = readUri(uri, context)
        if (data == null) {
            _uiState.update { it.copy(error = "Não foi possível ler o arquivo.") }
            return
        }
        val (name, mime, bytes) = data
        _uiState.update { it.copy(pending = PendingAttachment(kind, name, mime, bytes), error = null) }
    }

    fun attachAudio(file: File) {
        if (!file.exists() || file.length() == 0L) {
            _uiState.update { it.copy(error = "Gravação vazia.") }
            return
        }
        _uiState.update {
            it.copy(
                pending = PendingAttachment(AttachmentKind.AUDIO, file.name, "audio/mp4", file.readBytes()),
                error = null,
            )
        }
        file.delete()
    }

    fun clear() {
        val conversationId = _uiState.value.conversationId
        _uiState.update {
            it.copy(
                messages = emptyList(),
                error = null,
                draft = "",
                pending = null,
                savedId = null,
                replayHistory = false,
                conversationId = "c_${UUID.randomUUID()}",
            )
        }
        viewModelScope.launch {
            sessionStore.clear()
            chatStorage.setActiveConversationId(_uiState.value.conversationId)
            chatHistoryRepository.clear(conversationId)
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.messages.isEmpty()) {
            _uiState.update { it.copy(error = "Nada para salvar ainda.") }
            return
        }
        val id = state.savedId ?: UUID.randomUUID().toString()
        val title = state.messages.firstOrNull { it.role == "Você" }?.text?.take(40)?.ifBlank { null }
            ?: "Conversa ${state.messages.size}"
        viewModelScope.launch {
            chatStorage.save(
                SavedConversation(
                    id = id,
                    title = title,
                    conversationId = state.conversationId,
                    model = state.model,
                    savedAt = System.currentTimeMillis(),
                    messages = state.messages.map { SavedMessage(it.role, it.text, it.elapsedMs) },
                )
            )
            _uiState.update { it.copy(savedId = id, error = null) }
        }
    }

    fun open(id: String) {
        viewModelScope.launch {
            val saved = chatStorage.find(id) ?: return@launch
            _uiState.update {
                it.copy(
                    conversationId = saved.conversationId,
                    model = saved.model.ifBlank { it.model },
                    savedId = saved.id,
                    replayHistory = true,
                    pending = null,
                    error = null,
                    messages = saved.messages.map { message ->
                        ChatMessageUi(
                            id = UUID.randomUUID().toString(),
                            role = message.role,
                            text = message.text,
                            elapsedMs = message.elapsedMs,
                        )
                    },
                )
            }
            chatStorage.setActiveConversationId(saved.conversationId)
            persist()
        }
    }

    fun deleteSaved(id: String) {
        viewModelScope.launch {
            chatStorage.delete(id)
            _uiState.update { if (it.savedId == id) it.copy(savedId = null) else it }
        }
    }

    fun send() {
        val state = _uiState.value
        val draft = state.draft.trim()
        val pending = state.pending
        if (draft.isBlank() && pending == null) return

        when (pending?.kind) {
            AttachmentKind.IMAGE -> { sendImage(pending, draft); return }
            AttachmentKind.VIDEO -> { sendToDrive(pending, draft, video = true); return }
            AttachmentKind.FILE -> { sendFile(pending, draft); return }
            AttachmentKind.AUDIO -> { sendAudio(pending, draft); return }
            null -> Unit
        }
        sendText(draft)
    }

    private fun sendText(text: String) {
        val state = _uiState.value
        val history = if (state.replayHistory) {
            state.messages
                .filter { it.role == "Você" || it.role == "CyberBot" }
                .map { ChatHistoryItem(role = if (it.role == "CyberBot") "assistant" else "user", content = it.text) }
        } else {
            null
        }
        append("Você", text)
        _uiState.update { it.copy(draft = "", replayHistory = false) }
        markSending()

        viewModelScope.launch {
            when (
                val result = chatRepository.sendMessage(
                    conversationId = _uiState.value.conversationId,
                    message = text,
                    model = _uiState.value.model,
                    history = history,
                )
            ) {
                is ApiResult.Success -> append("CyberBot", result.body.reply)
                is ApiResult.HttpError -> _uiState.update { it.copy(sending = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(sending = false, error = result.cause.message ?: "Falha ao chamar o chat.")
                }
            }
            persist()
        }
    }

    private fun sendImage(pending: PendingAttachment, caption: String) {
        _uiState.update { it.copy(pending = null, draft = "") }
        markSending()
        append("Você", "🖼️ ${caption.ifBlank { pending.name }}")
        viewModelScope.launch {
            when (val result = mediaRepository.image(pending.name, pending.mime, pending.bytes,
                caption.ifBlank { "Descreva esta imagem." }, _uiState.value.conversationId)) {
                is ApiResult.Success -> append("CyberBot", result.body.reply)
                is ApiResult.HttpError -> _uiState.update { it.copy(sending = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(sending = false, error = result.cause.message ?: "Falha ao enviar imagem.")
                }
            }
            persist()
        }
    }

    private fun sendAudio(pending: PendingAttachment, caption: String) {
        _uiState.update { it.copy(pending = null, draft = "") }
        markSending()
        append("Você", "🎤 ${caption.ifBlank { pending.name }}")
        viewModelScope.launch {
            when (val result = mediaRepository.audio(pending.name, pending.mime, pending.bytes,
                caption.ifBlank { "Transcreva e responda este áudio" }, _uiState.value.conversationId)) {
                is ApiResult.Success -> {
                    val audio = result.body.audio_b64?.let { Base64.decode(it, Base64.DEFAULT) }
                    append("CyberBot", result.body.reply, audio)
                }

                is ApiResult.HttpError -> _uiState.update { it.copy(sending = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(sending = false, error = result.cause.message ?: "Falha ao enviar áudio.")
                }
            }
            persist()
        }
    }

    private fun sendToDrive(pending: PendingAttachment, caption: String, video: Boolean) {
        _uiState.update { it.copy(pending = null, draft = "") }
        markSending()
        append("Você", "${if (video) "🎬" else "📎"} ${caption.ifBlank { pending.name }}")
        viewModelScope.launch {
            when (val result = mediaRepository.video(pending.name, pending.mime, pending.bytes)) {
                is ApiResult.Success -> append("CyberBot",
                    "${if (video) "🎬 Vídeo" else "📎 Arquivo"} salvo no Drive: ${result.body.name}")
                is ApiResult.HttpError -> _uiState.update { it.copy(sending = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(sending = false, error = result.cause.message ?: "Falha ao enviar.")
                }
            }
            persist()
        }
    }

    private fun sendFile(pending: PendingAttachment, caption: String) {
        val isDocument = pending.mime.contains("pdf") || pending.name.lowercase()
            .let { it.endsWith(".pdf") || it.endsWith(".txt") || it.endsWith(".md") || it.endsWith(".csv") }
        if (!isDocument) {
            sendToDrive(pending, caption, video = false)
            return
        }
        _uiState.update { it.copy(pending = null, draft = "") }
        markSending()
        append("Você", "📄 ${caption.ifBlank { pending.name }}")
        viewModelScope.launch {
            when (val result = mediaRepository.document(pending.name, pending.mime, pending.bytes)) {
                is ApiResult.Success -> {
                    val doc = result.body
                    val question = caption.ifBlank { "Resuma este documento." }
                    val context = "Documento \"${doc.name}\" (${doc.pages} páginas, ${doc.chars} caracteres" +
                        (if (doc.truncated) ", truncado" else "") + "):\n\n${doc.text}"
                    val prompt = "$question\n\n---\n$context"
                    when (val reply = chatRepository.sendMessage(
                        conversationId = _uiState.value.conversationId,
                        message = prompt,
                        model = _uiState.value.model,
                        history = null,
                    )) {
                        is ApiResult.Success -> append("CyberBot", reply.body.reply)
                        is ApiResult.HttpError -> _uiState.update { it.copy(sending = false, error = reply.message) }
                        is ApiResult.NetworkError -> _uiState.update {
                            it.copy(sending = false, error = reply.cause.message ?: "Falha ao ler o documento.")
                        }
                    }
                }

                is ApiResult.HttpError -> _uiState.update { it.copy(sending = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(sending = false, error = result.cause.message ?: "Falha ao enviar documento.")
                }
            }
            persist()
        }
    }
}

@Composable
fun ChatRoute(
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordFile by remember { mutableStateOf<File?>(null) }
    var showSaved by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.attach(it, context, AttachmentKind.IMAGE) }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.attach(it, context, AttachmentKind.VIDEO) }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.attach(it, context, AttachmentKind.FILE) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) recording = true }

    LaunchedEffect(recording) {
        if (recording && recorder == null) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                recording = false
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return@LaunchedEffect
            }
            val file = File(context.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
            val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
            else @Suppress("DEPRECATION") MediaRecorder()
            created.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setOutputFile(file.absolutePath)
            }
            runCatching { created.prepare(); created.start() }
                .onSuccess { recorder = created; recordFile = file }
                .onFailure { runCatching { created.release() }; recording = false }
        } else if (!recording && recorder != null) {
            val current = recorder
            recorder = null
            runCatching { current?.stop(); current?.release() }
            recordFile?.let { viewModel.attachAudio(it) }
            recordFile = null
        }
    }

    if (showSaved) {
        SavedConversationsDialog(
            conversations = state.saved,
            onOpen = { id -> viewModel.open(id); showSaved = false },
            onDelete = viewModel::deleteSaved,
            onDismiss = { showSaved = false },
        )
    }

    ChatScreen(
        state = state,
        recording = recording,
        onDraftChange = viewModel::updateDraft,
        onSend = viewModel::send,
        onClear = viewModel::clear,
        onSave = viewModel::save,
        onOpenSaved = { showSaved = true },
        onClearPending = viewModel::clearPending,
        onPickImage = { imagePicker.launch("image/*") },
        onPickVideo = { videoPicker.launch("video/*") },
        onPickFile = { filePicker.launch("*/*") },
        onToggleRecording = { recording = !recording },
    )
}

@Composable
fun SavedConversationsDialog(
    conversations: List<SavedConversation>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conversas salvas") },
        text = {
            if (conversations.isEmpty()) {
                Text("Nenhuma conversa salva. Use o botão Salvar no topo do chat.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(conversations, key = { it.id }) { conversation ->
                        CyberCard(modifier = Modifier.fillMaxWidth()) {
                            Text(conversation.title)
                            Text(
                                "${conversation.messages.size} mensagens | ${conversation.model}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CyberTextButton(onClick = { onOpen(conversation.id) }) { Text("Abrir") }
                                CyberTextButton(onClick = { onDelete(conversation.id) }) { Text("Excluir") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { CyberTextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

@Composable
private fun AudioReplyPlayer(audio: ByteArray) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.release() }
            player = null
        }
    }

    CyberTextButton(onClick = {
        if (playing) {
            runCatching { player?.stop(); player?.release() }
            player = null
            playing = false
        } else {
            runCatching {
                val file = File(context.cacheDir, "reply_${System.currentTimeMillis()}.mp3")
                file.writeBytes(audio)
                player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { playing = false }
                    prepare()
                    start()
                }
                playing = true
            }.onFailure { playing = false }
        }
    }) { Text(if (playing) "⏹ Parar áudio" else "▶ Ouvir resposta") }
}

@Composable
fun ChatScreen(
    state: ChatUiState,
    recording: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onOpenSaved: () -> Unit,
    onClearPending: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickFile: () -> Unit,
    onToggleRecording: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.sending) {
        val last = state.messages.size - (if (state.sending) 0 else 1)
        if (last >= 0) {
            listState.animateScrollToItem(last)
        }
    }

    ScreenScaffold(title = "Chat", topBarActions = {
        CyberTextButton(onClick = onSave) { Text("Salvar", fontSize = 12.sp) }
        CyberTextButton(onClick = onOpenSaved) { Text("Abrir", fontSize = 12.sp) }
        CyberTextButton(onClick = onClear) { Text("Limpar", fontSize = 12.sp) }
    }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.replayHistory) {
                Text(
                    "Conversa retomada: o histórico vai no próximo prompt.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
                if (state.sending) {
                    item(key = "thinking") { ThinkingBubble(state.sendingSince) }
                }
            }
            state.error?.let {
                Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
            }
            state.pending?.let { pending ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Anexo: ${pending.name}", fontSize = 10.sp, modifier = Modifier.weight(1f))
                    CyberTextButton(onClick = onClearPending) { Text("Remover", fontSize = 10.sp) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatIconButton(Icons.Default.Image, "Imagem", onPickImage)
                ChatIconButton(Icons.Default.Videocam, "Video", onPickVideo)
                ChatIconButton(Icons.Default.AttachFile, "Arquivo/PDF", onPickFile)
                ChatIconButton(
                    Icons.Default.Mic, "Audio", onToggleRecording,
                    tint = if (recording) MaterialTheme.colorScheme.error else null,
                )
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    placeholder = {
                        Text(
                            if (recording) "gravando..." else state.model,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        )
                    },
                    textStyle = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                Spacer(Modifier.size(6.dp))
                CyberFab(onClick = onSend) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Enviar",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    CyberIconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThinkingBubble(since: Long?) {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(since) {
        while (true) {
            elapsed = if (since != null) (System.currentTimeMillis() - since) / 1000 else 0
            kotlinx.coroutines.delay(500)
        }
    }
    val hint = when {
        elapsed >= 30 -> "carregando o modelo na VRAM, isso demora na primeira vez..."
        elapsed >= 10 -> "ainda pensando..."
        else -> "IA pensando..."
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
        Spacer(Modifier.size(8.dp))
        Text(
            "$hint ${elapsed}s",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessageUi) {
    val isUser = message.role == "Você"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isUser) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            message.role,
            fontSize = 9.sp,
            color = if (isUser) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
        )
        Text(message.text, fontSize = 12.sp, lineHeight = 16.sp)
        message.elapsedMs?.let {
            Text("${it} ms", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        message.audio?.let { AudioReplyPlayer(it) }
    }
}

package com.cyberbot.mobile.ui.rag

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cyberbot.mobile.ui.common.CyberButton
import com.cyberbot.mobile.ui.common.CyberTextButton
import com.cyberbot.mobile.core.model.RagDocument
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.data.repo.RagRepository
import com.cyberbot.mobile.ui.common.CyberCard
import com.cyberbot.mobile.ui.common.ErrorPane
import com.cyberbot.mobile.ui.common.ScreenScaffold
import com.cyberbot.mobile.ui.common.SectionLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RagUiState(
    val documents: List<RagDocument> = emptyList(),
    val question: String = "",
    val answer: String? = null,
    val sources: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class RagViewModel @Inject constructor(
    private val ragRepository: RagRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RagUiState(isLoading = true))
    val uiState: StateFlow<RagUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = ragRepository.documents()) {
                is ApiResult.Success -> _uiState.update { it.copy(documents = result.body, isLoading = false) }
                is ApiResult.HttpError -> _uiState.update { it.copy(isLoading = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isLoading = false, error = result.cause.message)
                }
            }
        }
    }

    fun setQuestion(value: String) = _uiState.update { it.copy(question = value) }

    fun upload(uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, answer = "Indexando documento...") }
            try {
                val resolver = context.contentResolver
                val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                } ?: "documento"
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                when (val result = ragRepository.upload(name, mime, bytes)) {
                    is ApiResult.Success -> { _uiState.update { it.copy(answer = "Indexado: $name") }; load() }
                    is ApiResult.HttpError -> _uiState.update { it.copy(isLoading = false, error = result.message) }
                    is ApiResult.NetworkError -> _uiState.update {
                        it.copy(isLoading = false, error = result.cause.message)
                    }
                }
            } catch (error: Throwable) {
                _uiState.update { it.copy(isLoading = false, error = error.message) }
            }
        }
    }

    fun ask() {
        val question = _uiState.value.question.trim()
        if (question.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, answer = null, sources = emptyList()) }
            when (val result = ragRepository.ask(question)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, answer = result.body.reply, sources = result.body.sources)
                }

                is ApiResult.HttpError -> _uiState.update { it.copy(isLoading = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isLoading = false, error = result.cause.message)
                }
            }
        }
    }

    fun remove(id: Int) {
        viewModelScope.launch {
            ragRepository.remove(id)
            load()
        }
    }
}

@Composable
fun RagRoute(viewModel: RagViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.upload(uri, context)
    }
    RagScreen(
        state = state,
        onQuestion = viewModel::setQuestion,
        onAsk = viewModel::ask,
        onUpload = { picker.launch("*/*") },
        onRefresh = viewModel::load,
        onRemove = viewModel::remove,
    )
}

@Composable
fun RagScreen(
    state: RagUiState,
    onQuestion: (String) -> Unit,
    onAsk: () -> Unit,
    onUpload: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    ScreenScaffold(title = "RAG", topBarActions = {
        CyberTextButton(onClick = onUpload) { Text("Enviar") }
        CyberTextButton(onClick = onRefresh) { Text("Atualizar") }
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("Perguntar aos documentos")
                    OutlinedTextField(
                        value = state.question,
                        onValueChange = onQuestion,
                        label = { Text("Pergunta") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CyberButton(onClick = onAsk, enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth()) { Text("Perguntar") }
                }
            }
            state.answer?.let { answer ->
                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        SectionLabel("Resposta")
                        Text(answer, style = MaterialTheme.typography.bodyMedium)
                        if (state.sources.isNotEmpty()) {
                            Text("Fontes: ${state.sources.joinToString()}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (state.isLoading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            state.error?.let { message -> item { ErrorPane(message) } }
            item { SectionLabel("Documentos (${state.documents.size})") }
            items(state.documents) { document ->
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    Text(document.name)
                    Text("${document.chunks} trechos | ${document.created_at ?: "-"}",
                        style = MaterialTheme.typography.bodySmall)
                    CyberTextButton(onClick = { onRemove(document.id) }) { Text("Remover") }
                }
            }
        }
    }
}

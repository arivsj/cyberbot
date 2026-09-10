package com.cyberbot.mobile.ui.youtube

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cyberbot.mobile.ui.common.CyberButton
import com.cyberbot.mobile.ui.common.CyberTextButton
import com.cyberbot.mobile.core.model.YoutubeJob
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.data.repo.YoutubeRepository
import com.cyberbot.mobile.ui.common.CyberCard
import com.cyberbot.mobile.ui.common.ErrorPane
import com.cyberbot.mobile.ui.common.ScreenScaffold
import com.cyberbot.mobile.ui.common.SectionLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class YoutubeUiState(
    val url: String = "",
    val format: String = "mp4",
    val jobs: List<YoutubeJob> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class YoutubeViewModel @Inject constructor(
    private val youtubeRepository: YoutubeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(YoutubeUiState())
    val uiState: StateFlow<YoutubeUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            while (true) {
                delay(5000)
                if (_uiState.value.jobs.any { it.state == "running" }) refresh()
            }
        }
    }

    fun setUrl(value: String) = _uiState.update { it.copy(url = value) }
    fun setFormat(value: String) = _uiState.update { it.copy(format = value) }

    fun refresh() {
        viewModelScope.launch {
            when (val result = youtubeRepository.jobs()) {
                is ApiResult.Success -> _uiState.update { it.copy(jobs = result.body) }
                is ApiResult.HttpError -> _uiState.update { it.copy(error = result.message) }
                is ApiResult.NetworkError -> _uiState.update { it.copy(error = result.cause.message) }
            }
        }
    }

    fun download() {
        val state = _uiState.value
        if (state.url.isBlank()) {
            _uiState.update { it.copy(error = "Informe o link do YouTube") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = youtubeRepository.download(state.url, state.format)) {
                is ApiResult.Success -> _uiState.update { it.copy(isLoading = false, url = "") }
                is ApiResult.HttpError -> _uiState.update { it.copy(isLoading = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isLoading = false, error = result.cause.message)
                }
            }
            refresh()
        }
    }
}

@Composable
fun YoutubeRoute(viewModel: YoutubeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    YoutubeScreen(
        state = state,
        onUrl = viewModel::setUrl,
        onFormat = viewModel::setFormat,
        onDownload = viewModel::download,
        onRefresh = viewModel::refresh,
    )
}

@Composable
fun YoutubeScreen(
    state: YoutubeUiState,
    onUrl: (String) -> Unit,
    onFormat: (String) -> Unit,
    onDownload: () -> Unit,
    onRefresh: () -> Unit,
) {
    ScreenScaffold(title = "YouTube", topBarActions = {
        CyberTextButton(onClick = onRefresh) { Text("Atualizar") }
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("Baixar video ou audio")
                    OutlinedTextField(
                        value = state.url,
                        onValueChange = onUrl,
                        label = { Text("Link do YouTube") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(
                            selected = state.format == "mp4",
                            onClick = { onFormat("mp4") },
                            label = { Text("MP4") },
                        )
                        FilterChip(
                            selected = state.format == "mp3",
                            onClick = { onFormat("mp3") },
                            label = { Text("MP3") },
                        )
                    }
                    CyberButton(
                        onClick = onDownload,
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.isLoading) "Enviando..." else "Baixar no PC") }
                    Text(
                        "O download roda no PC e salva na pasta YouTube do Drive.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            state.error?.let { message -> item { ErrorPane(message) } }
            items(state.jobs) { job ->
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    Text("${job.state.uppercase()} | ${job.format ?: "-"}")
                    Text(job.title ?: job.url ?: "-", style = MaterialTheme.typography.bodySmall)
                    job.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

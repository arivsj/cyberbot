package com.cyberbot.mobile.ui.cleanup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cyberbot.mobile.ui.common.CyberButton
import com.cyberbot.mobile.ui.common.CyberTextButton
import com.cyberbot.mobile.core.model.CleanupTask
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.data.repo.CleanupRepository
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

data class CleanupUiState(
    val tasks: List<CleanupTask> = emptyList(),
    val results: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class CleanupViewModel @Inject constructor(
    private val cleanupRepository: CleanupRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CleanupUiState(isLoading = true))
    val uiState: StateFlow<CleanupUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = cleanupRepository.tasks()) {
                is ApiResult.Success -> _uiState.update { it.copy(tasks = result.body, isLoading = false) }
                is ApiResult.HttpError -> _uiState.update { it.copy(isLoading = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isLoading = false, error = result.cause.message)
                }
            }
        }
    }

    fun run(taskId: String?, mode: String?, pin: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = cleanupRepository.run(taskId, mode, pin)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, results = listOf(result.body.toString()))
                }

                is ApiResult.HttpError -> _uiState.update { it.copy(isLoading = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isLoading = false, error = result.cause.message)
                }
            }
        }
    }
}

@Composable
fun CleanupRoute(viewModel: CleanupViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingTask by remember { mutableStateOf<String?>(null) }
    var pendingSafe by remember { mutableStateOf(false) }

    if (pendingTask != null || pendingSafe) {
        PinPromptDialog(
            title = if (pendingSafe) "Limpeza segura" else "Executar tarefa",
            onDismiss = { pendingTask = null; pendingSafe = false },
            onConfirm = { pin ->
                if (pendingSafe) viewModel.run(null, "safe", pin) else viewModel.run(pendingTask, null, pin)
                pendingTask = null
                pendingSafe = false
            },
        )
    }

    CleanupScreen(
        state = state,
        onRefresh = viewModel::load,
        onRunSafe = { pendingSafe = true },
        onRunTask = { pendingTask = it },
    )
}

@Composable
fun CleanupScreen(
    state: CleanupUiState,
    onRefresh: () -> Unit,
    onRunSafe: () -> Unit,
    onRunTask: (String) -> Unit,
) {
    ScreenScaffold(title = "Limpeza", topBarActions = {
        CyberTextButton(onClick = onRefresh) { Text("Atualizar") }
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("Acoes")
                    CyberButton(onClick = onRunSafe, modifier = Modifier.fillMaxWidth()) { Text("Limpeza segura (sem sudo)") }
                }
            }
            if (state.isLoading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            state.error?.let { message -> item { ErrorPane(message) } }
            state.results.forEach { text ->
                item { CyberCard(modifier = Modifier.fillMaxWidth()) { Text(text, style = MaterialTheme.typography.bodySmall) } }
            }
            items(state.tasks) { task ->
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    Text(task.name)
                    Text(task.desc, style = MaterialTheme.typography.bodySmall)
                    if (task.needs_sudo) {
                        Text("Exige sudo - faca pelo desktop", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        CyberButton(onClick = { onRunTask(task.id) }) { Text("Executar") }
                    }
                }
            }
        }
    }
}

@Composable
fun PinPromptDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                label = { Text("PIN de 6 digitos") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { CyberTextButton(onClick = { onConfirm(pin) }) { Text("Confirmar") } },
        dismissButton = { CyberTextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

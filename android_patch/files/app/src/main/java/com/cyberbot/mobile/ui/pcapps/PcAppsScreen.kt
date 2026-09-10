package com.cyberbot.mobile.ui.pcapps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cyberbot.mobile.ui.common.CyberButton
import com.cyberbot.mobile.ui.common.CyberTextButton
import com.cyberbot.mobile.core.model.PcAppItem
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.data.repo.PcAppsRepository
import com.cyberbot.mobile.data.repo.SessionRepository
import com.cyberbot.mobile.ui.common.CyberCard
import com.cyberbot.mobile.ui.common.ErrorPane
import com.cyberbot.mobile.ui.common.ScreenScaffold
import com.cyberbot.mobile.ui.common.SecureScreenEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PcAppsUiState(
    val apps: List<PcAppItem> = emptyList(),
    val isLoading: Boolean = true,
    val actionMessage: String? = null,
    val error: String? = null,
    val pinDialogFor: PcAppItem? = null,
    val sleepMinutes: String = "90",
    val pin: String = "",
)

@HiltViewModel
class PcAppsViewModel @Inject constructor(
    private val repository: PcAppsRepository,
    private val sessionRepository: SessionRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        PcAppsUiState(pin = sessionRepository.cachedPin().orEmpty())
    )
    val uiState: StateFlow<PcAppsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = repository.listApps()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, apps = result.body)
                }
                is ApiResult.HttpError -> _uiState.update {
                    it.copy(isLoading = false, error = result.message)
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isLoading = false, error = result.cause.message ?: "Falha ao carregar apps.")
                }
            }
        }
    }

    fun requestRun(app: PcAppItem) {
        if (app.destructive) {
            _uiState.update { it.copy(pinDialogFor = app, actionMessage = null) }
        } else {
            runApp(app, pin = null)
        }
    }

    fun updatePin(value: String) {
        _uiState.update { it.copy(pin = value, error = null) }
    }

    fun updateSleepMinutes(value: String) {
        _uiState.update { it.copy(sleepMinutes = value.filter(Char::isDigit), error = null) }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(pinDialogFor = null) }
    }

    fun confirmDestructive() {
        val app = _uiState.value.pinDialogFor ?: return
        val pin = _uiState.value.pin
        if (pin.isBlank()) {
            _uiState.update { it.copy(error = "Informe o PIN para esta acao.") }
            return
        }

        sessionRepository.saveCachedPin(pin)
        if (app.id == "sleep") {
            val minutes = _uiState.value.sleepMinutes.toIntOrNull()
            if (minutes == null || minutes !in 1..1440) {
                _uiState.update { it.copy(error = "Use um tempo entre 1 e 1440 minutos.") }
                return
            }
            viewModelScope.launch {
                when (val result = repository.scheduleSleep(minutes, pin)) {
                    is ApiResult.Success -> _uiState.update {
                        it.copy(
                            pinDialogFor = null,
                            actionMessage = result.body.command ?: result.body.status,
                            error = null,
                        )
                    }
                    is ApiResult.HttpError -> _uiState.update { it.copy(error = result.message) }
                    is ApiResult.NetworkError -> _uiState.update {
                        it.copy(error = result.cause.message ?: "Falha ao agendar sleep.")
                    }
                }
            }
        } else {
            runApp(app, pin)
        }
    }

    private fun runApp(app: PcAppItem, pin: String?) {
        viewModelScope.launch {
            when (val result = repository.runApp(app.id, pin)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        pinDialogFor = null,
                        actionMessage = result.body.label ?: result.body.status,
                        error = null,
                    )
                }
                is ApiResult.HttpError -> _uiState.update { it.copy(error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(error = result.cause.message ?: "Falha ao executar ${app.label}.")
                }
            }
        }
    }
}

@Composable
fun PcAppsRoute(
    viewModel: PcAppsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PcAppsScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onRunApp = viewModel::requestRun,
        onDismissDialog = viewModel::dismissDialog,
        onPinChange = viewModel::updatePin,
        onSleepMinutesChange = viewModel::updateSleepMinutes,
        onConfirmDestructive = viewModel::confirmDestructive,
    )
}

@Composable
fun PcAppsScreen(
    state: PcAppsUiState,
    onRefresh: () -> Unit,
    onRunApp: (PcAppItem) -> Unit,
    onDismissDialog: () -> Unit,
    onPinChange: (String) -> Unit,
    onSleepMinutesChange: (String) -> Unit,
    onConfirmDestructive: () -> Unit,
) {
    if (state.pinDialogFor != null) {
        SecureScreenEffect(enabled = true)
        AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text("Confirmar ${state.pinDialogFor.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.pinDialogFor.id == "sleep") {
                        OutlinedTextField(
                            value = state.sleepMinutes,
                            onValueChange = onSleepMinutesChange,
                            label = { Text("Minutos") },
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(
                        value = state.pin,
                        onValueChange = onPinChange,
                        label = { Text("PIN da acao") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                CyberTextButton(onClick = onConfirmDestructive) { Text("Executar") }
            },
            dismissButton = {
                CyberTextButton(onClick = onDismissDialog) { Text("Cancelar") }
            },
        )
    }

    ScreenScaffold(title = "PC Apps") { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.actionMessage != null) {
                Text(
                    state.actionMessage,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.error?.let { ErrorPane(it) }
            CyberButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("Recarregar lista")
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.apps, key = { it.id }) { app ->
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Text(app.label)
                        Text(
                            if (app.destructive) "Requer PIN" else "Execucao direta",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CyberButton(onClick = { onRunApp(app) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Executar")
                        }
                    }
                }
            }
        }
    }
}

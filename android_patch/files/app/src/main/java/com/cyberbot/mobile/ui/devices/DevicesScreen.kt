package com.cyberbot.mobile.ui.devices

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
import com.cyberbot.mobile.core.model.DeviceInfo
import com.cyberbot.mobile.core.model.UpdatePinRequest
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.data.repo.DevicesRepository
import com.cyberbot.mobile.data.repo.SessionRepository
import com.cyberbot.mobile.ui.cleanup.PinPromptDialog
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

data class DevicesUiState(
    val devices: List<DeviceInfo> = emptyList(),
    val newPin: String = "",
    val isLoading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val devicesRepository: DevicesRepository,
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DevicesUiState(isLoading = true))
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = devicesRepository.listDevices()) {
                is ApiResult.Success -> _uiState.update { it.copy(devices = result.body, isLoading = false) }
                is ApiResult.HttpError -> _uiState.update { it.copy(isLoading = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isLoading = false, error = result.cause.message)
                }
            }
        }
    }

    fun revoke(deviceId: String, pin: String) {
        viewModelScope.launch {
            when (val result = devicesRepository.revoke(deviceId, pin)) {
                is ApiResult.Success -> { _uiState.update { it.copy(message = "Dispositivo revogado") }; load() }
                is ApiResult.HttpError -> _uiState.update { it.copy(error = result.message) }
                is ApiResult.NetworkError -> _uiState.update { it.copy(error = result.cause.message) }
            }
        }
    }

    fun setNewPin(value: String) = _uiState.update { it.copy(newPin = value.filter(Char::isDigit).take(6)) }

    fun savePin() {
        val pin = _uiState.value.newPin
        if (pin.length != 6) {
            _uiState.update { it.copy(error = "PIN deve ter 6 digitos") }
            return
        }
        viewModelScope.launch {
            when (val result = devicesRepository.updatePin(null, pin)) {
                is ApiResult.Success -> {
                    sessionRepository.saveCachedPin(pin)
                    _uiState.update { it.copy(message = "PIN salvo", newPin = "", error = null) }
                }

                is ApiResult.HttpError -> _uiState.update { it.copy(error = result.message) }
                is ApiResult.NetworkError -> _uiState.update { it.copy(error = result.cause.message) }
            }
        }
    }
}

@Composable
fun DevicesRoute(viewModel: DevicesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var revokeTarget by remember { mutableStateOf<String?>(null) }

    revokeTarget?.let { target ->
        PinPromptDialog(
            title = "Revogar dispositivo",
            onDismiss = { revokeTarget = null },
            onConfirm = { pin -> viewModel.revoke(target, pin); revokeTarget = null },
        )
    }

    DevicesScreen(
        state = state,
        onRefresh = viewModel::load,
        onRevoke = { revokeTarget = it },
        onNewPinChange = viewModel::setNewPin,
        onSavePin = viewModel::savePin,
    )
}

@Composable
fun DevicesScreen(
    state: DevicesUiState,
    onRefresh: () -> Unit,
    onRevoke: (String) -> Unit,
    onNewPinChange: (String) -> Unit,
    onSavePin: () -> Unit,
) {
    ScreenScaffold(title = "Dispositivos", topBarActions = {
        CyberTextButton(onClick = onRefresh) { Text("Atualizar") }
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("PIN das acoes destrutivas")
                    OutlinedTextField(
                        value = state.newPin,
                        onValueChange = onNewPinChange,
                        label = { Text("Novo PIN (6 digitos)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CyberButton(onClick = onSavePin, modifier = Modifier.fillMaxWidth()) { Text("Salvar PIN") }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            }
            if (state.isLoading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            state.error?.let { message -> item { ErrorPane(message) } }
            items(state.devices) { device ->
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    Text("${device.name} ${if (device.revoked) "(revogado)" else ""}")
                    Text(
                        "${device.id} | ultimo acesso: ${device.last_seen ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!device.revoked) {
                        CyberTextButton(onClick = { onRevoke(device.id) }) { Text("Revogar") }
                    }
                }
            }
        }
    }
}

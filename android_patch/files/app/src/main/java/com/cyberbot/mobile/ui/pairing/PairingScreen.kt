package com.cyberbot.mobile.ui.pairing

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import com.cyberbot.mobile.ui.common.CyberButton
import com.cyberbot.mobile.core.model.PairingPayload
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.data.repo.SessionRepository
import com.cyberbot.mobile.ui.common.CyberCard
import com.cyberbot.mobile.ui.common.ErrorPane
import com.cyberbot.mobile.ui.common.SecureScreenEffect
import com.cyberbot.mobile.ui.common.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PairingUiState(
    val code: String = "",
    val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val endpoint: String = "http://10.93.220.250:5000",
    val isSubmitting: Boolean = false,
    val message: String = "Leia o QR Code gerado no desktop (Configurar P2P) ou digite o codigo de 6 digitos e o endpoint direto.",
    val error: String? = null,
    val scanned: PairingPayload? = null,
)

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    fun updateCode(value: String) {
        _uiState.update { it.copy(code = value.take(6).filter(Char::isDigit), error = null) }
    }

    fun updateDeviceName(value: String) {
        _uiState.update { it.copy(deviceName = value, error = null) }
    }

    fun updateEndpoint(value: String) {
        _uiState.update { it.copy(endpoint = value, error = null) }
    }

    /** Aplica o conteudo do QR Code (codigo + endpoint + ticket Iroh) e ja pareia. */
    fun applyQr(payload: PairingPayload) {
        val alvo = payload.directUrls.firstOrNull()
        _uiState.update { current ->
            current.copy(
                code = payload.code,
                endpoint = alvo ?: current.endpoint,
                scanned = payload,
                error = null,
                message = buildString {
                    append("QR lido — senha ").append(payload.code)
                    append("\nPC: ").append(alvo ?: "sem endereço no QR (usando o salvo)")
                    append(
                        if (payload.hasIroh) "\nIroh: ok (funciona fora de casa)"
                        else "\nIroh: ausente (só na mesma rede)"
                    )
                },
            )
        }
        viewModelScope.launch {
            // Guarda endereços + ticket ANTES de parear: assim o pareamento já pode
            // usar o Iroh (4G) e o app tenta todos os endereços que vieram no QR.
            sessionRepository.prepareFromQr(payload.directUrls, payload.irohEndpointId, payload.irohTicket)
            pair()
        }
    }

    fun pair() {
        val state = _uiState.value
        if (state.isSubmitting) return
        if (state.code.length != 6) {
            _uiState.update { it.copy(error = "Informe um codigo valido de 6 digitos.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            when (val result = sessionRepository.pair(state.code, state.deviceName, state.endpoint)) {
                is ApiResult.Success -> {
                    val session = sessionRepository.session.first()
                    if (session.irohTicket.isBlank()) {
                        sessionRepository.saveIrohHint(
                            endpointId = state.scanned?.irohEndpointId,
                            ticket = state.scanned?.irohTicket,
                        )
                    }
                    _uiState.update { it.copy(isSubmitting = false, message = "Pareamento concluido.") }
                }
                is ApiResult.HttpError -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            error = result.message,
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            error = result.cause.message ?: "Falha de rede ao parear.",
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PairingRoute(
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }

    if (scanning) {
        BackHandler { scanning = false }
        QrScannerRoute(
            onScanned = { payload ->
                scanning = false
                viewModel.applyQr(payload)
            },
            onClose = { scanning = false },
        )
        return
    }

    PairingScreen(
        state = uiState,
        onCodeChange = viewModel::updateCode,
        onDeviceNameChange = viewModel::updateDeviceName,
        onEndpointChange = viewModel::updateEndpoint,
        onPair = viewModel::pair,
        onScanQr = { scanning = true },
    )
}

@Composable
fun PairingScreen(
    state: PairingUiState,
    onCodeChange: (String) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onPair: () -> Unit,
    onScanQr: () -> Unit,
) {
    SecureScreenEffect(enabled = true)
    ScreenScaffold(title = "Pairing") { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CyberCard(modifier = Modifier.fillMaxWidth()) {
                Text("CyberBot Mobile", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            CyberCard(modifier = Modifier.fillMaxWidth()) {
                CyberButton(
                    onClick = onScanQr,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Ler QR Code do PC")
                }
                Text(
                    text = "Mais rapido e ja inclui o ticket Iroh (conexao fora da rede local).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            CyberCard(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.code,
                    onValueChange = onCodeChange,
                    label = { Text("Codigo de 6 digitos") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.deviceName,
                    onValueChange = onDeviceNameChange,
                    label = { Text("Nome do dispositivo") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.endpoint,
                    onValueChange = onEndpointChange,
                    label = { Text("Endpoint direto") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("Ex.: http://100.x.y.z:5005 ou http://10.93.220.250:5005")
                    },
                    singleLine = true,
                )
                state.error?.let { ErrorPane(it) }
                CyberButton(
                    onClick = onPair,
                    enabled = !state.isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isSubmitting) "Pareando..." else "Parear com o PC")
                }
            }
        }
    }
}

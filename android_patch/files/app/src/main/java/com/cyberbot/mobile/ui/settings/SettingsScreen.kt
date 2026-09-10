package com.cyberbot.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cyberbot.mobile.core.model.SessionSnapshot
import com.cyberbot.mobile.core.model.TransportHealth
import com.cyberbot.mobile.core.model.TransportMode
import com.cyberbot.mobile.data.repo.SessionRepository
import com.cyberbot.mobile.data.repo.StatusRepository
import com.cyberbot.mobile.ui.common.CyberCard
import com.cyberbot.mobile.ui.common.SectionLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val session: SessionSnapshot = SessionSnapshot(),
    val directBaseUrl: String = "http://10.93.220.250:5000",
    val relayUrl: String = "",
    val cachedPin: String = "",
    val health: TransportHealth? = null,
    val testing: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val statusRepository: StatusRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(cachedPin = sessionRepository.cachedPin().orEmpty())
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sessionRepository.session.collect { session ->
                _uiState.update {
                    it.copy(
                        session = session,
                        directBaseUrl = session.directBaseUrl,
                        relayUrl = session.relayUrl,
                    )
                }
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(testing = true) }
            val health = statusRepository.transportHealth(forceRefresh = true)
            _uiState.update { it.copy(testing = false, health = health) }
        }
    }

    fun setTransportMode(mode: TransportMode) {
        viewModelScope.launch { sessionRepository.saveTransportMode(mode) }
    }

    fun updateDirectBaseUrl(value: String) {
        _uiState.update { it.copy(directBaseUrl = value) }
    }

    fun updateRelayUrl(value: String) {
        _uiState.update { it.copy(relayUrl = value) }
    }

    fun updateCachedPin(value: String) {
        _uiState.update { it.copy(cachedPin = value) }
    }

    fun persistNetworkConfig() {
        viewModelScope.launch {
            sessionRepository.saveDirectBaseUrl(_uiState.value.directBaseUrl)
            sessionRepository.saveRelayUrl(_uiState.value.relayUrl)
        }
    }

    fun persistCachedPin() {
        sessionRepository.saveCachedPin(_uiState.value.cachedPin)
    }

    fun clearLocalData() {
        viewModelScope.launch {
            sessionRepository.clearAll()
        }
    }

    /** Sai da conexão: o app volta para a tela de pareamento (pode parear de novo). */
    fun unpair() {
        viewModelScope.launch {
            sessionRepository.unpair()
        }
    }
}

/**
 * Cartões de conexão/transporte/PIN — antes viviam no Hub. Agora aparecem na aba
 * Status (o Hub virou a Home, que mostra só os módulos).
 */
@Composable
fun SettingsSection(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmUnpair by remember { mutableStateOf(false) }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Sair da conexão?") },
            text = {
                Text(
                    "O app esquece o PC pareado e volta para a tela de leitura de QR Code. " +
                        "Para usar de novo, gere um novo código no desktop.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnpair = false
                        viewModel.unpair()
                    },
                ) {
                    Text("Sair da conexão")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text("Cancelar") }
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CyberCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Conexão")
            Text("PC pareado: ${state.session.pcName ?: "nenhum"}")
            Text("Device ID: ${state.session.deviceId ?: "--"}")
            OutlinedButton(
                onClick = { confirmUnpair = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sair da conexão (desparear)")
            }
        }

        CyberCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel("Transporte")
            TransportMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setTransportMode(mode) },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = state.session.transportMode == mode,
                        onClick = { viewModel.setTransportMode(mode) },
                    )
                    Text(mode.name)
                }
            }
            OutlinedTextField(
                value = state.directBaseUrl,
                onValueChange = viewModel::updateDirectBaseUrl,
                label = { Text("Endpoint direto") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = state.relayUrl,
                onValueChange = viewModel::updateRelayUrl,
                label = { Text("Relay URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(onClick = viewModel::persistNetworkConfig, modifier = Modifier.fillMaxWidth()) {
                Text("Salvar rede")
            }
        }

        CyberCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel("PIN local")
            Text(
                "O PIN fica protegido por Tink para agilizar a confirmacao de acoes destrutivas.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.cachedPin,
                onValueChange = viewModel::updateCachedPin,
                label = { Text("PIN") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(onClick = viewModel::persistCachedPin, modifier = Modifier.fillMaxWidth()) {
                Text("Salvar PIN")
            }
        }

        OutlinedButton(
            onClick = viewModel::clearLocalData,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Apagar dados locais")
        }
    }
}

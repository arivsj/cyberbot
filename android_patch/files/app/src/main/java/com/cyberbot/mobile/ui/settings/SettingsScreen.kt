package com.cyberbot.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.cyberbot.mobile.ui.common.ScreenScaffold
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

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(testing = true) }
            val health = statusRepository.transportHealth(forceRefresh = true)
            _uiState.update { it.copy(testing = false, health = health) }
        }
    }
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

@Composable
fun SettingsRoute(
    onOpenDestination: (String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onSetTransportMode = viewModel::setTransportMode,
        onDirectBaseUrlChange = viewModel::updateDirectBaseUrl,
        onRelayUrlChange = viewModel::updateRelayUrl,
        onPersistNetworkConfig = viewModel::persistNetworkConfig,
        onCachedPinChange = viewModel::updateCachedPin,
        onPersistCachedPin = viewModel::persistCachedPin,
        onClearLocalData = viewModel::clearLocalData,
        onUnpair = viewModel::unpair,
        onOpenDestination = onOpenDestination,
        onTestConnection = viewModel::testConnection,
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onSetTransportMode: (TransportMode) -> Unit,
    onDirectBaseUrlChange: (String) -> Unit,
    onRelayUrlChange: (String) -> Unit,
    onPersistNetworkConfig: () -> Unit,
    onCachedPinChange: (String) -> Unit,
    onPersistCachedPin: () -> Unit,
    onClearLocalData: () -> Unit,
    onUnpair: () -> Unit,
    onOpenDestination: (String) -> Unit,
    onTestConnection: () -> Unit,
) {
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
                        onUnpair()
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

    ScreenScaffold(title = "Hub") { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CyberCard(modifier = Modifier.fillMaxWidth()) {
                Text("Conexão")
                Text("PC pareado: ${state.session.pcName ?: "nenhum"}")
                Text("Device ID: ${state.session.deviceId ?: "--"}")
                Text(
                    "Caminho: ${state.health?.path ?: if (state.session.isPaired) "pronto para testar" else "--"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { confirmUnpair = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sair da conexão (desparear)")
                }
                Text(
                    "Use quando quiser trocar de PC ou refazer o pareamento com um QR Code novo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CyberCard(modifier = Modifier.fillMaxWidth()) {
                Text("PC pareado: ${state.session.pcName ?: "nenhum"}")
                Text("Device ID: ${state.session.deviceId ?: "--"}")
            }
            CyberCard(modifier = Modifier.fillMaxWidth()) {
                Text("Transporte")
                TransportMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSetTransportMode(mode) },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(
                            selected = state.session.transportMode == mode,
                            onClick = { onSetTransportMode(mode) },
                        )
                        Text(mode.name)
                    }
                }
                OutlinedTextField(
                    value = state.directBaseUrl,
                    onValueChange = onDirectBaseUrlChange,
                    label = { Text("Endpoint direto") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.relayUrl,
                    onValueChange = onRelayUrlChange,
                    label = { Text("Relay URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(onClick = onPersistNetworkConfig, modifier = Modifier.fillMaxWidth()) {
                    Text("Salvar rede")
                }
            }
            CyberCard(modifier = Modifier.fillMaxWidth()) {
                Text("PIN local")
                Text(
                    "O PIN fica protegido por Tink para agilizar a confirmacao de acoes destrutivas.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.cachedPin,
                    onValueChange = onCachedPinChange,
                    label = { Text("PIN") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(onClick = onPersistCachedPin, modifier = Modifier.fillMaxWidth()) {
                    Text("Salvar PIN")
                }
            }
            CyberCard(modifier = Modifier.fillMaxWidth()) {
                Text("Diagnostico de conexao")
                Text("Pareado: ${if (state.session.isPaired) "sim" else "nao"}")
                Text("Ticket Iroh: ${if (state.session.irohTicket.isBlank()) "ausente" else "presente"}")
                Text("Endpoint salvo: ${state.session.directBaseUrl}")
                Text("Caminho ativo: ${state.health?.path ?: "--"}")
                Text("Latencia: ${state.health?.latencyMs?.let { "${it} ms" } ?: "--"}")
                state.health?.reason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = onTestConnection, enabled = !state.testing,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.testing) "Testando..." else "Testar conexao")
                }
                Text(
                    "Na rua o app usa o Iroh (P2P). Se o teste falhar, o motivo aparece acima.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CyberCard(modifier = Modifier.fillMaxWidth()) {
                Text("Modulos")
                Button(onClick = { onOpenDestination(com.cyberbot.mobile.ui.nav.Routes.Diagnostics) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Diagnostico")
                }
                Button(onClick = { onOpenDestination(com.cyberbot.mobile.ui.nav.Routes.Drive) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Drive")
                }
                Button(onClick = { onOpenDestination(com.cyberbot.mobile.ui.nav.Routes.Finance) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Financas")
                }
                Button(onClick = { onOpenDestination(com.cyberbot.mobile.ui.nav.Routes.Security) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Security")
                }
                Button(onClick = { onOpenDestination(com.cyberbot.mobile.ui.nav.Routes.Cleanup) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Limpeza")
                }
                Button(onClick = { onOpenDestination(com.cyberbot.mobile.ui.nav.Routes.Trilha) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Trilha Rede")
                }
                Button(onClick = { onOpenDestination(com.cyberbot.mobile.ui.nav.Routes.Youtube) }, modifier = Modifier.fillMaxWidth()) {
                    Text("YouTube")
                }
                Button(onClick = { onOpenDestination(com.cyberbot.mobile.ui.nav.Routes.WebSearch) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Web Search")
                }
                Button(onClick = { onOpenDestination(com.cyberbot.mobile.ui.nav.Routes.Rag) }, modifier = Modifier.fillMaxWidth()) {
                    Text("RAG (documentos)")
                }
                Button(onClick = { onOpenDestination(com.cyberbot.mobile.ui.nav.Routes.Plugins) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Plugins")
                }
                Button(onClick = { onOpenDestination(com.cyberbot.mobile.ui.nav.Routes.Model) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Trocar modelo")
                }
                Button(onClick = { onOpenDestination(com.cyberbot.mobile.ui.nav.Routes.Devices) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Dispositivos / PIN")
                }
            }
            Button(onClick = onClearLocalData, modifier = Modifier.fillMaxWidth()) {
                Text("Apagar dados locais")
            }
        }
    }
}

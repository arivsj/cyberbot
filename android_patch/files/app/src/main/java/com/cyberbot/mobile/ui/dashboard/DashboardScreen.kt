package com.cyberbot.mobile.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cyberbot.mobile.core.model.SessionSnapshot
import com.cyberbot.mobile.core.model.StatusResponse
import com.cyberbot.mobile.core.model.SysmonSnapshot
import com.cyberbot.mobile.core.model.TransportHealth
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.data.repo.StatusRepository
import com.cyberbot.mobile.ui.common.CyberCard
import com.cyberbot.mobile.ui.common.ErrorPane
import com.cyberbot.mobile.ui.common.ScreenScaffold
import com.cyberbot.mobile.ui.common.SectionLabel
import com.cyberbot.mobile.ui.settings.SettingsSection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val session: SessionSnapshot = SessionSnapshot(),
    val status: StatusResponse? = null,
    val sysmon: SysmonSnapshot? = null,
    val transportHealth: TransportHealth? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val statusRepository: StatusRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            statusRepository.session.collect { session ->
                _uiState.update { it.copy(session = session) }
            }
        }
        viewModelScope.launch {
            while (true) {
                refreshOnce(forceProbe = _uiState.value.transportHealth == null)
                delay(15_000)
            }
        }
    }

    fun refresh(forceProbe: Boolean = true) {
        viewModelScope.launch { refreshOnce(forceProbe) }
    }

    private suspend fun refreshOnce(forceProbe: Boolean) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        val health = statusRepository.transportHealth(forceRefresh = forceProbe)
        val status = statusRepository.getStatus()
        val sysmon = statusRepository.getSysmon()
        val nextState = DashboardUiState(
            session = _uiState.value.session,
            transportHealth = health,
            status = (status as? ApiResult.Success)?.body,
            sysmon = (sysmon as? ApiResult.Success)?.body,
            isLoading = false,
            error = listOf(status, sysmon)
                .mapNotNull {
                    when (it) {
                        is ApiResult.HttpError -> it.message
                        is ApiResult.NetworkError -> it.cause.message
                        is ApiResult.Success -> null
                    }
                }
                .firstOrNull(),
        )
        _uiState.value = nextState
    }
}

@Composable
fun DashboardRoute(
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardScreen(
        state = state,
        onRefresh = { viewModel.refresh(forceProbe = true) },
    )
}

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onRefresh: () -> Unit,
) {
    ScreenScaffold(title = "Status") { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CyberCard(modifier = Modifier.fillMaxWidth()) {
                SectionLabel("Diagnóstico de conexão P2P")
                Text("Pareado: ${if (state.session.isPaired) "sim" else "nao"}")
                Text("Modo: ${state.session.transportMode.name}")
                Text("PC: ${state.session.pcName ?: "Nao pareado"}")
                Text("Ticket Iroh: ${if (state.session.irohTicket.isBlank()) "ausente" else "presente"}")
                Text("Endpoint: ${state.transportHealth?.endpoint ?: state.session.directBaseUrl}")
                Text("Caminho ativo: ${state.transportHealth?.path ?: "direct"}")
                Text("Latencia: ${state.transportHealth?.latencyMs?.let { "$it ms" } ?: "--"}")
                state.transportHealth?.reason?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isLoading) "Testando..." else "Testar conexao")
                }
            }

            CyberCard(modifier = Modifier.fillMaxWidth()) {
                SectionLabel("Status do PC")
                Text("Host: ${state.status?.pc?.hostname ?: "--"}")
                Text("Bot: ${if (state.status?.bot?.running == true) "Ligado" else "Desligado"}")
                Text("Ollama: ${state.status?.ollama?.model ?: "--"}")
                Text("Tailnet IP: ${state.status?.pc?.tailnet_ip ?: "--"}")
                Text("LAN IP: ${state.status?.pc?.lan_ip ?: "--"}")
            }

            CyberCard(modifier = Modifier.fillMaxWidth()) {
                SectionLabel("Telemetria")
                Text("CPU: ${state.sysmon?.cpu?.toInt()?.toString() ?: "--"}%")
                Text("RAM: ${state.sysmon?.ram?.toInt()?.toString() ?: "--"}%")
                Text("GPU: ${state.sysmon?.gpu?.toInt()?.toString() ?: "--"}%")
                Text("Temp: ${state.sysmon?.temperature?.toInt()?.toString() ?: "--"} C")
            }

            state.error?.let { ErrorPane(it) }

            SettingsSection()

            Button(
                onClick = onRefresh,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isLoading) "Atualizando..." else "Atualizar")
            }
        }
    }
}

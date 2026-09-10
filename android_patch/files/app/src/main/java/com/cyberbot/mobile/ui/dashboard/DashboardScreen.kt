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
import com.cyberbot.mobile.ui.common.CyberButton
import com.cyberbot.mobile.ui.common.RevelarTerminal
import com.cyberbot.mobile.ui.common.StatRow
import com.cyberbot.mobile.ui.common.corPorUso
import com.cyberbot.mobile.ui.theme.Danger
import com.cyberbot.mobile.ui.theme.Neon
import com.cyberbot.mobile.ui.theme.TextDim
import com.cyberbot.mobile.ui.common.CyberOutlinedButton
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

/** Ritmo do preenchimento: cada linha entra 95ms depois da anterior. */
private const val ATRASO_BASE_MS = 140
private const val ATRASO_LINHA_MS = 95

private fun atrasoDaLinha(indice: Int) = ATRASO_BASE_MS + indice * ATRASO_LINHA_MS

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
            // Os cartoes aparecem vazios e vao sendo preenchidos de cima para baixo,
            // como a saida de um terminal. O atraso da linha e global na tela.
            CyberCard(modifier = Modifier.fillMaxWidth()) {
                RevelarTerminal(60) { SectionLabel("Diagnóstico de conexão P2P") }
                StatRow(
                    rotulo = "Pareado",
                    valor = if (state.session.isPaired) "SIM" else "NAO",
                    corValor = if (state.session.isPaired) Neon else Danger,
                    atrasoMs = atrasoDaLinha(0),
                )
                StatRow(
                    rotulo = "Modo",
                    valor = state.session.transportMode.name,
                    atrasoMs = atrasoDaLinha(1),
                )
                StatRow(
                    rotulo = "PC",
                    valor = state.session.pcName ?: "nao pareado",
                    atrasoMs = atrasoDaLinha(2),
                )
                StatRow(
                    rotulo = "Ticket Iroh",
                    valor = if (state.session.irohTicket.isBlank()) "ausente" else "presente",
                    corValor = if (state.session.irohTicket.isBlank()) TextDim else Neon,
                    atrasoMs = atrasoDaLinha(3),
                )
                StatRow(
                    rotulo = "Endpoint",
                    valor = state.transportHealth?.endpoint ?: state.session.directBaseUrl,
                    atrasoMs = atrasoDaLinha(4),
                )
                StatRow(
                    rotulo = "Caminho ativo",
                    valor = state.transportHealth?.path ?: "direct",
                    atrasoMs = atrasoDaLinha(5),
                )
                StatRow(
                    rotulo = "Latência",
                    valor = state.transportHealth?.latencyMs?.let { "$it ms" } ?: "--",
                    divisor = false,
                    atrasoMs = atrasoDaLinha(6),
                )
                state.transportHealth?.reason?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                RevelarTerminal(atrasoDaLinha(7)) {
                    CyberOutlinedButton(
                        onClick = onRefresh,
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.isLoading) "Testando..." else "Testar conexao")
                    }
                }
            }

            CyberCard(modifier = Modifier.fillMaxWidth()) {
                RevelarTerminal(atrasoDaLinha(7) - 90) { SectionLabel("Status do PC") }
                StatRow(
                    rotulo = "Host",
                    valor = state.status?.pc?.hostname ?: "--",
                    atrasoMs = atrasoDaLinha(7),
                )
                StatRow(
                    rotulo = "Bot",
                    valor = if (state.status?.bot?.running == true) "LIGADO" else "DESLIGADO",
                    corValor = if (state.status?.bot?.running == true) Neon else TextDim,
                    atrasoMs = atrasoDaLinha(8),
                )
                StatRow(
                    rotulo = "Ollama",
                    valor = state.status?.ollama?.model ?: "--",
                    atrasoMs = atrasoDaLinha(9),
                )
                StatRow(
                    rotulo = "Tailnet IP",
                    valor = state.status?.pc?.tailnet_ip ?: "--",
                    atrasoMs = atrasoDaLinha(10),
                )
                StatRow(
                    rotulo = "LAN IP",
                    valor = state.status?.pc?.lan_ip ?: "--",
                    divisor = false,
                    atrasoMs = atrasoDaLinha(11),
                )
            }

            CyberCard(modifier = Modifier.fillMaxWidth()) {
                RevelarTerminal(atrasoDaLinha(12) - 90) { SectionLabel("Telemetria") }
                StatRow(
                    rotulo = "CPU",
                    valor = state.sysmon?.cpu?.toInt()?.let { "$it%" } ?: "--",
                    corValor = corPorUso(state.sysmon?.cpu),
                    atrasoMs = atrasoDaLinha(12),
                )
                StatRow(
                    rotulo = "RAM",
                    valor = state.sysmon?.ram?.toInt()?.let { "$it%" } ?: "--",
                    corValor = corPorUso(state.sysmon?.ram),
                    atrasoMs = atrasoDaLinha(13),
                )
                StatRow(
                    rotulo = "GPU",
                    valor = state.sysmon?.gpu?.toInt()?.let { "$it%" } ?: "--",
                    corValor = corPorUso(state.sysmon?.gpu),
                    atrasoMs = atrasoDaLinha(14),
                )
                StatRow(
                    rotulo = "Temperatura",
                    valor = state.sysmon?.temperature?.toInt()?.let { "$it C" } ?: "--",
                    corValor = corPorUso(state.sysmon?.temperature),
                    divisor = false,
                    atrasoMs = atrasoDaLinha(15),
                )
            }

            state.error?.let { ErrorPane(it) }

            SettingsSection()

            CyberButton(
                onClick = onRefresh,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isLoading) "Atualizando..." else "Atualizar")
            }
        }
    }
}

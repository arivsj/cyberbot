package com.cyberbot.mobile.ui.security

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
import com.cyberbot.mobile.core.model.SecurityIncident
import com.cyberbot.mobile.core.model.SecurityResult
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.data.repo.SecurityRepository
import com.cyberbot.mobile.ui.common.CyberCard
import com.cyberbot.mobile.ui.common.ErrorPane
import com.cyberbot.mobile.ui.common.ScreenScaffold
import com.cyberbot.mobile.ui.common.SectionLabel
import com.cyberbot.mobile.ui.common.StatRow
import com.cyberbot.mobile.ui.theme.Danger
import com.cyberbot.mobile.ui.theme.TextDim
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SecurityUiState(
    val checks: Map<String, SecurityResult> = emptyMap(),
    val incidents: List<SecurityIncident> = emptyList(),
    val analysis: String? = null,
    val isLoading: Boolean = false,
    val isAnalysing: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val securityRepository: SecurityRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SecurityUiState())
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    init {
        run()
        carregarIncidentes()
    }

    /** Incidentes de acesso barrados na tela de bloqueio, gravados pelo PC. */
    fun carregarIncidentes() {
        viewModelScope.launch {
            when (val result = securityRepository.incidents()) {
                is ApiResult.Success -> _uiState.update { it.copy(incidents = result.body) }
                is ApiResult.HttpError, is ApiResult.NetworkError -> Unit
            }
        }
    }

    fun run() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = securityRepository.run()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(checks = result.body, isLoading = false)
                }

                is ApiResult.HttpError -> _uiState.update { it.copy(isLoading = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isLoading = false, error = result.cause.message)
                }
            }
        }
    }

    fun analyse() {
        viewModelScope.launch {
            _uiState.update { it.copy(isAnalysing = true, error = null) }
            when (val result = securityRepository.report()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(checks = result.body.report, analysis = result.body.ia_analysis, isAnalysing = false)
                }

                is ApiResult.HttpError -> _uiState.update { it.copy(isAnalysing = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isAnalysing = false, error = result.cause.message)
                }
            }
        }
    }
}

@Composable
fun SecurityRoute(viewModel: SecurityViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SecurityScreen(state = state, onRun = viewModel::run, onAnalyse = viewModel::analyse)
}

@Composable
fun SecurityScreen(state: SecurityUiState, onRun: () -> Unit, onAnalyse: () -> Unit) {
    val alerts = state.checks.values.sumOf { it.alerts.size }
    ScreenScaffold(title = "Security", topBarActions = {
        CyberTextButton(onClick = onRun) { Text("Rodar") }
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("Resumo")
                    Text("Checagens: ${state.checks.size}   Alertas: $alerts")
                    CyberButton(onClick = onAnalyse, enabled = !state.isAnalysing, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.isAnalysing) "Analisando com IA..." else "Relatorio com IA")
                    }
                }
            }
            if (state.incidents.isNotEmpty()) {
                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        SectionLabel("Incidentes de acesso")
                        state.incidents.take(6).forEach { incidente ->
                            StatRow(
                                rotulo = ("#" + incidente.id + "  " + incidente.date).trim(),
                                valor = incidente.tentativas.toString() + "x",
                                corValor = Danger,
                            )
                            Text(
                                incidente.motivo,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextDim,
                            )
                            Text(
                                "ate " + incidente.bloqueado_ate + "  |  " + incidente.dispositivo,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextDim,
                            )
                        }
                    }
                }
            }
            if (state.isLoading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            state.error?.let { message -> item { ErrorPane(message) } }
            state.analysis?.let { text ->
                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        SectionLabel("Analise da IA")
                        Text(text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(state.checks.entries.toList()) { (name, result) ->
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "${if (result.alerts.isEmpty()) "OK" else "ALERTA"} | $name",
                        color = if (result.alerts.isEmpty()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    result.alerts.take(4).forEach { alert ->
                        Text(alert, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    result.attentions.take(3).forEach { attention ->
                        Text(attention, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

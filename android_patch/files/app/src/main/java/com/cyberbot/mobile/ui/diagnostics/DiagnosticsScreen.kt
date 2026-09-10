package com.cyberbot.mobile.ui.diagnostics

import androidx.compose.foundation.background
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
import com.cyberbot.mobile.ui.common.CyberTextButton
import com.cyberbot.mobile.core.model.SysmonHistoryItem
import com.cyberbot.mobile.core.model.SysmonSnapshot
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.data.repo.DiagnosticsRepository
import com.cyberbot.mobile.data.repo.StatusRepository
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

data class DiagnosticsUiState(
    val current: SysmonSnapshot? = null,
    val history: List<SysmonHistoryItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val statusRepository: StatusRepository,
    private val diagnosticsRepository: DiagnosticsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsUiState(isLoading = true))
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val current = statusRepository.getSysmon()
            val history = diagnosticsRepository.history(30)
            _uiState.update {
                it.copy(
                    current = (current as? ApiResult.Success)?.body ?: it.current,
                    history = (history as? ApiResult.Success)?.body ?: it.history,
                    isLoading = false,
                    error = listOf(current, history).mapNotNull { result ->
                        when (result) {
                            is ApiResult.HttpError -> result.message
                            is ApiResult.NetworkError -> result.cause.message
                            is ApiResult.Success -> null
                        }
                    }.firstOrNull(),
                )
            }
        }
    }
}

@Composable
fun DiagnosticsRoute(viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DiagnosticsScreen(state = state, onRefresh = viewModel::refresh)
}

@Composable
fun DiagnosticsScreen(state: DiagnosticsUiState, onRefresh: () -> Unit) {
    ScreenScaffold(title = "Diagnostico", topBarActions = {
        CyberTextButton(onClick = onRefresh) { Text("Atualizar") }
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("Agora")
                    val s = state.current
                    Text("CPU: ${s?.cpu?.toInt() ?: 0}%   ${s?.temperature?.toInt() ?: 0} C")
                    Text("RAM: ${s?.ram?.toInt() ?: 0}%")
                    Text("GPU: ${s?.gpu?.toInt() ?: 0}%")
                }
            }
            item {
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("CPU (ultimos 30)")
                    val values = state.history.reversed().map { it.cpu }
                    MiniBars(values)
                }
            }
            item {
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("RAM (ultimos 30)")
                    MiniBars(state.history.reversed().map { it.ram_pct })
                }
            }
            state.error?.let { message -> item { ErrorPane(message) } }
            items(state.history) { item ->
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    Text(item.created_at ?: "-", style = MaterialTheme.typography.labelSmall)
                    Text("CPU ${item.cpu.toInt()}% | ${item.cpu_temp.toInt()}C")
                    Text("RAM ${item.ram_pct.toInt()}% (${item.ram_used}/${item.ram_total} MB)")
                    if (item.gpu_vram_total > 0) {
                        Text("GPU ${item.gpu_pct.toInt()}% | VRAM ${item.gpu_vram_used}/${item.gpu_vram_total} MB")
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniBars(values: List<Double>) {
    if (values.isEmpty()) {
        Text("Sem historico", style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Bottom,
    ) {
        values.forEach { value ->
            val fraction = (value.coerceIn(0.0, 100.0) / 100.0).toFloat()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction.coerceAtLeast(0.02f))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
            )
        }
    }
}

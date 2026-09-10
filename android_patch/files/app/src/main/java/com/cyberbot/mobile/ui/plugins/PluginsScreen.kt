package com.cyberbot.mobile.ui.plugins

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import com.cyberbot.mobile.ui.common.CyberTextButton
import com.cyberbot.mobile.ui.common.EmptyState
import com.cyberbot.mobile.core.model.PluginInfo
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.data.repo.PluginsRepository
import com.cyberbot.mobile.ui.common.CyberCard
import com.cyberbot.mobile.ui.common.ErrorPane
import com.cyberbot.mobile.ui.common.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PluginsUiState(
    val plugins: List<PluginInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class PluginsViewModel @Inject constructor(
    private val repository: PluginsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PluginsUiState(isLoading = true))
    val uiState: StateFlow<PluginsUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = repository.list()) {
                is ApiResult.Success -> _uiState.update { it.copy(plugins = result.body, isLoading = false) }
                is ApiResult.HttpError -> _uiState.update { it.copy(isLoading = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isLoading = false, error = result.cause.message)
                }
            }
        }
    }
}

@Composable
fun PluginsRoute(viewModel: PluginsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PluginsScreen(state = state, onRefresh = viewModel::load)
}

@Composable
fun PluginsScreen(state: PluginsUiState, onRefresh: () -> Unit) {
    ScreenScaffold(title = "Plugins", topBarActions = {
        CyberTextButton(onClick = onRefresh) { Text("Atualizar") }
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.isLoading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            state.error?.let { message -> item { ErrorPane(message) } }
            if (state.plugins.isEmpty() && !state.isLoading) {
                item { EmptyState(Icons.Filled.Extension, "Nenhum plugin instalado") }
            }
            items(state.plugins) { plugin ->
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    Text("\uD83E\uDDE9 ${plugin.name}")
                    Text(plugin.description, style = MaterialTheme.typography.bodySmall)
                    Text("Comandos: ${plugin.commands.joinToString()}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

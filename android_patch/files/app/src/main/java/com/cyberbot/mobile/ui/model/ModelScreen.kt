package com.cyberbot.mobile.ui.model

import androidx.compose.foundation.clickable
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
import com.cyberbot.mobile.core.model.ContextInfo
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.data.repo.ModelRepository
import com.cyberbot.mobile.ui.common.CyberCard
import com.cyberbot.mobile.ui.common.PressableCyberCard
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

data class ModelUiState(
    val models: List<String> = emptyList(),
    val current: String = "",
    val context: ContextInfo? = null,
    val isLoading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class ModelViewModel @Inject constructor(
    private val repository: ModelRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelUiState(isLoading = true))
    val uiState: StateFlow<ModelUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val models = repository.available()
            val current = repository.current()
            val context = repository.context()
            _uiState.update {
                it.copy(
                    models = (models as? ApiResult.Success)?.body?.models ?: it.models,
                    current = (current as? ApiResult.Success)?.body?.model ?: it.current,
                    context = (context as? ApiResult.Success)?.body ?: it.context,
                    isLoading = false,
                    error = listOf(models, current, context).mapNotNull { result ->
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

    fun select(model: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            when (val result = repository.select(model)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, current = result.body.model, message = "Modelo: ${result.body.model}")
                }

                is ApiResult.HttpError -> _uiState.update { it.copy(isLoading = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isLoading = false, error = result.cause.message)
                }
            }
            load()
        }
    }
}

@Composable
fun ModelRoute(viewModel: ModelViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ModelScreen(state = state, onSelect = viewModel::select, onRefresh = viewModel::load)
}

@Composable
fun ModelScreen(state: ModelUiState, onSelect: (String) -> Unit, onRefresh: () -> Unit) {
    ScreenScaffold(title = "Trocar modelo", topBarActions = {
        CyberTextButton(onClick = onRefresh) { Text("Atualizar") }
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("Modelo ativo")
                    Text(state.current.ifBlank { "--" }, style = MaterialTheme.typography.titleMedium)
                    state.context?.let { info ->
                        Text("Contexto maximo: ${info.max_context}", style = MaterialTheme.typography.bodySmall)
                        Text("Contexto otimo: ${info.optimal_context}", style = MaterialTheme.typography.bodySmall)
                    }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            }
            if (state.isLoading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            state.error?.let { message -> item { ErrorPane(message) } }
            item { SectionLabel("Modelos instalados") }
            items(state.models) { model ->
                PressableCyberCard(
                    onClick = { onSelect(model) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${if (model == state.current) "\u25CF" else "\u25CB"} $model",
                        color = if (model == state.current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

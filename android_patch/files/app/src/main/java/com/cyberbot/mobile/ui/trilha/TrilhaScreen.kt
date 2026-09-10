package com.cyberbot.mobile.ui.trilha

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
import androidx.compose.material.icons.filled.Hub
import com.cyberbot.mobile.ui.common.CyberTextButton
import com.cyberbot.mobile.ui.common.EmptyState
import com.cyberbot.mobile.core.model.TrilhaProject
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.data.repo.TrilhaRepository
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

data class TrilhaUiState(
    val projects: List<TrilhaProject> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class TrilhaViewModel @Inject constructor(
    private val trilhaRepository: TrilhaRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrilhaUiState(isLoading = true))
    val uiState: StateFlow<TrilhaUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = trilhaRepository.projects()) {
                is ApiResult.Success -> _uiState.update { it.copy(projects = result.body, isLoading = false) }
                is ApiResult.HttpError -> _uiState.update { it.copy(isLoading = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isLoading = false, error = result.cause.message)
                }
            }
        }
    }
}

@Composable
fun TrilhaRoute(viewModel: TrilhaViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TrilhaScreen(state = state, onRefresh = viewModel::load)
}

@Composable
fun TrilhaScreen(state: TrilhaUiState, onRefresh: () -> Unit) {
    ScreenScaffold(title = "Trilha Rede", topBarActions = {
        CyberTextButton(onClick = onRefresh) { Text("Atualizar") }
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.isLoading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            state.error?.let { message -> item { ErrorPane(message) } }
            if (state.projects.isEmpty() && !state.isLoading) {
                item { EmptyState(Icons.Filled.Hub, "Nenhum projeto gerado ainda") }
            }
            items(state.projects) { project ->
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    Text(project.name)
                    Text(
                        "status: ${project.status ?: "-"} | ${project.created_at ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    project.absolute_path?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

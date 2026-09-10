package com.cyberbot.mobile.ui.websearch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cyberbot.mobile.ui.common.CyberButton
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.data.repo.WebSearchRepository
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

data class WebSearchUiState(
    val query: String = "",
    val answer: String? = null,
    val sources: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class WebSearchViewModel @Inject constructor(
    private val repository: WebSearchRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebSearchUiState())
    val uiState: StateFlow<WebSearchUiState> = _uiState.asStateFlow()

    fun setQuery(value: String) = _uiState.update { it.copy(query = value) }

    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, answer = null, sources = emptyList()) }
            when (val result = repository.ask(query)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, answer = result.body.reply, sources = result.body.sources)
                }

                is ApiResult.HttpError -> _uiState.update { it.copy(isLoading = false, error = result.message) }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isLoading = false, error = result.cause.message)
                }
            }
        }
    }
}

@Composable
fun WebSearchRoute(viewModel: WebSearchViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    WebSearchScreen(state = state, onQuery = viewModel::setQuery, onSearch = viewModel::search)
}

@Composable
fun WebSearchScreen(state: WebSearchUiState, onQuery: (String) -> Unit, onSearch: () -> Unit) {
    ScreenScaffold(title = "Web Search") { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("Buscar na web")
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQuery,
                        label = { Text("O que procurar?") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CyberButton(onClick = onSearch, enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.isLoading) "Buscando..." else "Buscar e resumir")
                    }
                    Text("A busca roda no PC e a IA local resume os resultados.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            state.answer?.let { answer ->
                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        SectionLabel("Resposta")
                        Text(answer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (state.sources.isNotEmpty()) {
                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        SectionLabel("Fontes")
                        state.sources.forEach { source ->
                            Text(source, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (state.isLoading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            state.error?.let { message -> item { ErrorPane(message) } }
        }
    }
}

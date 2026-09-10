package com.cyberbot.mobile.ui.finance

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
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Category
import com.cyberbot.mobile.ui.common.CyberButton
import com.cyberbot.mobile.ui.common.EmptyState
import com.cyberbot.mobile.ui.common.CyberTextButton
import com.cyberbot.mobile.core.model.FinanceSummary
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.data.repo.FinanceRepository
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class FinanceUiState(
    val summary: FinanceSummary? = null,
    val selectedMonth: String? = null,
    val categoria: String = "",
    val conta: String = "",
    val valor: String = "",
    val descricao: String = "",
    val isLoading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class FinanceViewModel @Inject constructor(
    private val financeRepository: FinanceRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FinanceUiState(isLoading = true))
    val uiState: StateFlow<FinanceUiState> = _uiState.asStateFlow()

    init { load(null) }

    fun load(mes: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, selectedMonth = mes) }
            when (val result = financeRepository.summary(mes)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(summary = result.body, isLoading = false)
                }

                is ApiResult.HttpError -> _uiState.update {
                    it.copy(isLoading = false, error = result.message)
                }

                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isLoading = false, error = result.cause.message)
                }
            }
        }
    }

    fun setCategoria(value: String) = _uiState.update { it.copy(categoria = value) }
    fun setConta(value: String) = _uiState.update { it.copy(conta = value) }
    fun setValor(value: String) = _uiState.update { it.copy(valor = value.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }) }
    fun setDescricao(value: String) = _uiState.update { it.copy(descricao = value) }

    fun add() {
        val state = _uiState.value
        val valor = state.valor.replace(',', '.').toDoubleOrNull()
        if (state.categoria.isBlank() || state.conta.isBlank() || valor == null) {
            _uiState.update { it.copy(error = "Preencha categoria, conta e valor.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            when (val result = financeRepository.add(state.categoria, state.conta, valor, state.descricao)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(isLoading = false, message = "Transacao salva", categoria = "", valor = "", descricao = "")
                    }
                    load(_uiState.value.selectedMonth)
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
fun FinanceRoute(viewModel: FinanceViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FinanceScreen(
        state = state,
        onSelectMonth = viewModel::load,
        onCategoria = viewModel::setCategoria,
        onConta = viewModel::setConta,
        onValor = viewModel::setValor,
        onDescricao = viewModel::setDescricao,
        onAdd = viewModel::add,
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FinanceScreen(
    state: FinanceUiState,
    onSelectMonth: (String?) -> Unit,
    onCategoria: (String) -> Unit,
    onConta: (String) -> Unit,
    onValor: (String) -> Unit,
    onDescricao: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val summary = state.summary
    ScreenScaffold(title = "Financas") { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("Resumo ${state.selectedMonth ?: "(tudo)"}")
                    Text("Total gasto: R$ ${"%.2f".format(summary?.resumo?.total_gasto ?: 0.0)}")
                    Text("Transacoes: ${summary?.resumo?.total_transacoes ?: 0}")
                    Text("Media: R$ ${"%.2f".format(summary?.resumo?.media_gasto ?: 0.0)}")
                }
            }
            if (!summary?.meses.isNullOrEmpty()) {
                item {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        SectionLabel("Mes")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CyberTextButton(onClick = { onSelectMonth(null) }) { Text("Tudo") }
                            summary?.meses?.take(12)?.forEach { mes ->
                                CyberTextButton(onClick = { onSelectMonth(mes) }) { Text(mes) }
                            }
                        }
                    }
                }
            }
            item {
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("Categorias")
                    summary?.categorias?.take(8)?.forEach { item ->
                        Text("${item.text("categoria")}  R$ ${item.text("total")}")
                    }
                    if (summary?.categorias.isNullOrEmpty()) {
                        EmptyState(Icons.Filled.Category, "Nenhuma categoria no período")
                    }
                }
            }
            item {
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("Contas")
                    summary?.contas?.take(8)?.forEach { item ->
                        Text("${item.text("conta")}  R$ ${item.text("total")}")
                    }
                    if (summary?.contas.isNullOrEmpty()) {
                        EmptyState(Icons.Filled.AccountBalance, "Nenhuma conta cadastrada")
                    }
                }
            }
            item {
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("Nova transacao")
                    OutlinedTextField(value = state.categoria, onValueChange = onCategoria,
                        label = { Text("Categoria") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = state.conta, onValueChange = onConta,
                        label = { Text("Conta") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = state.valor, onValueChange = onValor,
                        label = { Text("Valor") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = state.descricao, onValueChange = onDescricao,
                        label = { Text("Descricao") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    CyberButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Salvar") }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            }
            state.error?.let { message -> item { ErrorPane(message) } }
        }
    }
}

private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.content ?: "-"

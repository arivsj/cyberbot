package com.cyberbot.mobile.ui.drive

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cyberbot.mobile.ui.common.CyberTextButton
import com.cyberbot.mobile.core.model.DriveFile
import com.cyberbot.mobile.core.model.DriveFolder
import com.cyberbot.mobile.core.net.ApiResult
import com.cyberbot.mobile.data.repo.DriveRepository
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

data class DriveUiState(
    val folderId: Int? = null,
    val folderName: String = "Raiz",
    val folders: List<DriveFolder> = emptyList(),
    val files: List<DriveFile> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class DriveViewModel @Inject constructor(
    private val driveRepository: DriveRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DriveUiState(isLoading = true))
    val uiState: StateFlow<DriveUiState> = _uiState.asStateFlow()

    init { load(null, "Raiz") }

    fun load(folderId: Int?, folderName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, folderId = folderId, folderName = folderName) }
            val folders = driveRepository.listFolders(folderId)
            val files = driveRepository.listFiles(folderId)
            _uiState.update {
                it.copy(
                    folders = (folders as? ApiResult.Success)?.body ?: emptyList(),
                    files = (files as? ApiResult.Success)?.body ?: emptyList(),
                    isLoading = false,
                    error = listOf(folders, files).mapNotNull { result ->
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

    fun upload(uri: Uri, context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, message = "Enviando...") }
            try {
                val resolver = context.contentResolver
                val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                } ?: "arquivo"
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                when (val result = driveRepository.upload(name, mime, bytes, _uiState.value.folderId)) {
                    is ApiResult.Success -> {
                        _uiState.update { it.copy(isLoading = false, message = "Enviado: ${result.body.name}") }
                        load(_uiState.value.folderId, _uiState.value.folderName)
                    }

                    is ApiResult.HttpError -> _uiState.update {
                        it.copy(isLoading = false, message = null, error = result.message)
                    }

                    is ApiResult.NetworkError -> _uiState.update {
                        it.copy(isLoading = false, message = null, error = result.cause.message ?: "Falha no upload")
                    }
                }
            } catch (error: Throwable) {
                _uiState.update { it.copy(isLoading = false, message = null, error = error.message) }
            }
        }
    }
}

@Composable
fun DriveRoute(viewModel: DriveViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.upload(uri, context)
    }
    DriveScreen(
        state = state,
        onOpenFolder = viewModel::load,
        onUpload = { picker.launch("*/*") },
    )
}

@Composable
fun DriveScreen(
    state: DriveUiState,
    onOpenFolder: (Int?, String) -> Unit,
    onUpload: () -> Unit,
) {
    ScreenScaffold(title = "Drive", topBarActions = {
        CyberTextButton(onClick = onUpload) { Text("Enviar") }
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("Pasta")
                    Text(state.folderName, style = MaterialTheme.typography.titleMedium)
                    if (state.folderId != null) {
                        CyberTextButton(onClick = { onOpenFolder(null, "Raiz") }) { Text("Voltar para a raiz") }
                    }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            }
            state.error?.let { message -> item { ErrorPane(message) } }
            if (state.isLoading) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
            if (state.folders.isEmpty() && state.files.isEmpty() && !state.isLoading) {
                item { Text("Pasta vazia", style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.folders) { folder ->
                PressableCyberCard(
                    onClick = { onOpenFolder(folder.id, folder.name) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("\uD83D\uDCC1 ${folder.name}")
                }
            }
            items(state.files) { file ->
                CyberCard(modifier = Modifier.fillMaxWidth()) {
                    Text(file.name)
                    Text(
                        "${formatSize(file.file_size)} | ${file.mime_type ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${bytes / 1_048_576} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

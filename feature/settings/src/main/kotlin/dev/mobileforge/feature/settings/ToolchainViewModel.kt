package dev.mobileforge.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.runtime.api.ToolchainImport
import dev.mobileforge.runtime.api.ToolchainManager
import dev.mobileforge.runtime.api.ToolchainRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ToolchainUiState(
    val installed: List<ToolchainRecord> = emptyList(),
    val selection: ToolchainImport? = null,
    val archiveDocument: String? = null,
    val busy: Boolean = false,
    val progress: Float = 0f,
    val error: AppError? = null,
    val message: String? = null,
)

class ToolchainViewModel(private val manager: ToolchainManager) : ViewModel() {
    private val mutableState = MutableStateFlow(ToolchainUiState())
    val state = mutableState.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = manager.installed()) {
                is AppResult.Success -> mutableState.update { it.copy(installed = result.value, busy = false) }
                is AppResult.Failure -> mutableState.update { it.copy(error = result.error, busy = false) }
            }
        }
    }

    fun selectManifest(reference: String) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, selection = null, archiveDocument = null,
            message = null, error = null) }
        viewModelScope.launch {
            when (val result = manager.inspect(reference)) {
                is AppResult.Success -> mutableState.update { it.copy(selection = result.value, busy = false) }
                is AppResult.Failure -> mutableState.update { it.copy(error = result.error, busy = false) }
            }
        }
    }

    fun selectArchive(reference: String) {
        if (!state.value.busy) mutableState.update { it.copy(archiveDocument = reference, error = null) }
    }

    fun install() {
        val before = state.value
        val selected = before.selection ?: return
        val archive = before.archiveDocument ?: return
        if (before.busy || selected.incompatibility != null) return
        mutableState.update { it.copy(busy = true, progress = 0f, error = null, message = null) }
        viewModelScope.launch {
            when (val result = manager.install(selected.token, archive) { progress ->
                mutableState.update { it.copy(progress = progress) }
            }) {
                is AppResult.Failure -> mutableState.update { it.copy(error = result.error, busy = false) }
                is AppResult.Success -> {
                    mutableState.update { it.copy(busy = false, selection = null, archiveDocument = null,
                        message = "${selected.name} installed. Open a new terminal to use it.") }
                    refresh()
                }
            }
        }
    }
}

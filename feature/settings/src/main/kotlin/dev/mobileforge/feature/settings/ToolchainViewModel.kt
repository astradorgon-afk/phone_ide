package dev.mobileforge.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.runtime.api.ToolchainImport
import dev.mobileforge.runtime.api.ToolchainManager
import dev.mobileforge.runtime.api.ToolchainRecord
import dev.mobileforge.runtime.toolchain.BundleCatalog
import dev.mobileforge.runtime.toolchain.CatalogInstallProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ToolchainUiState(
    val installed: List<ToolchainRecord> = emptyList(),
    val installedIds: Set<String> = emptySet(),

    /** Bundles this build knows how to fetch for this device; null until loaded. */
    val catalog: BundleCatalog? = null,
    val catalogError: AppError? = null,
    val sourceUrl: String? = null,

    /** A local bundle import, when the user is installing a file they obtained themselves. */
    val selection: ToolchainImport? = null,
    val archiveDocument: String? = null,

    val busy: Boolean = false,
    val progress: Float = 0f,
    val catalogProgress: CatalogInstallProgress? = null,
    val error: AppError? = null,
    val message: String? = null,
)

class ToolchainViewModel(
    private val manager: ToolchainManager,
    private val store: BundleStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ToolchainUiState())
    val state = mutableState.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            reloadInstalled(reportError = true)
            mutableState.update { it.copy(busy = false) }
            loadCatalog()
        }
    }

    /**
     * Re-reads the installed list without disturbing [ToolchainUiState.error].
     *
     * A run that fails part-way can still have installed something — `base` succeeds, then
     * `composer` is refused because `php` is missing — so the list has to be re-read after a
     * failure too. It is separate from [refresh] because [refresh] clears the error to show a
     * clean state, and that would erase the reason the run stopped.
     */
    private suspend fun reloadInstalled(reportError: Boolean) {
        when (val result = manager.installed()) {
            is AppResult.Success -> mutableState.update {
                it.copy(
                    installed = result.value,
                    installedIds = result.value.map { record -> record.id }.toSet(),
                )
            }
            is AppResult.Failure -> if (reportError) {
                mutableState.update { it.copy(error = result.error) }
            }
        }
    }

    /**
     * Loads the catalog separately from the installed list.
     *
     * A bundle source that is unreachable must not hide the bundles that are already
     * installed, so its failure lands in [ToolchainUiState.catalogError] and the rest of the
     * screen keeps working.
     */
    private suspend fun loadCatalog() {
        mutableState.update { it.copy(catalogError = null) }
        when (val result = store.catalog()) {
            is AppResult.Success -> mutableState.update {
                it.copy(catalog = result.value, sourceUrl = store.sourceUrl())
            }
            is AppResult.Failure -> mutableState.update {
                it.copy(catalog = null, catalogError = result.error)
            }
        }
    }

    /** Downloads and installs every missing bundle, in dependency order. */
    fun installMissing() {
        val before = state.value
        val catalog = before.catalog ?: return
        if (before.busy || catalog.bundles.isEmpty()) return
        mutableState.update {
            it.copy(busy = true, error = null, message = null, catalogProgress = null)
        }
        viewModelScope.launch {
            when (val result = store.installAll(catalog) { progress ->
                mutableState.update { it.copy(catalogProgress = progress) }
            }) {
                is AppResult.Failure -> {
                    mutableState.update {
                        it.copy(error = result.error, busy = false, catalogProgress = null)
                    }
                    // The run may have installed earlier bundles before it stopped; show them,
                    // but keep the error that explains why it stopped.
                    reloadInstalled(reportError = false)
                }
                is AppResult.Success -> {
                    mutableState.update {
                        it.copy(
                            busy = false,
                            catalogProgress = null,
                            message = "Toolchain ready. Open a new terminal to use it.",
                        )
                    }
                    reloadInstalled(reportError = false)
                }
            }
        }
    }

    /** Points the app at a different bundle source. Blank restores the built-in one. */
    fun setSourceUrl(raw: String) {
        if (state.value.busy) return
        val cleaned = raw.trim().ifBlank { null }
        mutableState.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            store.setSourceUrl(cleaned)
            mutableState.update { it.copy(busy = false) }
            loadCatalog()
            mutableState.update {
                it.copy(
                    message = if (cleaned == null) "Using the built-in bundle source."
                    else "Bundle source updated.",
                )
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

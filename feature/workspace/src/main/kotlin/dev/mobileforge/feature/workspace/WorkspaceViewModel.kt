package dev.mobileforge.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.data.workspace.WorkspaceRepository
import dev.mobileforge.core.filesystem.FrameworkDetector
import dev.mobileforge.core.filesystem.ProjectFacts
import dev.mobileforge.core.filesystem.WorkspaceFileSystem
import dev.mobileforge.core.model.Workspace
import dev.mobileforge.core.model.WorkspaceFile
import dev.mobileforge.core.model.WorkspaceId
import dev.mobileforge.core.model.WorkspaceTrust
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the open project: the file tree and which file is selected.
 *
 * The tree is loaded lazily, one directory at a time. Expanded directories are cached in
 * [TreeState.children] so collapsing and re-expanding does not re-hit the disk, but nothing is
 * walked recursively — a Laravel project with vendor/ installed is tens of thousands of files,
 * and eagerly walking it on a phone is how the explorer becomes unusable (RISK-009).
 */
class WorkspaceViewModel(
    private val workspaceId: WorkspaceId,
    private val repository: WorkspaceRepository,
    private val fileSystem: WorkspaceFileSystem,
    private val frameworkDetector: FrameworkDetector,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            when (val result = repository.find(workspaceId)) {
                is AppResult.Failure ->
                    _state.update { it.copy(isLoading = false, error = result.error) }

                is AppResult.Success -> {
                    val workspace = result.value
                    val facts = frameworkDetector.describe(workspace.rootPath)
                    _state.update {
                        it.copy(
                            workspace = workspace,
                            facts = facts,
                            isLoading = false,
                        )
                    }
                    expand("")
                    // Restore the file the user was last editing — the crash-recovery
                    // requirement, satisfied by persisted state rather than by a live process.
                    workspace.lastActiveFile?.let { selectFile(it) }
                }
            }
        }
    }

    fun toggleDirectory(relativePath: String) {
        val current = _state.value.tree
        if (current.expanded.contains(relativePath)) {
            _state.update {
                it.copy(tree = it.tree.copy(expanded = it.tree.expanded - relativePath))
            }
        } else {
            expand(relativePath)
        }
    }

    private fun expand(relativePath: String) {
        viewModelScope.launch {
            _state.update {
                it.copy(tree = it.tree.copy(loading = it.tree.loading + relativePath))
            }

            when (val result = fileSystem.list(relativePath)) {
                is AppResult.Failure -> _state.update {
                    it.copy(
                        error = result.error,
                        tree = it.tree.copy(loading = it.tree.loading - relativePath),
                    )
                }

                is AppResult.Success -> _state.update {
                    it.copy(
                        tree = it.tree.copy(
                            children = it.tree.children + (relativePath to result.value),
                            expanded = it.tree.expanded + relativePath,
                            loading = it.tree.loading - relativePath,
                        ),
                    )
                }
            }
        }
    }

    fun selectFile(relativePath: String) {
        _state.update { it.copy(selectedFile = relativePath) }
        viewModelScope.launch { repository.setLastActiveFile(workspaceId, relativePath) }
    }

    fun refresh() {
        val expanded = _state.value.tree.expanded
        _state.update { it.copy(tree = TreeState()) }
        expanded.forEach { expand(it) }
    }

    fun setShowHiddenFiles(show: Boolean) = _state.update { it.copy(showHiddenFiles = show) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun trustWorkspace() {
        viewModelScope.launch {
            repository.setTrust(workspaceId, WorkspaceTrust.Trusted)
            when (val result = repository.find(workspaceId)) {
                is AppResult.Failure -> _state.update { it.copy(error = result.error) }
                is AppResult.Success -> _state.update { it.copy(workspace = result.value) }
            }
        }
    }
}

data class WorkspaceUiState(
    val workspace: Workspace? = null,
    val facts: ProjectFacts? = null,
    val tree: TreeState = TreeState(),
    val selectedFile: String? = null,
    val showHiddenFiles: Boolean = false,
    val isLoading: Boolean = false,
    val error: AppError? = null,
) {
    /** Untrusted projects are read-only. The editor enforces this; the UI reflects it. */
    val isReadOnly: Boolean
        get() = workspace?.trust != WorkspaceTrust.Trusted
}

/**
 * Lazily-populated tree.
 *
 * [children] is keyed by directory path, so only directories the user actually opened are
 * held in memory.
 */
data class TreeState(
    val children: Map<String, List<WorkspaceFile>> = emptyMap(),
    val expanded: Set<String> = emptySet(),
    val loading: Set<String> = emptySet(),
)

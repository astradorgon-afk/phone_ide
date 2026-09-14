package dev.mobileforge.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.data.workspace.WorkspaceRepository
import dev.mobileforge.core.filesystem.FrameworkDetector
import dev.mobileforge.core.filesystem.ProjectScaffolder
import dev.mobileforge.core.filesystem.ScaffoldTemplate
import dev.mobileforge.core.model.DetectedFramework
import dev.mobileforge.core.model.Workspace
import dev.mobileforge.core.model.WorkspaceId
import dev.mobileforge.core.model.WorkspaceTrust
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProjectsViewModel(
    private val repository: WorkspaceRepository,
    private val frameworkDetector: FrameworkDetector,
    private val scaffolder: ProjectScaffolder,
) : ViewModel() {

    val workspaces: StateFlow<List<Workspace>> = repository.observeWorkspaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(ProjectsUiState())
    val uiState: StateFlow<ProjectsUiState> = _uiState.asStateFlow()

    fun showCreateDialog() = _uiState.update { it.copy(dialog = ProjectsDialog.Create) }

    fun dismissDialog() = _uiState.update { it.copy(dialog = null, error = null) }

    /**
     * Creates a project.
     *
     * Locally-created projects are [WorkspaceTrust.Trusted] because the user authored them.
     * Imported and cloned projects take the untrusted path — that distinction is made at the
     * call site rather than inferred, so it cannot be lost in a refactor (RISK-011).
     */
    fun createProject(name: String, template: ProjectTemplate) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }

            when (val result = repository.create(name, template.framework, WorkspaceTrust.Trusted)) {
                is AppResult.Failure ->
                    _uiState.update { it.copy(isBusy = false, error = result.error) }

                is AppResult.Success -> {
                    val workspace = result.value

                    // Scaffolding failure is reported but does not discard the project: the
                    // folder exists and is usable, and silently deleting a user's new project
                    // because a README could not be written would be worse than the failure.
                    val scaffold = scaffolder.scaffold(workspace.rootPath, template.scaffold)

                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            dialog = null,
                            error = scaffold.errorOrNull(),
                            createdWorkspaceId = workspace.id,
                        )
                    }
                }
            }
        }
    }

    /**
     * Asks whether to open a project, and how.
     *
     * An untrusted project prompts BEFORE it opens, not before its first command. By the time a
     * hostile repository has been browsed, an agent has read it, or a build script has run, a
     * prompt is too late.
     */
    fun requestOpen(workspace: Workspace) {
        if (workspace.trust == WorkspaceTrust.Untrusted) {
            _uiState.update { it.copy(dialog = ProjectsDialog.Trust(workspace)) }
        } else {
            open(workspace.id)
        }
    }

    fun trustAndOpen(id: WorkspaceId) {
        viewModelScope.launch {
            repository.setTrust(id, WorkspaceTrust.Trusted)
            _uiState.update { it.copy(dialog = null) }
            open(id)
        }
    }

    fun openRestricted(id: WorkspaceId) {
        _uiState.update { it.copy(dialog = null) }
        open(id)
    }

    fun removeProject(id: WorkspaceId, deleteFiles: Boolean) {
        viewModelScope.launch {
            when (val result = repository.remove(id, deleteFiles)) {
                is AppResult.Failure -> _uiState.update { it.copy(error = result.error) }
                is AppResult.Success -> _uiState.update { it.copy(dialog = null) }
            }
        }
    }

    fun confirmRemove(workspace: Workspace) =
        _uiState.update { it.copy(dialog = ProjectsDialog.ConfirmRemove(workspace)) }

    fun consumeNavigation() = _uiState.update { it.copy(openWorkspaceId = null, createdWorkspaceId = null) }

    private fun open(id: WorkspaceId) {
        viewModelScope.launch {
            repository.markOpened(id)
            _uiState.update { it.copy(openWorkspaceId = id) }
        }
    }
}

data class ProjectsUiState(
    val isBusy: Boolean = false,
    val dialog: ProjectsDialog? = null,
    val error: AppError? = null,
    val openWorkspaceId: WorkspaceId? = null,
    val createdWorkspaceId: WorkspaceId? = null,
)

sealed interface ProjectsDialog {
    data object Create : ProjectsDialog
    data class Trust(val workspace: Workspace) : ProjectsDialog
    data class ConfirmRemove(val workspace: Workspace) : ProjectsDialog
}

/**
 * Project templates available in Phase 1.
 *
 * Every template here creates a real directory the user can immediately edit in. Templates that
 * need tooling to scaffold — a genuine `laravel new`, or `npm create vite` — are NOT listed,
 * because Phase 1 has no runtime to run them and offering them would be a button that lies.
 * They arrive in Phase 3 alongside the runtime that makes them real.
 */
enum class ProjectTemplate(
    val displayName: String,
    val description: String,
    val framework: DetectedFramework,
    val scaffold: ScaffoldTemplate,
) {
    Empty(
        displayName = "Empty project",
        description = "An empty folder to start from.",
        framework = DetectedFramework.Unknown,
        scaffold = ScaffoldTemplate.Empty,
    ),
    StaticSite(
        displayName = "Static site",
        description = "index.html, styles.css and script.js.",
        framework = DetectedFramework.Static,
        scaffold = ScaffoldTemplate.StaticSite,
    ),
    Php(
        displayName = "PHP",
        description = "index.php to edit. Running it needs a PHP toolchain.",
        framework = DetectedFramework.Php,
        scaffold = ScaffoldTemplate.Php,
    ),
}

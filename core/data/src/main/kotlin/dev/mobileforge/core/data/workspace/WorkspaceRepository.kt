package dev.mobileforge.core.data.workspace

import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.model.DetectedFramework
import dev.mobileforge.core.model.Workspace
import dev.mobileforge.core.model.WorkspaceId
import dev.mobileforge.core.model.WorkspaceTrust
import kotlinx.coroutines.flow.Flow

/**
 * Project list and lifecycle.
 *
 * An interface so ViewModels can be tested with an in-memory fake and never touch Room —
 * mockable infrastructure is mandatory per the brief, and a repository that can only be
 * exercised through a real database quietly makes that impossible.
 */
interface WorkspaceRepository {

    /** Recent projects, most recently opened first. */
    fun observeWorkspaces(): Flow<List<Workspace>>

    suspend fun find(id: WorkspaceId): AppResult<Workspace>

    /**
     * Creates a project directory and its metadata row.
     *
     * New projects the user creates locally start [WorkspaceTrust.Trusted] — the user wrote
     * them. Imported and cloned projects must be created [WorkspaceTrust.Untrusted]; that is
     * the caller's responsibility and it is enforced at the import sites, not guessed here.
     */
    suspend fun create(
        name: String,
        framework: DetectedFramework,
        trust: WorkspaceTrust,
    ): AppResult<Workspace>

    suspend fun setTrust(id: WorkspaceId, trust: WorkspaceTrust): AppResult<Unit>

    suspend fun markOpened(id: WorkspaceId): AppResult<Unit>

    suspend fun setLastActiveFile(id: WorkspaceId, relativePath: String?): AppResult<Unit>

    /**
     * Removes the project.
     *
     * [deleteFiles] defaults to false so that "remove from list" can never silently destroy a
     * user's code. Deleting files is an explicit, separately-confirmed choice.
     */
    suspend fun remove(id: WorkspaceId, deleteFiles: Boolean = false): AppResult<Unit>

    /** Absolute root directory for a workspace. Only the filesystem layer should need this. */
    suspend fun rootPathOf(id: WorkspaceId): AppResult<String>
}

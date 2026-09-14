package dev.mobileforge.core.data.workspace

import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.Logger
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.core.data.database.WorkspaceDao
import dev.mobileforge.core.data.database.WorkspaceEntity
import dev.mobileforge.core.model.DetectedFramework
import dev.mobileforge.core.model.Workspace
import dev.mobileforge.core.model.WorkspaceId
import dev.mobileforge.core.model.WorkspaceTrust
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Room-backed [WorkspaceRepository].
 *
 * Project directories live under [workspacesRoot], which the caller supplies as the app's
 * internal storage. Internal storage is used because it needs no permission and because it is
 * the only location the Phase 2 runtime will be able to execute from (RISK-008, ADR-002).
 */
class RoomWorkspaceRepository(
    private val dao: WorkspaceDao,
    private val workspacesRoot: File,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
) : WorkspaceRepository {

    override fun observeWorkspaces(): Flow<List<Workspace>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun find(id: WorkspaceId): AppResult<Workspace> =
        withContext(dispatchers.io) {
            dao.findById(id.value)?.toDomain()?.asSuccess() ?: notFound(id)
        }

    override suspend fun create(
        name: String,
        framework: DetectedFramework,
        trust: WorkspaceTrust,
    ): AppResult<Workspace> = withContext(dispatchers.io) {
        val trimmed = name.trim()
        validateName(trimmed)?.let { return@withContext it.asFailure() }

        val directoryName = sanitiseDirectoryName(trimmed)
        val directory = File(workspacesRoot, directoryName)

        if (directory.exists()) {
            return@withContext AppError(
                category = ErrorCategory.Validation,
                message = "A project with that name already exists.",
                detail = "A folder named '$directoryName' is already present.",
                recovery = "Choose a different project name.",
            ).asFailure()
        }

        if (!directory.mkdirs()) {
            return@withContext AppError(
                category = ErrorCategory.FileSystem,
                message = "The project folder could not be created.",
                detail = "mkdirs() failed for '${directory.absolutePath}'.",
                recovery = "Check available storage and try again.",
            ).asFailure()
        }

        val now = System.currentTimeMillis()
        val entity = WorkspaceEntity(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            rootPath = directory.canonicalPath,
            trust = trust.name,
            framework = framework.name,
            createdAtEpochMs = now,
            lastOpenedAtEpochMs = now,
        )
        dao.upsert(entity)
        logger.info(TAG, "Created workspace '$trimmed' (trust=${trust.name})")
        entity.toDomain().asSuccess()
    }

    override suspend fun setTrust(id: WorkspaceId, trust: WorkspaceTrust): AppResult<Unit> =
        withContext(dispatchers.io) {
            if (dao.findById(id.value) == null) return@withContext notFound(id)
            dao.setTrust(id.value, trust.name)
            // Trust changes are security-relevant state transitions and are logged as such.
            logger.security(TAG, "Workspace ${id.value} trust set to ${trust.name}")
            AppResult.Success(Unit)
        }

    override suspend fun markOpened(id: WorkspaceId): AppResult<Unit> =
        withContext(dispatchers.io) {
            if (dao.findById(id.value) == null) return@withContext notFound(id)
            dao.touchLastOpened(id.value, System.currentTimeMillis())
            AppResult.Success(Unit)
        }

    override suspend fun setLastActiveFile(
        id: WorkspaceId,
        relativePath: String?,
    ): AppResult<Unit> = withContext(dispatchers.io) {
        if (dao.findById(id.value) == null) return@withContext notFound(id)
        dao.setLastActiveFile(id.value, relativePath)
        AppResult.Success(Unit)
    }

    override suspend fun remove(id: WorkspaceId, deleteFiles: Boolean): AppResult<Unit> =
        withContext(dispatchers.io) {
            val entity = dao.findById(id.value) ?: return@withContext notFound(id)

            if (deleteFiles) {
                val directory = File(entity.rootPath)
                // Containment guard: never recursively delete something that is not actually
                // one of our managed project folders, whatever the row happens to say.
                val root = workspacesRoot.canonicalPath
                val target = directory.canonicalPath
                if (!target.startsWith("$root${File.separator}")) {
                    logger.security(
                        TAG,
                        "Refused to delete '$target' - outside the managed workspace root",
                    )
                    return@withContext AppError(
                        category = ErrorCategory.Security,
                        message = "That project folder could not be deleted.",
                        detail = "The recorded path is outside the app's project storage.",
                        recovery = "Remove the project from the list without deleting files.",
                    ).asFailure()
                }
                if (directory.exists() && !directory.deleteRecursively()) {
                    return@withContext AppError(
                        category = ErrorCategory.FileSystem,
                        message = "The project files could not be fully deleted.",
                        detail = "Some files under '$target' could not be removed.",
                        recovery = "The project was left in the list so nothing is orphaned.",
                    ).asFailure()
                }
            }

            dao.delete(entity)
            AppResult.Success(Unit)
        }

    override suspend fun rootPathOf(id: WorkspaceId): AppResult<String> =
        withContext(dispatchers.io) {
            dao.findById(id.value)?.rootPath?.asSuccess() ?: notFound(id)
        }

    // -----------------------------------------------------------------------------

    private fun validateName(name: String): AppError? = when {
        name.isBlank() -> AppError(
            category = ErrorCategory.Validation,
            message = "Enter a project name.",
            recovery = "Project names cannot be empty.",
        )

        name.length > MAX_NAME_LENGTH -> AppError(
            category = ErrorCategory.Validation,
            message = "That project name is too long.",
            detail = "Names are limited to $MAX_NAME_LENGTH characters.",
            recovery = "Use a shorter name.",
        )

        else -> null
    }

    /**
     * Derives a safe directory name.
     *
     * Anything outside the allow-list becomes a hyphen, so a project named "../../etc" cannot
     * become a path. This runs in addition to, not instead of, PathValidator — the name never
     * reaches the filesystem in raw form.
     */
    private fun sanitiseDirectoryName(name: String): String {
        val cleaned = name
            .map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '-' }
            .joinToString("")
            .trim('-')
            .take(MAX_DIRECTORY_NAME)
        return cleaned.ifEmpty { "project-${System.currentTimeMillis()}" }
    }

    private fun notFound(id: WorkspaceId): AppResult<Nothing> = AppError(
        category = ErrorCategory.Validation,
        message = "That project could not be found.",
        detail = "No workspace with id '${id.value}'.",
        recovery = "It may have been removed. Go back to the project list.",
    ).asFailure()

    private fun WorkspaceEntity.toDomain() = Workspace(
        id = WorkspaceId(id),
        name = name,
        rootPath = rootPath,
        // Unknown persisted values decay to the SAFE option rather than throwing. A database
        // written by a newer build must never make an older build crash or over-permit.
        trust = runCatching { WorkspaceTrust.valueOf(trust) }.getOrDefault(WorkspaceTrust.Untrusted),
        framework = runCatching { DetectedFramework.valueOf(framework) }
            .getOrDefault(DetectedFramework.Unknown),
        createdAtEpochMs = createdAtEpochMs,
        lastOpenedAtEpochMs = lastOpenedAtEpochMs,
        lastActiveFile = lastActiveFile,
    )

    private companion object {
        const val TAG = "WorkspaceRepo"
        const val MAX_NAME_LENGTH = 80
        const val MAX_DIRECTORY_NAME = 64
    }
}

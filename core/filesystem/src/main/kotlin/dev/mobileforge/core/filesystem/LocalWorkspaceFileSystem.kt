package dev.mobileforge.core.filesystem

import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.Logger
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.core.model.DefaultExclusions
import dev.mobileforge.core.model.WorkspaceFile
import dev.mobileforge.core.security.PathValidator
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * [WorkspaceFileSystem] over app-managed internal storage.
 *
 * This is the storage class the Phase 2 runtime will execute from, and the only one with full
 * POSIX semantics. It requires no manifest permission, which is why Phase 1 declares none
 * (RISK-008).
 *
 * Two independent containment checks run on every path:
 *   1. [PathValidator.validateRelative] rejects logical escapes ("..", absolute, NUL, backslash).
 *   2. Canonicalisation plus [PathValidator.isContained] rejects symlink escapes, which are
 *      invisible to step 1 because they only exist on disk.
 *
 * Both are required. Step 1 alone is bypassed by a symlink; step 2 alone lets a malformed path
 * reach the filesystem API before being caught.
 */
class LocalWorkspaceFileSystem(
    private val workspaceRoot: File,
    private val pathValidator: PathValidator,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
) : WorkspaceFileSystem {

    /**
     * The root's own real path.
     *
     * Resolved the same way as every candidate, so that a workspace root which itself sits
     * under a symlinked directory (a common shape for temp dirs) does not cause every path
     * inside it to be rejected as an escape.
     */
    private val canonicalRoot: String = runCatching {
        workspaceRoot.toPath().toRealPath().toString()
    }.getOrElse { workspaceRoot.absolutePath }.replace('\\', '/')

    override suspend fun list(relativeDirectory: String): AppResult<List<WorkspaceFile>> =
        withContext(dispatchers.io) {
            val dir = resolve(relativeDirectory).let {
                when (it) {
                    is AppResult.Failure -> return@withContext it
                    is AppResult.Success -> it.value
                }
            }

            if (!dir.isDirectory) {
                return@withContext fileError(
                    "That path is not a folder.",
                    "Expected a directory at '$relativeDirectory'.",
                )
            }

            val children = dir.listFiles()
                ?: return@withContext fileError(
                    "The folder could not be read.",
                    "listFiles() returned null for '$relativeDirectory'.",
                    "Check that the project folder still exists and is readable.",
                )

            children
                .mapNotNull { child -> child.toWorkspaceFileOrNull(relativeDirectory) }
                .sortedWith(
                    // Directories first, then case-insensitive by name — the ordering people
                    // expect from a file tree.
                    compareByDescending<WorkspaceFile> { it.isDirectory }
                        .thenBy { it.name.lowercase() },
                )
                .asSuccess()
        }

    override suspend fun readText(relativePath: String): AppResult<String> =
        withContext(dispatchers.io) {
            val file = resolve(relativePath).let {
                when (it) {
                    is AppResult.Failure -> return@withContext it
                    is AppResult.Success -> it.value
                }
            }

            if (!file.isFile) {
                return@withContext fileError(
                    "That file does not exist.",
                    "No regular file at '$relativePath'.",
                )
            }
            if (file.length() > FileLimits.MAX_EDITABLE_BYTES) {
                return@withContext AppError(
                    category = ErrorCategory.Validation,
                    message = "That file is too large to open in the editor.",
                    detail = "The file is ${file.length() / 1024} KB; the editor limit is " +
                        "${FileLimits.MAX_EDITABLE_BYTES / 1024} KB.",
                    recovery = "Open it from the terminal once the runtime is available, " +
                        "or split the file.",
                ).asFailure()
            }

            try {
                file.readText().asSuccess()
            } catch (e: IOException) {
                fileError("The file could not be read.", e.message, cause = e)
            }
        }

    /**
     * Atomic save: write to a sibling temp file, force it to disk, then rename over the target.
     *
     * The rename is the atomic step. Writing in place would leave a truncated source file if
     * Android killed the process mid-write, and losing a user's code that way is not a
     * trade-off worth making for a few milliseconds.
     */
    override suspend fun writeText(relativePath: String, content: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            val target = resolve(relativePath).let {
                when (it) {
                    is AppResult.Failure -> return@withContext it
                    is AppResult.Success -> it.value
                }
            }

            target.parentFile?.let { if (!it.exists() && !it.mkdirs()) {
                return@withContext fileError(
                    "The folder could not be created.",
                    "mkdirs() failed for '${it.name}'.",
                )
            } }

            val temp = File(target.parentFile, "${target.name}.mf-tmp")
            try {
                temp.outputStream().use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                    // fsync: without it, rename can land before the data does.
                    out.fd.sync()
                }

                if (!temp.renameTo(target)) {
                    // renameTo can fail across some filesystems; fall back to copy+delete and
                    // report honestly if even that fails, rather than silently losing the edit.
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                AppResult.Success(Unit)
            } catch (e: IOException) {
                temp.delete()
                fileError("The file could not be saved.", e.message, cause = e)
            }
        }

    override suspend fun createFile(relativePath: String): AppResult<WorkspaceFile> =
        withContext(dispatchers.io) {
            val file = resolve(relativePath).let {
                when (it) {
                    is AppResult.Failure -> return@withContext it
                    is AppResult.Success -> it.value
                }
            }
            if (file.exists()) {
                return@withContext AppError(
                    category = ErrorCategory.Validation,
                    message = "A file with that name already exists.",
                    detail = "'$relativePath' is already present.",
                    recovery = "Choose a different name.",
                ).asFailure()
            }
            file.parentFile?.mkdirs()
            try {
                if (!file.createNewFile()) {
                    return@withContext fileError("The file could not be created.", relativePath)
                }
                file.toWorkspaceFile(relativePath).asSuccess()
            } catch (e: IOException) {
                fileError("The file could not be created.", e.message, cause = e)
            }
        }

    override suspend fun createDirectory(relativePath: String): AppResult<WorkspaceFile> =
        withContext(dispatchers.io) {
            val dir = resolve(relativePath).let {
                when (it) {
                    is AppResult.Failure -> return@withContext it
                    is AppResult.Success -> it.value
                }
            }
            if (dir.exists()) {
                return@withContext AppError(
                    category = ErrorCategory.Validation,
                    message = "A folder with that name already exists.",
                    detail = "'$relativePath' is already present.",
                    recovery = "Choose a different name.",
                ).asFailure()
            }
            if (!dir.mkdirs()) {
                return@withContext fileError("The folder could not be created.", relativePath)
            }
            dir.toWorkspaceFile(relativePath).asSuccess()
        }

    override suspend fun delete(relativePath: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            if (relativePath.isBlank()) {
                // Deleting "" would delete the workspace root. Refuse explicitly rather than
                // relying on a caller never passing an empty string.
                logger.security(TAG, "Refused delete of workspace root")
                return@withContext AppError(
                    category = ErrorCategory.Security,
                    message = "The project folder cannot be deleted from here.",
                    detail = "Refused an attempt to delete the workspace root.",
                    recovery = "Delete the project from the project list instead.",
                ).asFailure()
            }

            val file = resolve(relativePath).let {
                when (it) {
                    is AppResult.Failure -> return@withContext it
                    is AppResult.Success -> it.value
                }
            }
            if (!file.exists()) {
                return@withContext fileError("That item no longer exists.", relativePath)
            }

            val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
            if (deleted) {
                AppResult.Success(Unit)
            } else {
                fileError("The item could not be deleted.", relativePath)
            }
        }

    override suspend fun rename(
        fromRelativePath: String,
        toRelativePath: String,
    ): AppResult<Unit> = withContext(dispatchers.io) {
        val from = resolve(fromRelativePath).let {
            when (it) {
                is AppResult.Failure -> return@withContext it
                is AppResult.Success -> it.value
            }
        }
        val to = resolve(toRelativePath).let {
            when (it) {
                is AppResult.Failure -> return@withContext it
                is AppResult.Success -> it.value
            }
        }
        if (!from.exists()) {
            return@withContext fileError("That item no longer exists.", fromRelativePath)
        }
        if (to.exists()) {
            return@withContext AppError(
                category = ErrorCategory.Validation,
                message = "Something with that name already exists.",
                detail = "'$toRelativePath' is already present.",
                recovery = "Choose a different name.",
            ).asFailure()
        }
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) {
            AppResult.Success(Unit)
        } else {
            fileError("The item could not be renamed.", fromRelativePath)
        }
    }

    override suspend fun exists(relativePath: String): Boolean = withContext(dispatchers.io) {
        resolve(relativePath).getOrNull()?.exists() == true
    }

    override suspend fun stat(relativePath: String): AppResult<WorkspaceFile> =
        withContext(dispatchers.io) {
            val file = resolve(relativePath).let {
                when (it) {
                    is AppResult.Failure -> return@withContext it
                    is AppResult.Success -> it.value
                }
            }
            if (!file.exists()) {
                return@withContext fileError("That item does not exist.", relativePath)
            }
            file.toWorkspaceFile(relativePath).asSuccess()
        }

    override suspend fun sizeOf(relativePath: String): AppResult<Long> =
        withContext(dispatchers.io) {
            resolve(relativePath).let {
                when (it) {
                    is AppResult.Failure -> it
                    is AppResult.Success -> it.value.length().asSuccess()
                }
            }
        }

    // -----------------------------------------------------------------------------
    // Containment
    // -----------------------------------------------------------------------------

    /**
     * Validates, resolves, and then re-checks containment against the REAL on-disk path.
     *
     * The second check is what catches a symlink pointing outside the workspace — a logical
     * path check can never see that, because the escape only exists on disk.
     *
     * [java.nio.file.Path.toRealPath] is used rather than [File.getCanonicalPath]. That is not
     * a style preference: a test in this module proved that `canonicalPath` does NOT reliably
     * resolve symbolic links on every platform, and a containment check built on it lets a
     * symlinked path straight through. `toRealPath` is specified to resolve links.
     */
    private fun resolve(relativePath: String): AppResult<File> {
        val validated = pathValidator.validateRelative(relativePath)
        val safeRelative = when (validated) {
            is AppResult.Failure -> {
                logger.security(TAG, "Rejected path: ${validated.error.detail}")
                return validated
            }
            is AppResult.Success -> validated.value
        }

        val candidate = if (safeRelative.isEmpty()) {
            workspaceRoot
        } else {
            File(workspaceRoot, safeRelative)
        }

        val real = try {
            realPathOf(candidate)
        } catch (e: IOException) {
            return fileError("That path could not be resolved.", e.message, cause = e)
        }

        if (!pathValidator.isContained(canonicalRoot, real)) {
            logger.security(
                TAG,
                "Blocked symlink or canonicalisation escape from '$relativePath'",
            )
            return AppError(
                category = ErrorCategory.Security,
                message = "That location is outside the project.",
                detail = "The path resolves outside the workspace root, " +
                    "possibly through a symbolic link.",
                recovery = "Use a path inside the project folder.",
            ).asFailure()
        }

        return candidate.asSuccess()
    }

    /**
     * The real, link-resolved path of [candidate], in '/'-separated form.
     *
     * A file that does not exist yet (a new file, or the target of a save) cannot be resolved
     * directly, so we resolve its nearest EXISTING ancestor and re-append the remainder. That
     * is what stops a write into a symlinked directory: the directory exists and resolves, even
     * though the file inside it does not yet.
     */
    private fun realPathOf(candidate: File): String {
        var existing: File? = candidate
        val trailing = ArrayDeque<String>()

        while (existing != null && !existing.exists()) {
            trailing.addFirst(existing.name)
            existing = existing.parentFile
        }

        // Nothing on the path exists (a detached or malformed path): fall back to the
        // lexically-normalised absolute path rather than silently allowing it.
        val base = existing?.toPath()?.toRealPath()?.toString()
            ?: candidate.absolutePath

        val joined = if (trailing.isEmpty()) base else "$base/${trailing.joinToString("/")}"
        return joined.replace('\\', '/')
    }

    private fun File.toWorkspaceFile(relativePath: String) = WorkspaceFile(
        relativePath = relativePath,
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isDirectory) 0L else length(),
        lastModifiedEpochMs = lastModified(),
    )

    /**
     * Builds a child entry, skipping our own temp files so a save in flight never shows up as
     * a phantom file in the tree.
     */
    private fun File.toWorkspaceFileOrNull(parentRelative: String): WorkspaceFile? {
        if (name.endsWith(".mf-tmp")) return null
        val childRelative = if (parentRelative.isEmpty()) name else "$parentRelative/$name"
        return toWorkspaceFile(childRelative)
    }

    private fun fileError(
        message: String,
        detail: String?,
        recovery: String? = null,
        cause: Throwable? = null,
    ): AppResult<Nothing> = AppError(
        category = ErrorCategory.FileSystem,
        message = message,
        detail = detail,
        recovery = recovery,
        cause = cause,
    ).asFailure()

    private companion object {
        const val TAG = "LocalWorkspaceFs"
    }
}

/** Shared with the explorer so exclusion behaviour is identical in listing and in search. */
fun WorkspaceFile.isDefaultExcluded(): Boolean =
    isDirectory && DefaultExclusions.isExcluded(name)

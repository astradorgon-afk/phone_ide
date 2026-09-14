package dev.mobileforge.core.filesystem

import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.model.WorkspaceFile

/**
 * The only way features are permitted to touch project files.
 *
 * Every method takes a workspace-relative path, never an absolute one. Absolute paths are an
 * implementation detail of this layer, which is what makes containment enforceable in exactly
 * one place instead of at every call site.
 *
 * An interface rather than a concrete class because the brief makes mockable infrastructure
 * mandatory, and because SAF-backed and internal-storage-backed workspaces are genuinely
 * different implementations of the same contract.
 */
interface WorkspaceFileSystem {

    /**
     * Lists the immediate children of [relativeDirectory].
     *
     * Listing is shallow by design: recursively walking a project on a phone is how you get an
     * unresponsive explorer and a flat battery (RISK-009). The tree loads a level at a time.
     */
    suspend fun list(relativeDirectory: String): AppResult<List<WorkspaceFile>>

    suspend fun readText(relativePath: String): AppResult<String>

    /**
     * Writes atomically: temp file, flush, fsync, rename. A crash mid-save must never leave a
     * truncated source file — losing a user's code to a process kill is unacceptable.
     */
    suspend fun writeText(relativePath: String, content: String): AppResult<Unit>

    suspend fun createFile(relativePath: String): AppResult<WorkspaceFile>

    suspend fun createDirectory(relativePath: String): AppResult<WorkspaceFile>

    suspend fun delete(relativePath: String): AppResult<Unit>

    suspend fun rename(fromRelativePath: String, toRelativePath: String): AppResult<Unit>

    suspend fun exists(relativePath: String): Boolean

    suspend fun stat(relativePath: String): AppResult<WorkspaceFile>

    /** Bytes, for deciding whether a file is safe to open in the editor at all. */
    suspend fun sizeOf(relativePath: String): AppResult<Long>
}

/** Guard rails for opening files in a WebView-hosted editor on a phone. */
object FileLimits {
    /** Above this, the editor refuses to open and says why rather than freezing. */
    const val MAX_EDITABLE_BYTES: Long = 4L * 1024 * 1024

    /** Above this, a file is treated as binary unless it decodes cleanly as UTF-8. */
    const val BINARY_SNIFF_BYTES: Int = 8_000
}

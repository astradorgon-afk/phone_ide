package dev.mobileforge.core.security

import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess

/**
 * Enforces that a caller-supplied path stays inside its workspace.
 *
 * This is a pure function on purpose. It is the single choke point every file operation must
 * pass through, and keeping it free of Android types means its adversarial tests run as plain
 * JVM tests in milliseconds — security tests you cannot run cheaply are security tests that
 * stop being run.
 *
 * The validator works on *logical* paths and does not touch the disk. Symlink escape cannot be
 * detected without I/O, so it is handled separately at the filesystem layer by canonicalising
 * the resolved file and re-checking containment with [isContained].
 */
class PathValidator {

    /**
     * Normalises [rawPath] (interpreted relative to the workspace root) and rejects anything
     * that escapes the root, is absolute, or contains a NUL byte.
     *
     * Returns the normalised workspace-relative path using '/' separators.
     */
    fun validateRelative(rawPath: String): AppResult<String> {
        if (rawPath.isEmpty()) return "".asSuccess()

        if (rawPath.contains('\u0000')) {
            return deny("Path contains a NUL byte.", rawPath)
        }

        // Backslashes are normalised first: in a Windows-authored archive, "..\..\etc" is the
        // same attack as "../../etc", and treating a backslash as an ordinary filename
        // character would let it straight through.
        val unified = rawPath.replace('\\', '/')

        if (unified.startsWith("/")) {
            return deny("Absolute paths are not permitted inside a workspace.", rawPath)
        }

        // A Windows drive prefix ("C:/...") is absolute even without a leading slash.
        if (unified.length >= 2 && unified[1] == ':') {
            return deny("Drive-qualified paths are not permitted inside a workspace.", rawPath)
        }

        val normalised = normalise(unified)
            ?: return deny("Path escapes the workspace root.", rawPath)

        return normalised.asSuccess()
    }

    /**
     * Resolves [rawPath] against [workspaceRoot] and returns the absolute result, or a failure
     * if it would escape. [workspaceRoot] must already be absolute and canonical.
     */
    fun resolveWithin(workspaceRoot: String, rawPath: String): AppResult<String> {
        val relative = validateRelative(rawPath)
        val value = when (relative) {
            is AppResult.Failure -> return relative
            is AppResult.Success -> relative.value
        }
        val root = workspaceRoot.replace('\\', '/').trimEnd('/')
        val resolved = if (value.isEmpty()) root else "$root/$value"
        return resolved.asSuccess()
    }

    /**
     * Containment check for an already-resolved absolute path — used by the filesystem layer
     * after canonicalisation, which is the only way to catch symlink escape.
     *
     * Compares whole path segments, so "/data/workspaces/app-evil" is correctly rejected as
     * being outside "/data/workspaces/app" despite sharing a string prefix.
     */
    fun isContained(workspaceRoot: String, candidateAbsolutePath: String): Boolean {
        val root = workspaceRoot.replace('\\', '/').trimEnd('/')
        val candidate = candidateAbsolutePath.replace('\\', '/').trimEnd('/')
        if (candidate == root) return true
        return candidate.startsWith("$root/")
    }

    /**
     * Collapses "." and ".." segments. Returns null if the path escapes above the root — the
     * case the caller must treat as an attack rather than as a normalisation quirk.
     */
    private fun normalise(path: String): String? {
        val out = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isEmpty()) return null else out.removeLast()
                else -> out.addLast(segment)
            }
        }
        return out.joinToString("/")
    }

    private fun deny(reason: String, rawPath: String): AppResult<Nothing> = AppError(
        category = ErrorCategory.Security,
        message = "That location is outside the project.",
        detail = "$reason (requested: ${rawPath.take(MAX_REPORTED_PATH)})",
        recovery = "Use a path inside the project folder.",
    ).asFailure()

    private companion object {
        /** Caps what an attacker-controlled path can push into a log line or a dialog. */
        const val MAX_REPORTED_PATH = 128
    }
}

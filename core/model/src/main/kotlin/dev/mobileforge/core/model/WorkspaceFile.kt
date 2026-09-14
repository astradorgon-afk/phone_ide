package dev.mobileforge.core.model

/**
 * A node in the project tree.
 *
 * [relativePath] is always relative to the workspace root and uses '/' separators regardless
 * of platform. Absolute paths never leave the filesystem layer - keeping them out of the
 * domain model is what makes path-containment enforceable in one place.
 */
data class WorkspaceFile(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val isHidden: Boolean = name.startsWith("."),
) {
    val extension: String
        get() = name.substringAfterLast('.', missingDelimiterValue = "")

    val parentPath: String?
        get() = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() && it != relativePath }
}

/**
 * Directories excluded from tree listing and search by default.
 *
 * These are not hidden from the user - they are excluded from *eager* traversal, because
 * walking node_modules or vendor on a phone is how you produce an unresponsive file explorer
 * and a dead battery. The user can still navigate into them explicitly.
 */
object DefaultExclusions {
    val directories: Set<String> = setOf(
        "node_modules",
        "vendor",
        ".git",
        ".idea",
        ".gradle",
        "build",
        "dist",
        ".next",
        ".nuxt",
        "storage/framework",
        "storage/logs",
        "bootstrap/cache",
        "__pycache__",
        ".venv",
    )

    fun isExcluded(directoryName: String): Boolean = directoryName in directories
}

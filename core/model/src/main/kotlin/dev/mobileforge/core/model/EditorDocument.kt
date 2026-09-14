package dev.mobileforge.core.model

/**
 * An open editor buffer.
 *
 * Kotlin owns this, not the WebView. If the WebView is reclaimed or crashes, the buffer is
 * reloaded into a fresh one and no user text is lost. See docs/adr/ADR-003-monaco-integration.md.
 */
data class EditorDocument(
    val relativePath: String,
    val content: String,
    val languageId: String,
    val isDirty: Boolean = false,
    val encoding: String = "UTF-8",
    val readOnly: Boolean = false,
)

/**
 * Maps a filename to a Monaco language id.
 *
 * Kept in the pure domain module so it is unit-testable and so the editor implementation can
 * be swapped (Monaco to CodeMirror) without relocating this table.
 */
object LanguageDetector {

    private val byExtension: Map<String, String> = mapOf(
        "php" to "php",
        "js" to "javascript",
        "mjs" to "javascript",
        "cjs" to "javascript",
        "jsx" to "javascript",
        "ts" to "typescript",
        "tsx" to "typescript",
        "html" to "html",
        "htm" to "html",
        "vue" to "html",
        "css" to "css",
        "scss" to "scss",
        "less" to "less",
        "json" to "json",
        "jsonc" to "json",
        "yaml" to "yaml",
        "yml" to "yaml",
        "md" to "markdown",
        "markdown" to "markdown",
        "sql" to "sql",
        "sh" to "shell",
        "bash" to "shell",
        "zsh" to "shell",
        "xml" to "xml",
        "svg" to "xml",
        "kt" to "kotlin",
        "kts" to "kotlin",
        "java" to "java",
        "py" to "python",
        "rb" to "ruby",
        "go" to "go",
        "rs" to "rust",
        "toml" to "ini",
        "ini" to "ini",
        "env" to "ini",
        "txt" to "plaintext",
        "lock" to "json",
    )

    /** Files whose whole name determines the language, extension notwithstanding. */
    private val byFilename: Map<String, String> = mapOf(
        "dockerfile" to "dockerfile",
        "makefile" to "makefile",
        ".env" to "ini",
        ".env.example" to "ini",
        ".gitignore" to "plaintext",
        ".gitattributes" to "plaintext",
        "composer.json" to "json",
        "composer.lock" to "json",
        "package.json" to "json",
        "package-lock.json" to "json",
        "artisan" to "php",
    )

    fun detect(fileName: String): String {
        val lower = fileName.lowercase()
        byFilename[lower]?.let { return it }

        // Blade templates: welcome.blade.php must resolve to blade, not php.
        if (lower.endsWith(".blade.php")) return "blade"

        val ext = lower.substringAfterLast('.', missingDelimiterValue = "")
        return byExtension[ext] ?: "plaintext"
    }
}

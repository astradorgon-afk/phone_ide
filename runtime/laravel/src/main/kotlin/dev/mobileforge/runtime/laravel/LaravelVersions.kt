package dev.mobileforge.runtime.laravel

import dev.mobileforge.core.common.AppResult
import dev.mobileforge.runtime.api.OutputStream
import dev.mobileforge.runtime.api.ProcessExecutor
import dev.mobileforge.runtime.api.ProcessSpec
import kotlinx.coroutines.flow.toList

/**
 * Versions read by running the tools, not by reading files.
 *
 * `FrameworkDetector.describe()` deliberately reports only what composer.json *declares*, and
 * says so, because in Phase 1 there was no runtime to ask. A declared constraint is not a fact:
 * `"php": "^8.2"` tells you what the project wants, not what is installed, and `composer.json`
 * can name a Laravel version that `composer install` never actually fetched.
 *
 * This asks the tools. Every field is null when the tool is absent or did not answer in a shape
 * we recognise — an honest gap rather than a plausible-looking number nobody verified.
 */
class LaravelVersions(
    private val executor: ProcessExecutor,
    private val phpPath: String,
    private val environment: Map<String, String> = emptyMap(),
) {

    suspend fun read(projectRoot: String): ExecutedVersions = ExecutedVersions(
        php = parsePhp(capture(projectRoot, phpPath, listOf("--version"))),
        laravel = parseLaravel(capture(projectRoot, phpPath, listOf("artisan", "--version"))),
    )

    /**
     * Runs a command and returns its output, or null if it could not run at all.
     *
     * Failure is not an error here. "PHP is not installed" is a legitimate answer to "what
     * version of PHP is installed", and it is the caller's job to say so in the UI rather than
     * this class's job to invent something.
     */
    private suspend fun capture(
        workingDirectory: String,
        executable: String,
        arguments: List<String>,
    ): String? {
        val started = executor.start(
            ProcessSpec(
                executable = executable,
                arguments = arguments,
                workingDirectory = workingDirectory,
                environment = environment,
                shell = false,
            ),
        )
        val handle = (started as? AppResult.Success)?.value ?: return null

        // Drained before awaiting, or a process that fills its pipe blocks forever.
        val lines = handle.output.toList()
        val exit = (handle.await() as? AppResult.Success)?.value ?: return null
        if (exit != 0) return null

        return lines.filter { it.stream == OutputStream.Stdout }
            .joinToString("\n") { it.line }
            .takeIf { it.isNotBlank() }
    }

    private companion object {
        /** `PHP 8.5.1 (cli) (built: ...)` */
        private val PHP = Regex("""PHP\s+(\d+\.\d+\.\d+)""")

        /** `Laravel Framework 11.9.2` */
        private val LARAVEL = Regex("""Laravel Framework\s+(\d+\.\d+\.\d+)""")

        fun parsePhp(output: String?): String? =
            output?.let { PHP.find(it)?.groupValues?.get(1) }

        fun parseLaravel(output: String?): String? =
            output?.let { LARAVEL.find(it)?.groupValues?.get(1) }
    }
}

/**
 * What the installed tools actually reported.
 *
 * Null means "could not be determined", never "not installed" — the difference matters, because
 * a tool can be present and still fail to answer. The UI should say "unknown", not invent a
 * version or claim absence it did not establish.
 */
data class ExecutedVersions(
    val php: String? = null,
    val laravel: String? = null,
) {
    val anyKnown: Boolean get() = php != null || laravel != null
}

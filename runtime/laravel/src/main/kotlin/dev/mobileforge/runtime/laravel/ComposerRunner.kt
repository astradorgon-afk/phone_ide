package dev.mobileforge.runtime.laravel

import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.runtime.api.OutputStream
import dev.mobileforge.runtime.api.ProcessExecutor
import dev.mobileforge.runtime.api.ProcessHandle
import dev.mobileforge.runtime.api.ProcessSpec
import kotlinx.coroutines.flow.toList

/**
 * Runs Composer for project scaffolding.
 *
 * The narrow sibling of [ArtisanRunner]: where that one runs `php artisan` inside an
 * existing project, this one runs `composer create-project` to fetch one. Same rules —
 * no shell (so nothing typed can become shell syntax), and structured errors rather
 * than exceptions.
 *
 * What this deliberately does NOT do is gate risk. `create-project` executes
 * third-party package scripts, which the permission model counts as a separate
 * consent from "run commands" (RISK-015). That consent belongs in the UI that offers
 * the button — a library cannot verify a person was asked — so callers must confirm
 * first and say so at the call site, exactly as [ArtisanRunner] requires for
 * destructive commands.
 */
class ComposerRunner(
    private val executor: ProcessExecutor,
    /** Absolute path to the `composer` binary from the installed toolchain. */
    private val composerPath: String,
    private val environment: Map<String, String> = emptyMap(),
) {

    /**
     * Fetches [packageName] (e.g. `laravel/laravel`) into [targetDir].
     *
     * [targetDir] must be absent or empty — Composer refuses anything else, and failing
     * here first with a clear error beats downloading 200 MB into a folder that cannot
     * receive it. [workingDirectory] only needs to exist; the install lands in
     * [targetDir], addressed absolutely.
     */
    suspend fun createProject(
        packageName: String,
        targetDir: String,
        workingDirectory: String,
        onStarted: (ProcessHandle) -> Unit = {},
    ): AppResult<ComposerResult> {
        if (packageName.isBlank()) {
            return AppError(
                category = ErrorCategory.Validation,
                message = "No package was named.",
                detail = "composer create-project needs a package such as laravel/laravel.",
                recovery = "Choose the Laravel starter to install it.",
            ).asFailure()
        }
        if (targetDir.isBlank()) {
            return AppError(
                category = ErrorCategory.Validation,
                message = "No install location was given.",
                detail = "composer create-project needs an empty target directory.",
                recovery = "Create the project again with a valid name.",
            ).asFailure()
        }

        // Non-interactive and progress-free on purpose. A prompt mid-install on a phone
        // would hang the run with no way to answer it, and progress bars render as
        // carriage-return noise in a line-based log. Per-package lines still stream, which
        // is what the progress UI actually shows.
        val arguments = listOf(
            "create-project",
            packageName,
            targetDir,
            "--no-interaction",
            "--no-progress",
        )

        val started = executor.start(
            ProcessSpec(
                executable = composerPath,
                arguments = arguments,
                workingDirectory = workingDirectory,
                environment = environment,
                shell = false,
            ),
        )

        val handle = when (started) {
            is AppResult.Failure -> return started
            is AppResult.Success -> started.value.also(onStarted)
        }

        // Collected before awaiting: a downloader that fills its pipe while nobody reads
        // it blocks forever, and create-project is chatty.
        val lines = handle.output.toList()

        return when (val exit = handle.await()) {
            is AppResult.Failure -> exit
            is AppResult.Success -> ComposerResult(
                command = "composer ${arguments.joinToString(" ")}",
                exitCode = exit.value,
                stdout = lines.filter { it.stream == OutputStream.Stdout }.map { it.line },
                stderr = lines.filter { it.stream == OutputStream.Stderr }.map { it.line },
            ).asSuccess()
        }
    }
}

/**
 * What a Composer invocation produced.
 *
 * stdout and stderr stay apart: dependency resolution reports on stdout while download
 * and script errors go to stderr, and a UI that merges them cannot tell the user which
 * lines are the problem.
 */
data class ComposerResult(
    val command: String,
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
) {
    val succeeded: Boolean get() = exitCode == 0

    /** Everything printed, for a log pane. */
    val allOutput: List<String> get() = stdout + stderr
}

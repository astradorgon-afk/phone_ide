package dev.mobileforge.runtime.laravel

import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.runtime.api.OutputStream
import dev.mobileforge.runtime.api.ProcessExecutor
import dev.mobileforge.runtime.api.ProcessSpec
import kotlinx.coroutines.flow.toList
import java.io.File

/**
 * Runs `php artisan` in a Laravel project.
 *
 * Two things are deliberate here.
 *
 * **A destructive command cannot run by accident.** [run] refuses one unless the caller passes
 * `confirmed = true`, and the refusal is a returned error rather than an exception — so a
 * caller that forgets gets a failure it must handle, not a crash it might catch broadly. The
 * brief requires that destructive database operations never run without the user's consent, and
 * the cheapest way to keep that true is to make the unconfirmed path impossible to take
 * silently.
 *
 * **No shell.** The command is handed to the executor as an argument vector, so nothing the
 * user types can be interpreted as shell syntax. `ProcessSpec.shell` stays false.
 */
class ArtisanRunner(
    private val executor: ProcessExecutor,
    /** Absolute path to the `php` binary from the installed toolchain. */
    private val phpPath: String,
    private val environment: Map<String, String> = emptyMap(),
) {

    /**
     * Runs [command] in the project at [projectRoot].
     *
     * [confirmed] must be true for anything [ArtisanCommand.requiresConfirmation] flags. The
     * caller is expected to have actually asked a person — this cannot verify that, but it can
     * and does make the decision explicit at the call site.
     */
    suspend fun run(
        projectRoot: String,
        command: ArtisanCommand,
        confirmed: Boolean = false,
    ): AppResult<ArtisanResult> {
        if (command.requiresConfirmation && !confirmed) {
            return AppError(
                category = ErrorCategory.Security,
                message = "This command needs your confirmation.",
                detail = when (val risk = command.risk) {
                    is dev.mobileforge.core.security.CommandRisk.Destructive -> risk.reason
                    else -> "php artisan ${command.argv.joinToString(" ")} can affect your data."
                },
                recovery = "Confirm the command to run it.",
            ).asFailure()
        }

        val artisan = File(projectRoot, ARTISAN)
        if (!artisan.isFile) {
            return AppError(
                category = ErrorCategory.Unavailable,
                message = "This is not a Laravel project.",
                detail = "No artisan file at ${artisan.absolutePath}.",
                recovery = "Open a Laravel project, or create one first.",
            ).asFailure()
        }

        val started = executor.start(
            ProcessSpec(
                executable = phpPath,
                // `artisan` by relative name with the project as the working directory: Laravel
                // resolves its own base path from there, and an absolute path would still work
                // but makes the process listing harder to read.
                arguments = listOf(ARTISAN) + command.argv,
                workingDirectory = projectRoot,
                environment = environment,
                shell = false,
            ),
        )

        val handle = when (started) {
            is AppResult.Failure -> return started
            is AppResult.Success -> started.value
        }

        // Collected before awaiting: a process that fills its pipe while nobody reads it will
        // block forever, and `artisan test` produces plenty of output.
        val lines = handle.output.toList()

        return when (val exit = handle.await()) {
            is AppResult.Failure -> exit
            is AppResult.Success -> ArtisanResult(
                command = command,
                exitCode = exit.value,
                stdout = lines.filter { it.stream == OutputStream.Stdout }.map { it.line },
                stderr = lines.filter { it.stream == OutputStream.Stderr }.map { it.line },
            ).asSuccess()
        }
    }

    private companion object {
        const val ARTISAN = "artisan"
    }
}

/**
 * What an artisan command produced.
 *
 * stdout and stderr are kept apart rather than interleaved: artisan writes errors to stderr,
 * and a UI that merges them cannot tell the user which lines are the problem.
 */
data class ArtisanResult(
    val command: ArtisanCommand,
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
) {
    val succeeded: Boolean get() = exitCode == 0

    /** Everything the command printed, for a log pane. */
    val allOutput: List<String> get() = stdout + stderr
}

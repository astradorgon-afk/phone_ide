package dev.mobileforge.runtime.exec

import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The thin seam over actual OS process creation.
 *
 * An interface so [DefaultProcessManager] — where the lifecycle, buffering and error-reporting
 * logic lives — can be tested exhaustively with a fake, on a machine that cannot run Android
 * binaries at all. Without this seam, none of the manager's behaviour would be verifiable
 * before hardware exists.
 */
interface ProcessLauncher {
    fun launch(
        argv: List<String>,
        workingDirectory: String,
        environment: Map<String, String>,
    ): AppResult<LaunchedProcess>
}

/** A running OS process. */
interface LaunchedProcess {
    /** Null where the platform does not expose it. */
    val pid: Long?
    val stdout: InputStream
    val stderr: InputStream
    val stdin: OutputStream

    fun isAlive(): Boolean
    fun waitFor(): Int
    fun destroy(force: Boolean)
}

/**
 * [ProcessLauncher] over `java.lang.ProcessBuilder`, which is available and functional on
 * Android.
 *
 * `ProcessBuilder` gives pipes, not a terminal: there is no TTY, so line discipline, job
 * control and `isatty()` are absent. That is correct for running a build or a server, and NOT
 * sufficient for the interactive terminal, which needs a real PTY via the NDK. That is
 * deliberately a separate piece of work — see ROADMAP.md Phase 2b — rather than something
 * faked here.
 */
class JvmProcessLauncher : ProcessLauncher {

    override fun launch(
        argv: List<String>,
        workingDirectory: String,
        environment: Map<String, String>,
    ): AppResult<LaunchedProcess> {
        if (argv.isEmpty()) {
            return AppError(
                category = ErrorCategory.Validation,
                message = "No command was given.",
                detail = "The argument vector was empty.",
            ).asFailure()
        }

        val cwd = File(workingDirectory)
        if (!cwd.isDirectory) {
            return AppError(
                category = ErrorCategory.Process,
                message = "The working directory does not exist.",
                detail = "'$workingDirectory' is not a directory.",
                recovery = "Reopen the project and try again.",
            ).asFailure()
        }

        return try {
            val builder = ProcessBuilder(argv).directory(cwd)
            builder.environment().apply {
                // Replace rather than merge: inheriting the Android app's own environment
                // leaks host paths and would let a stale LD_LIBRARY_PATH break the toolchain.
                clear()
                putAll(environment)
            }
            JvmLaunchedProcess(builder.start()).asSuccess()
        } catch (e: IOException) {
            AppError(
                category = ErrorCategory.Process,
                message = "'${programName(argv)}' could not be started.",
                detail = describe(e, argv),
                recovery = recoveryFor(e),
                cause = e,
                retryable = false,
            ).asFailure()
        } catch (e: SecurityException) {
            AppError(
                category = ErrorCategory.Security,
                message = "'${programName(argv)}' was blocked by the system.",
                detail = e.message,
                recovery = "This program cannot be run from app storage on this device.",
                cause = e,
            ).asFailure()
        }
    }

    /**
     * Turns the platform's terse exec failures into something a developer can act on.
     *
     * EACCES here is the signature of the W^X policy — the exact failure the system-linker
     * strategy exists to avoid — so it is worth naming explicitly rather than surfacing
     * "error=13".
     */
    private fun describe(e: IOException, argv: List<String>): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("error=13") || raw.contains("Permission denied", ignoreCase = true) ->
                "The system refused to execute '${argv.first()}' (EACCES). On Android 10 and " +
                    "above, files in app storage cannot be executed directly; they must be " +
                    "launched through the system linker."

            raw.contains("error=2") || raw.contains("No such file", ignoreCase = true) ->
                "'${argv.first()}' does not exist."

            raw.contains("error=8") || raw.contains("Exec format", ignoreCase = true) ->
                "'${argv.first()}' is not a valid executable for this device's CPU " +
                    "architecture, or is not a dynamically linked binary."

            else -> raw.ifEmpty { "The process could not be started." }
        }
    }

    private fun recoveryFor(e: IOException): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("error=13") ->
                "Run diagnostics from Settings to check the execution strategy for this device."

            raw.contains("error=2") -> "Install the tool, or check the path."
            raw.contains("error=8") -> "Install a build matching this device's ABI."
            else -> "Check the command and try again."
        }
    }

    private fun programName(argv: List<String>) = argv.first().substringAfterLast('/')
}

private class JvmLaunchedProcess(private val process: Process) : LaunchedProcess {

    override val pid: Long?
        get() = runCatching { process.pid() }.getOrNull()

    override val stdout: InputStream get() = process.inputStream
    override val stderr: InputStream get() = process.errorStream
    override val stdin: OutputStream get() = process.outputStream

    override fun isAlive(): Boolean = process.isAlive

    override fun waitFor(): Int = process.waitFor()

    override fun destroy(force: Boolean) {
        if (force) process.destroyForcibly() else process.destroy()
    }
}

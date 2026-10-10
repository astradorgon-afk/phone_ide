package dev.mobileforge.runtime.exec

import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.runtime.api.OutputStream
import dev.mobileforge.runtime.api.ProcessExecutor
import dev.mobileforge.runtime.api.ProcessHandle
import dev.mobileforge.runtime.api.ProcessId
import dev.mobileforge.runtime.api.ProcessOutput
import dev.mobileforge.runtime.api.ProcessSignal
import dev.mobileforge.runtime.api.ProcessSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * A [ProcessExecutor] for commands that run to completion.
 *
 * This exists alongside [DefaultProcessManager] because the two answer different questions.
 * The manager owns long-lived processes — servers, watchers — with replayable logs and a
 * registry, so its output flow is infinite by design and its `await()` reports the latest
 * known state. [ArtisanRunner] needs the opposite: a finite stream that ends when the command
 * ends, and an `await()` that actually waits. Adapting the manager would have meant either
 * hanging on its infinite flow or silently changing its replay semantics, so short-lived
 * commands get their own executor instead.
 *
 * What is NOT here, deliberately:
 *
 * - No process registry. One-shot runs are not listed, restarted, or kept after exit. If
 *   artisan runs need visibility later, that is a manager feature, not a change to this class.
 * - No output cap. [ArtisanRunner] collects every line before awaiting; capping the flow
 *   here would silently rewrite its results. Display caps belong in the UI.
 *
 * Cancellation contract: collect [ProcessHandle.output] to completion before [ProcessHandle]
 * .await, exactly as [ArtisanRunner] does. A process that fills its pipe while nobody reads
 * blocks forever — the same warning the runner already carries, enforced by the same order.
 */
class OneShotProcessExecutor(
    private val launcher: ProcessLauncher,
    private val commandBuilder: ExecCommandBuilder,
    private val environmentBuilder: RuntimeEnvironmentBuilder,
    private val dispatchers: AppDispatchers,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProcessExecutor {

    override suspend fun start(spec: ProcessSpec): AppResult<ProcessHandle> =
        withContext(dispatchers.io) {
            val resolved = when (val built = commandBuilder.build(spec.executable, spec.arguments)) {
                is AppResult.Failure -> return@withContext built
                is AppResult.Success -> built.value
            }

            val environment = environmentBuilder.build(
                command = resolved,
                workingDirectory = spec.workingDirectory,
                extra = spec.environment,
            )

            when (val launched = launcher.launch(resolved.argv, spec.workingDirectory, environment)) {
                is AppResult.Failure -> launched
                is AppResult.Success ->
                    OneShotHandle(launched.value, spec.timeoutMs, dispatchers, clock).asSuccess()
            }
        }
}

/** How long drains get to finish after a timeout kill before the flow closes anyway. */
private const val DRAIN_GRACE_MS = 2_000L

/**
 * A handle whose output ends.
 *
 * The flow closes once the process has exited (or timed out) and its streams are drained, so
 * `output.toList()` returns instead of hanging. `await()` reports the real exit code — or a
 * timeout failure — rather than the latest known state.
 */
private class OneShotHandle(
    private val live: LaunchedProcess,
    private val timeoutMs: Long?,
    private val dispatchers: AppDispatchers,
    private val clock: () -> Long,
) : ProcessHandle {

    override val id = ProcessId(UUID.randomUUID().toString())

    @Volatile
    private var timedOut = false

    override val output: Flow<ProcessOutput> = channelFlow {
        val drains = listOf(
            launch(dispatchers.io) { drain(live.stdout, OutputStream.Stdout) },
            launch(dispatchers.io) { drain(live.stderr, OutputStream.Stderr) },
        )
        val waiter = launch(dispatchers.io) { live.waitFor() }

        // Drains run on IO threads explicitly: this flow is collected on whatever scope the
        // caller uses — including the main thread — and blocking reads must never land there.
        val exited = if (timeoutMs != null) {
            withTimeoutOrNull(timeoutMs) { waiter.join() } != null
        } else {
            waiter.join()
            true
        }

        if (!exited) {
            timedOut = true
            live.destroy(force = true)
            // Pipes close on destroy, which ends the drains — but a wedged stream must not
            // hold this flow open forever, so the grace wait is bounded too.
            withTimeoutOrNull(DRAIN_GRACE_MS) {
                drains.forEach { it.join() }
            }
        } else {
            drains.forEach { it.join() }
        }
        close()
    }

    private suspend fun ProducerScope<ProcessOutput>.drain(
        stream: InputStream,
        kind: OutputStream,
    ) {
        try {
            stream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    send(ProcessOutput(kind, line, clock()))
                }
            }
        } catch (_: IOException) {
            // Expected when the process is destroyed mid-read by stop(). Partial output
            // already sent stands; there is nothing to report that the exit code won't say.
        }
    }

    override suspend fun writeStdin(text: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                live.stdin.write(text.toByteArray())
                live.stdin.flush()
                AppResult.Success(Unit)
            } catch (e: IOException) {
                AppError(
                    category = ErrorCategory.Process,
                    message = "Input could not be sent to the process.",
                    detail = e.message,
                    recovery = "The process may have already exited.",
                    cause = e,
                ).asFailure()
            }
        }

    override suspend fun signal(signal: ProcessSignal): AppResult<Unit> =
        withContext(dispatchers.io) {
            when (signal) {
                ProcessSignal.Terminate, ProcessSignal.Interrupt -> {
                    live.destroy(force = false)
                    AppResult.Success(Unit)
                }

                ProcessSignal.Kill -> {
                    live.destroy(force = true)
                    AppResult.Success(Unit)
                }

                // Closing stdin is how a child sees EOF; there is no pipe-level Ctrl-D.
                ProcessSignal.EndOfFile -> {
                    runCatching { live.stdin.close() }
                    AppResult.Success(Unit)
                }
            }
        }

    override suspend fun await(): AppResult<Int> = withContext(dispatchers.io) {
        if (timedOut) {
            return@withContext AppError(
                category = ErrorCategory.Process,
                message = "The command timed out.",
                detail = "It ran longer than ${timeoutMs}ms and was stopped.",
                recovery = "Run it from the terminal if it needs longer, or split the work.",
            ).asFailure()
        }
        try {
            live.waitFor().asSuccess()
        } catch (e: CancellationException) {
            // Never swallowed: a cancelled waiter must stay cancelled, not become a failure.
            throw e
        } catch (e: Exception) {
            AppError(
                category = ErrorCategory.Process,
                message = "The command could not be waited for.",
                detail = e.message,
                recovery = "Try running it again.",
                cause = e,
            ).asFailure()
        }
    }
}

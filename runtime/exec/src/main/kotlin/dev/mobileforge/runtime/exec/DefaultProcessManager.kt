package dev.mobileforge.runtime.exec

import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.Logger
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.runtime.api.ManagedProcess
import dev.mobileforge.runtime.api.OutputStream as OutputStreamKind
import dev.mobileforge.runtime.api.ProcessHandle
import dev.mobileforge.runtime.api.ProcessId
import dev.mobileforge.runtime.api.ProcessManager
import dev.mobileforge.runtime.api.ProcessOutput
import dev.mobileforge.runtime.api.ProcessSignal
import dev.mobileforge.runtime.api.ProcessSpec
import dev.mobileforge.runtime.api.ProcessStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the lifetime of every process the IDE starts.
 *
 * Two Android realities shape this class:
 *
 * 1. **The OS will kill things.** A dev server is exactly what gets reclaimed under memory
 *    pressure (RISK-002). So process *metadata* is retained after death, and a terminated
 *    process is reported as [ProcessStatus.KilledBySystem] — distinct from a clean exit —
 *    with a restart offered. It never claims a dead process was resumed, because it cannot be.
 *
 * 2. **Output is unbounded but memory is not.** Every process gets a [BoundedOutputBuffer]
 *    rather than an ever-growing list (RISK-009).
 *
 * Concurrency: each process has one reader coroutine per stream, writing into its own buffer.
 * The registry is a concurrent map; state is published through flows so the UI never polls.
 */
class DefaultProcessManager(
    private val launcher: ProcessLauncher,
    private val commandBuilder: ExecCommandBuilder,
    private val environmentBuilder: RuntimeEnvironmentBuilder,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val bufferCapacity: Int = BoundedOutputBuffer.DEFAULT_CAPACITY,
) : ProcessManager {

    private val entries = ConcurrentHashMap<String, Entry>()
    private val _processes = MutableStateFlow<List<ManagedProcess>>(emptyList())
    override val processes: Flow<List<ManagedProcess>> = _processes.asStateFlow()

    private class Entry(
        @Volatile var managed: ManagedProcess,
        val buffer: BoundedOutputBuffer,
        val output: MutableSharedFlow<ProcessOutput>,
        @Volatile var live: LaunchedProcess? = null,
        @Volatile var readers: List<Job> = emptyList(),
    )

    override suspend fun start(spec: ProcessSpec): AppResult<ProcessId> =
        when (val handle = startInternal(spec)) {
            is AppResult.Failure -> handle
            is AppResult.Success -> handle.value.id.asSuccess()
        }

    /**
     * Starts a process and registers it.
     *
     * On failure NOTHING is registered: a process that never started is not a process, and
     * leaving a phantom entry in the list would be worse than the error itself.
     */
    suspend fun startInternal(spec: ProcessSpec): AppResult<ProcessHandle> =
        withContext(dispatchers.io) {
            val command = commandBuilder.build(spec.executable, spec.arguments)
            val resolved = when (command) {
                is AppResult.Failure -> return@withContext command
                is AppResult.Success -> command.value
            }

            val environment = environmentBuilder.build(
                command = resolved,
                workingDirectory = spec.workingDirectory,
                extra = spec.environment,
            )

            val launched = launcher.launch(resolved.argv, spec.workingDirectory, environment)
            val live = when (launched) {
                is AppResult.Failure -> {
                    logger.warn(
                        TAG,
                        "Failed to start ${spec.executable}: ${launched.error.detail}",
                    )
                    return@withContext launched
                }
                is AppResult.Success -> launched.value
            }

            val id = ProcessId(UUID.randomUUID().toString())
            val entry = Entry(
                managed = ManagedProcess(
                    id = id,
                    spec = spec,
                    pid = live.pid?.toInt(),
                    status = ProcessStatus.Running,
                    startedAtEpochMs = clock(),
                ),
                buffer = BoundedOutputBuffer(capacity = bufferCapacity),
                output = MutableSharedFlow(
                    replay = 0,
                    extraBufferCapacity = OUTPUT_BUFFER,
                    // A slow UI collector must never stall the process's stdout pipe: a full
                    // pipe blocks the child, which would hang the build being watched.
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                ),
                live = live,
            )
            entries[id.value] = entry
            publish()

            entry.readers = listOf(
                scope.launch(dispatchers.io) {
                    pump(entry, live.stdout, OutputStreamKind.Stdout)
                },
                scope.launch(dispatchers.io) {
                    pump(entry, live.stderr, OutputStreamKind.Stderr)
                },
                scope.launch(dispatchers.io) { awaitExit(entry, live) },
            )

            logger.info(TAG, "Started ${spec.executable} as ${id.value} (pid=${live.pid})")
            ManagedProcessHandle(id, this@DefaultProcessManager).asSuccess()
        }

    override suspend fun stop(id: ProcessId): AppResult<Unit> = withContext(dispatchers.io) {
        val entry = entries[id.value] ?: return@withContext unknown(id)
        val live = entry.live ?: return@withContext AppResult.Success(Unit)

        live.destroy(force = false)
        // A process that ignores SIGTERM is stopped hard rather than left running: the user
        // pressed stop, and a "stopped" button that leaves a server holding a port is a lie.
        if (live.isAlive()) {
            Thread.sleep(GRACEFUL_STOP_MS)
            if (live.isAlive()) live.destroy(force = true)
        }
        AppResult.Success(Unit)
    }

    override suspend fun restart(id: ProcessId): AppResult<ProcessId> {
        val entry = entries[id.value] ?: return unknown(id)
        val spec = entry.managed.spec
        stop(id)
        return start(spec)
    }

    override suspend fun inspect(id: ProcessId): AppResult<ManagedProcess> =
        entries[id.value]?.managed?.asSuccess() ?: unknown(id)

    /**
     * Replays the retained buffer, then streams live output.
     *
     * Replay-then-stream is what makes reopening a terminal tab show history instead of a blank
     * pane, without keeping a second copy of the output anywhere.
     */
    override fun logs(id: ProcessId): Flow<ProcessOutput> {
        val entry = entries[id.value] ?: return kotlinx.coroutines.flow.emptyFlow()
        return entry.output.asSharedFlow().onStart {
            entry.buffer.snapshot().forEach { emit(it) }
        }
    }

    /** Retained scrollback for [id], including how many lines were dropped. */
    fun bufferedOutput(id: ProcessId): List<ProcessOutput> =
        entries[id.value]?.buffer?.snapshot().orEmpty()

    fun droppedLineCount(id: ProcessId): Long =
        entries[id.value]?.buffer?.droppedCount ?: 0

    /** Forgets a finished process. Refuses while it is still running. */
    suspend fun forget(id: ProcessId): AppResult<Unit> {
        val entry = entries[id.value] ?: return unknown(id)
        if (entry.managed.status == ProcessStatus.Running) {
            return AppError(
                category = ErrorCategory.Validation,
                message = "That process is still running.",
                detail = "Stop it before removing it from the list.",
                recovery = "Stop the process first.",
            ).asFailure()
        }
        entry.readers.forEach(Job::cancel)
        entries.remove(id.value)
        publish()
        return AppResult.Success(Unit)
    }

    suspend fun writeStdin(id: ProcessId, text: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            val live = entries[id.value]?.live ?: return@withContext unknown(id)
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

    suspend fun signal(id: ProcessId, signal: ProcessSignal): AppResult<Unit> =
        when (signal) {
            ProcessSignal.Terminate, ProcessSignal.Interrupt -> stop(id)
            ProcessSignal.Kill -> withContext(dispatchers.io) {
                entries[id.value]?.live?.destroy(force = true)
                AppResult.Success(Unit)
            }
            // Closing stdin is how a child sees EOF; there is no pipe-level Ctrl-D.
            ProcessSignal.EndOfFile -> withContext(dispatchers.io) {
                runCatching { entries[id.value]?.live?.stdin?.close() }
                AppResult.Success(Unit)
            }
        }

    // -----------------------------------------------------------------------------

    private suspend fun pump(entry: Entry, stream: InputStream, kind: OutputStreamKind) {
        try {
            BufferedReader(stream.reader()).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val output = ProcessOutput(kind, line, clock())
                    entry.buffer.add(output)
                    entry.output.tryEmit(output)
                }
            }
        } catch (e: IOException) {
            // Expected when the process is destroyed mid-read; not worth surfacing as an error.
            logger.debug(TAG, "Output stream closed for ${entry.managed.id.value}: ${e.message}")
        }
    }

    /**
     * Waits for exit and classifies it.
     *
     * The distinction that matters: a process the user stopped exits normally, while one the OS
     * reclaimed did not. Android kills background processes with SIGKILL, which surfaces as
     * exit code 137 (128 + 9). Reporting that as [ProcessStatus.KilledBySystem] is what lets
     * the UI say "Android stopped this server" instead of implying the user's build crashed.
     */
    private fun awaitExit(entry: Entry, live: LaunchedProcess) {
        val exitCode = try {
            live.waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }

        val status = when {
            exitCode == 0 -> ProcessStatus.Exited
            exitCode == SIGKILL_EXIT -> ProcessStatus.KilledBySystem
            else -> ProcessStatus.Failed
        }

        entry.live = null
        entry.managed = entry.managed.copy(status = status, exitCode = exitCode, pid = null)
        publish()

        if (status == ProcessStatus.KilledBySystem) {
            logger.warn(
                TAG,
                "${entry.managed.spec.executable} was terminated by the system (exit $exitCode)",
            )
        }
    }

    private fun publish() {
        _processes.update { entries.values.map { it.managed }.sortedBy { it.startedAtEpochMs } }
    }

    private fun unknown(id: ProcessId): AppResult<Nothing> = AppError(
        category = ErrorCategory.Validation,
        message = "That process is no longer being tracked.",
        detail = "No process with id '${id.value}'.",
        recovery = "It may have been cleared. Refresh the process list.",
    ).asFailure()

    private companion object {
        const val TAG = "ProcessManager"
        const val OUTPUT_BUFFER = 256
        const val GRACEFUL_STOP_MS = 250L

        /** 128 + SIGKILL(9): how the shell reports a hard kill, and how Android reclaims. */
        const val SIGKILL_EXIT = 137
    }
}

private class ManagedProcessHandle(
    override val id: ProcessId,
    private val manager: DefaultProcessManager,
) : ProcessHandle {

    override val output: Flow<ProcessOutput> get() = manager.logs(id)

    override suspend fun writeStdin(text: String): AppResult<Unit> = manager.writeStdin(id, text)

    override suspend fun signal(signal: ProcessSignal): AppResult<Unit> =
        manager.signal(id, signal)

    override suspend fun await(): AppResult<Int> =
        when (val inspected = manager.inspect(id)) {
            is AppResult.Failure -> inspected
            is AppResult.Success -> (inspected.value.exitCode ?: 0).asSuccess()
        }
}

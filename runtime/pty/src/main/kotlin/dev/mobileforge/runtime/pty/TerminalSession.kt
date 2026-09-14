package dev.mobileforge.runtime.pty

import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * One interactive terminal: a PTY, an emulator to interpret its output, and the coroutines
 * that move bytes between them.
 *
 * Ownership is deliberate. The [TerminalEmulator] is confined to a single reader coroutine —
 * it is mutable, not thread-safe, and making it so would cost a lock on every character. The UI
 * never touches it directly; it observes [state], which is replaced wholesale on each update.
 */
class TerminalSession(
    private val launcher: PtyLauncher,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
    private val scope: CoroutineScope,
    val id: String = UUID.randomUUID().toString(),
    scrollbackLimit: Int = TerminalEmulator.DEFAULT_SCROLLBACK,
) {

    private val emulator = TerminalEmulator(scrollbackLimit = scrollbackLimit)

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var process: PtyProcess? = null
    private var readerJob: Job? = null

    val isRunning: Boolean get() = process?.isAlive() == true

    /**
     * Starts the terminal.
     *
     * [argv] must already be resolved by `ExecCommandBuilder` — this class does not know about
     * the system linker, and duplicating that logic here would put the Android exec workaround
     * in two places (ADR-009).
     */
    suspend fun start(
        argv: List<String>,
        workingDirectory: String,
        environment: Map<String, String>,
    ) = withContext(dispatchers.io) {
        if (process != null) return@withContext

        if (!launcher.isSupported) {
            _state.update {
                it.copy(
                    error = AppError(
                        category = ErrorCategory.Unavailable,
                        message = "The terminal is not available on this device.",
                        detail = "The native terminal library could not be loaded for this " +
                            "device's CPU architecture.",
                        recovery = "File browsing and editing still work.",
                    ),
                )
            }
            return@withContext
        }

        try {
            val pty = launcher.open(
                argv = argv,
                workingDirectory = workingDirectory,
                environment = environment,
                rows = emulator.rows,
                cols = emulator.cols,
            )
            process = pty
            _state.update { it.copy(isRunning = true, error = null) }
            readerJob = scope.launch(dispatchers.io) { pump(pty) }
        } catch (e: IOException) {
            logger.warn(TAG, "Terminal failed to start: ${e.message}", e)
            _state.update {
                it.copy(
                    error = AppError(
                        category = ErrorCategory.Process,
                        message = "The terminal could not be started.",
                        detail = e.message,
                        recovery = "Try opening it again.",
                        cause = e,
                    ),
                )
            }
        }
    }

    /**
     * Reads the PTY until it closes.
     *
     * Bytes go straight into the emulator rather than being buffered as lines: a terminal has
     * no concept of a complete line, and a shell prompt arrives with no trailing newline at all.
     * Waiting for one would leave the prompt invisible.
     */
    private suspend fun pump(pty: PtyProcess) {
        val buffer = ByteArray(READ_BUFFER)
        try {
            while (true) {
                val read = pty.input.read(buffer)
                if (read <= 0) break
                emulator.write(buffer, read)
                publish()
            }
        } catch (e: IOException) {
            // Expected when the PTY closes under us; not an error worth surfacing.
            logger.debug(TAG, "Terminal stream closed: ${e.message}")
        } finally {
            emulator.finishInput()
            val exitCode = pty.exitCodeOrNull()
            _state.update {
                it.copy(
                    isRunning = false,
                    exitCode = exitCode,
                    // A startup failure is a distinct, explainable case rather than
                    // "the shell exited with an error".
                    error = pty.startupFailure()?.let { detail ->
                        AppError(
                            category = ErrorCategory.Process,
                            message = "The terminal could not start.",
                            detail = detail,
                            recovery = "Reopen the project and try again.",
                        )
                    },
                )
            }
            publish()
        }
    }

    /** Sends typed input. What is written appears to the child as typed at a terminal. */
    suspend fun send(text: String) = withContext(dispatchers.io) {
        val pty = process ?: return@withContext
        runCatching {
            pty.output.write(text.toByteArray())
            pty.output.flush()
        }.onFailure { logger.debug(TAG, "Terminal input failed: ${it.message}") }
    }

    /**
     * Ctrl+C.
     *
     * Sends SIGINT to the foreground process group rather than writing 0x03, so it works even
     * when a program has put the terminal in raw mode and is not reading its own input.
     */
    suspend fun interrupt() = withContext(dispatchers.io) {
        process?.signal(PtySignal.Interrupt)
        Unit
    }

    /** Ctrl+D: end of input. A literal 0x04, which the line discipline turns into EOF. */
    suspend fun sendEndOfFile() = send("\u0004")

    suspend fun resize(rows: Int, cols: Int) = withContext(dispatchers.io) {
        emulator.resize(rows, cols)
        process?.resize(rows, cols)
        publish()
    }

    fun clear() {
        emulator.reset()
        publish()
    }

    fun close() {
        readerJob?.cancel()
        readerJob = null
        process?.close()
        process = null
        _state.update { it.copy(isRunning = false) }
    }

    /**
     * Publishes an immutable snapshot.
     *
     * A copy per update, because the emulator's own arrays are mutated in place and handing
     * them to Compose would produce a view that changes underneath the recomposition reading it.
     */
    private fun publish() {
        _state.update {
            it.copy(
                lines = emulator.visibleLines(),
                cursorRow = emulator.cursorRow,
                cursorCol = emulator.cursorCol,
                cursorVisible = emulator.cursorVisible,
                rows = emulator.rows,
                cols = emulator.cols,
                revision = emulator.revision,
            )
        }
    }

    private companion object {
        const val TAG = "TerminalSession"
        const val READ_BUFFER = 8192
    }
}

data class TerminalUiState(
    val lines: List<List<Cell>> = emptyList(),
    val cursorRow: Int = 0,
    val cursorCol: Int = 0,
    /** DECTCEM. Full-screen programs hide the cursor while redrawing a frame. */
    val cursorVisible: Boolean = true,
    val rows: Int = TerminalEmulator.DEFAULT_ROWS,
    val cols: Int = TerminalEmulator.DEFAULT_COLS,
    val isRunning: Boolean = false,
    val exitCode: Int? = null,
    val revision: Long = 0,
    val error: AppError? = null,
) {
    /** Shown when the shell exits, so a closed terminal never looks like a frozen one. */
    val exitSummary: String?
        get() = when {
            isRunning || exitCode == null -> null
            exitCode == 0 -> "Session ended."
            exitCode > 128 -> "Session ended (signal ${exitCode - 128})."
            else -> "Session ended with status $exitCode."
        }
}

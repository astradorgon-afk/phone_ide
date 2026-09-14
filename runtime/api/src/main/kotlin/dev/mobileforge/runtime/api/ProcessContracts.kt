package dev.mobileforge.runtime.api

import dev.mobileforge.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Contracts for the development runtime.
 *
 * PHASE 1 SHIPS THIS MODULE AS INTERFACES ONLY. There is no implementation, and there is
 * deliberately no stub that throws behind a button. The IDE reports these tools as
 * unavailable and says which phase delivers them.
 *
 * The implementation lands in Phase 2 as :runtime:exec, built on system-linker exec. The
 * reasoning, the platform constraint it works around, and the fallback chain are in
 * docs/adr/ADR-002-runtime-strategy.md. Callers must not encode any assumption about the
 * mechanism - that is the whole point of this boundary.
 */
interface ProcessExecutor {

    /**
     * Starts a process and returns a handle. Never blocks the caller's thread.
     *
     * Structured arguments, not a command string: [ProcessSpec.arguments] is a list precisely
     * so that no caller is tempted to build a shell command by concatenation. Where a real
     * shell is genuinely required, the caller must use [ProcessSpec.shell] explicitly, which
     * makes the risk visible in code review.
     */
    suspend fun start(spec: ProcessSpec): AppResult<ProcessHandle>
}

/**
 * Lifecycle management for running processes.
 *
 * Android will terminate background processes under memory pressure (RISK-002). This manager
 * therefore persists process *metadata* so the app can report accurate state after a kill and
 * offer a restart. It does not pretend a killed OS process can be resumed - it cannot.
 */
interface ProcessManager {
    val processes: Flow<List<ManagedProcess>>

    suspend fun start(spec: ProcessSpec): AppResult<ProcessId>
    suspend fun stop(id: ProcessId): AppResult<Unit>
    suspend fun restart(id: ProcessId): AppResult<ProcessId>
    suspend fun inspect(id: ProcessId): AppResult<ManagedProcess>

    /** Bounded stream. Implementations must cap retained output - see RISK-009. */
    fun logs(id: ProcessId): Flow<ProcessOutput>
}

@JvmInline
value class ProcessId(val value: String)

data class ProcessSpec(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String,
    val environment: Map<String, String> = emptyMap(),
    /**
     * Set only when a real shell is required (pipes, redirection, user-typed terminal input).
     * Kept explicit so that shell exposure is a deliberate, reviewable decision rather than an
     * accident of string building.
     */
    val shell: Boolean = false,
    val timeoutMs: Long? = null,
)

data class ManagedProcess(
    val id: ProcessId,
    val spec: ProcessSpec,
    /** Null when the platform does not expose it, or after the OS reclaimed the process. */
    val pid: Int?,
    val status: ProcessStatus,
    val startedAtEpochMs: Long,
    val exitCode: Int? = null,
    /** Set when a local server bound a port, so preview can find it. */
    val boundPort: Int? = null,
)

enum class ProcessStatus {
    Starting,
    Running,
    Exited,

    /**
     * The OS reclaimed the process - distinct from [Exited] because the user did not ask for
     * it and the UI must say so plainly rather than showing a silent stop.
     */
    KilledBySystem,
    Failed,
}

data class ProcessOutput(
    val stream: OutputStream,
    val line: String,
    val timestampEpochMs: Long,
)

enum class OutputStream { Stdout, Stderr }

interface ProcessHandle {
    val id: ProcessId
    val output: Flow<ProcessOutput>
    suspend fun writeStdin(text: String): AppResult<Unit>
    suspend fun signal(signal: ProcessSignal): AppResult<Unit>
    suspend fun await(): AppResult<Int>
}

/** Terminal control needs these; a plain "kill" is not sufficient for an interactive shell. */
enum class ProcessSignal { Interrupt, Terminate, Kill, EndOfFile }

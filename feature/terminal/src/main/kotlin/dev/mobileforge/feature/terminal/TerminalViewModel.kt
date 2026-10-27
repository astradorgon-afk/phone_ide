package dev.mobileforge.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.model.WorkspaceTrust
import dev.mobileforge.core.security.TrustPolicy
import dev.mobileforge.runtime.pty.TerminalSession
import dev.mobileforge.runtime.pty.TerminalUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the terminal sessions belonging to one workspace screen.
 *
 * All the difficult work lives in [TerminalSession] (ADR-010); this exists to bind sessions to
 * the Compose lifecycle and to make sure every PTY is released when the screen goes away. A
 * leaked PTY means a leaked child process, which on Android means the OS eventually kills the
 * app rather than the process.
 *
 * More than one session is the point: a dev server occupies its terminal for as long as it runs,
 * so a single-session terminal forces the user to stop the server to type anything. Sessions are
 * capped at [MAX_SESSIONS] because each is a real process with a real PTY, and an unbounded
 * number of them on a phone is a resource problem rather than a feature.
 *
 * A shell is the most direct way to run anything, so no session opens in a workspace that is not
 * trusted. The check is here, where sessions are created, and not only in the screen that hosts
 * the terminal: the screen hides the pane for a restricted project, but a view model that can
 * start a process has to refuse on its own account rather than rely on being kept out of sight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModel(
    private val newSession: () -> TerminalSession,
    private val shellCommand: ShellCommand,
    /**
     * The workspace's trust, read each time a session would open. Required, with no default: a
     * view model that can start a shell must have been told whether it may.
     */
    private val trust: StateFlow<WorkspaceTrust>,
) : ViewModel() {

    private class Entry(val id: Int, val session: TerminalSession) {
        var started = false
    }

    private val entries = MutableStateFlow<List<Entry>>(emptyList())
    private val activeId = MutableStateFlow(NO_SESSION)
    private var nextId = 1

    private val _refusal = MutableStateFlow<AppError?>(null)

    /** Why no shell was opened, or null. Shown by the screen so a refusal is never a blank pane. */
    val refusal: StateFlow<AppError?> = _refusal.asStateFlow()

    /** The active session's screen. Switching tabs switches which PTY this follows. */
    val state: StateFlow<TerminalUiState> =
        combine(entries, activeId) { list, id -> list.firstOrNull { it.id == id } }
            .flatMapLatest { entry -> entry?.session?.state ?: flowOf(TerminalUiState()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalUiState())

    /** One entry per open session, for the tab strip. */
    val tabs: StateFlow<List<TerminalTab>> =
        combine(entries, activeId) { list, id ->
            list.mapIndexed { index, entry ->
                TerminalTab(
                    id = entry.id,
                    label = "${index + 1}",
                    isActive = entry.id == id,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val canOpenSession: StateFlow<Boolean> =
        entries.map { it.size < MAX_SESSIONS }
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Starts the first shell.
     *
     * Idempotent because the composable calls it from a `LaunchedEffect`, which re-runs across
     * configuration changes; starting a second shell each rotation would be a slow leak.
     */
    fun start() {
        if (entries.value.isNotEmpty()) return
        openSession()
    }

    /** Opens an additional session and makes it active. */
    fun openSession() {
        if (entries.value.size >= MAX_SESSIONS) return
        // Every session goes through here — start(), the new-tab button, and the fresh session
        // that replaces a closed last one — so this is the single gate. `newSession` is not even
        // called on refusal: no PTY is created for a project that may not run one.
        val refusal = TrustPolicy.executionRefusal(trust.value)
        _refusal.value = refusal
        if (refusal != null) return
        val entry = Entry(id = nextId++, session = newSession())
        entries.value = entries.value + entry
        activeId.value = entry.id
        startIfNeeded(entry)
    }

    fun selectSession(id: Int) {
        val entry = entries.value.firstOrNull { it.id == id } ?: return
        activeId.value = entry.id
        startIfNeeded(entry)
    }

    /**
     * Closes a session and releases its PTY.
     *
     * Closing the last one immediately opens a fresh session rather than leaving an empty pane:
     * a terminal with no terminal in it is a dead end, and the user's next action would be to
     * open one anyway.
     */
    fun closeSession(id: Int) {
        val entry = entries.value.firstOrNull { it.id == id } ?: return
        val remaining = entries.value - entry
        entries.value = remaining
        entry.session.close()

        if (remaining.isEmpty()) {
            activeId.value = NO_SESSION
            openSession()
        } else if (activeId.value == id) {
            selectSession(remaining.last().id)
        }
    }

    private fun startIfNeeded(entry: Entry) {
        if (entry.started) return
        entry.started = true
        viewModelScope.launch {
            entry.session.start(
                argv = shellCommand.argv,
                workingDirectory = shellCommand.workingDirectory,
                environment = shellCommand.environment,
            )
        }
    }

    private fun withActive(action: suspend (TerminalSession) -> Unit) {
        val entry = entries.value.firstOrNull { it.id == activeId.value } ?: return
        viewModelScope.launch { action(entry.session) }
    }

    fun send(text: String) = withActive { it.send(text) }

    /** Ctrl+C — SIGINT to the foreground process group, not a literal 0x03. */
    fun interrupt() = withActive { it.interrupt() }

    /** Ctrl+D — end of input. */
    fun sendEndOfFile() = withActive { it.sendEndOfFile() }

    /**
     * Reports the visible size to the PTY.
     *
     * Called whenever the measured character grid changes — rotation, split-screen, or the soft
     * keyboard appearing. Every session is resized, not just the visible one: a background
     * session redrawing at a stale width would be wrong the moment the user switched to it.
     */
    fun resize(rows: Int, cols: Int) {
        entries.value.forEach { entry ->
            viewModelScope.launch { entry.session.resize(rows, cols) }
        }
    }

    fun clear() {
        entries.value.firstOrNull { it.id == activeId.value }?.session?.clear()
    }

    override fun onCleared() {
        entries.value.forEach { it.session.close() }
        entries.value = emptyList()
        super.onCleared()
    }

    private companion object {
        /** Each session is a process and a PTY; a phone should not be asked to hold many. */
        const val MAX_SESSIONS = 4
        const val NO_SESSION = 0
    }
}

/** One tab in the terminal's session strip. */
data class TerminalTab(
    val id: Int,
    val label: String,
    val isActive: Boolean,
)

/**
 * A fully-resolved shell invocation.
 *
 * Resolved by the composition root through `ExecCommandBuilder`, so the terminal never learns
 * about the system-linker workaround (ADR-009). If the argv is wrong, it is wrong in one place.
 */
data class ShellCommand(
    val argv: List<String>,
    val workingDirectory: String,
    val environment: Map<String, String>,
    /** What the user is actually running, for the header. */
    val displayName: String,
)

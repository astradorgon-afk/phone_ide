package dev.mobileforge.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.model.WorkspaceTrust
import dev.mobileforge.core.security.CommandRisk
import dev.mobileforge.core.security.TrustPolicy
import dev.mobileforge.runtime.api.DevTool
import dev.mobileforge.runtime.api.ProcessExecutor
import dev.mobileforge.runtime.api.ProcessHandle
import dev.mobileforge.runtime.api.ProcessSignal
import dev.mobileforge.runtime.api.RuntimeCapabilityProbe
import dev.mobileforge.runtime.api.ToolStatus
import dev.mobileforge.runtime.laravel.ArtisanCommand
import dev.mobileforge.runtime.laravel.ArtisanResult
import dev.mobileforge.runtime.laravel.ArtisanRunner
import dev.mobileforge.runtime.laravel.ExecutedVersions
import dev.mobileforge.runtime.laravel.LaravelVersions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns artisan execution state for one open project.
 *
 * The safety shape mirrors the runner's: a command the risk model flags cannot run without an
 * explicit confirmation, and the refusal lives in the type — [pendingConfirmation] is the UI's
 * cue to ask, and only [confirmPending] runs it confirmed. There is no path where the dialog
 * is skipped but the command still runs, because the unconfirmed call never leaves this class.
 *
 * PHP availability is measured, not assumed: the probe executes `php --version`, so a broken
 * install reports [PhpStatus.Missing] with its reason instead of failing every command
 * mysteriously. Versions likewise come from executed tools ([LaravelVersions]), never from
 * composer.json.
 *
 * Nothing here runs in a workspace that is not trusted ([trust]). That covers the commands the
 * user types and also the version readout, which looks passive but runs `php artisan --version`
 * — and that boots the application, so a hostile project's service providers would execute just
 * because the pane was created. The probe of PHP itself touches only the toolchain, not the
 * project, and is unaffected.
 */
class ArtisanViewModel(
    private val projectRoot: String,
    private val executor: ProcessExecutor,
    private val probe: RuntimeCapabilityProbe,
    /**
     * The workspace's trust, read at the moment of acting. Required, with no default: a view
     * model that can run commands must have been told whether it may.
     */
    private val trust: StateFlow<WorkspaceTrust>,
) : ViewModel() {

    private val _state = MutableStateFlow(ArtisanUiState())
    val state: StateFlow<ArtisanUiState> = _state.asStateFlow()

    /** The live handle of a running command, so Stop can signal it. Null when idle. */
    private var liveHandle: ProcessHandle? = null

    init {
        // Re-probes whenever execution becomes allowed or stops being allowed, so the versions
        // that were withheld from an untrusted project appear as soon as the user trusts it.
        // The first emission is the current value, which replaces the unconditional probe that
        // used to run here.
        viewModelScope.launch {
            trust
                .map { TrustPolicy.executionRefusal(it) == null }
                .distinctUntilChanged()
                .collect { refreshTool() }
        }
    }

    /** Re-probes PHP. Safe to call after installing the toolchain without leaving the pane. */
    fun refreshTool() {
        _state.update { it.copy(php = PhpStatus.Loading, error = null) }
        viewModelScope.launch {
            when (val status = runCatching { probe.probe(DevTool.Php) }.getOrNull()) {
                is ToolStatus.Available -> {
                    _state.update {
                        it.copy(php = PhpStatus.Ready(status.version, status.absolutePath))
                    }
                    // Checked here, after the probe's suspension, so the answer used is the one
                    // current when the project would be touched, not when the refresh began.
                    val versions = if (TrustPolicy.executionRefusal(trust.value) == null) {
                        runCatching {
                            LaravelVersions(executor, status.absolutePath).read(projectRoot)
                        }.getOrNull()
                    } else {
                        null
                    }
                    _state.update { it.copy(versions = versions) }
                }

                is ToolStatus.NotInstalled -> _state.update {
                    it.copy(
                        php = PhpStatus.Missing(
                            message = "PHP is not installed.",
                            detail = "Artisan needs the PHP toolchain on this device.",
                            recovery = "Install it from Settings → Toolchains, then retry.",
                        ),
                    )
                }

                is ToolStatus.Unsupported -> _state.update {
                    it.copy(
                        php = PhpStatus.Missing(
                            message = "PHP cannot run on this device.",
                            detail = status.reason,
                            recovery = status.recovery,
                        ),
                    )
                }

                is ToolStatus.NotImplementedYet -> _state.update {
                    it.copy(
                        php = PhpStatus.Missing(
                            message = "PHP is not available yet.",
                            detail = "It is scheduled for ${status.phase}.",
                            recovery = "See ROADMAP.md for what that phase delivers.",
                        ),
                    )
                }

                null -> _state.update {
                    it.copy(
                        php = PhpStatus.Missing(
                            message = "PHP could not be checked.",
                            detail = "The capability probe did not answer.",
                            recovery = "Retry. If it persists, run diagnostics from Settings.",
                        ),
                    )
                }
            }
        }
    }

    /**
     * Runs what the user typed.
     *
     * Unparseable input and a missing toolchain are errors, not silent no-ops — otherwise the
     * Run button would appear to do nothing.
     */
    fun submit(input: String) {
        if (_state.value.running) return
        // Before parsing, so a restricted project always hears "restricted" and never "that is
        // not a usable command", and before the confirmation dialog, so the user is not asked to
        // approve something that cannot run.
        TrustPolicy.executionRefusal(trust.value)?.let { refusal ->
            _state.update { it.copy(error = refusal, pendingConfirmation = null) }
            return
        }
        val command = ArtisanCommand.parse(input)
        if (command == null) {
            _state.update { it.copy(error = unusableCommandError(input)) }
            return
        }
        if (_state.value.php !is PhpStatus.Ready) {
            _state.update { it.copy(error = phpMissingError()) }
            return
        }
        if (command.requiresConfirmation) {
            _state.update { it.copy(pendingConfirmation = command, error = null) }
            return
        }
        execute(command, confirmed = false)
    }

    fun runQuick(name: String) = submit(name)

    /** The dialog's confirm button. The only path that runs a flagged command. */
    fun confirmPending() {
        val command = _state.value.pendingConfirmation ?: return
        _state.update { it.copy(pendingConfirmation = null) }
        execute(command, confirmed = true)
    }

    fun dismissPending() {
        _state.update { it.copy(pendingConfirmation = null) }
    }

    /**
     * Stops a running command.
     *
     * Destroying the process unblocks its readers, so the run completes on its own with
     * partial output and a non-zero exit — there is no cancelled state to reconcile.
     */
    fun stop() {
        val handle = liveHandle ?: return
        if (!_state.value.running) return
        viewModelScope.launch {
            when (val result = handle.signal(ProcessSignal.Terminate)) {
                is AppResult.Failure -> _state.update { it.copy(error = result.error) }
                is AppResult.Success -> _state.update { it.copy(stopped = true) }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun execute(command: ArtisanCommand, confirmed: Boolean) {
        // The one place a process starts, so the gate holds even for a command that was queued
        // behind a confirmation dialog before the project's trust changed.
        TrustPolicy.executionRefusal(trust.value)?.let { refusal ->
            _state.update { it.copy(error = refusal) }
            return
        }
        val phpPath = (_state.value.php as? PhpStatus.Ready)?.path
        if (phpPath == null) {
            _state.update { it.copy(error = phpMissingError()) }
            return
        }
        _state.update {
            it.copy(
                running = true,
                activeCommand = "php artisan ${command.argv.joinToString(" ")}",
                liveOutput = emptyList(),
                lastResult = null,
                stopped = false,
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                val runner = ArtisanRunner(executor, phpPath)
                when (
                    val result = runner.run(
                        projectRoot,
                        command,
                        confirmed,
                        onStarted = { liveHandle = it },
                        // Streamed so a server shows the URL it is listening on while it runs,
                        // rather than only after the user stops it.
                        onLine = { _, line ->
                            _state.update { current ->
                                current.copy(liveOutput = (current.liveOutput + line).takeLast(MAX_LIVE_LINES))
                            }
                        },
                    )
                ) {
                    is AppResult.Failure -> _state.update {
                        it.copy(running = false, activeCommand = null, liveOutput = emptyList(), error = result.error)
                    }

                    is AppResult.Success -> _state.update {
                        it.copy(
                            running = false,
                            activeCommand = null,
                            liveOutput = emptyList(),
                            lastResult = result.value,
                            error = null,
                        )
                    }
                }
            } finally {
                liveHandle = null
            }
        }
    }

    private fun unusableCommandError(input: String) = AppError(
        category = ErrorCategory.Validation,
        message = "That is not a usable artisan command.",
        detail = "Could not parse \"$input\" as an artisan subcommand.",
        recovery = "Try a subcommand such as migrate or route:list.",
    )

    private fun phpMissingError() = AppError(
        category = ErrorCategory.Unavailable,
        message = "PHP is not installed.",
        detail = "Artisan needs the PHP toolchain on this device.",
        recovery = "Install it from Settings → Toolchains, then retry.",
    )
}

/**
 * How much live output the running command keeps.
 *
 * A dev server prints a handful of lines and then goes quiet, but `queue:work` in verbose mode
 * can print continuously. Bounded so a chatty command cannot grow the UI state without limit;
 * the full output is still preserved in the final [ArtisanResult].
 */
private const val MAX_LIVE_LINES = 300

/** What the probe measured about PHP. Never a guess: unknown means unknown. */
sealed interface PhpStatus {
    data object Loading : PhpStatus

    data class Ready(val version: String, val path: String) : PhpStatus

    data class Missing(val message: String, val detail: String?, val recovery: String?) : PhpStatus
}

/** A one-tap command and whether tapping it asks first. Computed once from the parser. */
data class QuickCommand(val name: String, val requiresConfirmation: Boolean)

/** One immutable state per screen, exposed as a StateFlow (rule: conventions). */
data class ArtisanUiState(
    val php: PhpStatus = PhpStatus.Loading,
    val versions: ExecutedVersions? = null,
    val quickCommands: List<QuickCommand> = defaultQuickCommands(),
    val running: Boolean = false,
    val activeCommand: String? = null,
    /**
     * Output of the currently running command, line by line, as it arrives.
     *
     * Needed because a long-running command (`serve`, `queue:work`) produces its most
     * important line — the URL it is listening on — long before it exits, and [lastResult]
     * does not exist until it does. Cleared when the run ends so [lastResult] takes over.
     */
    val liveOutput: List<String> = emptyList(),
    val lastResult: ArtisanResult? = null,
    val pendingConfirmation: ArtisanCommand? = null,
    val stopped: Boolean = false,
    val error: AppError? = null,
) {
    /** The dialog explains WHY this command asks, from the same risk model that flagged it. */
    val confirmationReason: String? get() = when (val risk = pendingConfirmation?.risk) {
        null -> null
        is CommandRisk.Destructive -> risk.reason
        is CommandRisk.PackageInstall ->
            "This runs third-party package scripts, which you have not consented to by " +
                "granting \"run commands\"."
        else -> "This command can affect your data."
    }
}

private fun defaultQuickCommands(): List<QuickCommand> =
    ArtisanCommand.COMMON.mapNotNull { name ->
        ArtisanCommand.parse(name)?.let { QuickCommand(name, it.requiresConfirmation) }
    }

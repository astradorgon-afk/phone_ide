package dev.mobileforge.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.security.CommandRisk
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
 */
class ArtisanViewModel(
    private val projectRoot: String,
    private val executor: ProcessExecutor,
    private val probe: RuntimeCapabilityProbe,
) : ViewModel() {

    private val _state = MutableStateFlow(ArtisanUiState())
    val state: StateFlow<ArtisanUiState> = _state.asStateFlow()

    /** The live handle of a running command, so Stop can signal it. Null when idle. */
    private var liveHandle: ProcessHandle? = null

    init {
        refreshTool()
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
                    val versions = runCatching {
                        LaravelVersions(executor, status.absolutePath).read(projectRoot)
                    }.getOrNull()
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
        val phpPath = (_state.value.php as? PhpStatus.Ready)?.path
        if (phpPath == null) {
            _state.update { it.copy(error = phpMissingError()) }
            return
        }
        _state.update {
            it.copy(
                running = true,
                activeCommand = "php artisan ${command.argv.joinToString(" ")}",
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
                    )
                ) {
                    is AppResult.Failure -> _state.update {
                        it.copy(running = false, activeCommand = null, error = result.error)
                    }

                    is AppResult.Success -> _state.update {
                        it.copy(
                            running = false,
                            activeCommand = null,
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

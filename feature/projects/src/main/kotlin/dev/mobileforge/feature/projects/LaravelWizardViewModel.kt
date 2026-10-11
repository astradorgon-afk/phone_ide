package dev.mobileforge.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.data.workspace.WorkspaceRepository
import dev.mobileforge.core.filesystem.WorkspaceFileSystem
import dev.mobileforge.core.model.DetectedFramework
import dev.mobileforge.core.model.WorkspaceId
import dev.mobileforge.core.model.WorkspaceTrust
import dev.mobileforge.runtime.api.ProcessExecutor
import dev.mobileforge.runtime.api.ProcessHandle
import dev.mobileforge.runtime.api.ProcessSignal
import dev.mobileforge.runtime.api.ToolchainManager
import dev.mobileforge.runtime.laravel.ArtisanCommand
import dev.mobileforge.runtime.laravel.ArtisanRunner
import dev.mobileforge.runtime.laravel.ComposerRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WizardPhase {
    CHECKS,
    CONFIRM,
    RUNNING,
    DONE,
    FAILED,
}

data class WizardCheck(val label: String, val passed: Boolean, val detail: String)

data class LaravelWizardUiState(
    val phase: WizardPhase = WizardPhase.CHECKS,
    val checks: List<WizardCheck> = emptyList(),
    val stepLabel: String? = null,
    val log: List<String> = emptyList(),
    val running: Boolean = false,
    val workspaceId: WorkspaceId? = null,
    val error: AppError? = null,
)

/**
 * Guided Laravel creation: preflight, explicit consent, download, finish.
 *
 * `composer create-project` downloads ~200 MB and executes third-party install scripts,
 * which the permission model counts as a separate consent from "run commands" (RISK-015).
 * That consent is the CONFIRM screen — showing the exact command, the download size and
 * the time it takes — and nothing runs before it. Skipping it would make the Create
 * button the consent, which it is not: at that point the user has not been told what
 * happens next.
 *
 * A failed run keeps the project record with its error rather than deleting it: silent
 * deletion of a half-downloaded project would be worse than a visible failure the user
 * can delete and retry from the project list.
 */
class LaravelWizardViewModel(
    private val projectName: String,
    private val workspacesRoot: String,
    private val repository: WorkspaceRepository,
    private val fileSystemFor: (String) -> WorkspaceFileSystem,
    private val executor: ProcessExecutor,
    private val toolchain: ToolchainManager,
    private val composerPath: String,
    private val phpPath: String,
    private val networkAvailable: suspend () -> Boolean,
    private val freeBytes: suspend () -> Long,
) : ViewModel() {

    private val _state = MutableStateFlow(LaravelWizardUiState())
    val state: StateFlow<LaravelWizardUiState> = _state.asStateFlow()

    private var liveHandle: ProcessHandle? = null

    init {
        recheck()
    }

    fun recheck() {
        if (_state.value.running) return
        _state.update { LaravelWizardUiState(phase = WizardPhase.CHECKS) }
        viewModelScope.launch {
            val records = (toolchain.installed() as? AppResult.Success)
                ?.value.orEmpty()
            val phpOk = records.any { it.id == "php" && it.filesPresent }
            val composerOk = records.any { it.id == "composer" && it.filesPresent }
            val online = try {
                networkAvailable()
            } catch (_: Exception) {
                false
            }
            val free = try {
                freeBytes()
            } catch (_: Exception) {
                0L
            }
            val checks = listOf(
                WizardCheck(
                    label = "PHP toolchain",
                    passed = phpOk,
                    detail = if (phpOk) "Installed." else "Install it from the setup screen.",
                ),
                WizardCheck(
                    label = "Composer",
                    passed = composerOk,
                    detail = if (composerOk) "Installed." else "Install it from the setup screen.",
                ),
                WizardCheck(
                    label = "Network",
                    passed = online,
                    detail = if (online) "Connected." else "Connect to download the framework.",
                ),
                WizardCheck(
                    label = "Storage",
                    passed = free >= MIN_DISK_BYTES,
                    detail = if (free >= MIN_DISK_BYTES) {
                        "${formatMegabytes(free)} free."
                    } else {
                        "Needs about ${formatMegabytes(MIN_DISK_BYTES)} free."
                    },
                ),
            )
            _state.update {
                LaravelWizardUiState(
                    phase = if (checks.all { it.passed }) WizardPhase.CONFIRM else WizardPhase.CHECKS,
                    checks = checks,
                )
            }
        }
    }

    /**
     * Runs the download. Called only from the CONFIRM screen, which is the consent —
     * there is deliberately no other path here.
     */
    fun start() {
        if (_state.value.running || _state.value.phase != WizardPhase.CONFIRM) return
        _state.update {
            it.copy(phase = WizardPhase.RUNNING, running = true, log = emptyList(),
                stepLabel = "Creating project…", error = null)
        }
        viewModelScope.launch {
            val workspace = when (
                val created = repository.create(
                    projectName, DetectedFramework.Laravel, WorkspaceTrust.Trusted,
                )
            ) {
                is AppResult.Failure -> return@launch failed(created.error)
                is AppResult.Success -> created.value
            }
            val rootPath = workspace.rootPath
            appendLog("$ Fetching laravel/laravel into ${workspace.name}…")

            val download = ComposerRunner(executor, composerPath)
                .createProject("laravel/laravel", rootPath, workspacesRoot,
                    onStarted = { liveHandle = it })
            try {
                when (download) {
                    is AppResult.Failure -> return@launch failed(download.error, workspace.id)
                    is AppResult.Success -> {
                        download.value.stdout.forEach { appendLog(it) }
                        download.value.stderr.forEach { appendLog(it) }
                        if (!download.value.succeeded) {
                            return@launch failed(
                                downloadError(download.value.exitCode,
                                    download.value.stderr.takeLast(5)),
                                workspace.id,
                            )
                        }
                    }
                }

                _state.update { it.copy(stepLabel = "Finishing setup…") }
                val fileSystem = fileSystemFor(rootPath)
                if (fileSystem.exists(".env.example") && !fileSystem.exists(".env")) {
                    appendLog("$ Copying .env.example to .env…")
                    when (val example = fileSystem.readText(".env.example")) {
                        is AppResult.Failure -> return@launch failed(example.error, workspace.id)
                        is AppResult.Success -> {
                            when (val written = fileSystem.writeText(".env", example.value)) {
                                is AppResult.Failure -> return@launch failed(written.error, workspace.id)
                                is AppResult.Success -> Unit
                            }
                        }
                    }
                }

                appendLog("$ Generating application key…")
                val command = ArtisanCommand.parse("key:generate") ?: return@launch failed(
                    internalError("Could not parse an internal artisan command."),
                    workspace.id,
                )
                when (
                    val keyed = ArtisanRunner(executor, phpPath)
                        .run(rootPath, command, onStarted = { liveHandle = it })
                ) {
                    is AppResult.Failure -> return@launch failed(keyed.error, workspace.id)
                    is AppResult.Success -> {
                        keyed.value.stdout.forEach { appendLog(it) }
                        if (!keyed.value.succeeded) {
                            return@launch failed(
                                downloadError(keyed.value.exitCode,
                                    keyed.value.stderr.takeLast(5)),
                                workspace.id,
                            )
                        }
                    }
                }

                appendLog("Done. Opening the project…")
                _state.update {
                    it.copy(phase = WizardPhase.DONE, running = false, stepLabel = null,
                        workspaceId = workspace.id)
                }
            } finally {
                liveHandle = null
            }
        }
    }

    /**
     * Stops a running download. Destroying the process unblocks its readers, so the run
     * completes on its own as a failure — there is no cancelled state to reconcile.
     */
    fun stop() {
        val handle = liveHandle ?: return
        if (!_state.value.running) return
        viewModelScope.launch {
            when (val result = handle.signal(ProcessSignal.Terminate)) {
                is AppResult.Failure -> _state.update { it.copy(error = result.error) }
                is AppResult.Success -> appendLog("$ Stopping…")
            }
        }
    }

    fun backToConfirm() {
        if (_state.value.running) return
        _state.update { it.copy(phase = WizardPhase.CONFIRM, error = null) }
    }

    private fun failed(error: AppError, workspaceId: WorkspaceId? = null) {
        _state.update {
            it.copy(phase = WizardPhase.FAILED, running = false, stepLabel = null,
                workspaceId = workspaceId, error = error)
        }
        liveHandle = null
    }

    private fun appendLog(line: String) {
        val clean = line.replace("\r", "").replace(ANSI_ESCAPE, "")
        if (clean.isBlank()) return
        _state.update {
            it.copy(log = (it.log + clean).takeLast(MAX_LOG_LINES))
        }
    }

    private fun downloadError(exitCode: Int, tail: List<String>) = AppError(
        category = ErrorCategory.Process,
        message = "Laravel could not be downloaded.",
        detail = "The command exited with code $exitCode." +
            (tail.ifEmpty { null }?.let { " Last output:\n" + it.joinToString("\n") } ?: ""),
        recovery = "Check the connection and storage, delete the project from the list, " +
            "and try again.",
    )

    private fun internalError(detail: String) = AppError(
        category = ErrorCategory.Internal,
        message = "The setup step could not run.",
        detail = detail,
        recovery = "Please report it.",
    )

    companion object {
        /**
         * Free space refused below this. An estimate, not a measurement — a Laravel
         * checkout with vendor is ~200 MB plus Composer cache — revisit after the first
         * real-device download is measured.
         */
        const val MIN_DISK_BYTES = 500L * 1024 * 1024

        /** The log is a tail, not an archive: the last lines are what diagnose a failure. */
        const val MAX_LOG_LINES = 200

        private val ANSI_ESCAPE = Regex("\u001B\\[[;\\d]*m")

        private fun formatMegabytes(bytes: Long): String = "%.0f MB".format(bytes / 1048576.0)
    }
}

package dev.mobileforge.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.model.WorkspaceTrust
import dev.mobileforge.core.security.TrustPolicy
import dev.mobileforge.runtime.api.DevTool
import dev.mobileforge.runtime.api.ManagedProcess
import dev.mobileforge.runtime.api.ProcessId
import dev.mobileforge.runtime.api.ProcessManager
import dev.mobileforge.runtime.api.ProcessStatus
import dev.mobileforge.runtime.api.RuntimeCapabilityProbe
import dev.mobileforge.runtime.api.ToolStatus
import dev.mobileforge.runtime.laravel.ServeLaunch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Which server the user chose. Both serve the same project; they differ in how. */
enum class ServeKind(val label: String) {
    /** `php artisan serve` — the framework's built-in single-process dev server. */
    PhpDevServer("PHP dev server"),

    /** nginx in front of `php-cgi` — static files, concurrency, keep-alive. */
    Nginx("nginx"),
}

/**
 * State for the Serve pane: the choice, the port, and what is actually running.
 *
 * [nginxInstalled] and [php] are *measured*, never assumed — the pane must be able to tell the
 * user "nginx is not installed" with a way to fix it rather than failing when Start is pressed.
 */
data class ServeUiState(
    val kind: ServeKind = ServeKind.PhpDevServer,
    val portText: String = ServeLaunch.DEFAULT_PORT.toString(),
    val php: PhpStatus = PhpStatus.Loading,
    val nginxInstalled: Boolean = false,
    val running: Boolean = false,
    /** The URL the server is reachable at, while it runs. */
    val url: String? = null,
    /** Output of the running server (nginx errors, php-cgi notices), bounded. */
    val output: List<String> = emptyList(),
    val error: AppError? = null,
) {
    /** The parsed port, or null when the text is not a usable port number. */
    val port: Int? get() = portText.toIntOrNull()?.takeIf { it in MIN_SERVE_PORT..MAX_SERVE_PORT }

    val portValid: Boolean get() = port != null

    /** True when nginx is the choice but its bundle has not been installed. */
    val nginxMissing: Boolean get() = kind == ServeKind.Nginx && !nginxInstalled

    /** Whether pressing Start would actually do something. */
    val canStart: Boolean
        get() = !running && portValid && php is PhpStatus.Ready && !nginxMissing
}

/**
 * Owns starting and stopping a local server for one open project.
 *
 * Two decisions are deliberate.
 *
 * **One source of truth for "running".** The pane does not infer liveness from its own start
 * call; it watches the process manager's list. A server the OS kills is then shown as stopped
 * rather than as running until something else notices — the state the user sees is the state that
 * exists.
 *
 * **Stop is user-initiated, not a crash.** A SIGTERM'd process exits non-zero (143), which the
 * process manager reports as `Failed`. Without [userStopping] every deliberate Stop would flash a
 * scary "the server stopped unexpectedly" error. The flag distinguishes the two plainly.
 *
 * Servers are stopped in [onCleared] — leaving the project is a deliberate act, and a server left
 * holding a port with no UI to stop it is exactly the orphan this refuses to create. Backgrounding
 * the app does NOT clear the view model, so a running server survives it; that is what the
 * foreground service is for.
 */
class ServeViewModel(
    private val projectRoot: String,
    private val processManager: ProcessManager,
    private val probe: RuntimeCapabilityProbe,
    /**
     * The workspace's trust, read when Start is pressed. Required, with no default: starting a
     * server runs the project's own PHP, so a view model that can do it must have been told
     * whether it may. Stop is never gated — a user must always be able to stop what is running.
     */
    private val trust: StateFlow<WorkspaceTrust>,
    /** A writable directory for the generated nginx config, pid and temp files. */
    private val workDir: String,
    /** Where the small config file write happens. Injected so tests stay deterministic. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(ServeUiState())
    val state: StateFlow<ServeUiState> = _state.asStateFlow()

    private var mainId: ProcessId? = null
    private var supportId: ProcessId? = null
    private var pumps: List<Job> = emptyList()

    /** True between a user pressing Stop and the processes actually reaching a terminal state. */
    private var userStopping = false

    /**
     * A scope that outlives the view model, used only to stop servers in [onCleared].
     *
     * `onCleared` is not suspending and `viewModelScope` is already cancelled by the time it runs,
     * so a teardown that must still reach the process manager needs its own scope. It is cancelled
     * as soon as the last server has been stopped.
     */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        refreshTools()
        observeProcesses()
    }

    /** Re-probes PHP (and therefore nginx, whose binary lives beside it). */
    fun refreshTools() {
        _state.update { it.copy(php = PhpStatus.Loading) }
        viewModelScope.launch {
            when (val status = runCatching { probe.probe(DevTool.Php) }.getOrNull()) {
                is ToolStatus.Available -> _state.update {
                    it.copy(
                        php = PhpStatus.Ready(status.version, status.absolutePath),
                        nginxInstalled = File(binDirOf(status.absolutePath), NGINX_BINARY).isFile,
                    )
                }

                is ToolStatus.NotInstalled -> _state.update {
                    it.copy(php = missingPhpStatus(), nginxInstalled = false)
                }

                is ToolStatus.Unsupported -> _state.update {
                    it.copy(
                        php = PhpStatus.Missing(
                            message = "PHP cannot run on this device.",
                            detail = status.reason,
                            recovery = status.recovery,
                        ),
                        nginxInstalled = false,
                    )
                }

                is ToolStatus.NotImplementedYet -> _state.update {
                    it.copy(
                        php = PhpStatus.Missing(
                            message = "PHP is not available yet.",
                            detail = "It is scheduled for ${status.phase}.",
                            recovery = "See ROADMAP.md for what that phase delivers.",
                        ),
                        nginxInstalled = false,
                    )
                }

                null -> _state.update {
                    it.copy(
                        php = PhpStatus.Missing(
                            message = "PHP could not be checked.",
                            detail = "The capability probe did not answer.",
                            recovery = "Retry. If it persists, run diagnostics from Settings.",
                        ),
                        nginxInstalled = false,
                    )
                }
            }
        }
    }

    /** Changes the server choice. Ignored while a server is running, so state cannot drift. */
    fun selectKind(kind: ServeKind) {
        if (_state.value.running) return
        _state.update { it.copy(kind = kind, error = null) }
    }

    /** Sets the port from raw field text, keeping only digits. */
    fun setPort(text: String) {
        if (_state.value.running) return
        _state.update { it.copy(portText = text.filter(Char::isDigit).take(5), error = null) }
    }

    /**
     * Starts the chosen server on the chosen port.
     *
     * Every reason not to start — no PHP, an unusable port, nginx not installed — becomes a
     * structured error the pane shows, never a disabled button with no explanation.
     */
    fun start() {
        val current = _state.value
        if (current.running) return
        // First, ahead of every availability check: a restricted project should hear that it is
        // restricted, not be told to install nginx for a server it may not run anyway. This is
        // the only entry point — startPhp and startNginx are private.
        TrustPolicy.executionRefusal(trust.value)?.let { refusal ->
            _state.update { it.copy(error = refusal) }
            return
        }
        val php = current.php as? PhpStatus.Ready
        if (php == null) {
            _state.update { it.copy(error = missingPhp()) }
            return
        }
        val port = current.port
        if (port == null) {
            _state.update { it.copy(error = unusablePortError(current.portText)) }
            return
        }
        when (current.kind) {
            ServeKind.PhpDevServer -> startPhp(php.path, port)
            ServeKind.Nginx -> {
                if (!current.nginxInstalled) {
                    _state.update { it.copy(error = nginxMissingError()) }
                    return
                }
                startNginx(php.path, port)
            }
        }
    }

    /**
     * Stops a running server.
     *
     * nginx first, then its FastCGI backend: killing the backend while nginx is still accepting
     * requests would turn in-flight requests into 502s.
     */
    fun stop() {
        if (!_state.value.running) return
        // Set even when the start call has not handed back its id yet, so a Stop pressed during
        // startup is honoured the moment the process exists.
        userStopping = true
        viewModelScope.launch {
            mainId?.let { processManager.stop(it) }
            supportId?.let { processManager.stop(it) }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ---------------------------------------------------------------------------------

    private fun startPhp(phpPath: String, port: Int) {
        beginStart()
        viewModelScope.launch {
            when (val started = processManager.start(
                ServeLaunch.artisanServeSpec(phpPath, projectRoot, port),
            )) {
                is AppResult.Failure -> fail(started.error)
                is AppResult.Success -> {
                    mainId = started.value
                    if (userStopping) {
                        processManager.stop(started.value)
                        return@launch
                    }
                    pump(started.value)
                }
            }
        }
    }

    private fun startNginx(phpPath: String, port: Int) {
        beginStart()
        viewModelScope.launch {
            val binDir = binDirOf(phpPath)
            val phpCgi = File(binDir, PHP_CGI_BINARY)
            if (!phpCgi.isFile) {
                fail(phpCgiMissingError())
                return@launch
            }

            val fastCgiPort = ServeLaunch.fastCgiPortFor(port)
            val rendered = runCatching {
                withContext(ioDispatcher) {
                    val directory = File(workDir).apply { mkdirs() }
                    val config = File(directory, ServeLaunch.configFileName())
                    config.writeText(
                        ServeLaunch.renderNginxConfig(
                            port = port,
                            fastCgiPort = fastCgiPort,
                            documentRoot = File(projectRoot, "public").absolutePath,
                            workDir = directory.absolutePath,
                        ),
                    )
                    directory to config
                }
            }.getOrElse { cause ->
                fail(configWriteError(cause))
                return@launch
            }
            val (directory, config) = rendered

            val cgiId = when (
                val started = processManager.start(
                    ServeLaunch.phpCgiSpec(phpCgi.absolutePath, projectRoot, fastCgiPort),
                )
            ) {
                is AppResult.Failure -> {
                    fail(started.error)
                    return@launch
                }

                is AppResult.Success -> started.value
            }
            supportId = cgiId

            when (val nginx = processManager.start(
                ServeLaunch.nginxSpec(
                    nginxPath = File(binDir, NGINX_BINARY).absolutePath,
                    workDir = directory.absolutePath,
                    configPath = config.absolutePath,
                ),
            )) {
                is AppResult.Failure -> {
                    // Never leave the FastCGI backend running behind a front end that failed.
                    userStopping = true
                    processManager.stop(cgiId)
                    fail(nginx.error)
                }

                is AppResult.Success -> {
                    mainId = nginx.value
                    if (userStopping) {
                        processManager.stop(nginx.value)
                        processManager.stop(cgiId)
                        return@launch
                    }
                    pump(cgiId)
                    pump(nginx.value)
                }
            }
        }
    }

    private fun beginStart() {
        userStopping = false
        _state.update { it.copy(running = true, url = null, output = emptyList(), error = null) }
    }

    private fun fail(error: AppError) {
        userStopping = false
        mainId = null
        supportId = null
        _state.update { it.copy(running = false, url = null, error = error) }
    }

    private fun pump(id: ProcessId) {
        pumps = pumps + viewModelScope.launch {
            processManager.logs(id).collect { output ->
                _state.update { current ->
                    current.copy(output = (current.output + output.line).takeLast(MAX_OUTPUT_LINES))
                }
            }
        }
    }

    /**
     * Watches the process manager so "running" reflects reality, not intent.
     *
     * A process that ends without the user asking is surfaced as an error — a server that died on
     * a bad config must not look like a server that is quietly working.
     */
    private fun observeProcesses() {
        viewModelScope.launch {
            processManager.processes.collect { list ->
                val tracked = listOfNotNull(mainId, supportId).toSet()
                if (tracked.isEmpty()) return@collect

                val mine = list.filter { it.id in tracked }
                val running = mine.any {
                    it.status == ProcessStatus.Starting || it.status == ProcessStatus.Running
                }
                val crashed = if (userStopping) {
                    null
                } else {
                    mine.firstOrNull {
                        it.status == ProcessStatus.Failed || it.status == ProcessStatus.KilledBySystem
                    }
                }

                _state.update { current ->
                    current.copy(
                        running = running,
                        url = if (running) urlFor(current) else null,
                        error = crashed?.let(::abruptExitError) ?: current.error,
                    )
                }

                if (!running) {
                    mainId = null
                    supportId = null
                    userStopping = false
                    pumps.forEach(Job::cancel)
                    pumps = emptyList()
                }
            }
        }
    }

    private fun urlFor(current: ServeUiState): String? =
        current.port?.let { "http://127.0.0.1:$it" }

    override fun onCleared() {
        // Leaving the project stops what it started. A server that outlives its pane has no
        // Stop button and holds a port nobody can see.
        val ids = listOfNotNull(mainId, supportId)
        pumps.forEach(Job::cancel)
        pumps = emptyList()
        if (ids.isEmpty()) {
            teardownScope.cancel()
        } else {
            userStopping = true
            teardownScope.launch {
                ids.forEach { processManager.stop(it) }
                teardownScope.cancel()
            }
        }
        super.onCleared()
    }

    /** The probe's answer when PHP is absent, shaped for the pane's empty state. */
    private fun missingPhpStatus() = PhpStatus.Missing(
        message = "PHP is not installed.",
        detail = "Serving needs the PHP toolchain on this device.",
        recovery = "Install it from Settings → Toolchains, then retry.",
    )

    private fun missingPhp() = AppError(
        category = ErrorCategory.Unavailable,
        message = "PHP is not installed.",
        detail = "Serving needs the PHP toolchain on this device.",
        recovery = "Install it from Settings → Toolchains, then retry.",
    )

    private fun unusablePortError(raw: String) = AppError(
        category = ErrorCategory.Validation,
        message = "That port is not usable.",
        detail = "\"$raw\" is not a number between $MIN_SERVE_PORT and $MAX_SERVE_PORT.",
        recovery = "Enter a port such as ${
            ServeLaunch.DEFAULT_PORT
        }, or leave it as it is for the Laravel default.",
    )

    private fun nginxMissingError() = AppError(
        category = ErrorCategory.Unavailable,
        message = "nginx is not installed.",
        detail = "The nginx bundle has not been installed on this device yet.",
        recovery = "Open Toolchains and install nginx, then come back.",
    )

    private fun phpCgiMissingError() = AppError(
        category = ErrorCategory.Unavailable,
        message = "php-cgi is missing.",
        detail = "nginx serves PHP through php-cgi, which is built alongside PHP.",
        recovery = "Reinstall the PHP toolchain from Settings → Toolchains.",
    )

    private fun configWriteError(cause: Throwable) = AppError(
        category = ErrorCategory.FileSystem,
        message = "Could not prepare the nginx configuration.",
        detail = cause.message ?: cause::class.java.simpleName,
        recovery = "Free some storage space and try again.",
        cause = cause,
    )

    private fun abruptExitError(process: ManagedProcess) = AppError(
        category = ErrorCategory.Process,
        message = if (process.status == ProcessStatus.KilledBySystem) {
            "Android stopped the server."
        } else {
            "The server stopped unexpectedly."
        },
        detail = "${process.spec.executable.substringAfterLast('/')} exited " +
            "with code ${process.exitCode ?: "unknown"}.",
        recovery = "Check the output above, fix the cause, and start it again.",
    )

    private companion object {
        const val NGINX_BINARY = "nginx"
        const val PHP_CGI_BINARY = "php-cgi"
        const val MAX_OUTPUT_LINES = 400

        fun binDirOf(phpPath: String): String = phpPath.substringBeforeLast('/', missingDelimiterValue = "/")
    }
}

/**
 * The port range a local server may use.
 *
 * Matches PortAllocator: below 1024 needs privileges Android does not grant to an app. Top-level
 * because [ServeUiState]'s derived `port` needs them too, not just the view model.
 */
private const val MIN_SERVE_PORT = 1024
private const val MAX_SERVE_PORT = 65_535

package dev.mobileforge.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.net.toUri
import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.DefaultAppDispatchers
import dev.mobileforge.core.common.Logger
import dev.mobileforge.core.data.DataFactory
import dev.mobileforge.core.data.secure.SecretStore
import dev.mobileforge.core.data.settings.SettingsRepository
import dev.mobileforge.core.data.workspace.WorkspaceRepository
import dev.mobileforge.core.filesystem.FrameworkDetector
import dev.mobileforge.core.filesystem.LocalWorkspaceFileSystem
import dev.mobileforge.core.filesystem.ProjectScaffolder
import dev.mobileforge.core.filesystem.WorkspaceFileSystem
import dev.mobileforge.core.security.PathValidator
import dev.mobileforge.feature.settings.DefaultDiagnosticsProvider
import dev.mobileforge.feature.editor.WebViewCompatibility
import dev.mobileforge.feature.settings.BundleStore
import dev.mobileforge.feature.settings.DiagnosticsProvider
import dev.mobileforge.logging.AndroidLogger
import dev.mobileforge.runtime.AndroidBundleStore
import dev.mobileforge.runtime.AndroidRuntimeFactory
import dev.mobileforge.runtime.AndroidToolchainManager
import dev.mobileforge.runtime.RuntimeForegroundService
import dev.mobileforge.runtime.api.ToolchainManager
import dev.mobileforge.runtime.api.EmbeddedBundle
import dev.mobileforge.runtime.api.ProcessExecutor
import dev.mobileforge.runtime.api.ProcessSpec
import dev.mobileforge.runtime.api.ProcessStatus
import dev.mobileforge.runtime.api.REQUIRED_TOOLCHAIN_IDS
import dev.mobileforge.runtime.ShellCommandFactory
import dev.mobileforge.runtime.pty.NativePtyLauncher
import dev.mobileforge.runtime.pty.PtyLauncher
import dev.mobileforge.runtime.pty.TerminalSession
import dev.mobileforge.runtime.api.RuntimeCapabilityProbe
import dev.mobileforge.runtime.exec.DefaultProcessManager
import dev.mobileforge.runtime.exec.ExecCommandBuilder
import dev.mobileforge.runtime.exec.ExecEnvironment
import dev.mobileforge.runtime.exec.ExecStrategySelector
import dev.mobileforge.runtime.exec.JvmProcessLauncher
import dev.mobileforge.runtime.exec.OneShotProcessExecutor
import dev.mobileforge.runtime.exec.PortAllocator
import dev.mobileforge.runtime.exec.ProcessLauncher
import dev.mobileforge.runtime.exec.RuntimeEnvironmentBuilder
import dev.mobileforge.runtime.exec.SystemLinkerRuntimeProbe
import dev.mobileforge.runtime.toolchain.ToolchainManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The composition root. Manual DI, deliberately — see docs/adr/ADR-008-dependency-injection.md.
 *
 * The entire object graph is one readable file. A missing dependency is a compile error rather
 * than a runtime crash on a user's phone, and every collaborator here is an interface with a
 * trivially constructible fake for tests.
 */
interface AppContainer {
    val dispatchers: AppDispatchers
    val logger: Logger
    val pathValidator: PathValidator
    val workspaceRepository: WorkspaceRepository
    val settingsRepository: SettingsRepository
    val secretStore: SecretStore
    val frameworkDetector: FrameworkDetector
    val projectScaffolder: ProjectScaffolder

    // ---- Phase 2: development runtime ----
    val execEnvironment: ExecEnvironment
    val runtimeEnvironment: RuntimeEnvironmentBuilder
    val runtimeProbe: RuntimeCapabilityProbe
    val processManager: DefaultProcessManager
    val portAllocator: PortAllocator
    val ptyLauncher: PtyLauncher
    val shellCommandFactory: ShellCommandFactory
    val toolchainManager: ToolchainManager

    /** Published bundles the toolchain screen can install without the user finding a file. */
    val bundleStore: BundleStore

    /**
     * One-shot runs to completion (artisan, version probes). Unlike [processManager], its
     * output flow ends and its await waits — the contract `ArtisanRunner` is written against.
     */
    val oneShotExecutor: ProcessExecutor

    /** A fresh terminal session. Each pane owns one; closing the pane closes the PTY. */
    fun newTerminalSession(): TerminalSession

    /**
     * Toolchain bundles shipped inside this APK, staged as installable documents.
     *
     * Empty when this build bundles nothing — dev builds and store listings without
     * embedded toolchains must not pretend otherwise; the setup screen says so plainly.
     */
    suspend fun embeddedBundles(): AppResult<List<EmbeddedBundle>>

    /**
     * Whether the first-run toolchain setup earns its interruption.
     *
     * True only when the user has not dismissed it, something is actually embedded, and
     * something required is still missing. Anything less would nag on every launch.
     */
    suspend fun shouldShowSetup(): Boolean

    val diagnosticsProvider: DiagnosticsProvider

    /** A filesystem scoped to one workspace. Paths outside its root cannot be reached. */
    fun fileSystemFor(workspaceRootPath: String): WorkspaceFileSystem

    /** Where project directories live. The Laravel wizard downloads into a fresh one. */
    val workspacesRoot: File

    /**
     * Whether this device can currently reach the internet (validated, not merely
     * connected). Consulted before any download the user did not already approve
     * per-megabyte — a stalled `composer create-project` on a captive portal is a
     * failure the UI should refuse upfront rather than discover mid-download.
     */
    suspend fun networkAvailable(): Boolean
}

class DefaultAppContainer(context: Context) : AppContainer {

    private val appContext = context.applicationContext

    /**
     * All project files live here.
     *
     * Internal storage: it needs no runtime permission, is invisible to other apps, and is the
     * only location the runtime can execute from (RISK-008, ADR-002).
     */
    override val workspacesRoot: File =
        File(appContext.filesDir, "workspaces").apply { if (!exists()) mkdirs() }

    /**
     * Scope for work that outlives any single screen — output pumps and exit watchers.
     *
     * SupervisorJob so one process's reader failing cannot cancel every other process's.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + DefaultAppDispatchers.default)

    override val dispatchers: AppDispatchers = DefaultAppDispatchers

    override val logger: Logger = AndroidLogger()

    override val pathValidator: PathValidator = PathValidator()

    // Built through DataFactory so that Room never reaches this module's classpath.
    override val workspaceRepository: WorkspaceRepository by lazy {
        DataFactory.createWorkspaceRepository(
            context = appContext,
            workspacesRoot = workspacesRoot,
            dispatchers = dispatchers,
            logger = logger,
        )
    }

    override val settingsRepository: SettingsRepository by lazy {
        DataFactory.createSettingsRepository(appContext)
    }

    override val secretStore: SecretStore by lazy { DataFactory.createSecretStore(appContext) }

    override val frameworkDetector: FrameworkDetector by lazy { FrameworkDetector(dispatchers) }

    override val projectScaffolder: ProjectScaffolder by lazy { ProjectScaffolder(dispatchers) }

    // ---------------------------------------------------------------------------------
    // Development runtime (Phase 2)
    // ---------------------------------------------------------------------------------

    /** The single place Android's own APIs are read to describe execution (ADR-009). */
    override val execEnvironment: ExecEnvironment by lazy {
        AndroidRuntimeFactory.execEnvironment(appContext)
    }

    override val runtimeEnvironment: RuntimeEnvironmentBuilder by lazy {
        RuntimeEnvironmentBuilder(execEnvironment)
    }

    private val execCommandBuilder: ExecCommandBuilder by lazy {
        ExecCommandBuilder(
            strategySelector = ExecStrategySelector(execEnvironment),
            headerReader = AndroidRuntimeFactory.fileHeaderReader(),
        )
    }

    private val processLauncher: ProcessLauncher by lazy { JvmProcessLauncher() }

    /**
     * Phase 2 replaces Phase 1's `NotImplementedRuntimeProbe` with one that actually executes.
     *
     * Note what did NOT change to accommodate that: nothing outside this file. Every caller
     * only ever depended on [RuntimeCapabilityProbe], which is the point of the boundary.
     */
    override val runtimeProbe: RuntimeCapabilityProbe by lazy { systemLinkerProbe }

    /** Typed access for the self-test, which is richer than the shared probe interface. */
    val systemLinkerProbe: SystemLinkerRuntimeProbe by lazy {
        SystemLinkerRuntimeProbe(
            environment = execEnvironment,
            environmentBuilder = runtimeEnvironment,
            launcher = processLauncher,
            commandBuilder = execCommandBuilder,
            dispatchers = dispatchers,
        )
    }

    override val processManager: DefaultProcessManager by lazy {
        DefaultProcessManager(
            launcher = processLauncher,
            commandBuilder = execCommandBuilder,
            environmentBuilder = runtimeEnvironment,
            dispatchers = dispatchers,
            logger = logger,
            scope = applicationScope,
        )
    }

    override val portAllocator: PortAllocator by lazy { PortAllocator() }

    override val oneShotExecutor: ProcessExecutor by lazy {
        OneShotProcessExecutor(
            launcher = processLauncher,
            commandBuilder = execCommandBuilder,
            environmentBuilder = runtimeEnvironment,
            dispatchers = dispatchers,
        )
    }
    override val toolchainManager: ToolchainManager by lazy {
        AndroidToolchainManager(appContext, File(runtimeEnvironment.prefix), dispatchers, logger)
    }

    override val bundleStore: BundleStore by lazy {
        AndroidBundleStore(appContext, toolchainManager, settingsRepository, dispatchers, logger)
    }

    /**
     * Embedded bundles, staged from APK assets into cache files the installer accepts.
     *
     * Assets are read-only and have no URIs the document-based installer understands, so
     * each manifest+archive pair is copied to cache once per versioned filename — a
     * filename carries its version, so a stale copy cannot shadow a newer bundle. Only
     * this device's ABI is considered; other ABIs in the assets are ignored, not errors.
     */
    override suspend fun embeddedBundles(): AppResult<List<EmbeddedBundle>> =
        withContext(dispatchers.io) {
            try {
                val abi = Build.SUPPORTED_ABIS.first()
                val staged = File(appContext.cacheDir, "embedded-bundles").apply { mkdirs() }
                val parser = Json { ignoreUnknownKeys = true }
                val found = appContext.assets.list(EMBEDDED_BUNDLES_DIR).orEmpty()
                    .filter { it.endsWith("-$abi.json") }
                    .mapNotNull { name ->
                        val base = name.removeSuffix(".json")
                        val manifest = try {
                            val text = appContext.assets.open("$EMBEDDED_BUNDLES_DIR/$name")
                                .bufferedReader().use { it.readText() }
                            parser.decodeFromString<ToolchainManifest>(text)
                        } catch (_: Exception) {
                            logger.warn(TAG, "Skipping unreadable embedded manifest: $name")
                            return@mapNotNull null
                        }
                        try {
                            val manifestFile = File(staged, name)
                            val archiveFile = File(staged, "$base.zip")
                            if (!manifestFile.isFile) {
                                appContext.assets.open("$EMBEDDED_BUNDLES_DIR/$name").use { input ->
                                    manifestFile.outputStream().use { input.copyTo(it) }
                                }
                            }
                            if (!archiveFile.isFile) {
                                appContext.assets.open("$EMBEDDED_BUNDLES_DIR/$base.zip").use { input ->
                                    archiveFile.outputStream().use { input.copyTo(it) }
                                }
                            }
                            EmbeddedBundle(
                                id = manifest.id,
                                displayName = manifest.displayName,
                                version = manifest.version,
                                abi = manifest.abi,
                                sizeBytes = manifest.sizeBytes,
                                manifestDocument = manifestFile.toUri().toString(),
                                archiveDocument = archiveFile.toUri().toString(),
                            )
                        } catch (_: Exception) {
                            logger.warn(TAG, "Skipping embedded bundle with missing archive: $base")
                            null
                        }
                    }
                AppResult.Success(found)
            } catch (e: Exception) {
                AppResult.Failure(AppError(
                    category = ErrorCategory.FileSystem,
                    message = "Embedded toolchains could not be listed.",
                    detail = e.message,
                ))
            }
        }

    override suspend fun shouldShowSetup(): Boolean {
        val completed = try {
            settingsRepository.settings.first().completedFirstRun
        } catch (_: Exception) {
            false
        }
        if (completed) return false
        val embedded = (embeddedBundles() as? AppResult.Success)?.value.orEmpty()
        if (embedded.isEmpty()) return false
        val installed = (toolchainManager.installed() as? AppResult.Success)
            ?.value.orEmpty().map { it.id }.toSet()
        return REQUIRED_TOOLCHAIN_IDS.any { it !in installed }
    }

    override val ptyLauncher: PtyLauncher by lazy { NativePtyLauncher() }

    override val shellCommandFactory: ShellCommandFactory by lazy {
        ShellCommandFactory(
            commandBuilder = execCommandBuilder,
            environmentBuilder = runtimeEnvironment,
        )
    }

    override fun newTerminalSession(): TerminalSession = TerminalSession(
        launcher = ptyLauncher,
        dispatchers = dispatchers,
        logger = logger,
        scope = applicationScope,
    )

    override val diagnosticsProvider: DiagnosticsProvider by lazy {
        DefaultDiagnosticsProvider(
            context = appContext,
            runtimeProbe = runtimeProbe,
            workspacesRoot = workspacesRoot,
            dispatchers = dispatchers,
            execSelfTest = { systemLinkerProbe.selfTest().summary },
            webViewStatus = { WebViewCompatibility.check(appContext).summary },
        )
    }

    override fun fileSystemFor(workspaceRootPath: String): WorkspaceFileSystem =
        LocalWorkspaceFileSystem(
            workspaceRoot = File(workspaceRootPath),
            pathValidator = pathValidator,
            dispatchers = dispatchers,
            logger = logger,
        )

    override suspend fun networkAvailable(): Boolean = withContext(dispatchers.io) {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
            ?: return@withContext false
        val network = connectivity.activeNetwork ?: return@withContext false
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return@withContext false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Keeps the foreground service in step with what is actually running.
     *
     * A server the user started must survive the IDE being backgrounded, and the platform's
     * sanctioned way to say "this is user-initiated and ongoing" is a foreground service. Driven
     * from the process list rather than from the Start/Stop buttons on purpose: the notification
     * then reflects reality even when a process dies on its own, rather than whatever the last
     * button said.
     *
     * The service's `sync` takes a count and stops itself at zero, so this is one signal with no
     * start/stop bookkeeping to get wrong. `runCatching` guards the one platform restriction that
     * would otherwise be fatal — starting a foreground service while the app is in the background —
     * which cannot happen from a Start tap but must not crash the app if it ever did.
     */
    init {
        applicationScope.launch {
            processManager.processes.collect { processes ->
                val active = processes.filter {
                    it.status == ProcessStatus.Running || it.status == ProcessStatus.Starting
                }
                runCatching {
                    RuntimeForegroundService.sync(
                        context = appContext,
                        processCount = active.size,
                        summary = active.joinToString(", ") { summarize(it.spec) }.take(SUMMARY_MAX),
                    )
                }.onFailure { logger.warn(TAG, "Foreground service sync failed: ${it.message}") }
            }
        }
    }

    private fun summarize(spec: ProcessSpec): String {
        val name = spec.executable.substringAfterLast('/')
        val arguments = spec.arguments.joinToString(" ")
        return if (arguments.isEmpty()) name else "$name $arguments"
    }

    private companion object {
        const val TAG = "AppContainer"

        /** APK assets directory holding embedded toolchain bundles, if this build ships any. */
        const val EMBEDDED_BUNDLES_DIR = "bundles"

        /** How much of a command the notification's second line may show. */
        const val SUMMARY_MAX = 80
    }
}

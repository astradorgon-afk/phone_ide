package dev.mobileforge.di

import android.content.Context
import dev.mobileforge.core.common.AppDispatchers
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
import dev.mobileforge.feature.settings.DiagnosticsProvider
import dev.mobileforge.logging.AndroidLogger
import dev.mobileforge.runtime.AndroidRuntimeFactory
import dev.mobileforge.runtime.AndroidToolchainManager
import dev.mobileforge.runtime.api.ToolchainManager
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
import dev.mobileforge.runtime.exec.PortAllocator
import dev.mobileforge.runtime.exec.ProcessLauncher
import dev.mobileforge.runtime.exec.RuntimeEnvironmentBuilder
import dev.mobileforge.runtime.exec.SystemLinkerRuntimeProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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

    /** A fresh terminal session. Each pane owns one; closing the pane closes the PTY. */
    fun newTerminalSession(): TerminalSession

    val diagnosticsProvider: DiagnosticsProvider

    /** A filesystem scoped to one workspace. Paths outside its root cannot be reached. */
    fun fileSystemFor(workspaceRootPath: String): WorkspaceFileSystem
}

class DefaultAppContainer(context: Context) : AppContainer {

    private val appContext = context.applicationContext

    /**
     * All project files live here.
     *
     * Internal storage: it needs no runtime permission, is invisible to other apps, and is the
     * only location the runtime can execute from (RISK-008, ADR-002).
     */
    private val workspacesRoot: File =
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
    override val toolchainManager: ToolchainManager by lazy {
        AndroidToolchainManager(appContext, File(runtimeEnvironment.prefix), dispatchers, logger)
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
}

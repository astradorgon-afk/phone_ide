package dev.mobileforge.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.mobileforge.feature.settings.ToolchainScreen
import dev.mobileforge.feature.settings.ToolchainViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.mobileforge.feature.editor.WebViewCompatibility
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.mobileforge.core.model.WorkspaceId
import dev.mobileforge.di.AppContainer
import dev.mobileforge.feature.editor.EditorViewModel
import dev.mobileforge.feature.editor.bridge.MonacoBridge
import dev.mobileforge.feature.projects.ProjectsScreen
import dev.mobileforge.feature.projects.ProjectsViewModel
import dev.mobileforge.feature.settings.DiagnosticsScreen
import dev.mobileforge.feature.settings.SettingsScreen
import dev.mobileforge.feature.settings.SetupScreen
import dev.mobileforge.feature.settings.SetupViewModel
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.feature.settings.SettingsViewModel
import dev.mobileforge.feature.terminal.TerminalViewModel
import dev.mobileforge.feature.workspace.WorkspaceScreen
import dev.mobileforge.feature.workspace.EnvViewModel
import dev.mobileforge.feature.workspace.ArtisanViewModel
import android.net.Uri
import dev.mobileforge.feature.projects.LaravelWizardScreen
import dev.mobileforge.feature.projects.LaravelWizardViewModel
import dev.mobileforge.feature.workspace.WorkspaceViewModel
import dev.mobileforge.feature.workspace.ServeViewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * The whole navigation graph.
 *
 * MainActivity hosts this and does nothing else — no god Activity, per ADR-001.
 */
object Routes {
    const val PROJECTS = "projects"
    const val SETUP = "setup"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val TOOLCHAINS = "toolchains"
    const val WORKSPACE = "workspace/{workspaceId}"
    const val LARAVEL_WIZARD = "laravel/{projectName}"

    fun workspace(id: WorkspaceId) = "workspace/${id.value}"
    fun laravelWizard(projectName: String) = "laravel/${Uri.encode(projectName)}"
}

@Composable
fun MobileForgeNavHost(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.PROJECTS) {

        composable(Routes.PROJECTS) {
            val viewModel: ProjectsViewModel = viewModel(
                factory = viewModelFactory {
                    ProjectsViewModel(
                        repository = container.workspaceRepository,
                        frameworkDetector = container.frameworkDetector,
                        scaffolder = container.projectScaffolder,
                    )
                },
            )
            ProjectsScreen(
                viewModel = viewModel,
                onOpenWorkspace = { id -> navController.navigate(Routes.workspace(id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onCreateLaravel = { name -> navController.navigate(Routes.laravelWizard(name)) },
            )
            // First-run toolchain setup earns exactly one interruption: it fires once, only
            // when something required is actually missing, and finishing or skipping
            // persists dismissal — so this never nags.
            LaunchedEffect(Unit) {
                if (container.shouldShowSetup()) navController.navigate(Routes.SETUP)
            }
        }

        composable(Routes.SETUP) {
            val model: SetupViewModel = viewModel(factory = viewModelFactory {
                SetupViewModel(
                    container.toolchainManager,
                    container.settingsRepository,
                    container::embeddedBundles,
                )
            })
            val scope = rememberCoroutineScope()
            // Persist-then-leave, in that order: popping first would clear the ViewModel and
            // could cancel the dismissal write, nagging again next launch.
            SetupScreen(
                viewModel = model,
                onFinished = { scope.launch { model.finish(); navController.popBackStack() } },
                onSkip = { scope.launch { model.skip(); navController.popBackStack() } },
            )
        }

        composable(
            route = Routes.WORKSPACE,
            arguments = listOf(navArgument("workspaceId") { type = NavType.StringType }),
        ) { entry ->
            val rawId = entry.arguments?.getString("workspaceId").orEmpty()
            val workspaceId = remember(rawId) { WorkspaceId(rawId) }

            // Resolved asynchronously: this is a database read, and blocking the main thread
            // for it would drop frames on every project open.
            var rootPath by remember(rawId) { mutableStateOf<String?>(null) }
            LaunchedEffect(rawId) {
                rootPath = container.workspaceRepository.rootPathOf(workspaceId).getOrNull()
                    ?: MISSING_WORKSPACE
            }

            when (val resolved = rootPath) {
                null -> LoadingPane()

                MISSING_WORKSPACE -> {
                    // The row was removed between listing and navigating. Go back rather than
                    // render a workspace screen with no workspace behind it.
                    LaunchedEffect(rawId) { navController.popBackStack() }
                }

                else -> WorkspaceRoute(
                    container = container,
                    workspaceId = workspaceId,
                    rawId = rawId,
                    rootPath = resolved,
                    onOpenToolchains = { navController.navigate(Routes.TOOLCHAINS) },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(
            route = Routes.LARAVEL_WIZARD,
            arguments = listOf(navArgument("projectName") { type = NavType.StringType }),
        ) { entry ->
            val name = entry.arguments?.getString("projectName")?.let(Uri::decode).orEmpty()
            val wizardViewModel: LaravelWizardViewModel = viewModel(
                key = "laravel-$name",
                factory = viewModelFactory {
                    LaravelWizardViewModel(
                        projectName = name,
                        workspacesRoot = container.workspacesRoot.absolutePath,
                        repository = container.workspaceRepository,
                        fileSystemFor = container::fileSystemFor,
                        executor = container.oneShotExecutor,
                        toolchain = container.toolchainManager,
                        composerPath = "${container.runtimeEnvironment.binDir}/composer",
                        phpPath = "${container.runtimeEnvironment.binDir}/php",
                        networkAvailable = container::networkAvailable,
                        freeBytes = { container.workspacesRoot.usableSpace },
                    )
                },
            )
            LaravelWizardScreen(
                viewModel = wizardViewModel,
                projectName = name,
                onOpenWorkspace = { id -> navController.navigate(Routes.workspace(id)) },
                onOpenToolchains = { navController.navigate(Routes.TOOLCHAINS) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = settingsViewModel(container),
                onBack = { navController.popBackStack() },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onOpenToolchains = { navController.navigate(Routes.TOOLCHAINS) },
            )
        }

        composable(Routes.TOOLCHAINS) {
            val model: ToolchainViewModel = viewModel(factory = viewModelFactory {
                ToolchainViewModel(container.toolchainManager, container.bundleStore)
            })
            val manifestPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
                it?.let { uri -> model.selectManifest(uri.toString()) }
            }
            val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
                it?.let { uri -> model.selectArchive(uri.toString()) }
            }
            ToolchainScreen(model, onBack = { navController.popBackStack() },
                onSelectManifest = { manifestPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                onSelectArchive = { archivePicker.launch(arrayOf("application/zip", "application/octet-stream")) })
        }

        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(
                viewModel = settingsViewModel(container),
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * Builds the per-workspace object graph.
 *
 * [WorkspaceViewModel] and [EditorViewModel] share ONE filesystem instance, scoped to this
 * workspace's root. That is what makes containment structural: the editor is not given a
 * general-purpose file API and asked to behave, it is given one that cannot address anything
 * outside the project.
 */
@Composable
private fun WorkspaceRoute(
    container: AppContainer,
    workspaceId: WorkspaceId,
    rawId: String,
    rootPath: String,
    onOpenToolchains: () -> Unit,
    onBack: () -> Unit,
) {
    val fileSystem = remember(rootPath) { container.fileSystemFor(rootPath) }
    val context = LocalContext.current
    val useNativeEditor = remember { !WebViewCompatibility.check(context).canRunEditor }

    val workspaceViewModel: WorkspaceViewModel = viewModel(
        key = "workspace-$rawId",
        factory = viewModelFactory {
            WorkspaceViewModel(
                workspaceId = workspaceId,
                repository = container.workspaceRepository,
                fileSystem = fileSystem,
                frameworkDetector = container.frameworkDetector,
            )
        },
    )

    val editorViewModel: EditorViewModel = viewModel(
        key = "editor-$rawId",
        factory = viewModelFactory {
            EditorViewModel(
                fileSystem = fileSystem,
                bridge = MonacoBridge(container.logger),
                logger = container.logger,
                nativeEditing = useNativeEditor,
            )
        },
    )

    // The shell is resolved once per workspace. A failure here means no terminal on this
    // device, which the workspace screen reports plainly rather than showing a blank pane.
    val shellCommand = remember(rootPath) {
        (container.shellCommandFactory.create(rootPath) as? AppResult.Success)?.value
    }

    val terminalViewModel: TerminalViewModel? = shellCommand?.let { command ->
        viewModel(
            key = "terminal-$rawId",
            factory = viewModelFactory {
                TerminalViewModel(
                    newSession = container::newTerminalSession,
                    shellCommand = command,
                )
            },
        )
    }

    val envViewModel: EnvViewModel = viewModel(
        key = "env-$rawId",
        factory = viewModelFactory {
            EnvViewModel(fileSystem = fileSystem)
        },
    )

    val artisanViewModel: ArtisanViewModel = viewModel(
        key = "artisan-$rawId",
        factory = viewModelFactory {
            ArtisanViewModel(
                projectRoot = rootPath,
                executor = container.oneShotExecutor,
                probe = container.runtimeProbe,
            )
        },
    )

    // A per-workspace, writable scratch directory for the generated nginx config, its pid and
    // temp files. Cache is the right home: none of it is worth keeping between runs.
    val serveWorkDir = remember(rootPath) {
        File(File(context.cacheDir, "serve"), rawId.hashCode().toString())
            .apply { mkdirs() }
            .absolutePath
    }

    val serveViewModel: ServeViewModel = viewModel(
        key = "serve-$rawId",
        factory = viewModelFactory {
            ServeViewModel(
                projectRoot = rootPath,
                processManager = container.processManager,
                probe = container.runtimeProbe,
                workDir = serveWorkDir,
            )
        },
    )

    WorkspaceScreen(
        viewModel = workspaceViewModel,
        editorViewModel = editorViewModel,
        terminalViewModel = terminalViewModel,
        envViewModel = envViewModel,
        artisanViewModel = artisanViewModel,
        serveViewModel = serveViewModel,
        onOpenToolchains = onOpenToolchains,
        onBack = onBack,
    )
}

@Composable
private fun settingsViewModel(container: AppContainer): SettingsViewModel = viewModel(
    factory = viewModelFactory {
        SettingsViewModel(
            repository = container.settingsRepository,
            diagnostics = container.diagnosticsProvider,
        )
    },
)

@Composable
private fun LoadingPane() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Distinguishes "still loading" (null) from "no such workspace". */
private const val MISSING_WORKSPACE = "missing"

/**
 * Minimal ViewModel factory for constructor injection.
 *
 * This is the entire cost of manual DI in this app: a handful of lines, no annotation
 * processor, and a missing dependency is a compile error rather than a runtime one (ADR-008).
 */
private inline fun <reified T : ViewModel> viewModelFactory(
    crossinline create: () -> T,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
}

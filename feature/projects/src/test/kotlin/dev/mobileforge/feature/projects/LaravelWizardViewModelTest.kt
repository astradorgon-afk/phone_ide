package dev.mobileforge.feature.projects

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.core.data.workspace.WorkspaceRepository
import dev.mobileforge.core.filesystem.WorkspaceFileSystem
import dev.mobileforge.core.model.DetectedFramework
import dev.mobileforge.core.model.Workspace
import dev.mobileforge.core.model.WorkspaceFile
import dev.mobileforge.core.model.WorkspaceId
import dev.mobileforge.core.model.WorkspaceTrust
import dev.mobileforge.runtime.api.OutputStream
import dev.mobileforge.runtime.api.ProcessExecutor
import dev.mobileforge.runtime.api.ProcessHandle
import dev.mobileforge.runtime.api.ProcessId
import dev.mobileforge.runtime.api.ProcessOutput
import dev.mobileforge.runtime.api.ProcessSignal
import dev.mobileforge.runtime.api.ProcessSpec
import dev.mobileforge.runtime.api.ToolchainManager
import dev.mobileforge.runtime.api.ToolchainImport
import dev.mobileforge.runtime.api.ToolchainRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Laravel creation sequencing, against fakes.
 *
 * The cases that carry weight: nothing downloads before the CONFIRM screen's consent, a
 * failed download keeps the project with its error instead of vanishing it, and a stuck
 * download can be stopped. The consent boundary is asserted, not assumed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LaravelWizardViewModelTest {

    @get:Rule val temp = TemporaryFolder()

    private class FakeHandle(
        private val stdout: List<String> = emptyList(),
        private val exitCode: Int = 0,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : ProcessHandle {
        override val id = ProcessId("fake")
        override val output: Flow<ProcessOutput> = if (gate != null) {
            flow {
                gate.await()
                stdout.forEach { emit(ProcessOutput(OutputStream.Stdout, it, 0L)) }
            }
        } else {
            flowOf(*stdout.map { ProcessOutput(OutputStream.Stdout, it, 0L) }.toTypedArray())
        }

        var signalled: ProcessSignal? = null
            private set

        override suspend fun writeStdin(text: String) = Unit.asSuccess()
        override suspend fun signal(signal: ProcessSignal): AppResult<Unit> {
            signalled = signal
            return Unit.asSuccess()
        }

        override suspend fun await(): AppResult<Int> = exitCode.asSuccess()
    }

    private class FakeExecutor(
        private val responses: Map<String, Pair<List<String>, Int>> = emptyMap(),
        private val hanging: FakeHandle? = null,
    ) : ProcessExecutor {
        val invocations = mutableListOf<ProcessSpec>()

        override suspend fun start(spec: ProcessSpec): AppResult<ProcessHandle> {
            invocations += spec
            hanging?.let { return it.asSuccess() }
            // Matched by containment: absolute target paths carry temp-dir randomness.
            val key = spec.executable.substringAfterLast("/") + " " +
                spec.arguments.joinToString(" ")
            val response = responses.entries.firstOrNull { (stub, _) -> key.contains(stub) }?.value
                ?: return AppError(
                    category = ErrorCategory.Unavailable,
                    message = "no response stubbed",
                    detail = key,
                ).asFailure()
            return FakeHandle(response.first, response.second).asSuccess()
        }
    }

    private inner class FakeRepository : WorkspaceRepository {
        val rows = mutableListOf<Workspace>()
        var failCreate = false

        override fun observeWorkspaces(): Flow<List<Workspace>> = emptyFlow()
        override suspend fun find(id: WorkspaceId): AppResult<Workspace> =
            rows.firstOrNull { it.id == id }?.asSuccess()
                ?: AppError(category = ErrorCategory.FileSystem, message = "missing").asFailure()

        override suspend fun create(
            name: String,
            framework: DetectedFramework,
            trust: WorkspaceTrust,
        ): AppResult<Workspace> {
            if (failCreate || name.isBlank()) {
                return AppError(category = ErrorCategory.Validation, message = "bad").asFailure()
            }
            if (rows.any { it.name == name }) {
                return AppError(category = ErrorCategory.Validation, message = "taken").asFailure()
            }
            // A real directory, because ArtisanRunner checks the artisan file on disk rather
            // than trusting anyone's word that this is a Laravel project — including ours.
            // The marker stands in for what `composer create-project` would have written.
            val dir = File(temp.root, name).apply { mkdirs() }
            File(dir, "artisan").writeText("#!/usr/bin/env php\n")
            return Workspace(
                id = WorkspaceId("id-$name"),
                name = name,
                rootPath = dir.absolutePath,
                trust = trust,
                framework = framework,
                createdAtEpochMs = 0,
                lastOpenedAtEpochMs = 0,
            ).also { rows += it }.asSuccess()
        }

        override suspend fun setTrust(id: WorkspaceId, trust: WorkspaceTrust) = Unit.asSuccess()
        override suspend fun markOpened(id: WorkspaceId) = Unit.asSuccess()
        override suspend fun setLastActiveFile(id: WorkspaceId, relativePath: String?) =
            Unit.asSuccess()

        override suspend fun remove(id: WorkspaceId, deleteFiles: Boolean) = Unit.asSuccess()
        override suspend fun rootPathOf(id: WorkspaceId): AppResult<String> =
            rows.firstOrNull { it.id == id }?.rootPath?.asSuccess()
                ?: AppError(category = ErrorCategory.FileSystem, message = "missing").asFailure()
    }

    private class FakeFileSystem(
        private val files: MutableMap<String, String> = mutableMapOf(),
    ) : WorkspaceFileSystem {
        val written = mutableMapOf<String, String>()

        override suspend fun list(relativeDirectory: String) =
            emptyList<WorkspaceFile>().asSuccess()

        override suspend fun readText(relativePath: String): AppResult<String> =
            files[relativePath]?.asSuccess()
                ?: AppError(category = ErrorCategory.FileSystem, message = "missing").asFailure()

        override suspend fun writeText(relativePath: String, content: String): AppResult<Unit> {
            files[relativePath] = content
            written[relativePath] = content
            return Unit.asSuccess()
        }

        override suspend fun createFile(relativePath: String) =
            throw UnsupportedOperationException()

        override suspend fun createDirectory(relativePath: String) =
            throw UnsupportedOperationException()

        override suspend fun delete(relativePath: String) = Unit.asSuccess()
        override suspend fun rename(fromRelativePath: String, toRelativePath: String) =
            Unit.asSuccess()

        override suspend fun exists(relativePath: String): Boolean = files.containsKey(relativePath)

        override suspend fun stat(relativePath: String) =
            throw UnsupportedOperationException()

        override suspend fun sizeOf(relativePath: String) = 0L.asSuccess()
    }

    private class FakeManager(
        private val installedIds: Set<String> = setOf("base", "php", "composer"),
    ) : ToolchainManager {
        override suspend fun inspect(manifestDocument: String): AppResult<ToolchainImport> =
            throw UnsupportedOperationException()

        override suspend fun install(
            importToken: String,
            archiveDocument: String,
            onProgress: (Float) -> Unit,
        ): AppResult<Unit> = throw UnsupportedOperationException()

        override suspend fun installed(): AppResult<List<ToolchainRecord>> =
            installedIds.map {
                ToolchainRecord(it, it, "1", "x86_64", "MIT", "https://example.invalid", true)
            }.asSuccess()
    }

    private val scheduler = StandardTestDispatcher()

    @Before fun setup() {
        Dispatchers.setMain(scheduler)
    }

    @After fun teardown() {
        Dispatchers.resetMain()
    }

    private fun model(
        repository: FakeRepository = FakeRepository(),
        fileSystem: FakeFileSystem = FakeFileSystem(
            mutableMapOf(".env.example" to "APP_KEY=\n"),
        ),
        executor: FakeExecutor = FakeExecutor(
            mapOf(
                "create-project" to (listOf("Installing laravel/laravel") to 0),
                "key:generate" to (listOf("Application key set") to 0),
            ),
        ),
        manager: FakeManager = FakeManager(),
        network: Boolean = true,
        freeBytes: Long = 2L * 1024 * 1024 * 1024,
    ) = LaravelWizardViewModel(
        projectName = "blog",
        workspacesRoot = temp.root.absolutePath,
        repository = repository,
        fileSystemFor = { fileSystem },
        executor = executor,
        toolchain = manager,
        composerPath = "/prefix/bin/composer",
        phpPath = "/prefix/bin/php",
        networkAvailable = { network },
        freeBytes = { freeBytes },
    )

    @Test fun `clean preflight advances to confirm`() = runTest(scheduler) {
        val model = model()
        advanceUntilIdle()

        assertThat(model.state.value.phase).isEqualTo(WizardPhase.CONFIRM)
        assertThat(model.state.value.checks.map { it.passed }.toSet()).containsExactly(true)
        assertThat(model.state.value.error).isNull()
    }

    @Test fun `a missing toolchain blocks with specifics, never auto-running`() =
        runTest(scheduler) {
            val executor = FakeExecutor()
            val model = model(executor = executor, manager = FakeManager(setOf("base")))
            advanceUntilIdle()

            assertThat(model.state.value.phase).isEqualTo(WizardPhase.CHECKS)
            val failed = model.state.value.checks.filter { !it.passed }.map { it.label }
            assertThat(failed).containsExactly("PHP toolchain", "Composer").inOrder()
            // Nothing may run before CONFIRM — not even a version probe.
            assertThat(executor.invocations).isEmpty()
        }

    @Test fun `no network blocks before any download`() = runTest(scheduler) {
        val executor = FakeExecutor()
        val model = model(executor = executor, network = false)
        advanceUntilIdle()

        assertThat(model.state.value.phase).isEqualTo(WizardPhase.CHECKS)
        assertThat(model.state.value.checks.first { it.label == "Network" }.passed).isFalse()
        assertThat(executor.invocations).isEmpty()
    }

    @Test fun `start without confirm does nothing`() = runTest(scheduler) {
        val executor = FakeExecutor()
        val repository = FakeRepository()
        val model = model(executor = executor, repository = repository,
            manager = FakeManager(setOf("base")))
        advanceUntilIdle()

        // Still on CHECKS (toolchain missing): start() must refuse, not run.
        model.start()
        advanceUntilIdle()

        assertThat(executor.invocations).isEmpty()
        assertThat(repository.rows).isEmpty()
    }

    @Test fun `a full run creates, downloads, finishes and opens`() = runTest(scheduler) {
        val repository = FakeRepository()
        val fileSystem = FakeFileSystem(mutableMapOf(".env.example" to "APP_KEY=\n"))
        val executor = FakeExecutor(
            mapOf(
                "create-project" to (listOf("Installing laravel/laravel") to 0),
                "key:generate" to (listOf("Application key set") to 0),
            ),
        )
        val model = model(repository = repository, fileSystem = fileSystem, executor = executor)
        advanceUntilIdle()

        model.start()
        advanceUntilIdle()

        val state = model.state.value
        assertThat(state.phase).isEqualTo(WizardPhase.DONE)
        assertThat(state.workspaceId?.value).isEqualTo("id-blog")
        assertThat(state.error).isNull()
        // The project record is trusted and Laravel-typed, like a locally-authored project.
        val created = repository.rows.single()
        assertThat(created.trust).isEqualTo(WorkspaceTrust.Trusted)
        assertThat(created.framework).isEqualTo(DetectedFramework.Laravel)
        // .env was materialised from the example; the artisan key step ran through PHP.
        assertThat(fileSystem.written[".env"]).isEqualTo("APP_KEY=\n")
        val argv = executor.invocations.map {
            it.executable.substringAfterLast("/") + " " + it.arguments.joinToString(" ")
        }
        assertThat(argv).hasSize(2)
        assertThat(argv[0]).contains("composer create-project laravel/laravel")
        assertThat(argv[0]).contains(created.rootPath)
        assertThat(argv[0]).contains("--no-interaction")
        assertThat(argv[1]).isEqualTo("php artisan key:generate")
        assertThat(executor.invocations.all { !it.shell }).isTrue()
        assertThat(state.log).contains("Installing laravel/laravel")
    }

    @Test fun `a failed download keeps the project with its error`() = runTest(scheduler) {
        val repository = FakeRepository()
        val executor = FakeExecutor(
            mapOf("create-project" to (listOf("Killed") to 137)),
        )
        val model = model(repository = repository, executor = executor)
        advanceUntilIdle()

        model.start()
        advanceUntilIdle()

        val state = model.state.value
        assertThat(state.phase).isEqualTo(WizardPhase.FAILED)
        assertThat(state.error).isNotNull()
        // Kept, not silently discarded — the user deletes and retries from the list.
        assertThat(repository.rows.map { it.name }).containsExactly("blog")
        assertThat(state.workspaceId?.value).isEqualTo("id-blog")
    }

    @Test fun `a duplicate name fails before any download`() = runTest(scheduler) {
        val repository = FakeRepository()
        val first = model(repository = repository)
        advanceUntilIdle()
        first.start()
        advanceUntilIdle()
        assertThat(first.state.value.phase).isEqualTo(WizardPhase.DONE)

        val second = model(repository = repository, executor = FakeExecutor())
        advanceUntilIdle()
        second.start()
        advanceUntilIdle()

        assertThat(second.state.value.phase).isEqualTo(WizardPhase.FAILED)
        assertThat(second.state.value.error?.category).isEqualTo(ErrorCategory.Validation)
    }

    @Test fun `a stuck download can be stopped`() = runTest(scheduler) {
        val gate = CompletableDeferred<Unit>()
        val hanging = FakeHandle(stdout = listOf("Downloading…"), gate = gate)
        val executor = FakeExecutor(hanging = hanging)
        val model = model(executor = executor)
        advanceUntilIdle()

        model.start()
        runCurrent()
        assertThat(model.state.value.running).isTrue()

        model.stop()
        runCurrent()
        assertThat(hanging.signalled).isEqualTo(ProcessSignal.Terminate)

        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(model.state.value.running).isFalse()
    }
}

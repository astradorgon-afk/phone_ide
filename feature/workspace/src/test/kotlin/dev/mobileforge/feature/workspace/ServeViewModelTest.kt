package dev.mobileforge.feature.workspace

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.core.model.WorkspaceTrust
import dev.mobileforge.runtime.api.DevTool
import dev.mobileforge.runtime.api.ManagedProcess
import dev.mobileforge.runtime.api.OutputStream
import dev.mobileforge.runtime.api.ProcessId
import dev.mobileforge.runtime.api.ProcessManager
import dev.mobileforge.runtime.api.ProcessOutput
import dev.mobileforge.runtime.api.ProcessSpec
import dev.mobileforge.runtime.api.ProcessStatus
import dev.mobileforge.runtime.api.RuntimeCapabilityProbe
import dev.mobileforge.runtime.api.ToolStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
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
 * Choosing, starting and stopping a local server.
 *
 * The cases that carry weight: the choice determines which process actually starts, an
 * unavailable dependency is a clear error rather than a dead button, a chosen port that is not a
 * port starts nothing, and a deliberate Stop is never reported to the user as a crash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServeViewModelTest {

    @get:Rule val temp = TemporaryFolder()

    /**
     * A process manager good enough to exercise the view model: it records specs, publishes
     * process status the way the real one does, and lets a test feed output lines.
     */
    private class FakeProcessManager : ProcessManager {
        private val _processes = MutableStateFlow<List<ManagedProcess>>(emptyList())
        override val processes: Flow<List<ManagedProcess>> = _processes

        val started = mutableListOf<ProcessSpec>()
        val stopped = mutableListOf<ProcessId>()
        private val outputs = mutableMapOf<String, MutableSharedFlow<ProcessOutput>>()
        private var counter = 0

        override suspend fun start(spec: ProcessSpec): AppResult<ProcessId> {
            started += spec
            val id = ProcessId("p${counter++}")
            outputs[id.value] = MutableSharedFlow(extraBufferCapacity = 32)
            _processes.update {
                it + ManagedProcess(
                    id = id,
                    spec = spec,
                    pid = 100 + counter,
                    status = ProcessStatus.Running,
                    startedAtEpochMs = counter.toLong(),
                )
            }
            return id.asSuccess()
        }

        override suspend fun stop(id: ProcessId): AppResult<Unit> {
            stopped += id
            // SIGTERM exits 143, which the manager reports as Failed — the exact case the view
            // model must not mislabel as a crash.
            _processes.update { list ->
                list.map {
                    if (it.id == id) {
                        it.copy(status = ProcessStatus.Failed, exitCode = 143, pid = null)
                    } else {
                        it
                    }
                }
            }
            return Unit.asSuccess()
        }

        override suspend fun restart(id: ProcessId): AppResult<ProcessId> = id.asSuccess()

        override suspend fun inspect(id: ProcessId): AppResult<ManagedProcess> =
            _processes.value.first { it.id == id }.asSuccess()

        override fun logs(id: ProcessId): Flow<ProcessOutput> =
            outputs[id.value] ?: emptyFlow()

        fun emit(id: ProcessId, line: String) {
            outputs[id.value]?.tryEmit(ProcessOutput(OutputStream.Stdout, line, 0L))
        }
    }

    private class FakeProbe(private val php: ToolStatus) : RuntimeCapabilityProbe {
        override suspend fun probeAll(): Map<DevTool, ToolStatus> = mapOf(DevTool.Php to php)
        override suspend fun probe(tool: DevTool): ToolStatus = php
    }

    private data class Fixture(
        val vm: ServeViewModel,
        val manager: FakeProcessManager,
        val project: File,
        val work: File,
    )

    private val scheduler = StandardTestDispatcher()

    @Before fun setup() {
        Dispatchers.setMain(scheduler)
    }

    @After fun teardown() {
        Dispatchers.resetMain()
    }

    /**
     * Builds a model whose PHP prefix contains the binaries named in [binaries], so the
     * "is nginx installed" check is exercised against a real directory rather than a stub truth.
     */
    private fun model(
        binaries: List<String> = listOf("php"),
        // Trusted unless a test says otherwise, so the behavioural tests keep their meaning.
        trust: StateFlow<WorkspaceTrust> = MutableStateFlow(WorkspaceTrust.Trusted),
    ): Fixture {
        val bin = temp.newFolder("bin")
        binaries.forEach { File(bin, it).writeText("") }
        val project = temp.newFolder("project")
        File(project, "public").mkdirs()
        val work = File(temp.newFolder("work-parent"), "work").apply { mkdirs() }
        // Android absolute paths use `/`; the JVM's File.absolutePath uses `\` on Windows. The
        // view model derives sibling binaries (php-cgi, nginx) by splitting on `/`, so the probe
        // must hand it an Android-shaped path exactly as the real one would.
        val phpPath = File(bin, "php").absolutePath.replace('\\', '/')
        val php = ToolStatus.Available(version = "PHP 8.5.1", absolutePath = phpPath)
        val manager = FakeProcessManager()
        val vm = ServeViewModel(
            projectRoot = project.absolutePath,
            processManager = manager,
            probe = FakeProbe(php),
            trust = trust,
            workDir = work.absolutePath,
            ioDispatcher = scheduler,
        )
        return Fixture(vm, manager, project, work)
    }

    // --- Workspace trust ---------------------------------------------------------------
    //
    // A dev server runs the project's own PHP, so an untrusted project may not start one. As in
    // the Artisan tests, the assertion that matters is that nothing was launched.

    @Test fun `an untrusted project starts no server and says why`() = runTest(scheduler) {
        val fixture = model(trust = MutableStateFlow(WorkspaceTrust.Untrusted))
        advanceUntilIdle()

        fixture.vm.start()
        advanceUntilIdle()

        assertThat(fixture.manager.started).isEmpty()
        assertThat(fixture.vm.state.value.running).isFalse()
        assertThat(fixture.vm.state.value.error?.category).isEqualTo(ErrorCategory.Security)
    }

    @Test fun `a restricted project hears restricted before it hears nginx is missing`() =
        runTest(scheduler) {
            // Only php is installed, so on a trusted project this would be "nginx is not
            // installed". Telling a restricted project to install it would send the user off to
            // fix something that would then still refuse to run.
            val fixture = model(
                binaries = listOf("php"),
                trust = MutableStateFlow(WorkspaceTrust.Untrusted),
            )
            advanceUntilIdle()
            fixture.vm.selectKind(ServeKind.Nginx)
            runCurrent()

            fixture.vm.start()
            advanceUntilIdle()

            assertThat(fixture.manager.started).isEmpty()
            assertThat(fixture.vm.state.value.error?.category).isEqualTo(ErrorCategory.Security)
        }

    @Test fun `nginx is refused for an untrusted project even when fully installed`() =
        runTest(scheduler) {
            val fixture = model(
                binaries = listOf("php", "nginx", "php-cgi"),
                trust = MutableStateFlow(WorkspaceTrust.Untrusted),
            )
            advanceUntilIdle()
            fixture.vm.selectKind(ServeKind.Nginx)
            runCurrent()

            fixture.vm.start()
            advanceUntilIdle()

            // Neither process: not php-cgi, not nginx, and no config written for them.
            assertThat(fixture.manager.started).isEmpty()
            assertThat(File(fixture.work, "nginx.conf").exists()).isFalse()
        }

    @Test fun `trusting the project lets the server start`() = runTest(scheduler) {
        val trust = MutableStateFlow(WorkspaceTrust.Untrusted)
        val fixture = model(trust = trust)
        advanceUntilIdle()
        fixture.vm.start()
        advanceUntilIdle()
        assertThat(fixture.vm.state.value.error?.category).isEqualTo(ErrorCategory.Security)

        trust.value = WorkspaceTrust.Trusted
        fixture.vm.start()
        advanceUntilIdle()

        assertThat(fixture.manager.started).hasSize(1)
        assertThat(fixture.vm.state.value.running).isTrue()
        assertThat(fixture.vm.state.value.error).isNull()
    }

    @Test fun `stop still works after trust is lost`() = runTest(scheduler) {
        // Never trap a running server behind a gate: the user must always be able to stop it.
        val trust = MutableStateFlow(WorkspaceTrust.Trusted)
        val fixture = model(trust = trust)
        advanceUntilIdle()
        fixture.vm.start()
        advanceUntilIdle()
        assertThat(fixture.vm.state.value.running).isTrue()

        trust.value = WorkspaceTrust.Untrusted
        fixture.vm.stop()
        advanceUntilIdle()

        assertThat(fixture.manager.stopped).hasSize(1)
        assertThat(fixture.vm.state.value.running).isFalse()
    }

    @Test fun `starting the php dev server runs artisan serve on the chosen port`() =
        runTest(scheduler) {
            val fixture = model()
            advanceUntilIdle()

            fixture.vm.setPort("8123")
            runCurrent()
            fixture.vm.start()
            advanceUntilIdle()

            val spec = fixture.manager.started.single()
            assertThat(spec.arguments)
                .containsExactly("artisan", "serve", "--host=127.0.0.1", "--port=8123")
                .inOrder()
            assertThat(fixture.vm.state.value.running).isTrue()
            assertThat(fixture.vm.state.value.url).isEqualTo("http://127.0.0.1:8123")
            assertThat(fixture.vm.state.value.error).isNull()
        }

    @Test fun `nginx without its bundle is refused with an install hint and starts nothing`() =
        runTest(scheduler) {
            // Only php is present, so bin/nginx does not exist.
            val fixture = model(binaries = listOf("php"))
            advanceUntilIdle()

            fixture.vm.selectKind(ServeKind.Nginx)
            runCurrent()

            assertThat(fixture.vm.state.value.nginxMissing).isTrue()
            assertThat(fixture.vm.state.value.canStart).isFalse()

            fixture.vm.start()
            advanceUntilIdle()

            assertThat(fixture.manager.started).isEmpty()
            assertThat(fixture.vm.state.value.error?.category).isEqualTo(ErrorCategory.Unavailable)
            assertThat(fixture.vm.state.value.running).isFalse()
        }

    @Test fun `nginx starts php-cgi behind nginx and writes a config`() = runTest(scheduler) {
        val fixture = model(binaries = listOf("php", "nginx", "php-cgi"))
        advanceUntilIdle()

        fixture.vm.setPort("9000")
        runCurrent()
        fixture.vm.selectKind(ServeKind.Nginx)
        runCurrent()
        assertThat(fixture.vm.state.value.nginxInstalled).isTrue()

        fixture.vm.start()
        advanceUntilIdle()

        assertThat(fixture.manager.started).hasSize(2)
        // php-cgi first: the backend must exist before the front end can forward to it.
        assertThat(fixture.manager.started[0].executable).endsWith("php-cgi")
        assertThat(fixture.manager.started[0].arguments).containsExactly("-b", "127.0.0.1:9001")
        assertThat(fixture.manager.started[1].executable).endsWith("nginx")
        assertThat(fixture.vm.state.value.running).isTrue()
        assertThat(fixture.vm.state.value.url).isEqualTo("http://127.0.0.1:9000")

        val config = File(fixture.work, "nginx.conf")
        assertThat(config.isFile).isTrue()
        assertThat(config.readText()).contains("listen 127.0.0.1:9000;")
    }

    @Test fun `stopping nginx stops the front end before its fastcgi backend`() = runTest(scheduler) {
        val fixture = model(binaries = listOf("php", "nginx", "php-cgi"))
        advanceUntilIdle()
        fixture.vm.selectKind(ServeKind.Nginx)
        runCurrent()
        fixture.vm.start()
        advanceUntilIdle()

        fixture.vm.stop()
        advanceUntilIdle()

        assertThat(fixture.manager.stopped).hasSize(2)
        assertThat(fixture.manager.stopped[0].value).isEqualTo("p1") // nginx (started second)
        assertThat(fixture.manager.stopped[1].value).isEqualTo("p0") // php-cgi
        assertThat(fixture.vm.state.value.running).isFalse()
    }

    @Test fun `a deliberate stop is not reported as a crash`() = runTest(scheduler) {
        val fixture = model()
        advanceUntilIdle()
        fixture.vm.start()
        advanceUntilIdle()
        assertThat(fixture.vm.state.value.running).isTrue()

        fixture.vm.stop()
        advanceUntilIdle()

        assertThat(fixture.vm.state.value.running).isFalse()
        // The fake reports Failed(143) on stop, exactly as a real SIGTERM does.
        assertThat(fixture.vm.state.value.error).isNull()
    }

    @Test fun `an unusable port is a validation error and starts nothing`() = runTest(scheduler) {
        val fixture = model()
        advanceUntilIdle()

        fixture.vm.setPort("80") // below the privileged floor
        runCurrent()
        assertThat(fixture.vm.state.value.portValid).isFalse()

        fixture.vm.start()
        advanceUntilIdle()

        assertThat(fixture.manager.started).isEmpty()
        assertThat(fixture.vm.state.value.error?.category).isEqualTo(ErrorCategory.Validation)
    }

    @Test fun `the port field accepts digits only`() = runTest(scheduler) {
        val fixture = model()
        advanceUntilIdle()

        fixture.vm.setPort("8a0!0b0")
        runCurrent()

        assertThat(fixture.vm.state.value.portText).isEqualTo("8000")
    }

    @Test fun `live output from the server is surfaced while it runs`() = runTest(scheduler) {
        val fixture = model()
        advanceUntilIdle()
        fixture.vm.start()
        advanceUntilIdle()

        fixture.manager.emit(ProcessId("p0"), "Server running on [http://127.0.0.1:8000]")
        advanceUntilIdle()

        assertThat(fixture.vm.state.value.output)
            .contains("Server running on [http://127.0.0.1:8000]")
    }
}

package dev.mobileforge.runtime.exec

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.runtime.api.ProcessSpec
import dev.mobileforge.runtime.api.ProcessStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Lifecycle behaviour, verified against a fake launcher.
 *
 * This is the whole reason [ProcessLauncher] is an interface: none of these scenarios — a
 * process the OS kills, a failed exec, output eviction — can be reproduced reliably against
 * real processes, least of all on a machine that cannot run Android binaries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultProcessManagerTest {

    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }

    // ---------- fakes ----------

    private class FakeProcess(
        stdoutText: String = "",
        stderrText: String = "",
        private val exitCode: Int = 0,
        override val pid: Long? = 4242,
    ) : LaunchedProcess {
        override val stdout: InputStream = ByteArrayInputStream(stdoutText.toByteArray())
        override val stderr: InputStream = ByteArrayInputStream(stderrText.toByteArray())
        val stdinCapture = ByteArrayOutputStream()
        override val stdin: OutputStream get() = stdinCapture

        var destroyed = false
            private set
        var destroyedForcibly = false
            private set

        private var alive = true

        override fun isAlive(): Boolean = alive
        override fun waitFor(): Int {
            alive = false
            return exitCode
        }

        override fun destroy(force: Boolean) {
            destroyed = true
            if (force) destroyedForcibly = true
            alive = false
        }
    }

    private class FakeLauncher(
        private val result: (List<String>) -> AppResult<LaunchedProcess>,
    ) : ProcessLauncher {
        val launches = mutableListOf<List<String>>()
        override fun launch(
            argv: List<String>,
            workingDirectory: String,
            environment: Map<String, String>,
        ): AppResult<LaunchedProcess> {
            launches += argv
            return result(argv)
        }
    }

    private val environment = ExecEnvironment(
        deviceSdkInt = 34,
        appTargetSdk = 35,
        primaryAbi = "arm64-v8a",
        filesDir = "/data/data/dev.mobileforge/files",
        nativeLibraryDir = "/data/app/dev.mobileforge/lib/arm64",
    )

    private fun dynamicElf() = ByteArray(32).also {
        it[0] = 0x7F; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte()
        it[3] = 'F'.code.toByte(); it[16] = 3
    }

    private val php = "/data/data/dev.mobileforge/files/usr/bin/php"

    private fun manager(
        scope: TestScope,
        launcher: ProcessLauncher,
        capacity: Int = 100,
    ) = DefaultProcessManager(
        launcher = launcher,
        commandBuilder = ExecCommandBuilder(
            ExecStrategySelector(environment),
        ) { path, _ -> if (path == php) dynamicElf() else null },
        environmentBuilder = RuntimeEnvironmentBuilder(environment),
        dispatchers = dispatchers,
        logger = NoOpLogger,
        scope = scope,
        clock = { 1_000L },
        bufferCapacity = capacity,
    )

    private fun spec(vararg args: String) = ProcessSpec(
        executable = php,
        arguments = args.toList(),
        workingDirectory = "/",
    )

    // ---------- starting ----------

    @Test
    fun `a started process appears in the list as running`() = runTest(dispatcher) {
        val launcher = FakeLauncher { FakeProcess(stdoutText = "").asSuccess() }
        val manager = manager(this, launcher)

        val id = (manager.start(spec("-v")) as AppResult.Success).value
        val listed = manager.processes.first()

        assertThat(listed).hasSize(1)
        assertThat(listed.single().id).isEqualTo(id)
        assertThat(listed.single().pid).isEqualTo(4242)
    }

    @Test
    fun `the launcher receives the linker-prefixed argv`() = runTest(dispatcher) {
        // Proves the manager goes through ExecCommandBuilder rather than execing directly.
        val launcher = FakeLauncher { FakeProcess().asSuccess() }
        manager(this, launcher).start(spec("-v"))
        advanceUntilIdle()

        assertThat(launcher.launches.single())
            .containsExactly("/system/bin/linker64", php, "-v").inOrder()
    }

    @Test
    fun `a failed launch registers no process`() = runTest(dispatcher) {
        // A process that never started is not a process; a phantom row would be worse than
        // the error.
        val launcher = FakeLauncher {
            AppError(
                category = ErrorCategory.Process,
                message = "boom",
            ).asFailure()
        }
        val manager = manager(this, launcher)

        val result = manager.start(spec())

        assertThat(result.isSuccess).isFalse()
        assertThat(manager.processes.first()).isEmpty()
    }

    @Test
    fun `starting a missing program fails before reaching the launcher`() = runTest(dispatcher) {
        val launcher = FakeLauncher { FakeProcess().asSuccess() }
        val manager = manager(this, launcher)

        val result = manager.start(
            ProcessSpec(executable = "/nope/missing", workingDirectory = "/"),
        )

        assertThat(result.isSuccess).isFalse()
        assertThat(launcher.launches).isEmpty()
    }

    // ---------- output ----------

    @Test
    fun `stdout is captured into the buffer`() = runTest(dispatcher) {
        val launcher = FakeLauncher { FakeProcess(stdoutText = "PHP 8.3.0\nbuilt\n").asSuccess() }
        val manager = manager(this, launcher)

        val id = (manager.start(spec()) as AppResult.Success).value
        advanceUntilIdle()

        assertThat(manager.bufferedOutput(id).map { it.line })
            .containsExactly("PHP 8.3.0", "built").inOrder()
    }

    @Test
    fun `stderr is captured and tagged separately`() = runTest(dispatcher) {
        val launcher = FakeLauncher { FakeProcess(stderrText = "warning\n").asSuccess() }
        val manager = manager(this, launcher)

        val id = (manager.start(spec()) as AppResult.Success).value
        advanceUntilIdle()

        val err = manager.bufferedOutput(id).single()
        assertThat(err.stream).isEqualTo(dev.mobileforge.runtime.api.OutputStream.Stderr)
        assertThat(err.line).isEqualTo("warning")
    }

    @Test
    fun `output beyond capacity is evicted and counted`() = runTest(dispatcher) {
        val noisy = (1..50).joinToString("\n") { "line $it" } + "\n"
        val launcher = FakeLauncher { FakeProcess(stdoutText = noisy).asSuccess() }
        val manager = manager(this, launcher, capacity = 10)

        val id = (manager.start(spec()) as AppResult.Success).value
        advanceUntilIdle()

        assertThat(manager.bufferedOutput(id)).hasSize(10)
        assertThat(manager.droppedLineCount(id)).isEqualTo(40)
        assertThat(manager.bufferedOutput(id).last().line).isEqualTo("line 50")
    }

    // ---------- exit classification ----------

    @Test
    fun `a clean exit is reported as exited`() = runTest(dispatcher) {
        val launcher = FakeLauncher { FakeProcess(exitCode = 0).asSuccess() }
        val manager = manager(this, launcher)

        val id = (manager.start(spec()) as AppResult.Success).value
        advanceUntilIdle()

        val process = (manager.inspect(id) as AppResult.Success).value
        assertThat(process.status).isEqualTo(ProcessStatus.Exited)
        assertThat(process.exitCode).isEqualTo(0)
    }

    @Test
    fun `a non-zero exit is reported as failed`() = runTest(dispatcher) {
        val launcher = FakeLauncher { FakeProcess(exitCode = 1).asSuccess() }
        val manager = manager(this, launcher)

        val id = (manager.start(spec()) as AppResult.Success).value
        advanceUntilIdle()

        assertThat((manager.inspect(id) as AppResult.Success).value.status)
            .isEqualTo(ProcessStatus.Failed)
    }

    @Test
    fun `a SIGKILL exit is reported as killed by the system not as a crash`() =
        runTest(dispatcher) {
            // RISK-002: Android reclaiming a dev server must not look like the user's build
            // failing. 137 = 128 + SIGKILL.
            val launcher = FakeLauncher { FakeProcess(exitCode = 137).asSuccess() }
            val manager = manager(this, launcher)

            val id = (manager.start(spec()) as AppResult.Success).value
            advanceUntilIdle()

            assertThat((manager.inspect(id) as AppResult.Success).value.status)
                .isEqualTo(ProcessStatus.KilledBySystem)
        }

    @Test
    fun `pid is cleared once the process is gone`() = runTest(dispatcher) {
        val launcher = FakeLauncher { FakeProcess(exitCode = 0).asSuccess() }
        val manager = manager(this, launcher)

        val id = (manager.start(spec()) as AppResult.Success).value
        advanceUntilIdle()

        assertThat((manager.inspect(id) as AppResult.Success).value.pid).isNull()
    }

    // ---------- stopping and bookkeeping ----------

    @Test
    fun `stop destroys the process`() = runTest(dispatcher) {
        val fake = FakeProcess()
        val manager = manager(this, FakeLauncher { fake.asSuccess() })

        val id = (manager.start(spec()) as AppResult.Success).value
        manager.stop(id)
        advanceUntilIdle()

        assertThat(fake.destroyed).isTrue()
    }

    @Test
    fun `forget refuses while the process is running`() = runTest(dispatcher) {
        // Removing a live process from the list would orphan it, still holding its port.
        val manager = manager(this, FakeLauncher { FakeProcess().asSuccess() })
        val id = (manager.start(spec()) as AppResult.Success).value

        val result = manager.forget(id)

        assertThat(result.isSuccess).isFalse()
        assertThat(result.errorOrNull()!!.recovery).contains("Stop the process")
    }

    @Test
    fun `forget removes a finished process`() = runTest(dispatcher) {
        val manager = manager(this, FakeLauncher { FakeProcess(exitCode = 0).asSuccess() })
        val id = (manager.start(spec()) as AppResult.Success).value
        advanceUntilIdle()

        assertThat(manager.forget(id).isSuccess).isTrue()
        assertThat(manager.processes.first()).isEmpty()
    }

    @Test
    fun `inspecting an unknown process reports it is untracked`() = runTest(dispatcher) {
        val manager = manager(this, FakeLauncher { FakeProcess().asSuccess() })

        val result = manager.inspect(dev.mobileforge.runtime.api.ProcessId("nope"))

        assertThat(result.isSuccess).isFalse()
        assertThat(result.errorOrNull()!!.message).contains("no longer being tracked")
    }

    @Test
    fun `stdin is forwarded to the process`() = runTest(dispatcher) {
        val fake = FakeProcess()
        val manager = manager(this, FakeLauncher { fake.asSuccess() })

        val id = (manager.start(spec()) as AppResult.Success).value
        manager.writeStdin(id, "yes\n")
        advanceUntilIdle()

        assertThat(fake.stdinCapture.toString()).isEqualTo("yes\n")
    }

    @Test
    fun `restart launches the same command again`() = runTest(dispatcher) {
        val launcher = FakeLauncher { FakeProcess().asSuccess() }
        val manager = manager(this, launcher)

        val id = (manager.start(spec("-v")) as AppResult.Success).value
        manager.restart(id)
        advanceUntilIdle()

        assertThat(launcher.launches).hasSize(2)
        assertThat(launcher.launches[1]).isEqualTo(launcher.launches[0])
    }
}

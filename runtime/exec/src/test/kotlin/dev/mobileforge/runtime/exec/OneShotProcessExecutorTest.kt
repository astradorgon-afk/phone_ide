package dev.mobileforge.runtime.exec

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.runtime.api.OutputStream
import dev.mobileforge.runtime.api.ProcessHandle
import dev.mobileforge.runtime.api.ProcessSignal
import dev.mobileforge.runtime.api.ProcessSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream as JvmOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * One-shot execution against a fake launcher.
 *
 * The cases that matter here are the ones distinguishing this executor from the process
 * manager: the output flow must END (ArtisanRunner collects it with `toList()`), `await()`
 * must report the real exit code, and a runaway process must be stoppable and — with a
 * timeout — stopped automatically. Against real processes none of that is deterministic;
 * against fakes it is exact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OneShotProcessExecutorTest {

    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }

    private class FakeProcess(
        stdoutText: String = "",
        stderrText: String = "",
        private val exitCode: Int = 0,
    ) : LaunchedProcess {
        override val stdout: InputStream = ByteArrayInputStream(stdoutText.toByteArray())
        override val stderr: InputStream = ByteArrayInputStream(stderrText.toByteArray())
        val stdinCapture = ByteArrayOutputStream()
        override val stdin: JvmOutputStream get() = stdinCapture
        override val pid: Long? = 4242

        private var alive = true
        override fun isAlive(): Boolean = alive
        override fun waitFor(): Int {
            alive = false
            return exitCode
        }

        override fun destroy(force: Boolean) {
            alive = false
        }
    }

    /**
     * Never exits on its own and never yields EOF: the stand-in for `queue:work`.
     * Pipes — not sleeps — so `destroy()` is what unblocks the readers, exactly like the OS
     * closing a killed process's file descriptors.
     */
    private class HangingProcess : LaunchedProcess {
        private val pipeOut = PipedOutputStream()
        override val stdout: InputStream = PipedInputStream(pipeOut)
        override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))
        override val stdin: JvmOutputStream = ByteArrayOutputStream()
        override val pid: Long? = 4243

        @Volatile
        private var alive = true
        var destroyed = false
            private set

        override fun isAlive(): Boolean = alive

        override fun waitFor(): Int {
            while (alive) Thread.sleep(5)
            return 143
        }

        override fun destroy(force: Boolean) {
            destroyed = true
            alive = false
            runCatching { pipeOut.close() }
        }
    }

    private class FakeLauncher(
        private val result: (List<String>) -> AppResult<LaunchedProcess>,
    ) : ProcessLauncher {
        val launches = mutableListOf<List<String>>()
        val environments = mutableListOf<Map<String, String>>()
        override fun launch(
            argv: List<String>,
            workingDirectory: String,
            environment: Map<String, String>,
        ): AppResult<LaunchedProcess> {
            launches += argv
            environments += environment
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

    private fun executor(
        launcher: ProcessLauncher,
        dispatchers: AppDispatchers = this.dispatchers,
    ) = OneShotProcessExecutor(
        launcher = launcher,
        commandBuilder = ExecCommandBuilder(
            ExecStrategySelector(environment),
        ) { path, _ -> if (path == php) dynamicElf() else null },
        environmentBuilder = RuntimeEnvironmentBuilder(environment),
        dispatchers = dispatchers,
    )

    private fun spec(vararg args: String, timeoutMs: Long? = null) = ProcessSpec(
        executable = php,
        arguments = args.toList(),
        workingDirectory = "/",
        timeoutMs = timeoutMs,
    )

    private suspend fun runToCompletion(handle: ProcessHandle) =
        handle.output.toList() to (handle.await() as AppResult.Success).value

    // --- The contract that matters --------------------------------------------------

    @Test
    fun `output ends and await reports the real exit code`() = runTest(dispatcher) {
        val launcher = FakeLauncher {
            FakeProcess(stdoutText = "out1\nout2\n", stderrText = "err\n", exitCode = 3)
                .asSuccess()
        }
        val handle = (executor(launcher).start(spec("artisan", "about")) as AppResult.Success).value

        // toList() returning at all is the assertion that matters: against the process
        // manager's infinite flow this line would hang forever.
        val (output, exitCode) = runToCompletion(handle)

        assertThat(exitCode).isEqualTo(3)
        assertThat(output.filter { it.stream == OutputStream.Stdout }.map { it.line })
            .containsExactly("out1", "out2").inOrder()
        assertThat(output.filter { it.stream == OutputStream.Stderr }.map { it.line })
            .containsExactly("err")
    }

    @Test
    fun `a failed launch is returned, not thrown`() = runTest(dispatcher) {
        val launcher = FakeLauncher {
            AppError(category = ErrorCategory.Process, message = "boom").asFailure()
        }

        val result = executor(launcher).start(spec("artisan"))

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
    }

    @Test
    fun `an unreadable executable is refused before launching`() = runTest(dispatcher) {
        val launcher = FakeLauncher { FakeProcess().asSuccess() }

        val result = executor(launcher).start(spec("artisan").copy(executable = "/missing/php"))

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat(launcher.launches).isEmpty()
    }

    @Test
    fun `the launcher receives the linker-prefixed argv`() = runTest(dispatcher) {
        val launcher = FakeLauncher { FakeProcess().asSuccess() }
        val handle = (executor(launcher).start(spec("artisan", "-v")) as AppResult.Success).value
        advanceUntilIdle()

        assertThat(launcher.launches.single())
            .containsExactly("/system/bin/linker64", php, "artisan", "-v").inOrder()
        runToCompletion(handle)
    }

    @Test
    fun `spec environment extras reach the launcher on top of the assembled base`() =
        runTest(dispatcher) {
            val launcher = FakeLauncher { FakeProcess().asSuccess() }
            val handle = (
                executor(launcher).start(
                    spec("artisan").copy(environment = mapOf("APP_ENV" to "testing")),
                ) as AppResult.Success
                ).value
            advanceUntilIdle()

            val env = launcher.environments.single()
            assertThat(env["APP_ENV"]).isEqualTo("testing")
            assertThat(env["PREFIX"]).isEqualTo("/data/data/dev.mobileforge/files/usr")
            runToCompletion(handle)
        }

    @Test
    fun `writeStdin reaches the process`() = runTest(dispatcher) {
        val process = FakeProcess()
        val launcher = FakeLauncher { process.asSuccess() }
        val handle = (executor(launcher).start(spec("artisan")) as AppResult.Success).value

        handle.writeStdin("yes\n")

        assertThat(process.stdinCapture.toString()).isEqualTo("yes\n")
        runToCompletion(handle)
    }

    // --- Stopping: real threads, because blocking IO needs them ---------------------
    //
    // The tests above run everything on the test dispatcher. Stopping only means something
    // against a process that is genuinely stuck in a blocking read, and a blocking read
    // needs a real thread — parking one on the test scheduler would wedge virtual time.

    private val realDispatchers = object : AppDispatchers {
        override val main = Dispatchers.IO
        override val io = Dispatchers.IO
        override val default = Dispatchers.IO
    }

    @Test
    fun `stop destroys a stuck process and the partial run completes`() = runTest {
        val process = HangingProcess()
        val launcher = FakeLauncher { process.asSuccess() }
        val handle =
            (executor(launcher, realDispatchers).start(spec("queue:work")) as AppResult.Success)
                .value

        val collected = async { handle.output.toList() }
        handle.signal(ProcessSignal.Terminate)
        val output = collected.await()
        val exit = handle.await()

        assertThat(process.destroyed).isTrue()
        assertThat(output).isEmpty()
        assertThat((exit as AppResult.Success).value).isEqualTo(143)
    }

    @Test
    fun `a timeout stops the process and await reports it`() = runTest {
        val process = HangingProcess()
        val launcher = FakeLauncher { process.asSuccess() }
        val handle = (
            executor(launcher, realDispatchers).start(spec("queue:work", timeoutMs = 100))
                as AppResult.Success
            ).value

        val output = handle.output.toList()
        val exit = handle.await()

        assertThat(process.destroyed).isTrue()
        assertThat(output).isEmpty()
        val failure = exit as AppResult.Failure
        assertThat(failure.error.category).isEqualTo(ErrorCategory.Process)
        assertThat(failure.error.message).contains("timed out")
    }
}

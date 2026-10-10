package dev.mobileforge.runtime.laravel

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.runtime.api.OutputStream
import dev.mobileforge.runtime.api.ProcessExecutor
import dev.mobileforge.runtime.api.ProcessHandle
import dev.mobileforge.runtime.api.ProcessId
import dev.mobileforge.runtime.api.ProcessOutput
import dev.mobileforge.runtime.api.ProcessSignal
import dev.mobileforge.runtime.api.ProcessSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Running artisan through the executor seam.
 *
 * The cases that carry weight: a destructive command never reaches the executor without an
 * explicit confirmation, nothing is ever run through a shell, and the live handle is exposed
 * for stopping — `queue:work` without a stop button is a trap, not a feature.
 */
class ArtisanRunnerTest {

    @get:Rule val temp = TemporaryFolder()

    private class FakeHandle(
        private val stdout: List<String> = emptyList(),
        private val stderr: List<String> = emptyList(),
        private val exitCode: Int = 0,
    ) : ProcessHandle {
        override val id = ProcessId("fake")
        override val output: Flow<ProcessOutput> = flowOf(
            *stdout.map { ProcessOutput(OutputStream.Stdout, it, 0L) }.toTypedArray(),
            *stderr.map { ProcessOutput(OutputStream.Stderr, it, 0L) }.toTypedArray(),
        )

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
        private val handle: FakeHandle = FakeHandle(),
    ) : ProcessExecutor {
        val invocations = mutableListOf<ProcessSpec>()

        override suspend fun start(spec: ProcessSpec): AppResult<ProcessHandle> {
            invocations += spec
            return handle.asSuccess()
        }
    }

    private fun projectRoot(): String {
        val root = temp.newFolder("app")
        File(root, "artisan").writeText("#!/usr/bin/env php\n")
        return root.absolutePath
    }

    private fun runner(executor: FakeExecutor) =
        ArtisanRunner(executor, phpPath = "/prefix/bin/php")

    @Test
    fun `runs without a shell as an argument vector`() = runTest {
        val executor = FakeExecutor(FakeHandle(stdout = listOf("ok")))
        val command = ArtisanCommand.parse("route:list")!!

        val result = runner(executor).run(projectRoot(), command)

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        assertThat(executor.invocations).hasSize(1)
        val spec = executor.invocations[0]
        assertThat(spec.shell).isFalse()
        assertThat(spec.executable).isEqualTo("/prefix/bin/php")
        assertThat(spec.arguments).containsExactly("artisan", "route:list").inOrder()
    }

    @Test
    fun `an unconfirmed destructive command never reaches the executor`() = runTest {
        val executor = FakeExecutor()
        val command = ArtisanCommand.parse("migrate:fresh")!!
        assertThat(command.requiresConfirmation).isTrue()

        val result = runner(executor).run(projectRoot(), command, confirmed = false)

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat(executor.invocations).isEmpty()
    }

    @Test
    fun `a confirmed destructive command runs`() = runTest {
        val executor = FakeExecutor()
        val command = ArtisanCommand.parse("migrate:fresh")!!

        val result = runner(executor).run(projectRoot(), command, confirmed = true)

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        assertThat(executor.invocations).hasSize(1)
    }

    @Test
    fun `the live handle is exposed for stopping`() = runTest {
        val handle = FakeHandle()
        val executor = FakeExecutor(handle)
        var seen: ProcessHandle? = null

        runner(executor).run(
            projectRoot(),
            ArtisanCommand.parse("about")!!,
            onStarted = { seen = it },
        )

        assertThat(seen).isSameInstanceAs(handle)
    }

    @Test
    fun `stdout and stderr stay apart in the result`() = runTest {
        val executor = FakeExecutor(FakeHandle(stdout = listOf("a"), stderr = listOf("b")))

        val result =
            runner(executor).run(projectRoot(), ArtisanCommand.parse("about")!!) as AppResult.Success

        assertThat(result.value.stdout).containsExactly("a")
        assertThat(result.value.stderr).containsExactly("b")
        assertThat(result.value.succeeded).isTrue()
    }

    @Test
    fun `a missing artisan file is an unavailable project, not a crash`() = runTest {
        val executor = FakeExecutor()
        val emptyRoot = temp.newFolder("empty").absolutePath

        val result =
            runner(executor).run(emptyRoot, ArtisanCommand.parse("about")!!) as AppResult.Failure

        assertThat(result.error.category).isEqualTo(ErrorCategory.Unavailable)
        assertThat(executor.invocations).isEmpty()
    }
}

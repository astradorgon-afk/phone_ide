package dev.mobileforge.runtime.laravel

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
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
 * Composer scaffolding through the executor seam.
 *
 * The refusal cases matter most: a blank package or target must fail before anything is
 * downloaded, and nothing here may ever run through a shell — a package name reaching a
 * shell would turn project creation into command injection.
 */
class ComposerRunnerTest {

    @get:Rule val temp = TemporaryFolder()

    private class FakeHandle(
        private val stdout: List<String> = emptyList(),
        private val exitCode: Int = 0,
    ) : ProcessHandle {
        override val id = ProcessId("fake")
        override val output: Flow<ProcessOutput> = flowOf(
            *stdout.map { ProcessOutput(OutputStream.Stdout, it, 0L) }.toTypedArray(),
        )

        override suspend fun writeStdin(text: String) = Unit.asSuccess()
        override suspend fun signal(signal: ProcessSignal) = Unit.asSuccess()

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

    private fun runner(executor: FakeExecutor) =
        ComposerRunner(executor, composerPath = "/prefix/bin/composer")

    @Test
    fun `runs create-project without a shell as an argument vector`() = runTest {
        val executor = FakeExecutor(FakeHandle(stdout = listOf("Installing laravel/laravel")))
        val target = File(temp.root, "myapp").absolutePath

        val result = runner(executor)
            .createProject("laravel/laravel", target, temp.root.absolutePath)

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        assertThat(executor.invocations).hasSize(1)
        val spec = executor.invocations[0]
        assertThat(spec.shell).isFalse()
        assertThat(spec.executable).isEqualTo("/prefix/bin/composer")
        assertThat(spec.arguments).containsExactly(
            "create-project", "laravel/laravel", target, "--no-interaction", "--no-progress",
        ).inOrder()
    }

    @Test
    fun `a blank package is refused before anything starts`() = runTest {
        val executor = FakeExecutor()

        val result = runner(executor)
            .createProject("  ", "/target", temp.root.absolutePath) as AppResult.Failure

        assertThat(result.error.category).isEqualTo(ErrorCategory.Validation)
        assertThat(executor.invocations).isEmpty()
    }

    @Test
    fun `a blank target is refused before anything starts`() = runTest {
        val executor = FakeExecutor()

        val result = runner(executor)
            .createProject("laravel/laravel", "", temp.root.absolutePath) as AppResult.Failure

        assertThat(result.error.category).isEqualTo(ErrorCategory.Validation)
        assertThat(executor.invocations).isEmpty()
    }

    @Test
    fun `a non-zero exit reports failure with the output kept`() = runTest {
        val executor = FakeExecutor(FakeHandle(stdout = listOf("Killed"), exitCode = 137))

        val result = runner(executor)
            .createProject("laravel/laravel", "/target", temp.root.absolutePath)
            as AppResult.Success

        assertThat(result.value.succeeded).isFalse()
        assertThat(result.value.exitCode).isEqualTo(137)
        assertThat(result.value.stdout).containsExactly("Killed")
    }

    @Test
    fun `the live handle is exposed for stopping a download`() = runTest {
        val handle = FakeHandle()
        val executor = FakeExecutor(handle)
        var seen: ProcessHandle? = null

        runner(executor).createProject(
            "laravel/laravel",
            "/target",
            temp.root.absolutePath,
            onStarted = { seen = it },
        )

        assertThat(seen).isSameInstanceAs(handle)
    }
}

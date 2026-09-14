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
import org.junit.Test

/**
 * Versions read by executing the tools.
 *
 * The point of this class is that a *declared* version is not a fact — `composer.json` says what
 * the project wants, not what is installed. So the tests that matter are the ones proving it
 * never invents an answer: an absent tool, a non-zero exit, or output in an unexpected shape all
 * produce null, and null means "unknown" rather than "not installed".
 */
class LaravelVersionsTest {

    // --- Test doubles -------------------------------------------------------------

    private class FakeHandle(
        private val stdout: List<String>,
        private val exitCode: Int,
    ) : ProcessHandle {
        override val id = ProcessId("fake")
        override val output: Flow<ProcessOutput> = flowOf(
            *stdout.map { ProcessOutput(OutputStream.Stdout, it, 0L) }.toTypedArray(),
        )

        override suspend fun writeStdin(text: String) = Unit.asSuccess()
        override suspend fun signal(signal: ProcessSignal) = Unit.asSuccess()
        override suspend fun await(): AppResult<Int> = exitCode.asSuccess()
    }

    /** Answers each invocation by its argument vector, so the two calls can differ. */
    private class FakeExecutor(
        private val responses: Map<String, Pair<List<String>, Int>>,
    ) : ProcessExecutor {
        val invocations = mutableListOf<ProcessSpec>()

        override suspend fun start(spec: ProcessSpec): AppResult<ProcessHandle> {
            invocations += spec
            val key = spec.arguments.joinToString(" ")
            val response = responses[key]
                ?: return AppError(
                    category = ErrorCategory.Unavailable,
                    message = "not found",
                    detail = key,
                ).asFailure()
            return FakeHandle(response.first, response.second).asSuccess()
        }
    }

    private fun versions(responses: Map<String, Pair<List<String>, Int>>) =
        LaravelVersions(FakeExecutor(responses), phpPath = "/prefix/bin/php")

    // --- Reading ------------------------------------------------------------------

    @Test
    fun `reads php and laravel versions from real output`() = runTest {
        val result = versions(
            mapOf(
                "--version" to (listOf("PHP 8.5.1 (cli) (built: Sep 10 2026)") to 0),
                "artisan --version" to (listOf("Laravel Framework 11.9.2") to 0),
            ),
        ).read("/project")

        assertThat(result.php).isEqualTo("8.5.1")
        assertThat(result.laravel).isEqualTo("11.9.2")
        assertThat(result.anyKnown).isTrue()
    }

    @Test
    fun `runs artisan in the project directory`() = runTest {
        val executor = FakeExecutor(
            mapOf("artisan --version" to (listOf("Laravel Framework 11.9.2") to 0)),
        )
        LaravelVersions(executor, "/prefix/bin/php").read("/project")

        val artisan = executor.invocations.single { it.arguments.contains("artisan") }
        assertThat(artisan.workingDirectory).isEqualTo("/project")
        // No shell: Laravel's own base-path resolution depends on the working directory, and a
        // shell would only add a way for project content to be interpreted.
        assertThat(artisan.shell).isFalse()
    }

    // --- Honest gaps --------------------------------------------------------------

    @Test
    fun `a missing tool reports null, not a guess`() = runTest {
        val result = versions(emptyMap()).read("/project")

        assertThat(result.php).isNull()
        assertThat(result.laravel).isNull()
        assertThat(result.anyKnown).isFalse()
    }

    @Test
    fun `a non-zero exit reports null`() = runTest {
        // artisan exits non-zero when the project is broken. Its stdout may still contain
        // something version-shaped, and trusting that would report a version for a project
        // that cannot actually run.
        val result = versions(
            mapOf("artisan --version" to (listOf("Laravel Framework 11.9.2") to 1)),
        ).read("/project")

        assertThat(result.laravel).isNull()
    }

    @Test
    fun `unrecognised output reports null`() = runTest {
        val result = versions(
            mapOf(
                "--version" to (listOf("something else entirely") to 0),
                "artisan --version" to (listOf("no version here") to 0),
            ),
        ).read("/project")

        assertThat(result.php).isNull()
        assertThat(result.laravel).isNull()
    }

    @Test
    fun `empty output reports null`() = runTest {
        val result = versions(mapOf("--version" to (listOf("") to 0))).read("/project")
        assertThat(result.php).isNull()
    }

    @Test
    fun `one tool answering does not imply the other did`() = runTest {
        // PHP installed, Laravel not — a real state for a plain PHP project, and it must not
        // report a Laravel version by association.
        val result = versions(
            mapOf("--version" to (listOf("PHP 8.5.1 (cli)") to 0)),
        ).read("/project")

        assertThat(result.php).isEqualTo("8.5.1")
        assertThat(result.laravel).isNull()
        assertThat(result.anyKnown).isTrue()
    }

    @Test
    fun `a version buried in surrounding output is still found`() = runTest {
        val result = versions(
            mapOf(
                "artisan --version" to (
                    listOf("Warning: something", "Laravel Framework 10.48.22", "trailing") to 0
                    ),
            ),
        ).read("/project")

        assertThat(result.laravel).isEqualTo("10.48.22")
    }
}

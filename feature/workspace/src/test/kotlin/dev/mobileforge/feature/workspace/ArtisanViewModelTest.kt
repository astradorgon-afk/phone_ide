package dev.mobileforge.feature.workspace

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.runtime.api.DevTool
import dev.mobileforge.runtime.api.OutputStream
import dev.mobileforge.runtime.api.ProcessExecutor
import dev.mobileforge.runtime.api.ProcessHandle
import dev.mobileforge.runtime.api.ProcessId
import dev.mobileforge.runtime.api.ProcessOutput
import dev.mobileforge.runtime.api.ProcessSignal
import dev.mobileforge.runtime.api.ProcessSpec
import dev.mobileforge.runtime.api.RuntimeCapabilityProbe
import dev.mobileforge.runtime.api.ToolStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import org.junit.Test

/**
 * Artisan submission, confirmation gating and stopping, against fakes.
 *
 * The cases that carry weight: a flagged command never reaches the executor unconfirmed, an
 * unparseable line is an error rather than a silent no-op, and a stuck command can be
 * stopped — `queue:work` without Stop is a trap. PHP availability comes from the probe, so a
 * missing toolchain gates submission instead of failing every command mysteriously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtisanViewModelTest {

    private class FakeHandle(
        private val stdout: List<String> = listOf("ok"),
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
            val key = spec.arguments.joinToString(" ")
            val response = responses[key]
                ?: return AppError(
                    category = ErrorCategory.Unavailable,
                    message = "no response stubbed",
                    detail = key,
                ).asFailure()
            return FakeHandle(response.first, response.second).asSuccess()
        }
    }

    private class FakeProbe(private val php: ToolStatus) : RuntimeCapabilityProbe {
        override suspend fun probeAll(): Map<DevTool, ToolStatus> = mapOf(DevTool.Php to php)
        override suspend fun probe(tool: DevTool): ToolStatus = php
    }

    private val scheduler = StandardTestDispatcher()

    @Before fun setup() {
        Dispatchers.setMain(scheduler)
    }

    @After fun teardown() {
        Dispatchers.resetMain()
    }

    private val phpAvailable = ToolStatus.Available(
        version = "PHP 8.5.1 (cli)",
        absolutePath = "/prefix/bin/php",
    )

    private val versionResponses = mapOf(
        "--version" to (listOf("PHP 8.5.1 (cli) (built: Sep 1 2026)") to 0),
        "artisan --version" to (listOf("Laravel Framework 11.9.2") to 0),
    )

    private fun model(
        executor: FakeExecutor = FakeExecutor(versionResponses),
        php: ToolStatus = phpAvailable,
    ) = ArtisanViewModel(
        projectRoot = "/projects/app",
        executor = executor,
        probe = FakeProbe(php),
    )

    @Test fun `init probes PHP and reads executed versions`() = runTest(scheduler) {
        val model = model()
        advanceUntilIdle()

        val php = model.state.value.php
        assertThat(php).isInstanceOf(PhpStatus.Ready::class.java)
        assertThat((php as PhpStatus.Ready).path).isEqualTo("/prefix/bin/php")
        assertThat(model.state.value.versions?.php).isEqualTo("8.5.1")
        assertThat(model.state.value.versions?.laravel).isEqualTo("11.9.2")
        assertThat(model.state.value.error).isNull()
    }

    @Test fun `quick commands mirror the parser's non-destructive set`() = runTest(scheduler) {
        val model = model()
        advanceUntilIdle()

        // Deliberately short and entirely non-destructive: nothing here may ask first.
        assertThat(model.state.value.quickCommands.map { it.name })
            .containsExactly(
                "serve", "route:list", "migrate", "migrate:status", "db:seed", "cache:clear",
                "config:clear", "view:clear", "optimize:clear", "queue:work", "test", "about",
            ).inOrder()
        assertThat(model.state.value.quickCommands.map { it.requiresConfirmation }.toSet())
            .containsExactly(false)
    }

    @Test fun `a plain command runs immediately and shows its result`() = runTest(scheduler) {
        val executor = FakeExecutor(
            versionResponses + ("artisan about" to (listOf("Environment …") to 0)),
        )
        val model = model(executor)
        advanceUntilIdle()

        model.submit("about")
        advanceUntilIdle()

        val result = model.state.value.lastResult
        assertThat(result).isNotNull()
        assertThat(result!!.stdout).containsExactly("Environment …")
        assertThat(result.succeeded).isTrue()
        assertThat(model.state.value.running).isFalse()
        assertThat(model.state.value.error).isNull()
    }

    @Test fun `unparseable input is a validation error and runs nothing`() = runTest(scheduler) {
        val executor = FakeExecutor(versionResponses)
        val model = model(executor)
        advanceUntilIdle()
        val runsBefore = executor.invocations.size

        model.submit("migrate; rm -rf /")
        advanceUntilIdle()

        assertThat(model.state.value.error?.category).isEqualTo(ErrorCategory.Validation)
        assertThat(executor.invocations).hasSize(runsBefore)
        assertThat(model.state.value.lastResult).isNull()
    }

    @Test fun `a destructive command waits for confirmation instead of running`() =
        runTest(scheduler) {
            val executor = FakeExecutor(
                versionResponses + ("artisan migrate:fresh" to (listOf("dropped") to 0)),
            )
            val model = model(executor)
            advanceUntilIdle()
            val runsBefore = executor.invocations.size

            model.submit("migrate:fresh")
            advanceUntilIdle()

            val pending = model.state.value.pendingConfirmation
            assertThat(pending).isNotNull()
            assertThat(pending!!.name).isEqualTo("migrate:fresh")
            assertThat(executor.invocations).hasSize(runsBefore)
            assertThat(model.state.value.lastResult).isNull()
            // The dialog explains why, from the same risk model that flagged it.
            assertThat(model.state.value.confirmationReason).containsMatch("drop")
        }

    @Test fun `confirming runs the pending command`() = runTest(scheduler) {
        val executor = FakeExecutor(
            versionResponses + ("artisan migrate:fresh" to (listOf("dropped") to 0)),
        )
        val model = model(executor)
        advanceUntilIdle()

        model.submit("migrate:fresh")
        advanceUntilIdle()
        model.confirmPending()
        advanceUntilIdle()

        assertThat(model.state.value.pendingConfirmation).isNull()
        assertThat(executor.invocations.map { it.arguments.joinToString(" ") })
            .contains("artisan migrate:fresh")
        assertThat(model.state.value.lastResult?.stdout).containsExactly("dropped")
    }

    @Test fun `dismissing drops the pending command without running`() = runTest(scheduler) {
        val executor = FakeExecutor(versionResponses)
        val model = model(executor)
        advanceUntilIdle()
        val runsBefore = executor.invocations.size

        model.submit("migrate:fresh")
        runCurrent()
        model.dismissPending()
        advanceUntilIdle()

        assertThat(model.state.value.pendingConfirmation).isNull()
        assertThat(executor.invocations).hasSize(runsBefore)
    }

    @Test fun `submit without PHP is an unavailable error`() = runTest(scheduler) {
        val executor = FakeExecutor()
        val model = model(executor, ToolStatus.NotInstalled)
        advanceUntilIdle()

        model.submit("about")
        advanceUntilIdle()

        assertThat(model.state.value.error?.category).isEqualTo(ErrorCategory.Unavailable)
        assertThat(executor.invocations).isEmpty()
    }

    @Test fun `a stuck command can be stopped`() = runTest(scheduler) {
        val gate = CompletableDeferred<Unit>()
        val hanging = FakeHandle(stdout = listOf("working…"), exitCode = 143, gate = gate)
        val executor = FakeExecutor(versionResponses, hanging = hanging)
        val model = model(executor)
        advanceUntilIdle()

        model.submit("queue:work")
        runCurrent()
        assertThat(model.state.value.running).isTrue()

        model.stop()
        runCurrent()
        assertThat(hanging.signalled).isEqualTo(ProcessSignal.Terminate)

        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(model.state.value.running).isFalse()
        assertThat(model.state.value.lastResult?.exitCode).isEqualTo(143)
    }
}

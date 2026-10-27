package dev.mobileforge.feature.workspace

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppError
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.asFailure
import dev.mobileforge.core.common.asSuccess
import dev.mobileforge.core.model.WorkspaceTrust
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * Artisan submission, confirmation gating and stopping, against fakes.
 *
 * The cases that carry weight: a flagged command never reaches the executor unconfirmed, an
 * unparseable line is an error rather than a silent no-op, and a stuck command can be
 * stopped — `queue:work` without Stop is a trap. PHP availability comes from the probe, so a
 * missing toolchain gates submission instead of failing every command mysteriously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtisanViewModelTest {

    // The runner refuses a project without an artisan file before touching the executor,
    // so the fake project root has to be a real directory with one — exactly like a
    // Laravel project on disk.
    @get:Rule val temp = TemporaryFolder()

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

    // Trusted unless a test says otherwise, so the behavioural tests below keep their meaning;
    // the trust tests pass their own flow so they can change it mid-test.
    private fun model(
        executor: ProcessExecutor = FakeExecutor(versionResponses),
        php: ToolStatus = phpAvailable,
        trust: StateFlow<WorkspaceTrust> = MutableStateFlow(WorkspaceTrust.Trusted),
    ): ArtisanViewModel {
        val root = temp.newFolder("app")
        File(root, "artisan").writeText("#!/usr/bin/env php\n")
        return ArtisanViewModel(
            projectRoot = root.absolutePath,
            executor = executor,
            probe = FakeProbe(php),
            trust = trust,
        )
    }

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

    @Test fun `a long-running command streams output while it is still running`() =
        runTest(scheduler) {
            // A handle that produces its banner immediately but does not exit until told to —
            // which is exactly a dev server, and exactly the case the old code showed nothing
            // for until the user stopped it.
            val exit = CompletableDeferred<Unit>()
            val server = object : ProcessHandle {
                override val id = ProcessId("serve")
                override val output: Flow<ProcessOutput> = flowOf(
                    ProcessOutput(OutputStream.Stdout, "Server running on http://127.0.0.1:8000", 0L),
                )

                override suspend fun writeStdin(text: String) = Unit.asSuccess()
                override suspend fun signal(signal: ProcessSignal) = Unit.asSuccess()
                override suspend fun await(): AppResult<Int> {
                    exit.await()
                    return 0.asSuccess()
                }
            }
            val executor = object : ProcessExecutor {
                override suspend fun start(spec: ProcessSpec): AppResult<ProcessHandle> {
                    val key = spec.arguments.joinToString(" ")
                    if (key == "artisan serve") return server.asSuccess()
                    val response = versionResponses[key]
                        ?: return AppError(ErrorCategory.Unavailable, "no response stubbed", key)
                            .asFailure()
                    return FakeHandle(response.first, response.second).asSuccess()
                }
            }
            val model = model(executor)
            advanceUntilIdle()

            model.submit("serve")
            runCurrent()

            assertThat(model.state.value.running).isTrue()
            assertThat(model.state.value.liveOutput)
                .contains("Server running on http://127.0.0.1:8000")
            // Not finished, so there is no result yet — the banner came from the live stream.
            assertThat(model.state.value.lastResult).isNull()

            exit.complete(Unit)
            advanceUntilIdle()

            assertThat(model.state.value.running).isFalse()
            assertThat(model.state.value.liveOutput).isEmpty()
            assertThat(model.state.value.lastResult?.stdout)
                .containsExactly("Server running on http://127.0.0.1:8000")
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

    // --- Workspace trust ---------------------------------------------------------------
    //
    // An untrusted project is one the user has not vouched for, so nothing may run in it. The
    // assertions that carry weight are all "the executor was never called": a refusal that
    // still launched the process would be worse than no refusal at all.

    @Test fun `an untrusted project runs no command and says why`() = runTest(scheduler) {
        val executor = FakeExecutor(versionResponses + ("artisan about" to (listOf("Env") to 0)))
        val model = model(executor, trust = MutableStateFlow(WorkspaceTrust.Untrusted))
        advanceUntilIdle()
        val runsBefore = executor.invocations.size

        model.submit("about")
        advanceUntilIdle()

        assertThat(executor.invocations).hasSize(runsBefore)
        assertThat(model.state.value.error?.category).isEqualTo(ErrorCategory.Security)
        assertThat(model.state.value.lastResult).isNull()
        assertThat(model.state.value.running).isFalse()
    }

    @Test fun `an untrusted project is not asked to confirm a command that cannot run`() =
        runTest(scheduler) {
            val executor = FakeExecutor(versionResponses)
            val model = model(executor, trust = MutableStateFlow(WorkspaceTrust.Untrusted))
            advanceUntilIdle()

            model.submit("migrate:fresh")
            advanceUntilIdle()

            assertThat(model.state.value.pendingConfirmation).isNull()
            assertThat(model.state.value.error?.category).isEqualTo(ErrorCategory.Security)
        }

    @Test fun `a restricted project hears restricted even for input that does not parse`() =
        runTest(scheduler) {
            val model = model(trust = MutableStateFlow(WorkspaceTrust.Untrusted))
            advanceUntilIdle()

            model.submit("migrate; rm -rf /")
            advanceUntilIdle()

            // Not Validation: the trust check comes first, so the answer is the true reason.
            assertThat(model.state.value.error?.category).isEqualTo(ErrorCategory.Security)
        }

    @Test fun `opening an untrusted project does not run the project's code`() =
        runTest(scheduler) {
            // `php artisan --version` boots the application, so it runs the project's service
            // providers. Reading versions is therefore execution, and must wait for trust.
            val executor = FakeExecutor(versionResponses)
            val model = model(executor, trust = MutableStateFlow(WorkspaceTrust.Untrusted))
            advanceUntilIdle()

            assertThat(executor.invocations).isEmpty()
            assertThat(model.state.value.versions).isNull()
            // PHP itself was still probed: that touches the toolchain, not the project.
            assertThat(model.state.value.php).isInstanceOf(PhpStatus.Ready::class.java)
        }

    @Test fun `trusting the project reads the versions that were withheld`() =
        runTest(scheduler) {
            val executor = FakeExecutor(versionResponses)
            val trust = MutableStateFlow(WorkspaceTrust.Untrusted)
            val model = model(executor, trust = trust)
            advanceUntilIdle()
            assertThat(model.state.value.versions).isNull()

            trust.value = WorkspaceTrust.Trusted
            advanceUntilIdle()

            assertThat(model.state.value.versions?.laravel).isEqualTo("11.9.2")
            assertThat(executor.invocations.map { it.arguments.joinToString(" ") })
                .contains("artisan --version")
        }

    @Test fun `trusting the project lets commands run and clears the refusal`() =
        runTest(scheduler) {
            val executor = FakeExecutor(versionResponses + ("artisan about" to (listOf("Env") to 0)))
            val trust = MutableStateFlow(WorkspaceTrust.Untrusted)
            val model = model(executor, trust = trust)
            advanceUntilIdle()
            model.submit("about")
            advanceUntilIdle()
            assertThat(model.state.value.error?.category).isEqualTo(ErrorCategory.Security)

            trust.value = WorkspaceTrust.Trusted
            advanceUntilIdle()
            model.submit("about")
            advanceUntilIdle()

            assertThat(model.state.value.error).isNull()
            assertThat(model.state.value.lastResult?.stdout).containsExactly("Env")
        }

    @Test fun `a confirmation answered after trust is lost does not run`() = runTest(scheduler) {
        // The dialog can sit open while trust changes; the gate has to be at the point a process
        // starts, not only at the point the command is typed.
        val executor = FakeExecutor(
            versionResponses + ("artisan migrate:fresh" to (listOf("dropped") to 0)),
        )
        val trust = MutableStateFlow(WorkspaceTrust.Trusted)
        val model = model(executor, trust = trust)
        advanceUntilIdle()
        model.submit("migrate:fresh")
        advanceUntilIdle()
        assertThat(model.state.value.pendingConfirmation).isNotNull()
        val runsBefore = executor.invocations.size

        trust.value = WorkspaceTrust.Untrusted
        model.confirmPending()
        advanceUntilIdle()

        assertThat(executor.invocations).hasSize(runsBefore)
        assertThat(model.state.value.lastResult).isNull()
        assertThat(model.state.value.error?.category).isEqualTo(ErrorCategory.Security)
    }
}

package dev.mobileforge.feature.terminal

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.ErrorCategory
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.core.model.WorkspaceTrust
import dev.mobileforge.runtime.pty.PtyLauncher
import dev.mobileforge.runtime.pty.PtyProcess
import dev.mobileforge.runtime.pty.TerminalSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Session bookkeeping for the terminal.
 *
 * A dev server occupies its terminal for as long as it runs, so a single-session terminal forces
 * the user to stop the server to type anything. These tests cover the lifecycle rules that make
 * several sessions safe: the cap, the active selection, and what closing does to the tab list.
 *
 * The launcher here always refuses to open, which is deliberate: it makes the tests independent
 * of whether a PTY can actually be created on the build machine (it cannot — `PtyProcess` needs
 * Android). So what is under test is bookkeeping only. Two things these tests deliberately do
 * NOT claim, because a JVM test cannot observe them:
 *
 *  - that closing actually releases the PTY (`TerminalSession` is not an interface, so there is
 *    nothing here to spy on);
 *  - that two live sessions are genuinely independent.
 *
 * Both are covered on device by `TerminalSessionsVerificationTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionsTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        scope = CoroutineScope(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starting opens exactly one session`() {
        val model = viewModel()
        model.start()

        assertThat(model.tabs.value).hasSize(1)
        assertThat(model.tabs.value.single().isActive).isTrue()
    }

    @Test
    fun `starting twice does not open a second session`() {
        val model = viewModel()
        model.start()
        model.start()

        assertThat(model.tabs.value).hasSize(1)
    }

    @Test
    fun `opening a session makes it active and labels tabs in order`() {
        val model = viewModel()
        model.start()
        model.openSession()

        assertThat(model.tabs.value.map { it.label }).containsExactly("1", "2").inOrder()
        assertThat(model.tabs.value.last().isActive).isTrue()
        assertThat(model.tabs.value.first().isActive).isFalse()
    }

    @Test
    fun `sessions are capped`() {
        val model = viewModel()
        model.start()
        repeat(10) { model.openSession() }

        assertThat(model.tabs.value).hasSize(MAX_SESSIONS)
        assertThat(model.canOpenSession.value).isFalse()
    }

    @Test
    fun `selecting a session changes the active tab`() {
        val model = viewModel()
        model.start()
        model.openSession()
        val first = model.tabs.value.first().id

        model.selectSession(first)

        assertThat(model.tabs.value.first { it.id == first }.isActive).isTrue()
    }

    @Test
    fun `closing a session removes its tab`() {
        val model = viewModel()
        model.start()
        model.openSession()
        val closing = model.tabs.value.last().id

        model.closeSession(closing)

        assertThat(model.tabs.value).hasSize(1)
        assertThat(model.tabs.value.map { it.id }).doesNotContain(closing)
    }

    @Test
    fun `closing the active session activates another`() {
        val model = viewModel()
        model.start()
        model.openSession()
        val active = model.tabs.value.single { it.isActive }.id

        model.closeSession(active)

        assertThat(model.tabs.value.count { it.isActive }).isEqualTo(1)
        assertThat(model.tabs.value.single().id).isNotEqualTo(active)
    }

    @Test
    fun `closing the last session opens a fresh one rather than leaving an empty pane`() {
        val model = viewModel()
        model.start()
        val only = model.tabs.value.single().id

        model.closeSession(only)

        assertThat(model.tabs.value).hasSize(1)
        assertThat(model.tabs.value.single().id).isNotEqualTo(only)
        assertThat(model.canOpenSession.value).isTrue()
    }

    @Test
    fun `closing an unknown session is ignored`() {
        val model = viewModel()
        model.start()

        model.closeSession(9999)

        assertThat(model.tabs.value).hasSize(1)
    }

    // --- Workspace trust ----------------------------------------------------------
    //
    // A shell is the most direct way to run anything, so a project that has not been trusted gets
    // none. `sessionsCreated` counts calls to `newSession`: on refusal it must stay at zero, since
    // a PTY that was opened and then hidden is still a process running in the project.

    @Test
    fun `an untrusted project opens no shell and says why`() {
        val model = viewModel(MutableStateFlow(WorkspaceTrust.Untrusted))

        model.start()

        assertThat(model.tabs.value).isEmpty()
        assertThat(sessionsCreated).isEqualTo(0)
        assertThat(model.refusal.value?.category).isEqualTo(ErrorCategory.Security)
    }

    @Test
    fun `an untrusted project cannot open an additional session either`() {
        val model = viewModel(MutableStateFlow(WorkspaceTrust.Untrusted))

        model.openSession()
        model.openSession()

        assertThat(model.tabs.value).isEmpty()
        assertThat(sessionsCreated).isEqualTo(0)
    }

    @Test
    fun `trusting the project lets the shell open and clears the refusal`() {
        val trust = MutableStateFlow(WorkspaceTrust.Untrusted)
        val model = viewModel(trust)
        model.start()
        assertThat(model.refusal.value).isNotNull()

        trust.value = WorkspaceTrust.Trusted
        model.start()

        assertThat(model.tabs.value).hasSize(1)
        assertThat(model.refusal.value).isNull()
    }

    @Test
    fun `a shell requested before trust arrives opens by itself once it does`() {
        // The pane asks once, when it appears. If that lands a moment before trust is known to
        // be granted, the user must not be left with a blank terminal on a trusted project.
        val trust = MutableStateFlow(WorkspaceTrust.Untrusted)
        val model = viewModel(trust)
        model.start()
        assertThat(model.tabs.value).isEmpty()

        trust.value = WorkspaceTrust.Trusted

        assertThat(model.tabs.value).hasSize(1)
        assertThat(model.refusal.value).isNull()
        assertThat(sessionsCreated).isEqualTo(1)
    }

    @Test
    fun `granting trust opens no shell that nobody asked for`() {
        val trust = MutableStateFlow(WorkspaceTrust.Untrusted)
        val model = viewModel(trust)

        trust.value = WorkspaceTrust.Trusted

        assertThat(model.tabs.value).isEmpty()
        assertThat(sessionsCreated).isEqualTo(0)
    }

    @Test
    fun `losing trust stops the replacement session that closing the last one would open`() {
        val trust = MutableStateFlow(WorkspaceTrust.Trusted)
        val model = viewModel(trust)
        model.start()
        val only = model.tabs.value.single().id

        trust.value = WorkspaceTrust.Untrusted
        model.closeSession(only)

        // Normally closing the last session opens a fresh one; that path must not slip past.
        assertThat(model.tabs.value).isEmpty()
        assertThat(sessionsCreated).isEqualTo(1)
        assertThat(model.refusal.value?.category).isEqualTo(ErrorCategory.Security)
    }

    // --- Helpers ------------------------------------------------------------------

    private var sessionsCreated = 0

    // Trusted by default so the bookkeeping tests above are about bookkeeping.
    private fun viewModel(
        trust: StateFlow<WorkspaceTrust> = MutableStateFlow(WorkspaceTrust.Trusted),
    ) = TerminalViewModel(
        newSession = {
            sessionsCreated++
            TerminalSession(
                launcher = RefusingLauncher,
                dispatchers = TestDispatchers(dispatcher),
                logger = NoOpLogger,
                scope = scope,
            )
        },
        shellCommand = ShellCommand(
            argv = listOf("/system/bin/sh"),
            workingDirectory = "/tmp",
            environment = emptyMap(),
            displayName = "sh",
        ),
        trust = trust,
    )

    private object RefusingLauncher : PtyLauncher {
        override val isSupported: Boolean = false
        override fun open(
            argv: List<String>,
            workingDirectory: String,
            environment: Map<String, String>,
            rows: Int,
            cols: Int,
        ): PtyProcess = throw IOException("no PTY on the build machine")
    }

    private class TestDispatchers(private val dispatcher: CoroutineDispatcher) : AppDispatchers {
        override val main: CoroutineDispatcher get() = dispatcher
        override val io: CoroutineDispatcher get() = dispatcher
        override val default: CoroutineDispatcher get() = dispatcher
    }

    private companion object {
        const val MAX_SESSIONS = 4
    }
}

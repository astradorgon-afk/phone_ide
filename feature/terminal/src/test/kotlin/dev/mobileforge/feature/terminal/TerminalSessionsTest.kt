package dev.mobileforge.feature.terminal

import com.google.common.truth.Truth.assertThat
import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.runtime.pty.PtyLauncher
import dev.mobileforge.runtime.pty.PtyProcess
import dev.mobileforge.runtime.pty.TerminalSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    // --- Helpers ------------------------------------------------------------------

    private fun viewModel() = TerminalViewModel(
        newSession = {
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

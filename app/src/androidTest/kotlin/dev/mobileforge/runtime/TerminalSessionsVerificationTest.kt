package dev.mobileforge.runtime

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobileforge.core.common.DefaultAppDispatchers
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.runtime.pty.NativePtyLauncher
import dev.mobileforge.runtime.pty.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VERIFIES THAT TWO TERMINAL SESSIONS ARE GENUINELY SEPARATE PROCESSES.
 *
 * `TerminalSessionsTest` covers the tab bookkeeping against a launcher that refuses to open,
 * which is all a JVM test can do — `PtyProcess` needs Android. That leaves the two claims that
 * actually matter unproven, and they are proven here instead:
 *
 *  - each session is its own shell, with its own state, so output in one never appears in the
 *    other and a working directory changed in one does not move the other;
 *  - closing a session ends its process rather than leaking it, because a leaked PTY is a
 *    leaked child and Android eventually kills the app rather than the stray process.
 */
@RunWith(AndroidJUnit4::class)
class TerminalSessionsVerificationTest {

    private val launcher = NativePtyLauncher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = mutableListOf<TerminalSession>()
    private lateinit var homeDir: String

    @Before
    fun setUp() {
        homeDir = InstrumentationRegistry.getInstrumentation()
            .targetContext.filesDir.absolutePath
        assumeTrue("Native PTY library not available for this ABI", launcher.isSupported)
    }

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
        sessions.clear()
        scope.cancel()
    }

    @Test
    fun twoSessionsHaveIndependentShellsAndState() {
        val first = newSession()
        val second = newSession()

        start(first)
        start(second)

        // A marker echoed in one session must not appear in the other.
        send(first, "echo FIRST_MARKER\n")
        send(second, "echo SECOND_MARKER\n")
        settle()

        val firstText = screenText(first)
        val secondText = screenText(second)
        Log.i(TAG, "first id=${first.id} second id=${second.id}")

        assertTrue("First session lost its own output", firstText.contains("FIRST_MARKER"))
        assertTrue("Second session lost its own output", secondText.contains("SECOND_MARKER"))
        assertTrue(
            "The second session's output leaked into the first",
            !firstText.contains("SECOND_MARKER"),
        )
        assertTrue(
            "The first session's output leaked into the second",
            !secondText.contains("FIRST_MARKER"),
        )
    }

    @Test
    fun aDirectoryChangeInOneSessionDoesNotMoveTheOther() {
        val first = newSession()
        val second = newSession()
        start(first)
        start(second)

        send(first, "cd /system && pwd\n")
        settle()
        send(second, "pwd\n")
        settle()

        val secondText = screenText(second)
        assertTrue(
            "The first session did not change directory",
            screenText(first).contains("/system"),
        )
        // Deliberately not compared against `homeDir`: `filesDir` reports /data/user/0/... while
        // the shell's `pwd` resolves the symlink to /data/data/..., so the two never match even
        // when the behaviour is correct.
        assertTrue(
            "The second session followed the first's directory change. Screen was: $secondText",
            !secondText.contains("/system"),
        )
        assertTrue(
            "The second session did not report its own working directory. Screen was: " +
                secondText,
            secondText.contains("/files"),
        )
    }

    @Test
    fun closingASessionEndsItsProcess() {
        val session = newSession()
        start(session)
        settle()

        assertTrue("The session never started", session.state.value.isRunning)

        session.close()
        // Closing the PTY sends the child EOF and releases the descriptor; the session must
        // stop reporting itself as running rather than leaving a stray process behind.
        val stopped = waitFor { !session.state.value.isRunning }

        assertTrue("The session still reports itself as running after close()", stopped)
    }

    // --- Helpers ------------------------------------------------------------------

    private fun newSession(): TerminalSession = TerminalSession(
        launcher = launcher,
        dispatchers = DefaultAppDispatchers,
        logger = NoOpLogger,
        scope = scope,
    ).also { sessions += it }

    private fun start(session: TerminalSession) = kotlinx.coroutines.runBlocking {
        session.start(
            argv = listOf("/system/bin/sh"),
            workingDirectory = homeDir,
            environment = mapOf(
                "HOME" to homeDir,
                "PATH" to "/system/bin:/system/xbin",
                "TERM" to "dumb",
                "PS1" to "$ ",
            ),
        )
    }

    private fun send(session: TerminalSession, text: String) = kotlinx.coroutines.runBlocking {
        session.send(text)
    }

    private fun screenText(session: TerminalSession): String =
        session.state.value.lines.joinToString("\n") { row ->
            row.joinToString("") { it.text }
        }

    private fun settle() = Thread.sleep(SETTLE_MS)

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + SETTLE_MS * 4
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private companion object {
        const val TAG = "MF.SessionsVerify"
        const val SETTLE_MS = 1_500L
    }
}

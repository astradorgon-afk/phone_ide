package dev.mobileforge.runtime

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobileforge.runtime.pty.NativePtyLauncher
import dev.mobileforge.runtime.pty.PtyProcess
import dev.mobileforge.runtime.pty.PtySignal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * ON-DEVICE VERIFICATION OF THE PTY.
 *
 * The entire justification for writing native code in Phase 2b is that pipes are not a
 * terminal. This test proves the difference is real on an actual device rather than asserted
 * in an ADR:
 *
 *   [shellSeesATerminal]   `[ -t 0 ]` succeeds  — the thing pipes can never do
 *   [terminalEchoesInput]  line discipline is active
 *   [ctrlCInterrupts]      Ctrl+C reaches the foreground process GROUP
 *   [resizeIsAccepted]     TIOCSWINSZ is honoured, so ncurses/readline can lay out
 *
 * It uses `/system/bin/sh`, which exists on every Android device, so no bootstrapped toolchain
 * is required. If [shellSeesATerminal] ever fails, the terminal is a pipe wearing a costume and
 * the Phase 2b claim is false.
 *
 * Run with:  ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class PtyVerificationTest {

    private val launcher = NativePtyLauncher()
    private var process: PtyProcess? = null

    private lateinit var homeDir: String

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        homeDir = context.filesDir.absolutePath

        // A device whose ABI we did not build for is a real, reportable state — not a failure
        // of this test. Skipping is honest; passing would be a lie.
        assumeTrue(
            "Native PTY library not available for this ABI",
            launcher.isSupported,
        )
        Log.i(TAG, "PTY supported=${launcher.isSupported} home=$homeDir")
    }

    @After
    fun tearDown() {
        process?.close()
        process = null
    }

    /**
     * The one that matters.
     *
     * `[ -t 0 ]` asks the shell whether stdin is a terminal. Under `ProcessBuilder` pipes this
     * is always false, which is why shells there disable job control, colour and prompts, and
     * why composer/npm/git switch to non-interactive output. Under a real PTY it is true.
     */
    @Test
    fun shellSeesATerminal() {
        val pty = openShell()
        val output = interact(pty, "[ -t 0 ] && echo IS_A_TTY || echo NOT_A_TTY\n")

        Log.i(TAG, "tty check output: ${output.replace("\r", "<CR>")}")
        assertTrue(
            "The shell did not report a terminal. Output was: $output",
            output.contains("IS_A_TTY"),
        )
    }

    /**
     * Line discipline: characters written to the master appear back without the child echoing
     * them itself. That is the kernel's terminal layer, and it is what makes typing feel normal.
     */
    @Test
    fun terminalEchoesInput() {
        val pty = openShell()
        val output = interact(pty, "echo hello_from_pty\n")

        assertTrue(
            "Expected the typed command to be echoed. Output was: $output",
            output.contains("echo hello_from_pty"),
        )
        assertTrue(
            "Expected the command's own output. Output was: $output",
            output.contains("hello_from_pty"),
        )
    }

    /**
     * Ctrl+C must reach the FOREGROUND PROCESS GROUP, not just the shell.
     *
     * `sleep 30` runs in its own foreground group. Signalling only the shell's pid would leave
     * it running while the UI claimed the interrupt worked — the exact bug `killpg` avoids.
     */
    @Test
    fun ctrlCInterrupts() {
        val pty = openShell()

        pty.output.write("sleep 30\n".toByteArray())
        pty.output.flush()
        Thread.sleep(1_000)

        val signalled = pty.signal(PtySignal.Interrupt)
        assertTrue("killpg reported failure", signalled)

        Thread.sleep(1_000)
        val output = interact(pty, "echo AFTER_INTERRUPT\n", settleMs = 1_500)

        Log.i(TAG, "after interrupt: ${output.replace("\r", "<CR>")}")
        assertTrue(
            "The shell did not become responsive after Ctrl+C; sleep likely survived. " +
                "Output was: $output",
            output.contains("AFTER_INTERRUPT"),
        )
    }

    /** TIOCSWINSZ must be honoured, or anything using ncurses or readline lays out wrongly. */
    @Test
    fun resizeIsAccepted() {
        val pty = openShell()
        pty.resize(rows = 40, cols = 132)
        Thread.sleep(300)

        // `stty -a` reads the terminal's dimensions straight from the kernel.
        //
        // Deliberately NOT `stty size`: that is a GNU coreutils extension, and Android's stty
        // is a toybox applet that does not implement it. Probing for it made this test skip on
        // every Android device — the assumption read as "no stty here" when stty was in fact
        // present and TIOCSWINSZ simply went unverified.
        // The sentinel is split so the shell reassembles it: a PTY echoes the command line
        // back, so a literal sentinel would appear in the echo and match even when stty ran
        // perfectly. That is what made this test skip unconditionally on every device.
        val output = interact(pty, "stty -a 2>/dev/null || echo NO''_STTY\n", settleMs = 3_000)
        Log.i(TAG, "stty -a: ${output.replace("\r", "<CR>")}")

        // Not every Android build ships stty; absence is not a failure of the PTY.
        assumeTrue("stty unavailable on this device", !output.contains("NO_STTY"))
        assertTrue(
            "Terminal size was not applied. Output was: $output",
            output.contains("rows 40") && output.contains("columns 132"),
        )
    }

    @Test
    fun exitStatusIsReported() {
        val pty = openShell()
        pty.output.write("exit 3\n".toByteArray())
        pty.output.flush()

        val exitCode = pty.waitFor()
        Log.i(TAG, "exit code: $exitCode")
        assertEquals(3, exitCode)
    }

    @Test
    fun launchingAMissingProgramFails() {
        // Reported as an IOException or a distinct exit code — never as a silent success.
        val failure = runCatching {
            val pty = launcher.open(
                argv = listOf("/does/not/exist"),
                workingDirectory = homeDir,
                environment = baseEnvironment(),
                rows = 24,
                cols = 80,
            )
            process = pty
            pty.waitFor()
        }

        val outcome = failure.getOrNull()
        val threw = failure.exceptionOrNull() is IOException
        Log.i(TAG, "missing program -> threw=$threw exit=$outcome")

        assertTrue(
            "Expected a failure for a missing program, got exit=$outcome",
            threw || outcome == PTY_EXIT_EXEC_FAILED,
        )
    }

    // -----------------------------------------------------------------------------

    private fun openShell(): PtyProcess = launcher.open(
        argv = listOf(SYSTEM_SHELL),
        workingDirectory = homeDir,
        environment = baseEnvironment(),
        rows = 24,
        cols = 80,
    ).also { process = it }

    private fun baseEnvironment() = mapOf(
        "HOME" to homeDir,
        "PATH" to "/system/bin:/system/xbin",
        "TERM" to "xterm-256color",
        // Keeps mksh's prompt out of the way so assertions match on content, not decoration.
        "PS1" to "$ ",
    )

    /**
     * Writes a command and drains whatever the PTY produces within [settleMs].
     *
     * Time-bounded rather than waiting for a marker: a PTY has no end-of-output signal, and a
     * blocking read on a live shell never returns.
     */
    private fun interact(pty: PtyProcess, command: String, settleMs: Long = 1_200): String {
        pty.output.write(command.toByteArray())
        pty.output.flush()

        val collected = StringBuilder()
        val buffer = ByteArray(4096)
        val deadline = System.currentTimeMillis() + settleMs

        while (System.currentTimeMillis() < deadline) {
            if (pty.input.available() > 0) {
                val read = pty.input.read(buffer)
                if (read <= 0) break
                collected.append(String(buffer, 0, read))
            } else {
                Thread.sleep(50)
            }
        }
        return collected.toString()
    }

    private companion object {
        const val TAG = "MF.PtyVerify"
        const val SYSTEM_SHELL = "/system/bin/sh"

        /** Matches EXIT_EXEC_FAILED in pty.c. */
        const val PTY_EXIT_EXEC_FAILED = 127
    }
}

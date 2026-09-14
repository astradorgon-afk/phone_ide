package dev.mobileforge.runtime

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobileforge.runtime.pty.NativePtyLauncher
import dev.mobileforge.runtime.pty.PtyProcess
import dev.mobileforge.runtime.pty.TerminalEmulator
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VERIFIES THE ALTERNATE SCREEN AGAINST A REAL FULL-SCREEN PROGRAM.
 *
 * The unit tests for alternate screen, origin mode and DECTCEM drive the emulator with escape
 * sequences written by hand. That proves the emulator does what *we* think `vim` emits — it
 * does not prove that is what a real program actually emits.
 *
 * This closes the loop: it runs Android's own `vi` inside a real PTY, feeds the raw bytes into
 * [TerminalEmulator], and asserts the emulator switched buffers and switched back on quit.
 * Nothing here is synthetic except the keystrokes.
 */
@RunWith(AndroidJUnit4::class)
class TerminalTuiVerificationTest {

    private val launcher = NativePtyLauncher()
    private var process: PtyProcess? = null
    private lateinit var homeDir: String

    @Before
    fun setUp() {
        homeDir = InstrumentationRegistry.getInstrumentation()
            .targetContext.filesDir.absolutePath
        assumeTrue("Native PTY library not available for this ABI", launcher.isSupported)
    }

    @After
    fun tearDown() {
        process?.close()
        process = null
    }

    @Test
    fun aRealEditorEntersAndLeavesTheAlternateScreen() {
        val emulator = TerminalEmulator(rows = 24, cols = 80)
        val pty = openShell()

        // Shell output before the editor starts: this is ordinary scrollback territory.
        send(pty, emulator, "echo before editor\n")
        assumeTrue(
            "This device has no vi to drive the test with",
            send(pty, emulator, "command -v vi >/dev/null && echo HAVE''_VI\n")
                .contains("HAVE_VI"),
        )
        assertFalse("Should not be on the alternate screen yet", emulator.onAlternateScreen)

        val duringEditor = send(pty, emulator, "vi\n", settleMs = 4_000)
        Log.i(TAG, "vi startup bytes: ${duringEditor.take(200).replace(ESC, "<ESC>")}")
        Log.i(
            TAG,
            "onAlternateScreen=${emulator.onAlternateScreen} " +
                "cursorVisible=${emulator.cursorVisible}",
        )

        assertTrue(
            "vi did not put the terminal on the alternate screen. Bytes: " +
                duringEditor.replace(ESC, "<ESC>"),
            emulator.onAlternateScreen,
        )

        // Quit without saving: ESC to leave insert mode, then `:q!`.
        send(pty, emulator, ESC + ":q!\r", settleMs = 4_000)

        assertFalse(
            "vi did not restore the primary screen on exit",
            emulator.onAlternateScreen,
        )
        assertTrue(
            "The shell's own output was lost when the editor exited",
            emulator.visibleLines().any { row ->
                row.joinToString("") { it.text }.contains("before editor")
            },
        )
    }

    /**
     * Wide characters emitted by a real shell, decoded from real UTF-8 bytes.
     *
     * The unit tests construct the same situation from Kotlin strings, which skips the byte
     * decoding entirely. This drives it end to end: the shell writes UTF-8 to a PTY, the bytes
     * arrive in whatever chunks the kernel chooses, and the grid must still line up.
     */
    @Test
    fun wideCharactersFromARealShellOccupyTwoColumns() {
        val emulator = TerminalEmulator(rows = 24, cols = 80)
        val pty = openShell()

        send(pty, emulator, "echo '$WIDE_SAMPLE'\n", settleMs = 2_000)

        val row = emulator.visibleLines().firstOrNull { line ->
            line.joinToString("") { it.text } .trimEnd() == WIDE_SAMPLE
        }
        Log.i(TAG, "matched row found=${row != null}")

        assertTrue("The shell's wide-character output never appeared", row != null)
        requireNotNull(row)

        assertTrue("First column should hold the wide glyph", row[0].text == FIRST_WIDE_CHAR)
        assertTrue("Second column should be a continuation cell", row[1].continuation)
        assertTrue(
            "The ASCII marker should land in column 4, after two double-width glyphs",
            row[4].text == "|",
        )
    }

    private fun openShell(): PtyProcess = launcher.open(
        argv = listOf("/system/bin/sh"),
        workingDirectory = homeDir,
        environment = mapOf(
            "HOME" to homeDir,
            "PATH" to "/system/bin:/system/xbin",
            "TERM" to "xterm-256color",
            "PS1" to "$ ",
        ),
        rows = 24,
        cols = 80,
    ).also { process = it }

    /** Writes to the PTY, then feeds everything it produces into the emulator. */
    private fun send(
        pty: PtyProcess,
        emulator: TerminalEmulator,
        input: String,
        settleMs: Long = 1_500,
    ): String {
        pty.output.write(input.toByteArray())
        pty.output.flush()

        val raw = StringBuilder()
        val buffer = ByteArray(8192)
        val deadline = System.currentTimeMillis() + settleMs
        while (System.currentTimeMillis() < deadline) {
            if (pty.input.available() > 0) {
                val read = pty.input.read(buffer)
                if (read <= 0) break
                emulator.write(buffer, read)
                raw.append(String(buffer, 0, read))
            } else {
                Thread.sleep(50)
            }
        }
        return raw.toString()
    }

    private companion object {
        const val TAG = "MF.TuiVerify"
        const val ESC = "\u001B"
        const val WIDE_SAMPLE = "世界|"
        const val FIRST_WIDE_CHAR = "世"
    }
}

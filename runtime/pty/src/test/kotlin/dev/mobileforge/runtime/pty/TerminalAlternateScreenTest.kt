package dev.mobileforge.runtime.pty

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Alternate screen buffer, origin mode and cursor visibility.
 *
 * These are what separate a terminal that can run `vim`, `less` and `htop` from one that only
 * runs `ls`. The failure they prevent is specific and ugly: without a separate buffer, a
 * full-screen program's redraws land in scrollback, so quitting `vim` leaves the shell buried
 * under thousands of stale frames and the history the user actually wanted is gone.
 */
class TerminalAlternateScreenTest {

    private val esc = "\u001B"

    private fun terminal(rows: Int = 6, cols: Int = 20) =
        TerminalEmulator(rows = rows, cols = cols, scrollbackLimit = 100)

    // --- Alternate screen ---------------------------------------------------------

    @Test
    fun `entering the alternate screen hides shell output and leaving restores it`() {
        val term = terminal()
        term.write("shell output\r\n")

        term.write("$esc[?1049h")
        assertThat(term.onAlternateScreen).isTrue()
        assertThat(term.screenText()).isEmpty()

        term.write("full screen program")
        assertThat(term.screenText()).contains("full screen program")
        assertThat(term.screenText()).doesNotContain("shell output")

        term.write("$esc[?1049l")
        assertThat(term.onAlternateScreen).isFalse()
        assertThat(term.screenText()).contains("shell output")
        assertThat(term.screenText()).doesNotContain("full screen program")
    }

    @Test
    fun `alternate screen redraws never enter scrollback`() {
        val term = terminal(rows = 3)
        term.write("history line\r\n")
        val scrollbackBefore = term.scrollbackSize

        term.write("$esc[?1049h")
        // Far more output than fits: on the primary screen this would scroll into history.
        repeat(50) { term.write("frame $it\r\n") }

        assertThat(term.scrollbackSize).isEqualTo(scrollbackBefore)

        term.write("$esc[?1049l")
        assertThat(term.screenText()).contains("history line")
    }

    @Test
    fun `scrollback is withheld from the rendered view while on the alternate screen`() {
        val term = terminal(rows = 3)
        repeat(10) { term.write("line $it\r\n") }
        assertThat(term.scrollbackSize).isGreaterThan(0)
        val primaryLines = term.visibleLines().size

        term.write("$esc[?1049h")
        assertThat(term.visibleLines()).hasSize(3)

        term.write("$esc[?1049l")
        assertThat(term.visibleLines()).hasSize(primaryLines)
    }

    @Test
    fun `entering the alternate screen twice does not lose the primary screen`() {
        val term = terminal()
        term.write("shell history") // Short enough not to wrap at 20 columns.

        term.write("$esc[?1049h")
        term.write("first program")
        term.write("$esc[?1049h") // A second DECSET must be a no-op, not a re-save.
        term.write("$esc[?1049l")

        assertThat(term.screenText()).contains("shell history")
    }

    @Test
    fun `1049 restores the cursor position but 47 does not`() {
        val term = terminal()
        term.write("$esc[3;5H")
        val expected = term.cursorRow to term.cursorCol

        term.write("$esc[?1049h")
        term.write("$esc[1;1Hmoved")
        term.write("$esc[?1049l")
        assertThat(term.cursorRow to term.cursorCol).isEqualTo(expected)

        term.write("$esc[3;5H")
        term.write("$esc[?47h")
        term.write("$esc[1;1H")
        term.write("$esc[?47l")
        assertThat(term.cursorRow).isEqualTo(0)
        assertThat(term.cursorCol).isEqualTo(0)
    }

    @Test
    fun `leaving the alternate screen after a resize restores a correctly shaped screen`() {
        val term = terminal(rows = 6, cols = 20)
        term.write("shell output")

        term.write("$esc[?1049h")
        term.resize(newRows = 10, newCols = 30)
        term.write("$esc[?1049l")

        assertThat(term.rows).isEqualTo(10)
        assertThat(term.visibleLines().last()).hasSize(30)
        assertThat(term.screenText()).contains("shell output")
    }

    @Test
    fun `reset leaves the alternate screen`() {
        val term = terminal()
        term.write("$esc[?1049h")
        term.reset()
        assertThat(term.onAlternateScreen).isFalse()
    }

    // --- Cursor visibility --------------------------------------------------------

    @Test
    fun `cursor visibility follows DECTCEM`() {
        val term = terminal()
        assertThat(term.cursorVisible).isTrue()

        term.write("$esc[?25l")
        assertThat(term.cursorVisible).isFalse()

        term.write("$esc[?25h")
        assertThat(term.cursorVisible).isTrue()

        term.write("$esc[?25l")
        term.reset()
        assertThat(term.cursorVisible).isTrue()
    }

    // --- Origin mode --------------------------------------------------------------

    @Test
    fun `origin mode addresses rows relative to the scroll region`() {
        val term = terminal(rows = 10)
        term.write("$esc[3;7r") // Region is rows 3..7, i.e. indices 2..6.
        term.write("$esc[?6h")

        // Row 1 in origin mode is the top margin, not the top of the screen.
        term.write("$esc[1;1H")
        assertThat(term.cursorRow).isEqualTo(2)

        term.write("$esc[3;1H")
        assertThat(term.cursorRow).isEqualTo(4)
    }

    @Test
    fun `origin mode clamps addressing to the region`() {
        val term = terminal(rows = 10)
        term.write("$esc[3;7r")
        term.write("$esc[?6h")

        term.write("$esc[99;1H")
        assertThat(term.cursorRow).isEqualTo(6) // Bottom margin, not row 9.
    }

    @Test
    fun `without origin mode addressing is absolute despite a region`() {
        val term = terminal(rows = 10)
        term.write("$esc[3;7r")

        term.write("$esc[1;1H")
        assertThat(term.cursorRow).isEqualTo(0)
    }

    @Test
    fun `switching origin mode homes the cursor`() {
        val term = terminal(rows = 10)
        term.write("$esc[3;7r")
        term.write("$esc[5;5H")

        term.write("$esc[?6h")
        assertThat(term.cursorRow).isEqualTo(2)
        assertThat(term.cursorCol).isEqualTo(0)

        term.write("$esc[?6l")
        assertThat(term.cursorRow).isEqualTo(0)
        assertThat(term.cursorCol).isEqualTo(0)
    }

    @Test
    fun `VPA respects origin mode`() {
        val term = terminal(rows = 10)
        term.write("$esc[3;7r")
        term.write("$esc[?6h")

        term.write("$esc[2d")
        assertThat(term.cursorRow).isEqualTo(3)
    }

    // --- Saved cursor -------------------------------------------------------------

    @Test
    fun `DECSC and DECRC carry rendition and origin mode`() {
        val term = terminal(rows = 10)
        term.write("$esc[3;7r")
        term.write("$esc[?6h")
        term.write("$esc[1;31m")
        term.write("${esc}7") // DECSC

        term.write("$esc[?6l")
        term.write("$esc[0m")
        term.write("${esc}8") // DECRC

        term.write("X")
        val cell = term.visibleLines()[term.cursorRow][term.cursorCol - 1]
        assertThat(cell.style.bold).isTrue()
        assertThat(cell.style.foreground).isEqualTo(1)
    }

    @Test
    fun `1048 saves and restores the cursor without switching buffers`() {
        val term = terminal()
        term.write("$esc[4;9H")
        term.write("$esc[?1048h")
        term.write("$esc[1;1H")

        term.write("$esc[?1048l")
        assertThat(term.onAlternateScreen).isFalse()
        assertThat(term.cursorRow).isEqualTo(3)
        assertThat(term.cursorCol).isEqualTo(8)
    }

    // --- Regression guard ---------------------------------------------------------

    @Test
    fun `unknown private modes are still consumed silently`() {
        val term = terminal()
        term.write("$esc[?2004h") // Bracketed paste: recognised syntax, not implemented.
        term.write("visible")
        assertThat(term.screenText()).isEqualTo("visible")
    }
}

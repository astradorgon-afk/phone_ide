package dev.mobileforge.runtime.pty

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Escape-sequence handling.
 *
 * Terminal bugs manifest as subtly corrupted output that is miserable to reproduce by hand, so
 * every sequence a shell actually emits is pinned here. Written against real-world cases —
 * a shell prompt redrawing, `clear`, `ls --color`, a progress line rewriting itself.
 */
class TerminalEmulatorTest {

    private val esc = "\u001B"

    private fun emulator(rows: Int = 5, cols: Int = 20) = TerminalEmulator(rows, cols)

    // ---------- plain text ----------

    @Test
    fun `writes plain text`() {
        val term = emulator()
        term.write("hello")
        assertThat(term.screenText()).isEqualTo("hello")
    }

    @Test
    fun `carriage return returns to column zero`() {
        val term = emulator()
        term.write("hello\rworld")
        // A progress line rewriting itself in place — the classic CR use.
        assertThat(term.screenText()).isEqualTo("world")
    }

    @Test
    fun `newline moves down a row`() {
        val term = emulator()
        term.write("a\nb")
        assertThat(term.screenText()).isEqualTo("a\n b")
    }

    @Test
    fun `backspace moves the cursor back`() {
        val term = emulator()
        term.write("abc\b\bX")
        assertThat(term.screenText()).isEqualTo("aXc")
    }

    @Test
    fun `tab advances to the next tab stop`() {
        val term = emulator(cols = 40)
        term.write("a\tb")
        assertThat(term.screenText()).isEqualTo("a       b")
    }

    @Test
    fun `text wraps at the right margin`() {
        val term = emulator(rows = 3, cols = 5)
        term.write("abcdefgh")
        assertThat(term.screenText()).isEqualTo("abcde\nfgh")
    }

    // ---------- cursor movement ----------

    @Test
    fun `cursor position sets row and column`() {
        val term = emulator()
        term.write("${esc}[2;3Hx")
        assertThat(term.cursorRow).isEqualTo(1)
        assertThat(term.screenText()).isEqualTo("\n  x")
    }

    @Test
    fun `cursor up and down move within bounds`() {
        val term = emulator()
        term.write("${esc}[5;1H")
        term.write("${esc}[2A")
        assertThat(term.cursorRow).isEqualTo(2)
        term.write("${esc}[10B")
        assertThat(term.cursorRow).isEqualTo(4) // clamped to the last row
    }

    @Test
    fun `cursor forward and back move within bounds`() {
        val term = emulator()
        term.write("${esc}[10C")
        assertThat(term.cursorCol).isEqualTo(10)
        term.write("${esc}[99D")
        assertThat(term.cursorCol).isEqualTo(0)
    }

    @Test
    fun `column absolute positions the cursor`() {
        val term = emulator()
        term.write("${esc}[5Gx")
        assertThat(term.screenText()).isEqualTo("    x")
    }

    @Test
    fun `save and restore cursor`() {
        val term = emulator()
        term.write("${esc}[2;5H${esc}[s${esc}[1;1H${esc}[uX")
        assertThat(term.cursorRow).isEqualTo(1)
    }

    // ---------- erase ----------

    @Test
    fun `erase display clears the screen and homes the cursor`() {
        val term = emulator()
        term.write("hello\nworld")
        term.write("${esc}[2J")
        assertThat(term.screenText()).isEmpty()
        assertThat(term.cursorRow).isEqualTo(0)
        assertThat(term.cursorCol).isEqualTo(0)
    }

    @Test
    fun `clearing the screen does not push lines into scrollback`() {
        // `clear` means the user wanted them gone; retaining them would make the scroll
        // position jump unexpectedly next time they scroll up.
        val term = emulator()
        term.write("hello\nworld")
        term.write("${esc}[2J")
        assertThat(term.scrollbackSize).isEqualTo(0)
    }

    @Test
    fun `erase to end of line clears from the cursor`() {
        val term = emulator()
        term.write("abcdef")
        term.write("${esc}[1;3H${esc}[K")
        assertThat(term.screenText()).isEqualTo("ab")
    }

    @Test
    fun `erase whole line clears everything on it`() {
        val term = emulator()
        term.write("abcdef")
        term.write("${esc}[2K")
        assertThat(term.screenText()).isEmpty()
    }

    @Test
    fun `erase characters blanks a span without shifting`() {
        val term = emulator()
        term.write("abcdef")
        term.write("${esc}[1;2H${esc}[3X")
        assertThat(term.screenText()).isEqualTo("a   ef")
    }

    // ---------- insert and delete ----------

    @Test
    fun `delete characters shifts the rest left`() {
        val term = emulator()
        term.write("abcdef")
        term.write("${esc}[1;2H${esc}[2P")
        assertThat(term.screenText()).isEqualTo("adef")
    }

    @Test
    fun `insert characters shifts the rest right`() {
        val term = emulator()
        term.write("abcdef")
        term.write("${esc}[1;2H${esc}[2@")
        assertThat(term.screenText()).isEqualTo("a  bcdef")
    }

    @Test
    fun `delete lines pulls following lines up`() {
        val term = emulator()
        // "\r\n", not "\n": the PTY sets ONLCR (pty.c), so a program printing "\n" actually
        // delivers "\r\n". A bare LF moves down WITHOUT returning to column 0, which is
        // correct VT behaviour and produces the classic staircase — see the newline test.
        term.write("one\r\ntwo\r\nthree")
        term.write("${esc}[1;1H${esc}[1M")
        assertThat(term.screenText()).isEqualTo("two\nthree")
    }

    @Test
    fun `insert lines pushes following lines down`() {
        val term = emulator()
        term.write("one\r\ntwo")
        term.write("${esc}[1;1H${esc}[1L")
        assertThat(term.screenText()).isEqualTo("\none\ntwo")
    }

    // ---------- scrolling ----------

    @Test
    fun `scrolling past the last row moves lines into scrollback`() {
        val term = emulator(rows = 2, cols = 10)
        term.write("a\nb\nc")
        assertThat(term.screenText()).isEqualTo(" b\n  c")
        assertThat(term.scrollbackText().trim()).isEqualTo("a")
    }

    @Test
    fun `scrollback is bounded`() {
        // RISK-009: a build printing a million lines must not become a million retained rows.
        val term = TerminalEmulator(rows = 2, cols = 10, scrollbackLimit = 3)
        repeat(50) { term.write("line\n") }
        assertThat(term.scrollbackSize).isAtMost(3)
    }

    // ---------- SGR ----------

    @Test
    fun `sets and resets foreground colour`() {
        val term = emulator()
        term.write("${esc}[31mR${esc}[0mN")
        val row = term.visibleLines().first()
        assertThat(row[0].style.foreground).isEqualTo(1)
        assertThat(row[1].style.foreground).isEqualTo(CellStyle.DEFAULT_COLOR)
    }

    @Test
    fun `bright colours map above the base eight`() {
        val term = emulator()
        term.write("${esc}[92mG")
        assertThat(term.visibleLines().first()[0].style.foreground).isEqualTo(10)
    }

    @Test
    fun `applies bold and underline`() {
        val term = emulator()
        term.write("${esc}[1;4mX")
        val style = term.visibleLines().first()[0].style
        assertThat(style.bold).isTrue()
        assertThat(style.underline).isTrue()
    }

    @Test
    fun `256-colour sequences are consumed and mapped`() {
        val term = emulator()
        term.write("${esc}[38;5;9mX")
        // Mapped into our 16-index space rather than dropped, so colour survives.
        assertThat(term.visibleLines().first()[0].style.foreground).isEqualTo(9)
    }

    @Test
    fun `truecolour sequences do not leak digits onto the screen`() {
        val term = emulator()
        term.write("${esc}[38;2;255;0;0mX")
        assertThat(term.screenText()).isEqualTo("X")
    }

    @Test
    fun `background colour is tracked separately`() {
        val term = emulator()
        term.write("${esc}[44mX")
        assertThat(term.visibleLines().first()[0].style.background).isEqualTo(4)
    }

    // ---------- robustness ----------

    @Test
    fun `unknown escape sequences do not print garbage`() {
        val term = emulator()
        term.write("${esc}[99ZX")
        assertThat(term.screenText()).isEqualTo("X")
    }

    @Test
    fun `OSC title sequences are consumed`() {
        // A shell setting its window title must not dump the title onto the screen.
        val term = emulator()
        term.write("${esc}]0;my titledone")
        assertThat(term.screenText()).isEqualTo("done")
    }

    @Test
    fun `private mode sequences are consumed`() {
        val term = emulator()
        term.write("${esc}[?25lX${esc}[?25h")
        assertThat(term.screenText()).isEqualTo("X")
    }

    @Test
    fun `an escape sequence split across writes still parses`() {
        // A PTY read can end mid-sequence; treating the fragment as text would corrupt output.
        val term = emulator()
        term.write("${esc}[")
        term.write("31m")
        term.write("R")
        assertThat(term.screenText()).isEqualTo("R")
        assertThat(term.visibleLines().first()[0].style.foreground).isEqualTo(1)
    }

    @Test
    fun `a runaway parameter sequence does not grow without bound`() {
        val term = emulator()
        term.write(esc + "[" + "1".repeat(5_000) + "m")
        assertThat(term.screenText()).isEmpty()
    }

    @Test
    fun `bell is silent and invisible`() {
        val term = emulator()
        term.write("a\u0007b")
        assertThat(term.screenText()).isEqualTo("ab")
    }

    // ---------- resize ----------

    @Test
    fun `resize preserves content that still fits`() {
        val term = emulator(rows = 5, cols = 20)
        term.write("hello")
        term.resize(10, 40)
        assertThat(term.screenText()).isEqualTo("hello")
        assertThat(term.rows).isEqualTo(10)
        assertThat(term.cols).isEqualTo(40)
    }

    @Test
    fun `resize clamps the cursor into the new bounds`() {
        val term = emulator(rows = 10, cols = 40)
        term.write("${esc}[10;40H")
        term.resize(3, 5)
        assertThat(term.cursorRow).isAtMost(2)
        assertThat(term.cursorCol).isAtMost(4)
    }

    @Test
    fun `reset clears the screen and scrollback`() {
        val term = emulator(rows = 2, cols = 5)
        term.write("a\nb\nc")
        term.reset()
        assertThat(term.screenText()).isEmpty()
        assertThat(term.scrollbackSize).isEqualTo(0)
    }

    @Test
    fun `revision advances on write so the UI can skip redundant redraws`() {
        val term = emulator()
        val before = term.revision
        term.write("x")
        assertThat(term.revision).isGreaterThan(before)
    }
}

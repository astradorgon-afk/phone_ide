package dev.mobileforge.runtime.pty

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Character display width.
 *
 * A terminal grid is columns, not characters, and the two only coincide for plain ASCII. Three
 * cases break that assumption, and each corrupts alignment in its own way:
 *
 *  - **Wide characters** (CJK, Hangul, emoji) occupy two columns. Treating them as one makes
 *    every TUI drawn to their right drift left by a column per character.
 *  - **Combining marks** occupy none. Treating them as one makes accented text drift right.
 *  - **Astral code points** (emoji) are surrogate pairs in UTF-16. Writing each half into its
 *    own cell renders two replacement boxes instead of one glyph.
 */
class TerminalUnicodeWidthTest {

    private val esc = "\u001B"

    private fun terminal(rows: Int = 4, cols: Int = 20) =
        TerminalEmulator(rows = rows, cols = cols, scrollbackLimit = 50)

    private fun TerminalEmulator.row(index: Int) = visibleLines()[index]

    // --- Width classification -----------------------------------------------------

    @Test
    fun `ascii is one column`() {
        assertThat(TerminalEmulator.displayWidth('A'.code)).isEqualTo(1)
        assertThat(TerminalEmulator.displayWidth(' '.code)).isEqualTo(1)
    }

    @Test
    fun `cjk and fullwidth forms are two columns`() {
        assertThat(TerminalEmulator.displayWidth(0x4E16)).isEqualTo(2) // 世
        assertThat(TerminalEmulator.displayWidth(0xAC00)).isEqualTo(2) // 가
        assertThat(TerminalEmulator.displayWidth(0x3042)).isEqualTo(2) // あ
        assertThat(TerminalEmulator.displayWidth(0xFF21)).isEqualTo(2) // Ａ
    }

    @Test
    fun `emoji are two columns`() {
        assertThat(TerminalEmulator.displayWidth(0x1F600)).isEqualTo(2) // 😀
        assertThat(TerminalEmulator.displayWidth(0x1F680)).isEqualTo(2) // 🚀
    }

    @Test
    fun `combining marks are zero columns`() {
        assertThat(TerminalEmulator.displayWidth(0x0301)).isEqualTo(0) // combining acute
        assertThat(TerminalEmulator.displayWidth(0x200D)).isEqualTo(0) // zero-width joiner
        assertThat(TerminalEmulator.displayWidth(0xFE0F)).isEqualTo(0) // variation selector
    }

    @Test
    fun `ambiguous width characters are treated as narrow`() {
        // Box drawing and Cyrillic are "ambiguous" in UAX #11; a Western font renders them
        // narrow, and that is what we render too.
        assertThat(TerminalEmulator.displayWidth(0x2500)).isEqualTo(1)
        assertThat(TerminalEmulator.displayWidth(0x0416)).isEqualTo(1)
    }

    // --- Placement on the grid ----------------------------------------------------

    @Test
    fun `a wide character consumes two columns`() {
        val term = terminal()
        term.write("世")

        assertThat(term.cursorCol).isEqualTo(2)
        assertThat(term.row(0)[0].text).isEqualTo("世")
        assertThat(term.row(0)[1].continuation).isTrue()
        assertThat(term.row(0)[1].text).isEmpty()
    }

    @Test
    fun `wide characters keep following text aligned`() {
        val term = terminal()
        term.write("世界|")

        // Two wide characters occupy four columns, so the bar lands in column 4.
        assertThat(term.cursorCol).isEqualTo(5)
        assertThat(term.row(0)[4].text).isEqualTo("|")
        assertThat(term.screenText()).isEqualTo("世界|")
    }

    @Test
    fun `an emoji occupies one cell not two`() {
        val term = terminal()
        term.write("🚀x")

        assertThat(term.row(0)[0].text).isEqualTo("🚀")
        assertThat(term.row(0)[1].continuation).isTrue()
        assertThat(term.row(0)[2].text).isEqualTo("x")
        assertThat(term.screenText()).isEqualTo("🚀x")
    }

    @Test
    fun `a combining mark attaches to the character before it`() {
        val term = terminal()
        term.write("é") // e + combining acute

        assertThat(term.cursorCol).isEqualTo(1)
        assertThat(term.row(0)[0].text).isEqualTo("é")
        assertThat(term.screenText()).isEqualTo("é")
    }

    @Test
    fun `a combining mark attaches through a wide character's continuation cell`() {
        val term = terminal()
        term.write("世́")

        assertThat(term.cursorCol).isEqualTo(2)
        assertThat(term.row(0)[0].text).isEqualTo("世́")
    }

    @Test
    fun `a leading combining mark is dropped rather than drawn alone`() {
        val term = terminal()
        term.write("́")
        assertThat(term.screenText()).isEmpty()
    }

    // --- Wrapping and overwriting -------------------------------------------------

    @Test
    fun `a wide character wraps whole rather than straddling the margin`() {
        val term = terminal(cols = 5)
        term.write("abcd")   // Fills columns 0..3, leaving one column free.
        term.write("世")      // Does not fit in one column, so it wraps.

        assertThat(term.row(0)[4].text).isEqualTo(" ")
        assertThat(term.row(1)[0].text).isEqualTo("世")
        assertThat(term.row(1)[1].continuation).isTrue()
    }

    @Test
    fun `overwriting the left half of a wide character clears its other half`() {
        val term = terminal()
        term.write("世")
        term.write("\r")
        term.write("x")

        assertThat(term.row(0)[0].text).isEqualTo("x")
        assertThat(term.row(0)[1].continuation).isFalse()
        assertThat(term.screenText()).isEqualTo("x")
    }

    @Test
    fun `overwriting the right half of a wide character clears its other half`() {
        val term = terminal()
        term.write("世")
        term.write(esc + "[1;2H") // Park the cursor on the continuation cell.
        term.write("x")

        assertThat(term.row(0)[0].text).isEqualTo(" ")
        assertThat(term.row(0)[1].text).isEqualTo("x")
        assertThat(term.screenText()).isEqualTo(" x")
    }

    // --- Streaming ----------------------------------------------------------------

    @Test
    fun `an emoji split across two byte reads still renders as one glyph`() {
        val term = terminal()
        val bytes = "🚀".toByteArray(Charsets.UTF_8)
        assertThat(bytes.size).isEqualTo(4)

        // Deliver the UTF-8 sequence in two chunks, as a PTY read boundary would.
        term.write(bytes.copyOfRange(0, 2), 2)
        term.write(bytes.copyOfRange(2, 4), 2)

        assertThat(term.row(0)[0].text).isEqualTo("🚀")
        assertThat(term.screenText()).isEqualTo("🚀")
    }

    @Test
    fun `an unpaired surrogate is dropped`() {
        val term = terminal()
        term.write("a\uD83Db") // High surrogate with no partner.
        assertThat(term.screenText()).isEqualTo("ab")
    }
}

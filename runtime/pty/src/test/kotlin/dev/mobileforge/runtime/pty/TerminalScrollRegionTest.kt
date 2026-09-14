package dev.mobileforge.runtime.pty

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TerminalScrollRegionTest {
    private val esc = "\u001B"

    private fun terminal(): TerminalEmulator = TerminalEmulator(rows = 5, cols = 10).apply {
        write("header\r\none\r\ntwo\r\nthree\r\nfooter")
        write("${esc}[2;4r")
    }

    @Test
    fun `line feed preserves header footer and scrollback`() {
        val term = terminal()
        term.write("${esc}[4;1H\nnew")
        assertThat(term.screenText()).isEqualTo("header\ntwo\nthree\nnew\nfooter")
        assertThat(term.scrollbackSize).isEqualTo(0)
    }

    @Test
    fun `reverse index preserves rows outside region`() {
        val term = terminal()
        term.write("${esc}[2;1H${esc}Mnew")
        assertThat(term.screenText()).isEqualTo("header\nnew\none\ntwo\nfooter")
        assertThat(term.scrollbackSize).isEqualTo(0)
    }

    @Test
    fun `index and next line scroll within margins`() {
        for (sequence in listOf("${esc}D", "${esc}E")) {
            val term = terminal()
            term.write("${esc}[4;1H${sequence}new")
            assertThat(term.screenText()).isEqualTo("header\ntwo\nthree\nnew\nfooter")
        }
    }

    @Test
    fun `insert lines stops at bottom margin`() {
        val term = terminal()
        term.write("${esc}[3;1H${esc}[L")
        assertThat(term.screenText()).isEqualTo("header\none\n\ntwo\nfooter")
    }

    @Test
    fun `delete lines stops at bottom margin`() {
        val term = terminal()
        term.write("${esc}[3;1H${esc}[M")
        assertThat(term.screenText()).isEqualTo("header\none\nthree\n\nfooter")
    }

    @Test
    fun `insert and delete outside region do nothing`() {
        for (row in listOf(1, 5)) {
            for (command in listOf('L', 'M')) {
                val term = terminal()
                term.write("${esc}[$row;1H${esc}[2$command")
                assertThat(term.screenText()).isEqualTo("header\none\ntwo\nthree\nfooter")
            }
        }
    }

    @Test
    fun `large counts clear only remaining region`() {
        for (command in listOf('L', 'M', 'S', 'T')) {
            val term = terminal()
            term.write("${esc}[2;1H${esc}[2147483647$command")
            assertThat(term.screenText()).isEqualTo("header\n\n\n\nfooter")
        }
    }

    @Test
    fun `explicit scrolling leaves cursor in place`() {
        val term = terminal()
        term.write("${esc}[3;3H${esc}[S")
        assertThat(term.screenText()).isEqualTo("header\ntwo\nthree\n\nfooter")
        term.write("${esc}[T")
        assertThat(term.screenText()).isEqualTo("header\n\ntwo\nthree\nfooter")
        assertThat(term.cursorRow).isEqualTo(2)
        assertThat(term.cursorCol).isEqualTo(2)
    }

    @Test
    fun `invalid margins preserve region and cursor`() {
        for (params in listOf("4;2", "3;3", "1;6", "6;7")) {
            val term = terminal()
            term.write("${esc}[4;1H${esc}[${params}r\nnew")
            assertThat(term.screenText()).isEqualTo("header\ntwo\nthree\nnew\nfooter")
        }
    }

    @Test
    fun `omitted and zero margins restore full screen scrolling`() {
        for (params in listOf("", "0;0", "1;")) {
            val term = terminal()
            term.write("${esc}[${params}r")
            assertThat(term.cursorRow).isEqualTo(0)
            assertThat(term.cursorCol).isEqualTo(0)
            term.write("${esc}[5;1H\nnew")
            assertThat(term.screenText()).isEqualTo("one\ntwo\nthree\nfooter\nnew")
            assertThat(term.scrollbackText()).isEqualTo("header")
        }
    }

    @Test
    fun `line feed below region does not scroll`() {
        val term = terminal()
        term.write("${esc}[5;1H\n")
        assertThat(term.screenText()).isEqualTo("header\none\ntwo\nthree\nfooter")
        assertThat(term.cursorRow).isEqualTo(4)
    }

    @Test
    fun `reverse index above region does not scroll`() {
        val term = terminal()
        term.write("${esc}[1;1H${esc}M")
        assertThat(term.screenText()).isEqualTo("header\none\ntwo\nthree\nfooter")
        assertThat(term.cursorRow).isEqualTo(0)
    }

    @Test
    fun `resize resets margins to new screen bounds`() {
        val term = terminal()
        term.resize(3, 10)
        term.write("${esc}[3;1H\nnew")
        assertThat(term.screenText()).isEqualTo("three\nfooter\nnew")
        assertThat(term.scrollbackText()).isEqualTo("two")
    }

    @Test
    fun `reset restores full screen region`() {
        val term = terminal()
        term.reset()
        term.write("a\r\nb\r\nc\r\nd\r\ne\r\nf")
        assertThat(term.screenText()).isEqualTo("b\nc\nd\ne\nf")
        assertThat(term.scrollbackText()).isEqualTo("a")
    }

    @Test
    fun `scroll margin escape can be split across reads`() {
        val term = TerminalEmulator(rows = 5, cols = 10)
        term.write("header\r\none\r\ntwo\r\nthree\r\nfooter")
        for (part in listOf(esc, "[", "2;", "4", "r")) term.write(part)
        term.write("${esc}[4;1H\nnew")
        assertThat(term.screenText()).isEqualTo("header\ntwo\nthree\nnew\nfooter")
    }

    @Test
    fun `single row terminal still scrolls after margin command`() {
        val term = TerminalEmulator(rows = 1, cols = 10)
        term.write("${esc}[rfirst\r\nsecond")
        assertThat(term.screenText()).isEqualTo("second")
        assertThat(term.scrollbackText()).isEqualTo("first")
    }
}

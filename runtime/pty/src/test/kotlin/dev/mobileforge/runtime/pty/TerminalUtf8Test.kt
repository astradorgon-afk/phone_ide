package dev.mobileforge.runtime.pty

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TerminalUtf8Test {
    @Test
    fun `every split of multibyte output matches uninterrupted output`() {
        val text = "café € 日本 😀 done"
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (split in 0..bytes.size) {
            val terminal = TerminalEmulator()
            terminal.write(bytes, split)
            val remainder = bytes.copyOfRange(split, bytes.size)
            terminal.write(remainder, remainder.size)
            terminal.finishInput()
            assertThat(terminal.screenText()).isEqualTo(text)
        }
    }

    @Test
    fun `single byte reads preserve unicode and escape sequences`() {
        val terminal = TerminalEmulator()
        val bytes = "\u001B[31mcafé € 日本\u001B[0m".toByteArray(Charsets.UTF_8)
        bytes.forEach { terminal.write(byteArrayOf(it), 1) }
        terminal.finishInput()
        assertThat(terminal.screenText()).isEqualTo("café € 日本")
        assertThat(terminal.visibleLines()[0][0].style.foreground).isEqualTo(1)
    }

    @Test
    fun `incomplete character stays invisible until complete`() {
        val terminal = TerminalEmulator()
        terminal.write(byteArrayOf(0xE2.toByte(), 0x82.toByte()), 2)
        assertThat(terminal.screenText()).isEmpty()
        terminal.write(byteArrayOf(0xAC.toByte()), 1)
        assertThat(terminal.screenText()).isEqualTo("€")
    }

    @Test
    fun `end of input replaces truncated character once`() {
        val terminal = TerminalEmulator()
        terminal.write(byteArrayOf(0xE2.toByte(), 0x82.toByte()), 2)
        terminal.finishInput()
        terminal.finishInput()
        assertThat(terminal.screenText()).isEqualTo("\uFFFD")
        terminal.write(byteArrayOf(65), 1)
        assertThat(terminal.screenText()).isEqualTo("\uFFFDA")
    }

    @Test
    fun `malformed input does not swallow following text`() {
        val terminal = TerminalEmulator()
        terminal.write(byteArrayOf(0xE2.toByte()), 1)
        terminal.write(byteArrayOf(65, 0xFF.toByte(), 66), 3)
        terminal.finishInput()
        assertThat(terminal.screenText()).isEqualTo("\uFFFDA\uFFFDB")
    }

    @Test
    fun `read length ignores stale buffer contents`() {
        val terminal = TerminalEmulator()
        terminal.write(byteArrayOf(65, 66, 67), 1)
        terminal.write(byteArrayOf(88), 0)
        terminal.finishInput()
        assertThat(terminal.screenText()).isEqualTo("A")
    }

    @Test
    fun `terminal reset sequence does not discard subsequent bytes in the same read`() {
        val terminal = TerminalEmulator()
        val bytes = "old\u001Bc€".toByteArray(Charsets.UTF_8)
        terminal.write(bytes, bytes.size - 1)
        terminal.write(byteArrayOf(bytes.last()), 1)
        assertThat(terminal.screenText()).isEqualTo("€")
    }
}

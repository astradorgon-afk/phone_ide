package dev.mobileforge.feature.terminal

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Hardware key mapping.
 *
 * Unit-tested rather than driven through an instrumented test with a physical keyboard: the
 * failures here are wrong *bytes*, which surface far from their cause — an arrow key that emits
 * the wrong sequence looks like a broken shell, not a broken key map.
 *
 * The cases that matter most are the ones that must NOT be claimed. Consuming an ordinary letter
 * would stop the user typing at all, so [HardwareKeyAction.Unhandled] is asserted deliberately.
 */
class HardwareKeyMapTest {

    private val esc = Char(0x1B).toString()

    private fun sent(
        keyCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
    ): String? = (HardwareKeyMap.resolve(keyCode, ctrl = ctrl, alt = alt) as? HardwareKeyAction.Send)
        ?.text

    // --- Control characters -------------------------------------------------------

    @Test
    fun `ctrl and a letter produce the matching control character`() {
        assertThat(sent(KeyEvent.KEYCODE_A, ctrl = true)).isEqualTo(Char(0x01).toString())
        assertThat(sent(KeyEvent.KEYCODE_L, ctrl = true)).isEqualTo(Char(0x0C).toString())
        assertThat(sent(KeyEvent.KEYCODE_Z, ctrl = true)).isEqualTo(Char(0x1A).toString())
    }

    @Test
    fun `ctrl C is a signal rather than a byte`() {
        // Sending 0x03 relies on the line discipline translating it, which a program in raw
        // mode has switched off — the signal reaches a wedged process where the byte would not.
        assertThat(HardwareKeyMap.resolve(KeyEvent.KEYCODE_C, ctrl = true))
            .isEqualTo(HardwareKeyAction.Interrupt)
    }

    @Test
    fun `ctrl D ends input`() {
        assertThat(HardwareKeyMap.resolve(KeyEvent.KEYCODE_D, ctrl = true))
            .isEqualTo(HardwareKeyAction.EndOfFile)
    }

    @Test
    fun `ctrl space sends NUL`() {
        assertThat(sent(KeyEvent.KEYCODE_SPACE, ctrl = true)).isEqualTo(Char(0x00).toString())
    }

    @Test
    fun `ctrl bracket sends escape`() {
        assertThat(sent(KeyEvent.KEYCODE_LEFT_BRACKET, ctrl = true)).isEqualTo(esc)
    }

    // --- Navigation ---------------------------------------------------------------

    @Test
    fun `cursor keys send the sequences a shell reads for history and editing`() {
        assertThat(sent(KeyEvent.KEYCODE_DPAD_UP)).isEqualTo(esc + "[A")
        assertThat(sent(KeyEvent.KEYCODE_DPAD_DOWN)).isEqualTo(esc + "[B")
        assertThat(sent(KeyEvent.KEYCODE_DPAD_RIGHT)).isEqualTo(esc + "[C")
        assertThat(sent(KeyEvent.KEYCODE_DPAD_LEFT)).isEqualTo(esc + "[D")
    }

    @Test
    fun `home end and paging send their sequences`() {
        assertThat(sent(KeyEvent.KEYCODE_MOVE_HOME)).isEqualTo(esc + "[H")
        assertThat(sent(KeyEvent.KEYCODE_MOVE_END)).isEqualTo(esc + "[F")
        assertThat(sent(KeyEvent.KEYCODE_PAGE_UP)).isEqualTo(esc + "[5~")
        assertThat(sent(KeyEvent.KEYCODE_PAGE_DOWN)).isEqualTo(esc + "[6~")
        assertThat(sent(KeyEvent.KEYCODE_FORWARD_DEL)).isEqualTo(esc + "[3~")
    }

    @Test
    fun `escape and tab are passed through`() {
        assertThat(sent(KeyEvent.KEYCODE_ESCAPE)).isEqualTo(esc)
        assertThat(sent(KeyEvent.KEYCODE_TAB)).isEqualTo(Char(0x09).toString())
    }

    @Test
    fun `function keys send their sequences`() {
        assertThat(sent(KeyEvent.KEYCODE_F1)).isEqualTo(esc + "OP")
        assertThat(sent(KeyEvent.KEYCODE_F4)).isEqualTo(esc + "OS")
    }

    // --- Meta ---------------------------------------------------------------------

    @Test
    fun `alt prefixes the sequence with escape`() {
        // This is how a shell tells Alt+Left ("back one word") from a plain Left.
        assertThat(sent(KeyEvent.KEYCODE_DPAD_LEFT, alt = true)).isEqualTo(esc + esc + "[D")
    }

    @Test
    fun `alt does not change a control character`() {
        // Ctrl wins: Ctrl+Alt+A is still 0x01, not an escape-prefixed one.
        assertThat(sent(KeyEvent.KEYCODE_A, ctrl = true, alt = true))
            .isEqualTo(Char(0x01).toString())
    }

    // --- Must NOT be claimed ------------------------------------------------------

    @Test
    fun `plain letters are left to the text field`() {
        assertThat(HardwareKeyMap.resolve(KeyEvent.KEYCODE_A))
            .isEqualTo(HardwareKeyAction.Unhandled)
        assertThat(HardwareKeyMap.resolve(KeyEvent.KEYCODE_Z))
            .isEqualTo(HardwareKeyAction.Unhandled)
    }

    @Test
    fun `digits punctuation and space are left alone`() {
        assertThat(HardwareKeyMap.resolve(KeyEvent.KEYCODE_1))
            .isEqualTo(HardwareKeyAction.Unhandled)
        assertThat(HardwareKeyMap.resolve(KeyEvent.KEYCODE_SPACE))
            .isEqualTo(HardwareKeyAction.Unhandled)
        assertThat(HardwareKeyMap.resolve(KeyEvent.KEYCODE_PERIOD))
            .isEqualTo(HardwareKeyAction.Unhandled)
    }

    @Test
    fun `enter and backspace stay with the text field`() {
        // Enter is the field's Send action and backspace is ordinary editing; claiming either
        // would break the prompt for everyone without a hardware keyboard shortcut in mind.
        assertThat(HardwareKeyMap.resolve(KeyEvent.KEYCODE_ENTER))
            .isEqualTo(HardwareKeyAction.Unhandled)
        assertThat(HardwareKeyMap.resolve(KeyEvent.KEYCODE_DEL))
            .isEqualTo(HardwareKeyAction.Unhandled)
    }

    @Test
    fun `shift alone changes nothing`() {
        assertThat(HardwareKeyMap.resolve(KeyEvent.KEYCODE_A, shift = true))
            .isEqualTo(HardwareKeyAction.Unhandled)
    }
}

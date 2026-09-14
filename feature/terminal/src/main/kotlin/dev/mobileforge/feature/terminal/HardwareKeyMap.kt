package dev.mobileforge.feature.terminal

import android.view.KeyEvent

/**
 * What a hardware key press should do to the terminal.
 *
 * Separate from Compose so the mapping is unit-testable: key handling is exactly the kind of
 * code where a wrong byte produces a baffling symptom three layers away, and driving a physical
 * keyboard from an instrumented test to check it is far more effort than it is worth.
 */
sealed interface HardwareKeyAction {

    /** Write these bytes to the PTY. */
    data class Send(val text: String) : HardwareKeyAction

    /**
     * SIGINT to the foreground process group.
     *
     * Ctrl+C is deliberately NOT the byte 0x03. Sending the byte relies on the line discipline
     * translating it, which does not happen for a program that has put the terminal in raw
     * mode — the signal reaches a wedged process where the byte would not.
     */
    data object Interrupt : HardwareKeyAction

    /** Ctrl+D — end of input. */
    data object EndOfFile : HardwareKeyAction

    /** Not a terminal key; let the text field handle it normally. */
    data object Unhandled : HardwareKeyAction
}

/**
 * Maps hardware key presses to terminal input.
 *
 * A hardware keyboard is the difference between a toy terminal and a usable one: without this,
 * Ctrl+C types the letter "c", Escape does nothing, and arrow keys do not recall history. Only
 * keys a terminal actually needs are claimed — everything else returns [HardwareKeyAction
 * .Unhandled] so ordinary typing, selection and IME behaviour are left alone.
 */
object HardwareKeyMap {

    private val ESC = Char(0x1B).toString()

    fun resolve(
        keyCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): HardwareKeyAction {
        if (ctrl) {
            resolveControl(keyCode)?.let { return it }
        }

        val base = when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> ESC
            KeyEvent.KEYCODE_TAB -> Char(0x09).toString()

            // Cursor keys. These are what a shell reads for history recall and line editing;
            // without them the up arrow is silently inert.
            KeyEvent.KEYCODE_DPAD_UP -> ESC + "[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> ESC + "[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> ESC + "[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> ESC + "[D"

            KeyEvent.KEYCODE_MOVE_HOME -> ESC + "[H"
            KeyEvent.KEYCODE_MOVE_END -> ESC + "[F"
            KeyEvent.KEYCODE_PAGE_UP -> ESC + "[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> ESC + "[6~"
            KeyEvent.KEYCODE_INSERT -> ESC + "[2~"
            KeyEvent.KEYCODE_FORWARD_DEL -> ESC + "[3~"

            KeyEvent.KEYCODE_F1 -> ESC + "OP"
            KeyEvent.KEYCODE_F2 -> ESC + "OQ"
            KeyEvent.KEYCODE_F3 -> ESC + "OR"
            KeyEvent.KEYCODE_F4 -> ESC + "OS"

            else -> return HardwareKeyAction.Unhandled
        }

        // Alt is Meta: the convention is to prefix the sequence with ESC, which is how a shell
        // distinguishes Alt+B ("back one word") from a plain B.
        return HardwareKeyAction.Send(if (alt) ESC + base else base)
    }

    /**
     * Ctrl combinations.
     *
     * Ctrl+letter is the control character at the letter's position in the alphabet — Ctrl+A is
     * 0x01, Ctrl+Z is 0x1A. Ctrl+C and Ctrl+D are singled out because they mean "signal" and
     * "end of input" rather than "these bytes".
     */
    private fun resolveControl(keyCode: Int): HardwareKeyAction? = when (keyCode) {
        KeyEvent.KEYCODE_C -> HardwareKeyAction.Interrupt
        KeyEvent.KEYCODE_D -> HardwareKeyAction.EndOfFile

        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
            HardwareKeyAction.Send(Char(0x01 + (keyCode - KeyEvent.KEYCODE_A)).toString())

        // Ctrl+Space sends NUL, which some editors use to set a mark.
        KeyEvent.KEYCODE_SPACE -> HardwareKeyAction.Send(Char(0x00).toString())

        // The traditional spellings of Escape, File Separator and Group Separator.
        KeyEvent.KEYCODE_LEFT_BRACKET -> HardwareKeyAction.Send(Char(0x1B).toString())
        KeyEvent.KEYCODE_BACKSLASH -> HardwareKeyAction.Send(Char(0x1C).toString())
        KeyEvent.KEYCODE_RIGHT_BRACKET -> HardwareKeyAction.Send(Char(0x1D).toString())

        else -> null
    }
}

package dev.mobileforge.runtime.pty

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * A VT100/xterm-subset terminal emulator.
 *
 * Pure Kotlin with no Android dependency, so every escape sequence is unit-testable without a
 * device. That matters more here than almost anywhere else in the project: terminal bugs show
 * up as subtly corrupted output that is miserable to reproduce by hand.
 *
 * **Scope is deliberately a subset.** It implements what a shell, `ls`, `git`, `composer` and
 * `npm` actually emit — cursor movement, erase, SGR colour/bold, line insert/delete, scroll
 * regions — and consumes-without-acting on the rest. A full xterm implementation is a project
 * in its own right, and pretending to be one would mean silently mis-rendering the sequences we
 * did not really handle. Unknown sequences are dropped cleanly rather than printed as garbage.
 */
class TerminalEmulator(
    rows: Int = DEFAULT_ROWS,
    cols: Int = DEFAULT_COLS,
    private val scrollbackLimit: Int = DEFAULT_SCROLLBACK,
) {

    var rows: Int = rows.coerceAtLeast(1)
        private set

    var cols: Int = cols.coerceAtLeast(1)
        private set

    private var screen: Array<Array<Cell>> = blankScreen(this.rows, this.cols)

    /** Lines scrolled off the top. Bounded — a build printing millions of lines must not OOM. */
    private val scrollback = ArrayDeque<Array<Cell>>()

    var cursorRow: Int = 0
        private set

    var cursorCol: Int = 0
        private set

    private var currentStyle = CellStyle.DEFAULT
    private var savedCursor: CursorState? = null
    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    /**
     * The primary screen, parked here while the alternate screen is in use.
     *
     * Non-null means "currently on the alternate screen". Full-screen programs (`vim`, `less`,
     * `htop`) switch to it so that quitting restores the shell exactly as it was — and so that
     * their redraws never enter scrollback, which is what makes `git log` scrollable but `vim`
     * not leave thousands of stale frames behind.
     */
    private var primaryScreen: Array<Array<Cell>>? = null

    /** DECOM. When set, row addressing is relative to the scroll region and clamped to it. */
    private var originMode = false

    /** DECTCEM. TUI programs hide the cursor while redrawing; the UI honours this. */
    var cursorVisible: Boolean = true
        private set

    /** True while a full-screen program owns the display. */
    val onAlternateScreen: Boolean get() = primaryScreen != null

    /** Parser state. A terminal stream can split an escape sequence across reads. */
    private var state = ParseState.Ground
    private val paramBuffer = StringBuilder()
    private val oscBuffer = StringBuilder()
    private val utf8Decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pendingBytes = ByteArray(0)

    /** A high surrogate awaiting its partner, so an emoji is not split across two cells. */
    private var pendingHighSurrogate: Char? = null

    /** Incremented on any visible change so the UI can skip redundant recomposition. */
    var revision: Long = 0
        private set

    // ---------------------------------------------------------------------------------

    fun write(text: String) {
        for (ch in text) process(ch)
        revision++
    }

    fun write(bytes: ByteArray, length: Int) {
        require(length in 0..bytes.size)
        decodeBytes(bytes, length, endOfInput = false)
    }

    /** Flush an incomplete final character as a replacement when the PTY closes. */
    fun finishInput() {
        decodeBytes(ByteArray(0), 0, endOfInput = true)
        utf8Decoder.reset()
    }

    private fun decodeBytes(bytes: ByteArray, length: Int, endOfInput: Boolean) {
        val input = ByteBuffer.allocate(pendingBytes.size + length)
        input.put(pendingBytes).put(bytes, 0, length).flip()
        // UTF-8 produces no more UTF-16 code units than input bytes. One extra slot
        // accommodates replacement of a truncated sequence at end of input.
        val output = CharBuffer.allocate(input.remaining() + 1)
        utf8Decoder.decode(input, output, endOfInput).throwExceptionIfError()
        if (endOfInput) utf8Decoder.flush(output).throwExceptionIfError()
        pendingBytes = ByteArray(input.remaining()).also { input.get(it) }
        output.flip()
        if (output.hasRemaining()) write(output.toString())
    }

    private fun java.nio.charset.CoderResult.throwExceptionIfError() {
        if (isError) throwException()
    }

    private fun process(ch: Char) {
        when (state) {
            ParseState.Ground -> processGround(ch)
            ParseState.Escape -> processEscape(ch)
            ParseState.Csi -> processCsi(ch)
            ParseState.Osc -> processOsc(ch)
        }
    }

    private fun processGround(ch: Char) {
        when (ch) {
            '\u001B' -> state = ParseState.Escape
            '\n' -> lineFeed()
            '\r' -> cursorCol = 0
            '\b' -> if (cursorCol > 0) cursorCol--
            '\t' -> cursorCol = minOf(((cursorCol / TAB_WIDTH) + 1) * TAB_WIDTH, cols - 1)
            '\u0007' -> Unit // BEL: no audible bell on a phone.
            else -> if (ch.code >= 32) acceptText(ch)
        }
    }

    /**
     * Assembles a code point before it reaches the screen.
     *
     * Emoji live outside the BMP and arrive as a surrogate pair, so writing each `Char` into its
     * own cell would split one glyph across two and render mojibake. A high surrogate is held
     * until its partner arrives; an unpaired one is dropped rather than printed as garbage.
     */
    private fun acceptText(ch: Char) {
        val high = pendingHighSurrogate
        if (high != null) {
            pendingHighSurrogate = null
            if (ch.isLowSurrogate()) {
                putCodePoint(Character.toCodePoint(high, ch))
                return
            }
        }
        if (ch.isHighSurrogate()) {
            pendingHighSurrogate = ch
            return
        }
        if (ch.isLowSurrogate()) return // Unpaired: nothing sensible to draw.
        putCodePoint(ch.code)
    }

    private fun processEscape(ch: Char) {
        when (ch) {
            '[' -> {
                paramBuffer.clear()
                state = ParseState.Csi
            }
            ']' -> {
                oscBuffer.clear()
                state = ParseState.Osc
            }
            // Save/restore cursor. DECSC/DECRC also carry the graphic rendition and origin
            // mode, which is why restoring is not merely a cursor move.
            '7' -> { saveCursor(); state = ParseState.Ground }
            '8' -> { restoreCursor(); state = ParseState.Ground }
            'D' -> { lineFeed(); state = ParseState.Ground }
            'E' -> { cursorCol = 0; lineFeed(); state = ParseState.Ground }
            // Reverse index scrolls only when the cursor is on the top margin.
            'M' -> {
                if (cursorRow == scrollTop) scrollDown()
                else cursorRow = (cursorRow - 1).coerceAtLeast(0)
                state = ParseState.Ground
            }
            'c' -> { reset(); state = ParseState.Ground }
            // Charset selection and anything else: consume the byte and carry on.
            else -> state = ParseState.Ground
        }
    }

    private fun processCsi(ch: Char) {
        if (ch in '0'..'9' || ch == ';' || ch == '?' || ch == '>' || ch == '!') {
            // Cap the parameter buffer: a malformed stream must not grow it without bound.
            if (paramBuffer.length < MAX_PARAM_LENGTH) paramBuffer.append(ch)
            return
        }
        applyCsi(ch, paramBuffer.toString())
        state = ParseState.Ground
    }

    /** OSC sets things like the window title. Consumed to the terminator, then discarded. */
    private fun processOsc(ch: Char) {
        when {
            ch == '\u0007' -> state = ParseState.Ground
            ch == '\u001B' -> state = ParseState.Ground
            oscBuffer.length < MAX_PARAM_LENGTH -> oscBuffer.append(ch)
            else -> state = ParseState.Ground
        }
    }

    private fun applyCsi(command: Char, raw: String) {
        // Private-mode sequences (DECSET `?h` / DECRST `?l`). Only the modes listed in
        // [setPrivateMode] are acted on; the rest are consumed silently, because a mode we
        // acknowledge but do not implement renders worse than one we never claimed.
        if (raw.startsWith("?")) {
            if (command == 'h' || command == 'l') {
                raw.drop(1).split(';').mapNotNull(String::toIntOrNull)
                    .forEach { setPrivateMode(it, enable = command == 'h') }
            }
            return
        }

        val params = raw.split(';').map { it.toIntOrNull() ?: 0 }
        fun param(index: Int, default: Int = 1): Int =
            params.getOrNull(index)?.takeIf { it > 0 } ?: default

        when (command) {
            'A' -> cursorRow = (cursorRow - param(0)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + param(0)).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + param(0)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - param(0)).coerceAtLeast(0)
            'E' -> { cursorRow = (cursorRow + param(0)).coerceAtMost(rows - 1); cursorCol = 0 }
            'F' -> { cursorRow = (cursorRow - param(0)).coerceAtLeast(0); cursorCol = 0 }
            'G' -> cursorCol = (param(0) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                cursorRow = addressRow(param(0))
                cursorCol = (param(1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> eraseDisplay(params.getOrNull(0) ?: 0)
            'K' -> eraseLine(params.getOrNull(0) ?: 0)
            'L' -> insertLines(param(0))
            'M' -> deleteLines(param(0))
            'S' -> repeat(param(0).coerceAtMost(scrollBottom - scrollTop + 1)) { scrollUp() }
            'T' -> if (params.size == 1) {
                repeat(param(0).coerceAtMost(scrollBottom - scrollTop + 1)) { scrollDown() }
            }
            'r' -> setScrollRegion(param(0), param(1, rows))
            'P' -> deleteChars(param(0))
            '@' -> insertChars(param(0))
            'X' -> eraseChars(param(0))
            'd' -> cursorRow = addressRow(param(0))
            'm' -> applyStyle(params.ifEmpty { listOf(0) })
            's' -> saveCursor()
            'u' -> restoreCursor()
            else -> Unit
        }
    }

    /**
     * SGR — colour and text attributes.
     *
     * Colours are stored as indices, not RGB, so the UI maps them onto the app's own theme.
     * A terminal hard-coding #00FF00 for green would fight the editor's palette.
     */
    private fun applyStyle(params: List<Int>) {
        var index = 0
        while (index < params.size) {
            when (val code = params[index]) {
                0 -> currentStyle = CellStyle.DEFAULT
                1 -> currentStyle = currentStyle.copy(bold = true)
                2 -> currentStyle = currentStyle.copy(dim = true)
                3 -> currentStyle = currentStyle.copy(italic = true)
                4 -> currentStyle = currentStyle.copy(underline = true)
                7 -> currentStyle = currentStyle.copy(inverse = true)
                22 -> currentStyle = currentStyle.copy(bold = false, dim = false)
                23 -> currentStyle = currentStyle.copy(italic = false)
                24 -> currentStyle = currentStyle.copy(underline = false)
                27 -> currentStyle = currentStyle.copy(inverse = false)
                in 30..37 -> currentStyle = currentStyle.copy(foreground = code - 30)
                39 -> currentStyle = currentStyle.copy(foreground = CellStyle.DEFAULT_COLOR)
                in 40..47 -> currentStyle = currentStyle.copy(background = code - 40)
                49 -> currentStyle = currentStyle.copy(background = CellStyle.DEFAULT_COLOR)
                in 90..97 -> currentStyle = currentStyle.copy(foreground = code - 90 + 8)
                in 100..107 -> currentStyle = currentStyle.copy(background = code - 100 + 8)
                38, 48 -> {
                    // 256-colour (5;n) and truecolour (2;r;g;b). Truecolour is flattened to the
                    // nearest of our 16 indices rather than ignored, so `ls --color` and diff
                    // output stay legible instead of losing all colour.
                    val mode = params.getOrNull(index + 1)
                    when (mode) {
                        5 -> {
                            val value = params.getOrNull(index + 2) ?: 0
                            val mapped = value.coerceIn(0, 255).let { if (it < 16) it else it % 16 }
                            currentStyle = if (code == 38) {
                                currentStyle.copy(foreground = mapped)
                            } else {
                                currentStyle.copy(background = mapped)
                            }
                            index += 2
                        }
                        2 -> index += 4
                        else -> Unit
                    }
                }
                else -> Unit
            }
            index++
        }
    }

    // ---------------------------------------------------------------------------------

    /**
     * Writes one code point, honouring its display width.
     *
     * Three widths matter (UAX #11 and UAX #29, in the subset [displayWidth] implements):
     *
     *  - **0** — combining marks. These belong to the character already on screen, so they are
     *    appended to that cell rather than consuming one of their own. Writing them into their
     *    own cell is what makes accented text drift a column per accent.
     *  - **2** — CJK, Hangul and emoji. These occupy two columns; the second is a continuation
     *    cell holding no text, so the glyph is emitted once but the grid still lines up.
     *  - **1** — everything else.
     */
    private fun putCodePoint(codePoint: Int) {
        val width = displayWidth(codePoint)

        if (width == 0) {
            attachCombiningMark(codePoint)
            return
        }

        // A double-width glyph will not straddle the right margin: it wraps whole.
        if (cursorCol + width > cols) {
            cursorCol = 0
            lineFeed()
        }

        clearOverwrittenWideChar(cursorCol)
        val row = screen[cursorRow]
        row[cursorCol] = Cell(String(Character.toChars(codePoint)), currentStyle)

        if (width == 2) {
            clearOverwrittenWideChar(cursorCol + 1)
            row[cursorCol + 1] = Cell("", currentStyle, continuation = true)
        }
        cursorCol += width
    }

    /** Appends a zero-width mark to the cell it modifies, which is the one behind the cursor. */
    private fun attachCombiningMark(codePoint: Int) {
        var column = cursorCol - 1
        // Step back over the continuation half so the mark lands on the glyph itself.
        while (column > 0 && screen[cursorRow][column].continuation) column--
        if (column < 0) return

        val target = screen[cursorRow][column]
        if (target.text.isEmpty()) return
        screen[cursorRow][column] =
            target.copy(text = target.text + String(Character.toChars(codePoint)))
    }

    /**
     * Blanks the other half of a double-width character that [column] is about to overwrite.
     *
     * Without this, overwriting one half leaves the other orphaned on screen — a stray half
     * glyph that belongs to a character no longer there.
     */
    private fun clearOverwrittenWideChar(column: Int) {
        if (column !in 0 until cols) return
        val row = screen[cursorRow]

        if (row[column].continuation && column > 0) {
            row[column - 1] = Cell.BLANK
        }
        if (column + 1 < cols && row[column + 1].continuation) {
            row[column + 1] = Cell.BLANK
        }
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) scrollUp()
        else cursorRow = (cursorRow + 1).coerceAtMost(rows - 1)
    }

    private fun setScrollRegion(top: Int, bottom: Int) {
        if (top !in 1..rows || bottom !in 1..rows || top >= bottom) return
        scrollTop = top - 1
        scrollBottom = bottom - 1
        // DECSTBM homes the cursor — to the region's origin under DECOM, else the screen's.
        cursorRow = if (originMode) scrollTop else 0
        cursorCol = 0
    }

    /** Applies DECOM to a 1-based row parameter: relative to the region, and clamped to it. */
    private fun addressRow(oneBased: Int): Int = if (originMode) {
        (scrollTop + oneBased - 1).coerceIn(scrollTop, scrollBottom)
    } else {
        (oneBased - 1).coerceIn(0, rows - 1)
    }

    private fun saveCursor() {
        savedCursor = CursorState(cursorRow, cursorCol, currentStyle, originMode)
    }

    private fun restoreCursor() {
        val saved = savedCursor ?: return
        cursorRow = saved.row.coerceIn(0, rows - 1)
        cursorCol = saved.col.coerceIn(0, cols - 1)
        currentStyle = saved.style
        originMode = saved.originMode
    }

    private fun setPrivateMode(mode: Int, enable: Boolean) {
        when (mode) {
            // DECOM. Switching it homes the cursor, per the VT100 specification.
            6 -> {
                originMode = enable
                cursorRow = if (enable) scrollTop else 0
                cursorCol = 0
            }
            25 -> cursorVisible = enable
            // 47/1047 switch buffers without touching the cursor; 1049 saves and restores it.
            47, 1047 -> useAlternateScreen(enable, withCursor = false)
            1048 -> if (enable) saveCursor() else restoreCursor()
            1049 -> useAlternateScreen(enable, withCursor = true)
            else -> Unit
        }
    }

    /**
     * Enters or leaves the alternate screen.
     *
     * Entering is idempotent: a program that sets 1049 twice must not lose the primary screen
     * it saved the first time, which would leave the shell's output unrecoverable on exit.
     */
    private fun useAlternateScreen(enable: Boolean, withCursor: Boolean) {
        if (enable) {
            if (primaryScreen != null) return
            if (withCursor) saveCursor()
            primaryScreen = screen
            screen = blankScreen(rows, cols)
        } else {
            val primary = primaryScreen ?: return
            primaryScreen = null
            // The screen may have been resized while the alternate buffer was in front.
            screen = if (primary.size == rows && primary[0].size == cols) {
                primary
            } else {
                regrid(primary, rows, cols)
            }
            if (withCursor) restoreCursor()
        }
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun scrollUp() {
        // Redrawing a partial region — or an alternate-screen program — must not fill shell
        // history with stale frames.
        if (primaryScreen == null && scrollTop == 0 && scrollBottom == rows - 1) {
            scrollback.addLast(screen[0])
            while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
        }

        for (row in scrollTop until scrollBottom) screen[row] = screen[row + 1]
        screen[scrollBottom] = blankRow(cols)
    }

    private fun scrollDown() {
        for (row in scrollBottom downTo scrollTop + 1) screen[row] = screen[row - 1]
        screen[scrollTop] = blankRow(cols)
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseLine(0); for (r in cursorRow + 1 until rows) screen[r] = blankRow(cols) }
            1 -> { for (r in 0 until cursorRow) screen[r] = blankRow(cols); eraseLine(1) }
            else -> {
                // Mode 2/3 clears the screen. The cleared lines are NOT pushed to scrollback:
                // `clear` means the user wanted them gone, and retaining them would make the
                // scroll position jump unexpectedly.
                for (r in 0 until rows) screen[r] = blankRow(cols)
                cursorRow = 0
                cursorCol = 0
            }
        }
    }

    private fun eraseLine(mode: Int) {
        val row = screen[cursorRow]
        when (mode) {
            0 -> for (c in cursorCol until cols) row[c] = Cell.BLANK
            1 -> for (c in 0..cursorCol.coerceAtMost(cols - 1)) row[c] = Cell.BLANK
            else -> for (c in 0 until cols) row[c] = Cell.BLANK
        }
    }

    private fun eraseChars(count: Int) {
        val row = screen[cursorRow]
        for (c in cursorCol until minOf(cursorCol + count, cols)) row[c] = Cell.BLANK
    }

    private fun deleteChars(count: Int) {
        val row = screen[cursorRow]
        val n = count.coerceAtMost(cols - cursorCol)
        for (c in cursorCol until cols - n) row[c] = row[c + n]
        for (c in cols - n until cols) row[c] = Cell.BLANK
    }

    private fun insertChars(count: Int) {
        val row = screen[cursorRow]
        val n = count.coerceAtMost(cols - cursorCol)
        for (c in cols - 1 downTo cursorCol + n) row[c] = row[c - n]
        for (c in cursorCol until cursorCol + n) row[c] = Cell.BLANK
    }

    private fun insertLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        val n = count.coerceAtMost(scrollBottom - cursorRow + 1)
        for (r in scrollBottom downTo cursorRow + n) screen[r] = screen[r - n]
        for (r in cursorRow until cursorRow + n) screen[r] = blankRow(cols)
    }

    private fun deleteLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        val n = count.coerceAtMost(scrollBottom - cursorRow + 1)
        for (r in cursorRow..scrollBottom - n) screen[r] = screen[r + n]
        for (r in scrollBottom - n + 1..scrollBottom) screen[r] = blankRow(cols)
    }

    // ---------------------------------------------------------------------------------

    /**
     * Resizes, preserving content where it still fits.
     *
     * Reflow is deliberately not implemented — rewrapping historical lines to a new width is
     * where terminal emulators get genuinely hard, and getting it half-right corrupts
     * scrollback. Lines are truncated or padded instead, which is predictable.
     */
    fun resize(newRows: Int, newCols: Int) {
        val targetRows = newRows.coerceAtLeast(1)
        val targetCols = newCols.coerceAtLeast(1)
        if (targetRows == rows && targetCols == cols) return

        screen = regrid(screen, targetRows, targetCols)
        // The parked primary screen is resized too, or leaving the alternate screen would
        // restore a grid of the wrong shape.
        primaryScreen = primaryScreen?.let { regrid(it, targetRows, targetCols) }
        rows = targetRows
        cols = targetCols
        scrollTop = 0
        scrollBottom = rows - 1
        savedCursor = savedCursor?.let {
            it.copy(
                row = it.row.coerceIn(0, rows - 1),
                col = it.col.coerceIn(0, cols - 1),
            )
        }
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        revision++
    }

    fun reset() {
        screen = blankScreen(rows, cols)
        scrollback.clear()
        cursorRow = 0
        cursorCol = 0
        currentStyle = CellStyle.DEFAULT
        scrollTop = 0
        scrollBottom = rows - 1
        savedCursor = null
        primaryScreen = null
        originMode = false
        cursorVisible = true
        pendingHighSurrogate = null
        state = ParseState.Ground
        revision++
    }

    /**
     * Scrollback followed by the live screen — what the UI renders.
     *
     * On the alternate screen the scrollback is withheld: a full-screen program owns the
     * viewport, and prepending shell history above it would both misrepresent the display and
     * let the user scroll into content the program is not managing.
     */
    fun visibleLines(): List<List<Cell>> = if (primaryScreen != null) {
        screen.map { it.toList() }
    } else {
        scrollback.map { it.toList() } + screen.map { it.toList() }
    }

    /** Plain text of the current screen, trailing blanks trimmed. For tests and copy. */
    fun screenText(): String = screen.joinToString("\n") { row ->
        row.joinToString("") { it.text }.trimEnd()
    }.trimEnd()

    fun scrollbackText(): String = scrollback.joinToString("\n") { row ->
        row.joinToString("") { it.text }.trimEnd()
    }

    val scrollbackSize: Int get() = scrollback.size

    private enum class ParseState { Ground, Escape, Csi, Osc }

    /** DECSC saves more than a position: restoring must bring rendition and DECOM back too. */
    private data class CursorState(
        val row: Int,
        val col: Int,
        val style: CellStyle,
        val originMode: Boolean,
    )

    companion object {
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80
        const val DEFAULT_SCROLLBACK = 2_000
        const val TAB_WIDTH = 8
        private const val MAX_PARAM_LENGTH = 64

        /**
         * Display width of a code point, in terminal columns.
         *
         * A pragmatic subset of UAX #11 East Asian Width: the ranges below are the ones that
         * actually appear in source files, commit messages and CLI output — CJK, Hangul,
         * fullwidth forms and emoji. It is deliberately a table rather than a full property
         * lookup, because Android's `Character` API exposes no East Asian Width and shipping a
         * complete ICU table for this would cost more than it returns.
         *
         * Ambiguous-width characters (box drawing, Greek, Cyrillic) are treated as width 1,
         * which is what a terminal using a Western font does.
         */
        internal fun displayWidth(codePoint: Int): Int {
            if (isZeroWidth(codePoint)) return 0
            return if (isWide(codePoint)) 2 else 1
        }

        private fun isZeroWidth(codePoint: Int): Boolean {
            // Zero-width joiner and variation selectors compose the glyph before them.
            if (codePoint == 0x200D || codePoint in 0xFE00..0xFE0F) return true
            if (codePoint in 0xE0100..0xE01EF) return true
            return when (Character.getType(codePoint).toByte()) {
                Character.NON_SPACING_MARK,
                Character.ENCLOSING_MARK,
                Character.COMBINING_SPACING_MARK,
                -> true

                else -> false
            }
        }

        private fun isWide(codePoint: Int): Boolean = WIDE_RANGES.any { codePoint in it }

        private val WIDE_RANGES = listOf(
            0x1100..0x115F, // Hangul Jamo initial consonants
            0x2E80..0x303E, // CJK radicals, Kangxi, CJK symbols
            0x3041..0x33FF, // Kana, Bopomofo, Hangul compatibility, CJK compatibility
            0x3400..0x4DBF, // CJK Extension A
            0x4E00..0x9FFF, // CJK Unified Ideographs
            0xA000..0xA4CF, // Yi
            0xA960..0xA97F, // Hangul Jamo Extended-A
            0xAC00..0xD7A3, // Hangul syllables
            0xF900..0xFAFF, // CJK compatibility ideographs
            0xFE10..0xFE19, // Vertical forms
            0xFE30..0xFE6F, // CJK compatibility forms, small form variants
            0xFF00..0xFF60, // Fullwidth forms
            0xFFE0..0xFFE6, // Fullwidth signs
            0x1F300..0x1F64F, // Emoji: symbols, pictographs, emoticons
            0x1F680..0x1F6FF, // Emoji: transport and map
            0x1F900..0x1F9FF, // Emoji: supplemental symbols
            0x20000..0x2FFFD, // CJK Extension B and beyond
            0x30000..0x3FFFD,
        )

        private fun blankRow(cols: Int) = Array(cols) { Cell.BLANK }
        private fun blankScreen(rows: Int, cols: Int) = Array(rows) { blankRow(cols) }

        /**
         * Rebuilds a grid at a new size, anchoring to the bottom when rows are lost.
         *
         * Keeping the newest lines is what a user expects when a keyboard appears and shrinks
         * the view: the prompt stays put rather than scrolling away.
         */
        private fun regrid(
            grid: Array<Array<Cell>>,
            targetRows: Int,
            targetCols: Int,
        ): Array<Array<Cell>> = Array(targetRows) { r ->
            Array(targetCols) { c ->
                val sourceRow = r - (targetRows - grid.size).coerceAtMost(0)
                grid.getOrNull(sourceRow)?.getOrNull(c) ?: Cell.BLANK
            }
        }
    }
}

/**
 * One grid position.
 *
 * [text] is a String rather than a Char because a single displayed character is not always a
 * single UTF-16 unit: emoji are surrogate pairs, and a base character plus its combining marks
 * is one grapheme occupying one cell.
 */
data class Cell(
    val text: String,
    val style: CellStyle,
    /**
     * True for the right-hand half of a double-width character. It renders nothing of its own —
     * the glyph in the preceding cell already covers this column — but it must occupy the slot
     * so that column arithmetic stays aligned.
     */
    val continuation: Boolean = false,
) {
    companion object {
        val BLANK = Cell(" ", CellStyle.DEFAULT)
    }
}

/**
 * Colours are ANSI indices (0-15), not RGB.
 *
 * The UI maps them onto the app's palette, so terminal output sits in the same visual system as
 * the editor rather than fighting it with hard-coded values.
 */
data class CellStyle(
    val foreground: Int = DEFAULT_COLOR,
    val background: Int = DEFAULT_COLOR,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
) {
    companion object {
        /** "Use the theme's default" rather than any particular colour. */
        const val DEFAULT_COLOR = -1
        val DEFAULT = CellStyle()
    }
}

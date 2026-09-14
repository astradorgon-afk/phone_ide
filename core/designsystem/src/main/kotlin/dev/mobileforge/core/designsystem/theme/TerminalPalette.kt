package dev.mobileforge.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * The 16 ANSI colours, as this app renders them.
 *
 * The emulator stores colours as *indices*, not RGB, precisely so this mapping can exist: a
 * terminal that hard-coded `#00FF00` for green would fight the editor's palette and look like a
 * different application pasted into the same screen.
 *
 * The values are tuned for legibility against the app's own surfaces rather than copied from
 * the classic VGA set — pure `#0000FF` blue on a dark background is close to unreadable, which
 * is why almost every real terminal lightens it.
 */
data class TerminalPalette(
    val foreground: Color,
    val background: Color,
    val cursor: Color,
    val colors: List<Color>,
) {
    /**
     * Resolves a cell colour.
     *
     * [CellStyleIndex.DEFAULT] means "use the theme's own foreground/background", which is what
     * keeps an un-styled terminal consistent with the rest of the UI.
     */
    fun resolve(index: Int, default: Color): Color =
        if (index in colors.indices) colors[index] else default

    companion object {
        /** Matches `CellStyle.DEFAULT_COLOR` in :runtime:pty. */
        const val DEFAULT_INDEX = -1
    }
}

private val DarkAnsi = listOf(
    Color(0xFF1F2630), // 0 black — lifted off pure black so it is visible on our surface
    Color(0xFFE0524A), // 1 red
    Color(0xFF6FBF73), // 2 green
    Color(0xFFE0A03A), // 3 yellow
    Color(0xFF4C8DF6), // 4 blue — lightened; pure blue on dark is unreadable
    Color(0xFFB07BD4), // 5 magenta
    Color(0xFF2AB7CA), // 6 cyan
    Color(0xFFC3CBD5), // 7 white
    Color(0xFF4B5563), // 8 bright black
    Color(0xFFFF7B72), // 9 bright red
    Color(0xFF8FE39A), // 10 bright green
    Color(0xFFFFC86B), // 11 bright yellow
    Color(0xFF79B8FF), // 12 bright blue
    Color(0xFFD2A8FF), // 13 bright magenta
    Color(0xFF56D4DD), // 14 bright cyan
    Color(0xFFF0F6FC), // 15 bright white
)

private val LightAnsi = listOf(
    Color(0xFF11161B),
    Color(0xFFB3261E),
    Color(0xFF1A7F37),
    Color(0xFF8A5D06),
    Color(0xFF0A55B8),
    Color(0xFF7B3FA8),
    Color(0xFF11707D),
    Color(0xFF56606B),
    Color(0xFF7A838D),
    Color(0xFFCF432C),
    Color(0xFF2DA44E),
    Color(0xFFB07408),
    Color(0xFF2A6FD6),
    Color(0xFF9550C7),
    Color(0xFF1D8B99),
    Color(0xFF11161B),
)

@Composable
@ReadOnlyComposable
fun terminalPalette(): TerminalPalette {
    val scheme = MaterialTheme.colorScheme
    // Luminance of the surface tells us which set to use, so the terminal follows the app's
    // theme rather than needing its own light/dark switch.
    val isDark = scheme.surface.luminanceIsDark()

    return TerminalPalette(
        foreground = scheme.onSurface,
        background = scheme.surface,
        cursor = scheme.primary,
        colors = if (isDark) DarkAnsi else LightAnsi,
    )
}

private fun Color.luminanceIsDark(): Boolean =
    (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5

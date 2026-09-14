package dev.mobileforge.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The single source of colour, type and spacing for the whole app.
 *
 * No feature module defines a raw colour — that rule is what makes editor theming and, later,
 * extension-provided themes tractable rather than a search-and-replace exercise
 * (docs/adr/ADR-001-compose-architecture.md).
 *
 * The palette is deliberately restrained and information-dense: this is a developer tool, and
 * the brief calls for density over decoration. Dark is the primary design target because that
 * is where developers actually work.
 */

// A slate/steel palette. Neutral enough that syntax colours stay the loudest thing on screen,
// which is the correct hierarchy for a code editor.
private val ForgeBlue = Color(0xFF4C8DF6)
private val ForgeBlueDark = Color(0xFF1F5FBF)
private val ForgeCyan = Color(0xFF2AB7CA)
private val ForgeAmber = Color(0xFFE0A03A)
private val ForgeRed = Color(0xFFE0524A)

private val SurfaceDark = Color(0xFF0D1117)
private val SurfaceDarkElevated = Color(0xFF161B22)
private val SurfaceDarkHigh = Color(0xFF1F2630)
private val OutlineDark = Color(0xFF30363D)
private val OnSurfaceDark = Color(0xFFE6EDF3)
private val OnSurfaceVariantDark = Color(0xFF9BA6B2)

private val SurfaceLight = Color(0xFFFBFCFD)
private val SurfaceLightElevated = Color(0xFFF2F4F7)
private val SurfaceLightHigh = Color(0xFFE7EAEF)
private val OutlineLight = Color(0xFFD3D8DE)
private val OnSurfaceLight = Color(0xFF11161B)
private val OnSurfaceVariantLight = Color(0xFF56606B)

private val DarkColors = darkColorScheme(
    primary = ForgeBlue,
    onPrimary = Color.White,
    primaryContainer = ForgeBlueDark,
    onPrimaryContainer = Color.White,
    secondary = ForgeCyan,
    onSecondary = Color(0xFF06202B),
    tertiary = ForgeAmber,
    onTertiary = Color(0xFF2A1D02),
    error = ForgeRed,
    onError = Color.White,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceDarkElevated,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainer = SurfaceDarkElevated,
    surfaceContainerHigh = SurfaceDarkHigh,
    outline = OutlineDark,
    outlineVariant = Color(0xFF232A33),
)

private val LightColors = lightColorScheme(
    primary = ForgeBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E5FB),
    onPrimaryContainer = Color(0xFF0A2A57),
    secondary = Color(0xFF11707D),
    onSecondary = Color.White,
    tertiary = Color(0xFF8A5D06),
    onTertiary = Color.White,
    error = Color(0xFFB3261E),
    onError = Color.White,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceLightElevated,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainer = SurfaceLightElevated,
    surfaceContainerHigh = SurfaceLightHigh,
    outline = OutlineLight,
    outlineVariant = Color(0xFFE3E7EC),
)

/**
 * Monospace styles used by anything that shows code, paths or command output.
 *
 * Exposed through a CompositionLocal rather than hard-coded so the editor, the future terminal
 * and the diagnostics screen stay visually consistent, and so font size becomes a single
 * settable preference later.
 */
data class ForgeCodeTypography(
    val code: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    val codeSmall: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
    val path: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
    ),
)

val LocalForgeCodeTypography = staticCompositionLocalOf { ForgeCodeTypography() }

/**
 * Dark/light/system selection.
 *
 * Dynamic colour is intentionally NOT used. A code editor needs a stable, predictable palette:
 * wallpaper-derived accents fight syntax highlighting and make the UI inconsistent between
 * devices, which is the opposite of what a tool should do.
 */
@Composable
fun MobileForgeTheme(
    themeMode: ForgeThemeMode = ForgeThemeMode.System,
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode) {
        ForgeThemeMode.System -> isSystemInDarkTheme()
        ForgeThemeMode.Dark -> true
        ForgeThemeMode.Light -> false
    }

    CompositionLocalProvider(LocalForgeCodeTypography provides ForgeCodeTypography()) {
        MaterialTheme(
            colorScheme = if (useDark) DarkColors else LightColors,
            typography = ForgeTypography,
            content = content,
        )
    }
}

enum class ForgeThemeMode {
    System,
    Light,
    Dark,
    ;

    val displayName: String
        get() = when (this) {
            System -> "Follow system"
            Light -> "Light"
            Dark -> "Dark"
        }
}

/** Slightly tightened from the Material defaults — this is a dense tool, not a content app. */
private val ForgeTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

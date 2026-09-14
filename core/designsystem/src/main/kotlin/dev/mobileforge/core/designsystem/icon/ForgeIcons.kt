package dev.mobileforge.core.designsystem.icon

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The app's icon vocabulary, in one place.
 *
 * Icons come from the CORE Material set that ships with material3. The `material-icons-extended`
 * artifact is deliberately NOT a dependency: it added roughly 44 MB of dex to the debug APK in
 * exchange for a handful of glyphs, which is not a trade a mobile IDE should make. That was
 * measured against a real build, not assumed — see the note in core/designsystem/build.gradle.kts.
 *
 * The core set has no folder, file or code glyph, so those three are declared here as small
 * local vectors. Three hand-written paths cost bytes; the extended artifact costs megabytes.
 *
 * Naming icons semantically (rather than using `Icons.Filled.*` at call sites) means a later
 * swap to a custom icon set is a change in this file, not a search across every feature module.
 */
object ForgeIcons {

    // ---- from the core Material set ----
    val Add: ImageVector = Icons.Filled.Add
    val Back: ImageVector = Icons.AutoMirrored.Filled.ArrowBack
    val Check: ImageVector = Icons.Filled.Check
    val CheckCircle: ImageVector = Icons.Filled.CheckCircle
    val Close: ImageVector = Icons.Filled.Close
    val Delete: ImageVector = Icons.Filled.Delete
    val Edit: ImageVector = Icons.Filled.Edit
    val Info: ImageVector = Icons.Filled.Info
    val More: ImageVector = Icons.Filled.MoreVert
    val Refresh: ImageVector = Icons.Filled.Refresh
    val Search: ImageVector = Icons.Filled.Search
    val Settings: ImageVector = Icons.Filled.Settings
    val Warning: ImageVector = Icons.Filled.Warning
    val Terminal: ImageVector = Icons.Filled.Build
    val ChevronRight: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight
    val ChevronDown: ImageVector = Icons.Filled.KeyboardArrowDown

    // ---- local vectors: absent from the core set ----
    val Folder: ImageVector by lazy { buildFolder() }
    val File: ImageVector by lazy { buildFile() }
    val Code: ImageVector by lazy { buildCode() }

    /** Alias used where a document, rather than a generic file, is meant. */
    val Description: ImageVector get() = File

    /** Unselected radio marker. The core set has no outlined circle. */
    val Circle: ImageVector by lazy { buildCircleOutline() }
}

private fun materialVector(
    name: String,
    block: ImageVector.Builder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply(block).build()

private fun buildFolder(): ImageVector = materialVector("Forge.Folder") {
    path(fill = SolidColor(Color.White)) {
        moveTo(10f, 4f)
        horizontalLineTo(4f)
        curveTo(2.9f, 4f, 2.01f, 4.9f, 2.01f, 6f)
        lineTo(2f, 18f)
        curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
        horizontalLineTo(20f)
        curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
        verticalLineTo(8f)
        curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
        horizontalLineTo(12f)
        lineTo(10f, 4f)
        close()
    }
}

private fun buildFile(): ImageVector = materialVector("Forge.File") {
    path(fill = SolidColor(Color.White)) {
        moveTo(6f, 2f)
        curveTo(4.9f, 2f, 4.01f, 2.9f, 4.01f, 4f)
        lineTo(4f, 20f)
        curveTo(4f, 21.1f, 4.89f, 22f, 5.99f, 22f)
        horizontalLineTo(18f)
        curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
        verticalLineTo(8f)
        lineTo(14f, 2f)
        horizontalLineTo(6f)
        close()
        moveTo(13f, 9f)
        verticalLineTo(3.5f)
        lineTo(18.5f, 9f)
        horizontalLineTo(13f)
        close()
    }
}

/** The angle-bracket pair used for source files and code-flavoured badges. */
private fun buildCode(): ImageVector = materialVector("Forge.Code") {
    path(fill = SolidColor(Color.White)) {
        moveTo(9.4f, 16.6f)
        lineTo(4.8f, 12f)
        lineTo(9.4f, 7.4f)
        lineTo(8f, 6f)
        lineTo(2f, 12f)
        lineTo(8f, 18f)
        lineTo(9.4f, 16.6f)
        close()
        moveTo(14.6f, 16.6f)
        lineTo(19.2f, 12f)
        lineTo(14.6f, 7.4f)
        lineTo(16f, 6f)
        lineTo(22f, 12f)
        lineTo(16f, 18f)
        lineTo(14.6f, 16.6f)
        close()
    }
}

private fun buildCircleOutline(): ImageVector = materialVector("Forge.CircleOutline") {
    path(fill = SolidColor(Color.White)) {
        moveTo(12f, 2f)
        curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
        curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
        curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
        curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
        close()
        moveTo(12f, 20f)
        curveTo(7.59f, 20f, 4f, 16.41f, 4f, 12f)
        curveTo(4f, 7.59f, 7.59f, 4f, 12f, 4f)
        curveTo(16.41f, 4f, 20f, 7.59f, 20f, 12f)
        curveTo(20f, 16.41f, 16.41f, 20f, 12f, 20f)
        close()
    }
}

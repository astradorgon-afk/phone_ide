package dev.mobileforge.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobileforge.core.designsystem.component.ErrorBanner
import dev.mobileforge.core.designsystem.theme.TerminalPalette
import dev.mobileforge.core.designsystem.theme.terminalPalette
import dev.mobileforge.runtime.pty.Cell
import dev.mobileforge.runtime.pty.CellStyle
import dev.mobileforge.runtime.pty.TerminalUiState

/**
 * The terminal pane.
 *
 * Each row is rendered as one `AnnotatedString`, with runs of identically-styled cells merged
 * into a single span. A naive span-per-character would be ~2000 spans per screen, which Compose
 * re-pays on every recomposition and which shows up immediately as stutter while output scrolls.
 */
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val palette = terminalPalette()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { viewModel.start() }

    // Follow new output only when the user is already at the bottom. Yanking the view down
    // while someone is reading earlier output is one of the more irritating things a terminal
    // can do to you.
    val pinnedToBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }

    LaunchedEffect(state.revision) {
        if (pinnedToBottom && state.lines.isNotEmpty()) {
            listState.scrollToItem(state.lines.lastIndex)
        }
    }

    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val canOpen by viewModel.canOpenSession.collectAsStateWithLifecycle()

    Column(modifier.background(palette.background).imePadding()) {
        state.error?.let { ErrorBanner(error = it, onDismiss = {}) }

        // Only worth the vertical space once there is a choice to make. A single-session
        // terminal on a phone should not spend a row of screen on a strip of one tab.
        if (tabs.size > 1 || canOpen) {
            TerminalTabStrip(
                tabs = tabs,
                canOpen = canOpen,
                onSelect = viewModel::selectSession,
                onClose = viewModel::closeSession,
                onOpen = viewModel::openSession,
            )
            HorizontalDivider()
        }

        TerminalGrid(
            state = state,
            palette = palette,
            listState = listState,
            onMeasured = viewModel::resize,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        state.exitSummary?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        HorizontalDivider()

        TerminalInput(
            enabled = state.isRunning,
            onSend = viewModel::send,
            onInterrupt = viewModel::interrupt,
            onEndOfFile = viewModel::sendEndOfFile,
        )
    }
}

/**
 * Session tabs.
 *
 * Closing is offered only on the active tab. On a phone the tap targets are already small, and
 * a close affordance on every tab is the kind of thing a thumb hits by accident — which here
 * would kill a running process.
 */
@Composable
private fun TerminalTabStrip(
    tabs: List<TerminalTab>,
    canOpen: Boolean,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val background = if (tab.isActive) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                Color.Transparent
            }
            Row(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(background)
                    .clickable { onSelect(tab.id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (tab.isActive) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (tab.isActive && tabs.size > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "×",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onClose(tab.id) }
                            .padding(horizontal = 4.dp)
                            .semantics { contentDescription = "Close session ${tab.label}" },
                    )
                }
            }
        }

        if (canOpen) {
            Text(
                text = "+",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .semantics { contentDescription = "New terminal session" },
            )
        }
    }
}

@Composable
private fun TerminalGrid(
    state: TerminalUiState,
    palette: TerminalPalette,
    listState: LazyListState,
    onMeasured: (rows: Int, cols: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyle = remember {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
    }

    // Measuring one glyph is exact for a monospace font, and the resulting grid is what gets
    // reported to the PTY through TIOCSWINSZ — get it wrong and the shell wraps at the wrong
    // column.
    val measurer = rememberTextMeasurer()
    val charSize = remember(textStyle) { measurer.measure(AnnotatedString("M"), textStyle).size }

    val horizontalScroll = rememberScrollState()

    // The cursor sits on the last `rows` lines of the buffer; everything before that is
    // scrollback and has no cursor.
    val cursorLineIndex = state.lines.size - state.rows + state.cursorRow

    Box(
        modifier = modifier.onSizeChanged { size ->
            if (charSize.width > 0 && charSize.height > 0) {
                onMeasured(
                    (size.height / charSize.height).coerceIn(MIN_ROWS, MAX_ROWS),
                    (size.width / charSize.width).coerceIn(MIN_COLS, MAX_COLS),
                )
            }
        },
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxSize().horizontalScroll(horizontalScroll),
        ) {
            items(state.lines.size) { index ->
                Text(
                    text = state.lines[index].toStyledRow(
                        palette = palette,
                        cursorCol = if (index == cursorLineIndex && state.isRunning &&
                            state.cursorVisible
                        ) {
                            state.cursorCol
                        } else {
                            NO_CURSOR
                        },
                    ),
                    style = textStyle,
                    color = palette.foreground,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * Builds one styled row, merging runs of identical style.
 *
 * The cursor is drawn as an inverted cell rather than a positioned overlay: it stays aligned
 * with the text at any font size or density, which a separately-placed rectangle does not.
 */
private fun List<Cell>.toStyledRow(
    palette: TerminalPalette,
    cursorCol: Int,
): AnnotatedString = buildAnnotatedString {
    if (isEmpty()) {
        append(" ")
        return@buildAnnotatedString
    }

    val cursorStyle = SpanStyle(color = palette.background, background = palette.cursor)

    var index = 0
    while (index < size) {
        if (index == cursorCol) {
            // A continuation cell carries no text of its own, but the cursor still has to be
            // visible when escape sequences park it on the right half of a wide character.
            val cell = this@toStyledRow[index]
            withStyle(cursorStyle) { append(cell.text.ifEmpty { " " }) }
            index++
            continue
        }

        // Extend the run while the style is unchanged and we have not reached the cursor.
        val style = this@toStyledRow[index].style
        var end = index + 1
        while (end < size && this@toStyledRow[end].style == style && end != cursorCol) end++

        val text = subList(index, end).joinToString("") { it.text }
        withStyle(style.toSpanStyle(palette)) { append(text) }
        index = end
    }
}

private fun CellStyle.toSpanStyle(palette: TerminalPalette): SpanStyle {
    val resolvedForeground = palette.resolve(foreground, palette.foreground)
    val resolvedBackground = palette.resolve(background, Color.Unspecified)

    return SpanStyle(
        color = if (inverse) palette.background else resolvedForeground,
        background = if (inverse) resolvedForeground else resolvedBackground,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None,
    )
}

private const val NO_CURSOR = -1
private const val MIN_COLS = 20
private const val MAX_COLS = 400
private const val MIN_ROWS = 4
private const val MAX_ROWS = 200

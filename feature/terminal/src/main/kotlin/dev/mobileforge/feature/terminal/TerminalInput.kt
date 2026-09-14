package dev.mobileforge.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Terminal input for a touch device.
 *
 * A phone's soft keyboard has no Ctrl, no Esc, no Tab and no arrows — every key a terminal
 * actually depends on. Without the key row above the field, this would be a text box that can
 * run commands but cannot interrupt one, complete a path, or recall history: technically a
 * terminal, practically useless.
 *
 * Input is line-based rather than character-by-character. The PTY has canonical mode on
 * (`ICANON` in pty.c), so the kernel does the line editing; sending whole lines matches that and
 * avoids fighting the soft keyboard's own autocomplete and composition behaviour.
 */
@Composable
internal fun TerminalInput(
    enabled: Boolean,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onEndOfFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }

    Column(modifier.fillMaxWidth()) {
        TerminalKeyRow(
            enabled = enabled,
            onInterrupt = onInterrupt,
            onEndOfFile = onEndOfFile,
            onControlSequence = onSend,
            onTab = {
                // Tab must reach the shell for completion; it is never inserted as text.
                if (text.isNotEmpty()) {
                    onSend(text + "\t")
                    text = ""
                } else {
                    onSend("\t")
                }
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )

            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = enabled,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(
                        // Autocorrect and capitalisation are actively harmful here: a shell
                        // command is not prose, and "Ls" or "ls." does not run.
                        autoCorrect = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            onSend(text + "\n")
                            text = ""
                        },
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(
                        MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 28.dp)
                        // Hardware keys are intercepted BEFORE the text field sees them, or
                        // Ctrl+C would type "c" and Escape would do nothing. Only keys the
                        // terminal claims are consumed; everything else falls through so
                        // ordinary typing, selection and the IME behave normally.
                        .onPreviewKeyEvent { event ->
                            if (!enabled || event.type != KeyEventType.KeyDown) {
                                return@onPreviewKeyEvent false
                            }
                            handleHardwareKey(
                                event = event,
                                pending = text,
                                onSend = { payload -> onSend(payload); text = "" },
                                // Ctrl+C abandons the half-typed line, as a real terminal does.
                                onInterrupt = { onInterrupt(); text = "" },
                                onEndOfFile = onEndOfFile,
                            )
                        },
                )

                if (text.isEmpty()) {
                    Text(
                        text = if (enabled) "Type a command" else "Session ended",
                        style = LocalTextStyle.current.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Applies one hardware key press, returning true when the terminal consumed it.
 *
 * Anything already typed at the prompt is flushed ahead of the key. Without that, pressing Tab
 * after typing `git chec` would send a bare Tab and the shell would complete nothing — the
 * characters are still sitting in the text field, not in the shell.
 */
private fun handleHardwareKey(
    event: KeyEvent,
    pending: String,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onEndOfFile: () -> Unit,
): Boolean {
    val action = HardwareKeyMap.resolve(
        keyCode = event.nativeKeyEvent.keyCode,
        ctrl = event.isCtrlPressed,
        alt = event.isAltPressed,
        shift = event.isShiftPressed,
    )

    return when (action) {
        is HardwareKeyAction.Send -> {
            onSend(pending + action.text)
            true
        }

        HardwareKeyAction.Interrupt -> {
            onInterrupt()
            true
        }

        HardwareKeyAction.EndOfFile -> {
            onEndOfFile()
            true
        }

        HardwareKeyAction.Unhandled -> false
    }
}

/**
 * The keys a soft keyboard does not provide.
 *
 * Ctrl+C is a real SIGINT to the process group, not the literal byte 0x03 — so it works even
 * when a program has put the terminal in raw mode and is not reading its own input.
 */
@Composable
private fun TerminalKeyRow(
    enabled: Boolean,
    onInterrupt: () -> Unit,
    onEndOfFile: () -> Unit,
    onControlSequence: (String) -> Unit,
    onTab: () -> Unit,
) {
    val scroll = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyChip("Ctrl+C", enabled, onInterrupt)
        KeyChip("Ctrl+D", enabled, onEndOfFile)
        KeyChip("Tab", enabled, onTab)
        KeyChip("Esc", enabled) { onControlSequence("\u001B") }
        // Arrow keys as the escape sequences a shell expects, which is how history recall and
        // line editing work at all.
        KeyChip("↑", enabled) { onControlSequence("\u001B[A") }
        KeyChip("↓", enabled) { onControlSequence("\u001B[B") }
        KeyChip("←", enabled) { onControlSequence("\u001B[D") }
        KeyChip("→", enabled) { onControlSequence("\u001B[C") }
        KeyChip("Ctrl+L", enabled) { onControlSequence("\u000C") }
        KeyChip("|", enabled) { onControlSequence("|") }
        KeyChip("~", enabled) { onControlSequence("~") }
        KeyChip("/", enabled) { onControlSequence("/") }
        KeyChip("-", enabled) { onControlSequence("-") }
    }
}

@Composable
private fun KeyChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            // 32dp minimum: these are hit with a thumb, often in a hurry to stop something.
            .heightIn(min = 32.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
    }
}

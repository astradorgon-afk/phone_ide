package dev.mobileforge.feature.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobileforge.core.designsystem.component.EmptyState
import dev.mobileforge.core.designsystem.component.ErrorBanner
import dev.mobileforge.core.designsystem.icon.ForgeIcons
import dev.mobileforge.core.designsystem.theme.LocalForgeCodeTypography
import dev.mobileforge.runtime.laravel.ArtisanResult

/**
 * Running artisan without leaving the IDE.
 *
 * Commands the risk model flags always ask first — the dialog's confirm button is the only
 * path that runs them confirmed. A missing toolchain or a non-Laravel project is reported
 * plainly with the next step, never as a disabled button with no explanation.
 */
@Composable
fun ArtisanPane(
    viewModel: ArtisanViewModel,
    isLaravel: Boolean,
    frameworkLabel: String?,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (!isLaravel) {
        EmptyState(
            icon = ForgeIcons.CheckCircle,
            title = "Not a Laravel project",
            message = "Artisan runs in Laravel projects. " +
                (frameworkLabel?.let { "This project was detected as $it." }
                    ?: "This project was not detected as Laravel."),
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    when (val php = state.php) {
        PhpStatus.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is PhpStatus.Missing -> EmptyState(
            icon = ForgeIcons.Warning,
            title = php.message,
            message = listOfNotNull(php.detail, php.recovery).joinToString(" "),
            actionLabel = "Retry",
            onAction = viewModel::refreshTool,
            modifier = modifier.fillMaxSize(),
        )

        is PhpStatus.Ready -> ArtisanConsole(state, viewModel, modifier)
    }

    state.pendingConfirmation?.let { command ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPending,
            title = { Text("Run \"php artisan ${command.argv.joinToString(" ")}\"?") },
            text = { Text(state.confirmationReason ?: "This command can affect your data.") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPending) { Text("Run it") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPending) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ArtisanConsole(
    state: ArtisanUiState,
    viewModel: ArtisanViewModel,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }

    Column(modifier.fillMaxSize()) {
        state.error?.let { ErrorBanner(it, onDismiss = viewModel::dismissError) }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                VersionsLine(state)
                HorizontalDivider()
            }

            item {
                Text(
                    text = "Quick commands",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            items(state.quickCommands, key = { it.name }) { quick ->
                QuickRow(
                    quick = quick,
                    enabled = !state.running,
                    onRun = {
                        input = ""
                        viewModel.runQuick(quick.name)
                    },
                )
            }

            item {
                HorizontalDivider()
                CommandInput(
                    input = input,
                    onInput = { input = it },
                    running = state.running,
                    onRun = {
                        viewModel.submit(input)
                    },
                    onStop = viewModel::stop,
                )
                HorizontalDivider()
            }

            if (state.running) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = state.activeCommand ?: "Running…",
                            style = LocalForgeCodeTypography.current.codeSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            state.lastResult?.let { result ->
                item {
                    ResultConsole(result = result, stopped = state.stopped)
                }
            }
        }
    }
}

@Composable
private fun VersionsLine(state: ArtisanUiState) {
    val parts = listOfNotNull(
        state.versions?.php?.let { "PHP $it" },
        state.versions?.laravel?.let { "Laravel $it" },
    )
    Text(
        text = if (parts.isEmpty()) {
            "Tool versions unknown — the tools did not answer."
        } else {
            parts.joinToString(" · ")
        },
        style = LocalForgeCodeTypography.current.codeSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun QuickRow(quick: QuickCommand, enabled: Boolean, onRun: () -> Unit) {
    OutlinedButton(
        onClick = onRun,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Text(
            text = quick.name,
            style = LocalForgeCodeTypography.current.code,
            modifier = Modifier.weight(1f),
        )
        if (quick.requiresConfirmation) {
            Spacer(Modifier.width(4.dp))
            Icon(
                ForgeIcons.Warning,
                contentDescription = "Asks for confirmation",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun CommandInput(
    input: String,
    onInput: (String) -> Unit,
    running: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            enabled = !running,
            label = { Text("php artisan …") },
            singleLine = true,
            textStyle = LocalForgeCodeTypography.current.code,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (running) {
                Button(onClick = onStop) { Text("Stop") }
            } else {
                Button(onClick = onRun, enabled = input.isNotBlank()) { Text("Run") }
            }
        }
    }
}

@Composable
private fun ResultConsole(result: ArtisanResult, stopped: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (result.succeeded && !stopped) {
                    "Exit ${result.exitCode}"
                } else if (stopped) {
                    "Stopped — exit ${result.exitCode}"
                } else {
                    "Exit ${result.exitCode}"
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (result.succeeded && !stopped) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = result.command.argv.joinToString(" "),
                style = LocalForgeCodeTypography.current.codeSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        // Bounded display, full fidelity underneath: the result keeps every line, the screen
        // shows the tail of each stream. An artisan test run can print thousands of lines; a
        // phone cannot usefully scroll all of them, but the data must not be silently
        // discarded either. Streams stay apart, as the result models them.
        StreamBlock(lines = result.stdout, isStdout = true)
        StreamBlock(lines = result.stderr, isStdout = false)
    }
}

@Composable
private fun StreamBlock(lines: List<String>, isStdout: Boolean) {
    if (lines.isEmpty()) return
    if (lines.size > MAX_CONSOLE_LINES) {
        Text(
            text = "Showing last $MAX_CONSOLE_LINES of ${lines.size} " +
                if (isStdout) "output lines." else "error lines.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    lines.takeLast(MAX_CONSOLE_LINES).forEach { line ->
        Text(
            text = line,
            style = LocalForgeCodeTypography.current.codeSmall,
            color = if (isStdout) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

/** How much of a result the screen renders. The rest is one scroll away in the terminal. */
private const val MAX_CONSOLE_LINES = 300

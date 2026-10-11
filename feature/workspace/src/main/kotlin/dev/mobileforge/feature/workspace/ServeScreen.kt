package dev.mobileforge.feature.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobileforge.core.designsystem.component.EmptyState
import dev.mobileforge.core.designsystem.component.ErrorBanner
import dev.mobileforge.core.designsystem.icon.ForgeIcons
import dev.mobileforge.core.designsystem.theme.LocalForgeCodeTypography

/**
 * Start a local server for this project.
 *
 * The pane exists because "how do I see the site" should be a choice the user makes, not a
 * command they have to know: pick the PHP dev server or nginx, pick a port, press Start. What is
 * running, on which URL, and any output are shown while it runs, with a Stop that actually frees
 * the port.
 *
 * A missing dependency is never a dead disabled button: nginx that is not installed says so and
 * offers the way to install it, exactly as a missing PHP toolchain does.
 */
@Composable
fun ServePane(
    viewModel: ServeViewModel,
    isLaravel: Boolean,
    frameworkLabel: String?,
    onOpenToolchains: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (!isLaravel) {
        EmptyState(
            icon = ForgeIcons.Warning,
            title = "Not a Laravel project",
            message = "Serving targets a Laravel project's public directory. " +
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
            onAction = viewModel::refreshTools,
            modifier = modifier.fillMaxSize(),
        )

        is PhpStatus.Ready -> ServeBoard(state, viewModel, onOpenToolchains, modifier)
    }
}

@Composable
private fun ServeBoard(
    state: ServeUiState,
    viewModel: ServeViewModel,
    onOpenToolchains: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize()) {
        state.error?.let { error ->
            item { ErrorBanner(error, onDismiss = viewModel::dismissError) }
        }

        item { KindSelector(state, viewModel) }

        item { PortField(state, viewModel) }

        if (state.nginxMissing) {
            item { NginxInstallHint(onOpenToolchains) }
        }

        item {
            HorizontalDivider()
            StartStop(state, viewModel)
            HorizontalDivider()
        }

        if (state.running || state.url != null) {
            item { RunningStatus(state) }
        }

        if (state.output.isNotEmpty()) {
            item { ServerOutput(state.output) }
        }
    }
}

@Composable
private fun KindSelector(state: ServeUiState, viewModel: ServeViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Serve this project with",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ServeKind.entries.forEach { kind ->
                FilterChip(
                    selected = state.kind == kind,
                    onClick = { viewModel.selectKind(kind) },
                    enabled = !state.running,
                    label = { Text(kind.label) },
                )
            }
        }
    }
}

@Composable
private fun PortField(state: ServeUiState, viewModel: ServeViewModel) {
    OutlinedTextField(
        value = state.portText,
        onValueChange = viewModel::setPort,
        enabled = !state.running,
        label = { Text("Port") },
        singleLine = true,
        isError = state.portText.isNotEmpty() && !state.portValid,
        supportingText = {
            Text(
                if (state.portText.isNotEmpty() && !state.portValid) {
                    "Enter a port between 1024 and 65535."
                } else {
                    "The app reaches this at 127.0.0.1. Forward it to a computer with " +
                        "`adb forward tcp:${state.portText.ifBlank { "8000" }} " +
                        "tcp:${state.portText.ifBlank { "8000" }}`."
                },
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = LocalForgeCodeTypography.current.code,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    )
}

/**
 * The nginx "not installed" hint.
 *
 * Kept in the pane rather than hidden until installed, because the choice is the feature — a user
 * who picks nginx should learn immediately what it needs and be one tap from fixing it, instead of
 * wondering why the option is missing.
 */
@Composable
private fun NginxInstallHint(onOpenToolchains: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            ForgeIcons.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "nginx is not installed yet. Install it, then choose it here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenToolchains) { Text("Install") }
    }
}

@Composable
private fun StartStop(state: ServeUiState, viewModel: ServeViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.weight(1f))
        if (state.running) {
            Button(onClick = viewModel::stop) {
                Icon(ForgeIcons.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Stop")
            }
        } else {
            Button(onClick = viewModel::start, enabled = state.canStart) {
                Icon(ForgeIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Start")
            }
        }
    }
}

@Composable
private fun RunningStatus(state: ServeUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.running) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Text(
                text = if (state.running) {
                    "${state.kind.label} running"
                } else {
                    "${state.kind.label} stopped"
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
        }
        state.url?.let { url ->
            Text(
                text = url,
                style = LocalForgeCodeTypography.current.code,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ServerOutput(lines: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "Output",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lines.takeLast(MAX_SERVER_OUTPUT_LINES).forEach { line ->
            Text(
                text = line,
                style = LocalForgeCodeTypography.current.codeSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** How much of the server output the pane renders. The rest stays bounded in state. */
private const val MAX_SERVER_OUTPUT_LINES = 200

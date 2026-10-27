package dev.mobileforge.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobileforge.core.designsystem.component.ErrorBanner
import dev.mobileforge.core.designsystem.icon.ForgeIcons

/**
 * First-run toolchain setup: the development tools, installed, not downloaded.
 *
 * Everything here ships inside the APK, so this works fully offline — no account, no
 * store, no network. Skipping is always available and persists: the setup earns its
 * interruption exactly once, and anything can still be installed later from Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onFinished: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // System back means "not now", same as Skip — never a trap with no way out.
    BackHandler(enabled = !state.busy) { onSkip() }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Development tools") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("Everything the IDE runs on", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "PHP, Composer and the shell tools ship inside this app and install " +
                        "directly into its private storage. No download, no account.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (state.loading) {
                item { CircularProgressIndicator() }
            } else if (state.items.isEmpty()) {
                item {
                    Text(
                        "This build bundles no toolchains, so there is nothing to install. " +
                            "You can still browse and edit files, and import bundles later " +
                            "from Settings → Toolchains.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item { Button(onClick = onFinished) { Text("Continue") } }
            } else {
                items(state.items, key = { it.id }) { item ->
                    SetupRow(item = item)
                }

                val pendingBytes = state.pendingBytes
                if (pendingBytes > 0 && !state.done) {
                    item {
                        Text(
                            "${formatMegabytes(pendingBytes)} to install.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                state.items.filter { it.status == SetupItemStatus.MISSING }.forEach { missing ->
                    item {
                        Text(
                            "“${missing.id}” is not included in the app itself — install it from " +
                                "Settings → Toolchains if you need it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (state.busy) {
                    item {
                        LinearProgressIndicator(
                            progress = { state.overallProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        state.currentName?.let {
                            Text(
                                "Installing $it…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                state.error?.let { error ->
                    item { ErrorBanner(error, onDismiss = viewModel::dismissError) }
                    item {
                        OutlinedButton(onClick = viewModel::installAll, enabled = !state.busy) {
                            Text("Retry")
                        }
                    }
                }

                if (state.done) {
                    item {
                        Text(
                            "Installed. Open a new terminal to use the tools.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    item { Button(onClick = onFinished) { Text("Continue") } }
                } else if (!state.busy) {
                    item {
                        Button(
                            onClick = viewModel::installAll,
                            enabled = state.pending.isNotEmpty(),
                        ) { Text("Install tools") }
                    }
                    item {
                        OutlinedButton(onClick = onSkip) { Text("Skip for now") }
                    }
                }

                item { HorizontalDivider() }
                item {
                    Text(
                        "Each bundle is checksum-verified before anything is written, and a " +
                            "failed install leaves no half-installed files behind.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupRow(item: SetupItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (item.status) {
            SetupItemStatus.DONE -> Icon(
                ForgeIcons.CheckCircle,
                contentDescription = "${item.displayName} installed",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            SetupItemStatus.INSTALLING -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            SetupItemStatus.MISSING -> Icon(
                ForgeIcons.Warning,
                contentDescription = "${item.displayName} not included in the app",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            SetupItemStatus.PENDING -> Icon(
                ForgeIcons.Circle,
                contentDescription = "${item.displayName} pending",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(item.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                if (item.version.isNotEmpty()) {
                    "${item.version} · ${formatMegabytes(item.sizeBytes)}"
                } else {
                    when (item.status) {
                        SetupItemStatus.DONE -> "Already installed"
                        else -> "Not included in the app — install from Toolchains"
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatMegabytes(bytes: Long): String {
    val megabytes = bytes / 1_048_576.0
    return if (megabytes < 0.1 && bytes > 0) {
        "$bytes bytes"
    } else {
        "%.1f MB".format(megabytes)
    }
}

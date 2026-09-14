package dev.mobileforge.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobileforge.core.designsystem.icon.ForgeIcons

/** A local bundle import: review publisher metadata before allowing executable installation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolchainScreen(viewModel: ToolchainViewModel, onBack: () -> Unit,
    onSelectManifest: () -> Unit, onSelectArchive: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Toolchains") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(ForgeIcons.Back, "Back") }
        })
    }) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Install a bundle", style = MaterialTheme.typography.headlineSmall)
                Text("Choose a MobileForge JSON manifest and its ZIP archive. " +
                    "Bundles must match this app’s install location and device architecture.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            item {
                OutlinedButton(onClick = onSelectManifest, enabled = !state.busy) {
                    Text("Choose manifest")
                }
            }
            state.selection?.let { selection ->
                item {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(selection.name, style = MaterialTheme.typography.titleLarge)
                            Text("${selection.version} · ${selection.abi} · ${selection.sizeBytes} bytes")
                            Text("License: ${selection.license}")
                            Text("Source: ${selection.sourceUrl}")
                            Text(selection.prefix, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall)
                            Text("SHA-256\n${selection.sha256}", fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val incompatibility = selection.incompatibility
                if (incompatibility != null) {
                    item { Text(incompatibility, color = MaterialTheme.colorScheme.error) }
                } else {
                    item {
                        Text("Only install bundles from a publisher you trust. The checksum checks " +
                            "the archive against this manifest; it does not authenticate its publisher.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        OutlinedButton(onClick = onSelectArchive, enabled = !state.busy) {
                            Text(if (state.archiveDocument == null) "Choose ZIP archive" else "Change ZIP archive")
                        }
                    }
                    item {
                        Button(onClick = viewModel::install,
                            enabled = !state.busy && state.archiveDocument != null) {
                            Text("Install ${selection.name}")
                        }
                    }
                }
            }
            if (state.busy) item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (state.progress > 0) Text("Installing: ${(state.progress * 100).toInt()}%")
            }
            state.error?.let { error -> item {
                Text(error.message, color = MaterialTheme.colorScheme.error)
                error.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error.recovery?.let { Text(it) }
            } }
            state.message?.let { message -> item { Text(message) } }
            item { HorizontalDivider() }
            item { Text("Installed bundles", style = MaterialTheme.typography.titleLarge) }
            if (state.installed.isEmpty()) item {
                Text("No imported bundles are recorded. Android’s system shell is still available.")
            }
            items(state.installed, key = { it.id }) { installed ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(installed.name, style = MaterialTheme.typography.titleMedium)
                    Text("${installed.version} · ${installed.abi} · ${installed.license}")
                    Text(if (installed.filesPresent) "Executable files present; runtime not verified."
                        else "Executable files missing or none declared. Reimport the bundle.",
                        style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text(installed.sourceUrl, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { OutlinedButton(onClick = viewModel::refresh, enabled = !state.busy) { Text("Refresh") } }
        }
    }
}

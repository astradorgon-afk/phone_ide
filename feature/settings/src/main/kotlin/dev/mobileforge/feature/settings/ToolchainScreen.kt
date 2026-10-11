package dev.mobileforge.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobileforge.core.designsystem.icon.ForgeIcons

/**
 * Install toolchains.
 *
 * The screen leads with *install*, not *find a file*: the versions are fixed by MobileForge
 * and fetched automatically, so a person never has to know what a manifest is. Importing a
 * bundle by hand still exists, because a reviewer or an offline user may have one, but it is
 * tucked behind [Advanced] rather than being the only way forward.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolchainScreen(viewModel: ToolchainViewModel, onBack: () -> Unit,
    onSelectManifest: () -> Unit, onSelectArchive: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var advancedOpen by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Toolchains") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(ForgeIcons.Back, "Back") }
        })
    }) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Install the tools", style = MaterialTheme.typography.headlineSmall)
                Text("MobileForge downloads the tools your projects need. The versions are " +
                    "chosen by the app, so there is nothing to look up and no files to find.",
                    style = MaterialTheme.typography.bodyMedium)
            }

            catalogItem(state, viewModel)

            state.error?.let { error -> item {
                Text(error.message, color = MaterialTheme.colorScheme.error)
                error.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error.recovery?.let { Text(it) }
            } }
            state.message?.let { message -> item { Text(message) } }

            item { HorizontalDivider() }
            item { Text("Installed bundles", style = MaterialTheme.typography.titleLarge) }
            if (state.installed.isEmpty()) item {
                Text("Nothing is installed yet. Android’s system shell is still available.")
            }
            items(state.installed, key = { it.id }) { installed ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(installed.name, style = MaterialTheme.typography.titleMedium)
                    Text("${installed.version} · ${installed.abi} · ${installed.license}")
                    Text(if (installed.filesPresent) "Executable files present; runtime not verified."
                        else "Executable files missing or none declared. Reinstall the bundle.",
                        style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text(installed.sourceUrl, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { OutlinedButton(onClick = viewModel::refresh, enabled = !state.busy) { Text("Refresh") } }

            item { HorizontalDivider() }
            item {
                TextButton(onClick = { advancedOpen = !advancedOpen }) {
                    Text(if (advancedOpen) "Hide advanced options" else "Advanced options")
                }
            }
            if (advancedOpen) advancedItems(state, viewModel, onSelectManifest, onSelectArchive)
        }
    }
}

/** What this build knows how to install, and the one button that installs it. */
private fun LazyListScope.catalogItem(
    state: ToolchainUiState,
    viewModel: ToolchainViewModel,
) {
    val catalog = state.catalog
    if (catalog == null) {
        item {
            Text("Checking what is available…", style = MaterialTheme.typography.bodyMedium)
        }
        state.catalogError?.let { error -> item {
            Text(error.message, color = MaterialTheme.colorScheme.error)
            error.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            error.recovery?.let { Text(it) }
            Text("You can set a different source under Advanced options.",
                style = MaterialTheme.typography.bodySmall)
        } }
        return
    }

    if (catalog.bundles.isEmpty()) {
        item {
            Text("No toolchains are available for this device yet.",
                style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val missing = catalog.bundles.filter { it.id !in state.installedIds }
    item {
        Text("Available for this device", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        catalog.bundles.forEach { entry ->
            val installed = entry.id in state.installedIds
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${entry.displayName} ${entry.version}")
                Text(if (installed) "Installed" else "Not installed",
                    color = if (installed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    item {
        Button(onClick = viewModel::installMissing,
            enabled = !state.busy && missing.isNotEmpty()) {
            Text(if (missing.isEmpty()) "Everything is installed"
            else "Download and install (${missing.size})")
        }
    }
    if (state.busy) item {
        val progress = state.catalogProgress
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(progress?.let { "Fetching ${it.displayName}: ${(it.overall * 100).toInt()}%" }
            ?: "Working…")
    }
}

/** Bundle source and manual import: for people who host bundles or already have a file. */
private fun LazyListScope.advancedItems(
    state: ToolchainUiState,
    viewModel: ToolchainViewModel,
    onSelectManifest: () -> Unit,
    onSelectArchive: () -> Unit,
) {
    item {
        Text("Bundle source", style = MaterialTheme.typography.titleMedium)
        Text("Where downloaded toolchains come from. Leave blank to use the source this " +
            "build ships with.", style = MaterialTheme.typography.bodySmall)
    }
    item {
        var draft by remember(state.sourceUrl) { mutableStateOf(state.sourceUrl.orEmpty()) }
        OutlinedTextField(value = draft, onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Source URL") },
            placeholder = { Text("https://…") })
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { viewModel.setSourceUrl(draft) }, enabled = !state.busy) {
            Text("Save source")
        }
    }

    item {
        HorizontalDivider()
        Text("Install from a file", style = MaterialTheme.typography.titleMedium)
        Text("Choose a MobileForge JSON manifest and its ZIP archive. Bundles must match this " +
            "app’s install location and device architecture.",
            style = MaterialTheme.typography.bodySmall)
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
    if (state.busy && state.catalogProgress == null) item {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (state.progress > 0) Text("Installing: ${(state.progress * 100).toInt()}%")
    }
}

package dev.mobileforge.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobileforge.core.designsystem.icon.ForgeIcons
import dev.mobileforge.core.designsystem.theme.ForgeThemeMode
import dev.mobileforge.core.designsystem.theme.LocalForgeCodeTypography

/**
 * Settings, grouped rather than presented as one long list.
 *
 * Only categories with real settings behind them appear. Terminal, Runtime, Git, AI, Agents and
 * Extensions are absent rather than shown greyed out — an empty category is a promise the build
 * cannot keep, and the brief is explicit about not shipping "coming soon" placeholders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenToolchains: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(ForgeIcons.Back, contentDescription = "Back")
                    }
                },
                title = { Text("Settings") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item { SectionHeader("Appearance") }
            item {
                SettingRow(title = "Theme") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ForgeThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode.name,
                                onClick = { viewModel.setThemeMode(mode.name) },
                                label = { Text(mode.displayName) },
                            )
                        }
                    }
                }
            }

            item { SectionHeader("Editor") }
            item {
                SettingRow(
                    title = "Font size",
                    subtitle = "${settings.editorFontSizeSp} sp",
                ) {
                    Slider(
                        value = settings.editorFontSizeSp.toFloat(),
                        onValueChange = { viewModel.setEditorFontSize(it.toInt()) },
                        valueRange = 8f..28f,
                        steps = 19,
                    )
                }
            }
            item {
                ToggleRow(
                    title = "Word wrap",
                    subtitle = "Wrap long lines instead of scrolling sideways",
                    checked = settings.wordWrap,
                    onCheckedChange = viewModel::setWordWrap,
                )
            }
            item {
                ToggleRow(
                    title = "Minimap",
                    subtitle = "Off by default: it costs screen width and memory on a phone",
                    checked = settings.minimapEnabled,
                    onCheckedChange = viewModel::setMinimap,
                )
            }
            item {
                ToggleRow(
                    title = "Auto-save",
                    subtitle = "Off by default so nothing writes to your files unprompted",
                    checked = settings.autoSave,
                    onCheckedChange = viewModel::setAutoSave,
                )
            }

            item { SectionHeader("Explorer") }
            item {
                ToggleRow(
                    title = "Show dotfiles",
                    subtitle = "Display .env, .gitignore and other hidden entries",
                    checked = settings.showHiddenFiles,
                    onCheckedChange = viewModel::setShowHiddenFiles,
                )
            }

            item { SectionHeader("Development runtime") }
            item {
                NavigationRow(title = "Toolchains", subtitle = "Import bundles and inspect installed tools",
                    onClick = onOpenToolchains)
            }
            item { SectionHeader("About") }
            item {
                NavigationRow(
                    title = "Diagnostics",
                    subtitle = "Device, storage and subsystem status",
                    onClick = onOpenDiagnostics,
                )
            }
            item {
                SettingRow(title = "Build", subtitle = "0.2.0-phase2a · IDE shell + execution core") {}
            }
            item {
                Text(
                    text = "The IDE shell and interactive terminal are available. Import compatible " +
                        "toolchain bundles to add developer commands. PHP, Node and Git are not bundled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Column {
        Spacer(Modifier.height(12.dp))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        HorizontalDivider()
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NavigationRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            ForgeIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Diagnostics.
 *
 * Every row is something the app actually measured. Subsystems that do not exist say
 * "Not implemented" with the phase that delivers them — never a green tick, never a blank.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val report by viewModel.diagnosticsReport.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(ForgeIcons.Back, contentDescription = "Back")
                    }
                },
                title = { Text("Diagnostics") },
                actions = {
                    IconButton(onClick = viewModel::runDiagnostics) {
                        Icon(ForgeIcons.Refresh, contentDescription = "Re-run diagnostics")
                    }
                },
            )
        },
    ) { padding ->
        val current = report
        if (current == null) {
            Column(
                Modifier.padding(padding).fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Run a system check to see device and subsystem status.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = viewModel::runDiagnostics) { Text("Run system check") }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionHeader("Platform") }
            items(current.platform.size) { KeyValueRow(current.platform[it]) }

            item { SectionHeader("Storage") }
            items(current.storage.size) { KeyValueRow(current.storage[it]) }

            item { SectionHeader("Execution") }
            item {
                Text(
                    text = current.execution,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            item { SectionHeader("Development tools") }
            items(current.tools.size) { index ->
                val tool = current.tools[index]
                KeyValueRow(DiagnosticEntry(tool.name, tool.status))
            }

            item { SectionHeader("Subsystems") }
            items(current.subsystems.size) { index ->
                SubsystemRow(current.subsystems[index])
            }
        }
    }
}

@Composable
private fun KeyValueRow(entry: DiagnosticEntry) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = entry.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(130.dp),
        )
        Text(
            text = entry.value,
            style = LocalForgeCodeTypography.current.codeSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SubsystemRow(report: SubsystemReport) {
    val color = when (report.state) {
        SubsystemState.Available -> MaterialTheme.colorScheme.primary
        SubsystemState.Degraded -> MaterialTheme.colorScheme.tertiary
        SubsystemState.NotImplemented -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (report.state) {
                SubsystemState.Available -> ForgeIcons.CheckCircle
                SubsystemState.Degraded -> ForgeIcons.Warning
                SubsystemState.NotImplemented -> ForgeIcons.Circle
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(report.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${report.state.label} — ${report.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

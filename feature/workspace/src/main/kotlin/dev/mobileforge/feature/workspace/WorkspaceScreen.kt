package dev.mobileforge.feature.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobileforge.core.designsystem.component.EmptyState
import dev.mobileforge.core.designsystem.component.ErrorBanner
import dev.mobileforge.core.designsystem.icon.ForgeIcons
import dev.mobileforge.core.designsystem.theme.LocalForgeCodeTypography
import dev.mobileforge.feature.editor.EditorViewModel
import dev.mobileforge.feature.editor.MonacoEditorView
import dev.mobileforge.feature.editor.NativeCodeEditor
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import dev.mobileforge.feature.terminal.TerminalScreen
import dev.mobileforge.feature.terminal.TerminalViewModel

/**
 * The IDE shell for an open project.
 *
 * Adaptive by window width, not by device type:
 *   - under 720 dp  : one pane at a time, switched from a bottom bar (phone, and any device in
 *                     split-screen or a small floating window)
 *   - 720 dp and up : explorer and editor side by side (tablet, landscape phone, desktop mode)
 *
 * The breakpoint is measured against the actual window, so a phone in landscape and a tablet
 * in a narrow split-screen both get the layout that fits, rather than the layout their form
 * factor implies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    viewModel: WorkspaceViewModel,
    editorViewModel: EditorViewModel,
    terminalViewModel: TerminalViewModel?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editorState by editorViewModel.state.collectAsStateWithLifecycle()

    val widthDp = LocalConfiguration.current.screenWidthDp
    val isWide = widthDp >= WIDE_LAYOUT_BREAKPOINT_DP

    var pane by rememberSaveable { mutableStateOf(WorkspacePane.Explorer) }
    var pendingFile by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExit by rememberSaveable { mutableStateOf(false) }
    val requestBack: () -> Unit = {
        if (!editorState.isSaving) {
            if (editorState.isDirty) pendingExit = true else onBack()
        }
    }
    val selectFile: (String) -> Unit = { path ->
        if (!editorState.isSaving) {
            if (path == editorState.openPath) pane = WorkspacePane.Editor
            else if (editorState.isDirty) pendingFile = path
            else viewModel.selectFile(path)
        }
    }
    BackHandler(enabled = editorState.isDirty || editorState.isSaving, onBack = requestBack)
    if (pendingFile != null || pendingExit) {
        AlertDialog(
            onDismissRequest = { pendingFile = null; pendingExit = false },
            title = { Text("Discard unsaved changes?") },
            text = { Text("${editorState.fileName ?: "This file"} has unsaved changes. " +
                "Keep editing to save them before continuing.") },
            confirmButton = { TextButton(onClick = {
                val next = pendingFile
                val exit = pendingExit
                pendingFile = null
                pendingExit = false
                if (exit) onBack() else next?.let(viewModel::selectFile)
            }) { Text("Discard changes") } },
            dismissButton = { TextButton(onClick = { pendingFile = null; pendingExit = false }) {
                Text("Keep editing")
            } },
        )
    }

    // Opening a file moves a narrow layout to the editor; on a wide layout both are visible so
    // there is nothing to switch.
    LaunchedEffect(state.selectedFile, state.isReadOnly) {
        val path = state.selectedFile ?: return@LaunchedEffect
        editorViewModel.openFile(path, readOnly = state.isReadOnly)
        if (!isWide) pane = WorkspacePane.Editor
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(ForgeIcons.Back, contentDescription = "Back to projects")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = state.workspace?.name ?: "Loading",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = editorState.openPath?.let { path ->
                            if (editorState.isDirty) "$path *" else path
                        } ?: state.facts?.framework?.displayName
                        subtitle?.let {
                            Text(
                                text = it,
                                style = LocalForgeCodeTypography.current.codeSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (editorState.openPath != null && !state.isReadOnly) {
                        IconButton(
                            onClick = editorViewModel::save,
                            enabled = editorState.isDirty && !editorState.isSaving,
                        ) {
                            if (editorState.isSaving) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(ForgeIcons.Check, contentDescription = "Save")
                            }
                        }
                    }
                    if (isWide) {
                        // No bottom bar on wide layouts, so the terminal needs a way in.
                        IconButton(
                            onClick = {
                                pane = if (pane == WorkspacePane.Terminal) {
                                    WorkspacePane.Editor
                                } else {
                                    WorkspacePane.Terminal
                                }
                            },
                        ) {
                            Icon(
                                ForgeIcons.Terminal,
                                contentDescription = if (pane == WorkspacePane.Terminal) {
                                    "Show editor"
                                } else {
                                    "Show terminal"
                                },
                                tint = if (pane == WorkspacePane.Terminal) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(ForgeIcons.Refresh, contentDescription = "Refresh file tree")
                    }
                },
            )
        },
        bottomBar = {
            if (!isWide) {
                NavigationBar {
                    WorkspacePane.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = pane == entry,
                            onClick = { pane = entry },
                            icon = { Icon(entry.icon(), contentDescription = null) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            if (state.isReadOnly && state.workspace != null) {
                RestrictedBanner(onTrust = viewModel::trustWorkspace)
            }

            state.error?.let { ErrorBanner(it, onDismiss = viewModel::dismissError) }
            editorState.error?.let { ErrorBanner(it, onDismiss = editorViewModel::dismissError) }

            Box(Modifier.weight(1f)) {
                when {
                    state.isLoading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    isWide -> Row(Modifier.fillMaxSize()) {
                        Box(Modifier.width(EXPLORER_WIDTH_DP.dp).fillMaxHeight()) {
                            ExplorerPane(state, viewModel, selectFile)
                        }
                        VerticalDivider()
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (pane == WorkspacePane.Terminal) {
                                TerminalPane(terminalViewModel)
                            } else {
                                EditorPane(editorState.openPath != null, editorViewModel)
                            }
                        }
                    }

                    pane == WorkspacePane.Explorer -> ExplorerPane(state, viewModel, selectFile)

                    pane == WorkspacePane.Terminal -> TerminalPane(terminalViewModel)

                    else -> EditorPane(editorState.openPath != null, editorViewModel)
                }
            }
        }
    }
}

@Composable
private fun ExplorerPane(state: WorkspaceUiState, viewModel: WorkspaceViewModel,
    onSelectFile: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "EXPLORER",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { viewModel.setShowHiddenFiles(!state.showHiddenFiles) },
            ) {
                Text(
                    text = if (state.showHiddenFiles) "Hide dotfiles" else "Show dotfiles",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        HorizontalDivider()

        FileExplorer(
            tree = state.tree,
            selectedFile = state.selectedFile,
            showHiddenFiles = state.showHiddenFiles,
            onToggleDirectory = viewModel::toggleDirectory,
            onSelectFile = onSelectFile,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun EditorPane(hasOpenFile: Boolean, editorViewModel: EditorViewModel) {
    if (!hasOpenFile) {
        EmptyState(
            icon = ForgeIcons.File,
            title = "No file open",
            message = "Choose a file in the explorer to start editing.",
            modifier = Modifier.fillMaxSize(),
        )
    } else if (editorViewModel.nativeEditing) {
        NativeCodeEditor(editorViewModel, Modifier.fillMaxSize())
    } else {
        MonacoEditorView(
            bridge = editorViewModel.bridge,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Restricted-mode banner.
 *
 * Present and persistent while a project is untrusted, because a user who cannot work out why
 * saving does nothing will conclude the app is broken rather than that it is protecting them.
 */
@Composable
private fun RestrictedBanner(onTrust: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            ForgeIcons.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "Restricted mode — read only",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onTrust) { Text("Trust", style = MaterialTheme.typography.labelSmall) }
    }
}

enum class WorkspacePane(val label: String) {
    Explorer("Files"),
    Editor("Code"),
    Terminal("Shell"),
    ;

    @Composable
    fun icon() = when (this) {
        Explorer -> ForgeIcons.Folder
        Editor -> ForgeIcons.Code
        Terminal -> ForgeIcons.Terminal
    }
}

/**
 * The terminal pane.
 *
 * A null view model means the PTY could not be prepared on this device — reported plainly
 * rather than shown as an empty black rectangle.
 */
@Composable
private fun TerminalPane(terminalViewModel: TerminalViewModel?) {
    if (terminalViewModel == null) {
        EmptyState(
            icon = ForgeIcons.Terminal,
            title = "Terminal unavailable",
            message = "A shell could not be started on this device. File browsing and " +
                "editing still work.",
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        TerminalScreen(viewModel = terminalViewModel, modifier = Modifier.fillMaxSize())
    }
}

/**
 * Below this the explorer and editor cannot both be useful at once — an explorer narrower than
 * about 240 dp truncates every filename, and an editor narrower than about 480 dp wraps
 * constantly. 720 dp is the smallest width where both are genuinely usable together.
 */
private const val WIDE_LAYOUT_BREAKPOINT_DP = 720

private const val EXPLORER_WIDTH_DP = 280

package dev.mobileforge.feature.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobileforge.core.designsystem.component.EmptyState
import dev.mobileforge.core.designsystem.component.ErrorBanner
import dev.mobileforge.core.designsystem.icon.ForgeIcons
import dev.mobileforge.core.designsystem.theme.LocalForgeCodeTypography
import dev.mobileforge.core.model.DetectedFramework
import dev.mobileforge.core.model.Workspace
import dev.mobileforge.core.model.WorkspaceId
import dev.mobileforge.core.model.WorkspaceTrust

/**
 * The first screen a user sees.
 *
 * Kept deliberately plain: create, open, and a recent list. The brief is explicit that a new
 * user should not be met with the whole toolchain at once, so runtime, Git and AI surfaces are
 * absent here rather than present-but-disabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    viewModel: ProjectsViewModel,
    onOpenWorkspace: (WorkspaceId) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.openWorkspaceId, state.createdWorkspaceId) {
        (state.openWorkspaceId ?: state.createdWorkspaceId)?.let {
            onOpenWorkspace(it)
            viewModel.consumeNavigation()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MobileForge") },
                actions = {
                    IconButton(onClick = viewModel::showCreateDialog) {
                        Icon(ForgeIcons.Add, contentDescription = "New project")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(ForgeIcons.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { error ->
                ErrorBanner(error = error, onDismiss = viewModel::dismissDialog)
            }

            if (workspaces.isEmpty()) {
                EmptyState(
                    icon = ForgeIcons.Folder,
                    title = "No projects yet",
                    message = "Create a project to start editing. Running PHP, Composer " +
                        "and Git needs a toolchain, which is not bundled yet.",
                    actionLabel = "Create project",
                    onAction = viewModel::showCreateDialog,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(workspaces, key = { it.id.value }) { workspace ->
                        ProjectCard(
                            workspace = workspace,
                            onClick = { viewModel.requestOpen(workspace) },
                            onRemove = { viewModel.confirmRemove(workspace) },
                        )
                    }
                }
            }
        }
    }

    when (val dialog = state.dialog) {
        ProjectsDialog.Create -> CreateProjectDialog(
            isBusy = state.isBusy,
            onCreate = viewModel::createProject,
            onDismiss = viewModel::dismissDialog,
        )

        is ProjectsDialog.Trust -> TrustProjectDialog(
            workspace = dialog.workspace,
            onTrust = { viewModel.trustAndOpen(dialog.workspace.id) },
            onOpenRestricted = { viewModel.openRestricted(dialog.workspace.id) },
            onDismiss = viewModel::dismissDialog,
        )

        is ProjectsDialog.ConfirmRemove -> RemoveProjectDialog(
            workspace = dialog.workspace,
            onRemove = { deleteFiles ->
                viewModel.removeProject(dialog.workspace.id, deleteFiles)
            },
            onDismiss = viewModel::dismissDialog,
        )

        null -> Unit
    }
}

@Composable
private fun ProjectCard(
    workspace: Workspace,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FrameworkBadge(workspace.framework)
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = workspace.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = workspace.framework.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (workspace.trust == WorkspaceTrust.Untrusted) {
                        Text(
                            text = "  ·  Restricted",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                workspace.lastActiveFile?.let { file ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = file,
                        style = LocalForgeCodeTypography.current.codeSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(onClick = onRemove) {
                Icon(
                    ForgeIcons.Delete,
                    contentDescription = "Remove ${workspace.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FrameworkBadge(framework: DetectedFramework) {
    val (icon: ImageVector, tint) = when (framework) {
        DetectedFramework.Laravel -> ForgeIcons.Code to MaterialTheme.colorScheme.error
        DetectedFramework.Php -> ForgeIcons.Code to MaterialTheme.colorScheme.secondary
        DetectedFramework.Node -> ForgeIcons.Code to MaterialTheme.colorScheme.tertiary
        DetectedFramework.Static -> ForgeIcons.Description to MaterialTheme.colorScheme.primary
        DetectedFramework.Unknown -> ForgeIcons.Folder to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun CreateProjectDialog(
    isBusy: Boolean,
    onCreate: (String, ProjectTemplate) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(ProjectTemplate.Empty) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text("Template", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                ProjectTemplate.entries.forEach { option ->
                    TemplateRow(
                        template = option,
                        selected = option == template,
                        onSelect = { template = option },
                    )
                }
                Spacer(Modifier.height(10.dp))
                // Said plainly rather than discovered later by a confused user.
                Text(
                    text = "Laravel and Vite scaffolding need Composer and npm, which arrive " +
                        "with the development runtime in Phase 2.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, template) },
                enabled = name.isNotBlank() && !isBusy,
            ) {
                if (isBusy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TemplateRow(
    template: ProjectTemplate,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onSelect)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surface,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (selected) ForgeIcons.CheckCircle else ForgeIcons.Circle,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(template.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                template.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The workspace-trust prompt.
 *
 * Wording matters here: it states what could actually happen, and "Open restricted" is offered
 * with equal weight rather than buried, so the safe choice is not the inconvenient one.
 */
@Composable
private fun TrustProjectDialog(
    workspace: Workspace,
    onTrust: () -> Unit,
    onOpenRestricted: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(ForgeIcons.Warning, contentDescription = null) },
        title = { Text("Do you trust this project?") },
        text = {
            Column {
                Text(
                    "\"${workspace.name}\" contains code that MobileForge can run once the " +
                        "development runtime is available.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "In restricted mode you can browse and read files, but editing, terminal " +
                        "commands, network access and Git push stay disabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text("Trust project") } },
        dismissButton = {
            TextButton(onClick = onOpenRestricted) { Text("Open restricted") }
        },
    )
}

@Composable
private fun RemoveProjectDialog(
    workspace: Workspace,
    onRemove: (deleteFiles: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var deleteFiles by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove \"${workspace.name}\"?") },
        text = {
            Column {
                Text(
                    "This removes the project from your list.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { deleteFiles = !deleteFiles }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (deleteFiles) ForgeIcons.CheckCircle else ForgeIcons.Circle,
                        contentDescription = null,
                        tint = if (deleteFiles) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Also delete the files permanently",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (deleteFiles) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (deleteFiles) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The project folder and everything in it will be deleted. " +
                            "This cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRemove(deleteFiles) }) {
                Text(if (deleteFiles) "Delete permanently" else "Remove")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
